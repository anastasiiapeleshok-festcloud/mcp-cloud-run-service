package ai.festcloud.adkpoc.engine.agent;

import ai.festcloud.adkpoc.engine.sandbox.CloudRunStdioTransport;
import ai.festcloud.adkpoc.engine.sandbox.SandboxSessionManager;
import com.google.adk.JsonBaseModel;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.plugins.LoggingPlugin;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.tools.mcp.McpSessionManager;
import com.google.adk.tools.mcp.McpToolset;
import com.google.adk.tools.mcp.StdioConnectionParameters;
import com.google.adk.tools.mcp.StdioServerParameters;
import io.modelcontextprotocol.client.transport.ServerParameters;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Replaces the old static {@code @Bean}-wired agent hierarchy: instead of one
 * hardcoded tool built once at Spring startup, this builds the WHOLE agent
 * hierarchy — MCP session, tool discovery, {@code operaton_agent}, {@code
 * orchestrator_agent}, {@code Runner} — fresh, on every chat request.
 *
 * <p>Why per request and not once at startup: the real MCP server only exists inside an
 * ephemeral, per-invocation sandbox (see SandboxSessionManager) — there is no live
 * server to run {@code tools/list} against until a sandbox has actually been started for
 * THIS request's sessionKey. Building the agent hierarchy once at startup, before any
 * sandbox exists, is exactly what forced the old hardcoded single-tool approach.
 *
 * <p>Trade-off this buys: every chat request now pays sandbox start-up + tools/list
 * discovery up front (cold: ~20s+, warm/reused instance: much less), even for a message
 * that turns out not to need any tool at all — there is no more "only spin up the
 * sandbox if the LLM decides to call something." Full dynamic tool visibility and "lazy
 * until called" are in tension; this class picks the former, per the explicit ask.
 *
 * <p>{@code sessionService}/{@code artifactService}/{@code memoryService} are shared,
 * injected singletons (see AgentBootstrap) — only the agent/tool/Runner OBJECTS are
 * rebuilt per request, so ADK conversation history still persists across requests for
 * the same user.
 *
 * <p>Which MCP server gets spawned is the ONE remaining toggle, controlled by {@code
 * mcp.server} ({@code MCP_SERVER} env var) — {@code "operaton"} (default) or {@code
 * "demo"}. Because tools are discovered live, switching this doesn't require touching
 * any tool declaration anywhere — the agent's actual tool surface just follows whichever
 * server is configured. The agent instruction text below is deliberately
 * server-agnostic for the same reason.
 */
@Component
public class DynamicAgentFactory {

  private static final Logger log = LoggerFactory.getLogger(DynamicAgentFactory.class);

  private static final List<String> DEMO_MCP_ARGS = List.of("-y", "@modelcontextprotocol/server-everything");
  private static final List<String> OPERATON_MCP_ARGS = List.of("-y", "operaton-mcp");

  private final SandboxSessionManager sessionManager;
  private final OperatonMcpProperties operatonProps;
  private final BaseSessionService sessionService;
  private final BaseArtifactService artifactService;
  private final BaseMemoryService memoryService;

  @Value("${mcp.server:operaton}")
  private String mcpServer;

  public DynamicAgentFactory(SandboxSessionManager sessionManager, OperatonMcpProperties operatonProps,
      BaseSessionService sessionService, BaseArtifactService artifactService, BaseMemoryService memoryService) {
    this.sessionManager = sessionManager;
    this.operatonProps = operatonProps;
    this.sessionService = sessionService;
    this.artifactService = artifactService;
    this.memoryService = memoryService;
  }

  /** Builds a fresh Runner whose operaton_agent uses a stock McpToolset over the Cloud Run
   *  transport. The sandbox itself starts lazily, on first use of the toolset. Caller owns
   *  tearing it down (via SandboxSessionManager.destroySandbox(sessionKey)) once the turn
   *  is done. */
  public Runner buildRunnerForSession(String sessionKey) throws Exception {
    McpServerSpec spec = resolveMcpServer();
    log.info("{} — spawning MCP server '{}' ({} {})", sessionKey, mcpServer, spec.command(), spec.args());

    // The stock ADK McpToolset, unchanged — only its transport is ours: instead of spawning
    // the stdio process locally it asks the Cloud Run Service to spawn it and tunnels the
    // JSON-RPC over HTTP. Nothing is started here: the sandbox is spun up lazily, the first
    // time the agent actually needs the tool list (see CloudRunStdioTransport.connect).
    StdioConnectionParameters connection = StdioConnectionParameters.builder()
        .serverParams(StdioServerParameters.builder()
            .command(spec.command())
            .args(spec.args())
            .env(spec.env())
            .build())
        .timeout(90f) // MCP initialize budget, includes the Cloud Run cold start
        .build();
    McpSessionManager mcpSessions = new McpSessionManager(connection,
        // ADK has already turned StdioConnectionParameters into the SDK's ServerParameters
        // (command/args/env) by the time it asks for a transport.
        params -> new CloudRunStdioTransport(sessionManager, sessionKey, (ServerParameters) params));
    McpToolset toolset = new McpToolset(mcpSessions, JsonBaseModel.getMapper());

    LlmAgent operatonAgent = LlmAgent.builder()
        .name("operaton_agent")
        .description("Handles domain queries via the MCP server's tools, discovered live "
            + "from the sandboxed MCP server for this request.")
        .model("gemini-3.8-flash")
        .instruction("""
            You have direct access to tools that were just discovered live from the
            sandboxed MCP server for this request — use whichever one matches what the user
            asked for, with the arguments its own schema requires. Report back exactly what
            the tool returned.
            """)
        .tools(toolset)
        .build();

    LlmAgent orchestratorAgent = LlmAgent.builder()
        .name("orchestrator_agent")
        .description("Main entry point; delegates domain-specific queries to operaton_agent.")
        .model("gemini-3.8-flash")
        .instruction("""
            You are the main orchestrator agent. If the request might be answerable by one
            of operaton_agent's tools, delegate to it so it can check what's actually
            available. Otherwise answer directly.
            """)
        .subAgents(operatonAgent)
        .build();

    return Runner.builder()
        .agent(orchestratorAgent)
        .appName("adk-mcp-sandbox-service-poc")
        .sessionService(sessionService)
        .artifactService(artifactService)
        .memoryService(memoryService)
        .plugins(new LoggingPlugin())
        .build();
  }

  private McpServerSpec resolveMcpServer() {
    String normalized = mcpServer == null ? "" : mcpServer.toLowerCase(Locale.ROOT).trim();
    return switch (normalized) {
      case "demo" -> new McpServerSpec("npx", DEMO_MCP_ARGS, Map.of());
      case "operaton" -> new McpServerSpec("npx", OPERATON_MCP_ARGS, Map.of(
          "OPERATON_BASE_URL", operatonProps.baseUrl(),
          "OPERATON_USERNAME", operatonProps.username(),
          "OPERATON_PASSWORD", operatonProps.password(),
          "NODE_TLS_REJECT_UNAUTHORIZED", "0"));
      default -> throw new IllegalStateException(
          "Unknown mcp.server value '" + mcpServer + "' — expected 'operaton' or 'demo' "
              + "(set via MCP_SERVER env var)");
    };
  }

  private record McpServerSpec(String command, List<String> args, Map<String, String> env) {}
}
