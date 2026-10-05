package poc.mcp.sandboxservice;

import com.google.adk.tools.mcp.McpSessionManager;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unmodified copy of adk-mcp-sandbox's sandbox-bridge McpBridge — restored per request:
 * "use google adk stdio as it was for the cloud job". Everything MCP-specific here is
 * the real ADK Java library (com.google.adk.tools.mcp.McpSessionManager), completely
 * unaffected by whether the container hosting it is reached over a WebSocket (Job
 * variant) or plain HTTP (this Service variant) — see SandboxServiceMain for the only
 * thing that actually changed: how a caller reaches this class.
 */
public class McpBridge implements SandboxSession {

  private static final Logger log = LoggerFactory.getLogger(McpBridge.class);

  private McpSyncClient mcpClient;

  /** Spawns the real stdio MCP subprocess and performs the real MCP handshake. */
  public void start(String command, List<String> args, Map<String, String> env) {
    ServerParameters params = ServerParameters.builder(command)
        .args(args)
        .env(env)
        .build();

    log.info("Spawning stdio MCP server: {} {}", command, args);
    long t0 = System.currentTimeMillis();
    this.mcpClient = McpSessionManager.initializeSession(params);
    log.info("[TIMING] MCP spawn + handshake: {} ms", System.currentTimeMillis() - t0);
    log.info("MCP handshake complete: {}", mcpClient.getCurrentInitializationResult());
  }

  public List<Map<String, Object>> listTools() {
    return mcpClient.listTools().tools().stream()
        .map(this::toolToMap)
        .collect(Collectors.toList());
  }

  private Map<String, Object> toolToMap(Tool tool) {
    return Map.of(
        "name", tool.name(),
        "description", tool.description() == null ? "" : tool.description(),
        "inputSchema", tool.inputSchema() == null ? Map.of() : tool.inputSchema());
  }

  /** A real tools/call against the real subprocess, via the real ADK MCP client. */
  public Map<String, Object> callTool(String toolName, Map<String, Object> args) {
    long t0 = System.currentTimeMillis();
    CallToolResult result = mcpClient.callTool(new CallToolRequest(toolName, args));
    log.info("[TIMING] real MCP tools/call '{}': {} ms", toolName, System.currentTimeMillis() - t0);
    return toJsonFriendlyResult(result);
  }

  @Override
  public void close() {
    if (mcpClient != null) {
      mcpClient.close();
    }
  }

  // Mirrors AbstractMcpTool.wrapCallResult() in ADK Java — same shape a co-located
  // McpTool would hand the agent.
  private Map<String, Object> toJsonFriendlyResult(CallToolResult result) {
    if (Boolean.TRUE.equals(result.isError())) {
      return Map.of("error", firstText(result.content()).orElse("tool execution failed"));
    }
    return firstText(result.content())
        .<Map<String, Object>>map(text -> Map.of("text", text))
        .orElseGet(() -> Map.of("content", result.content().toString()));
  }

  private java.util.Optional<String> firstText(List<Content> contents) {
    if (contents == null) {
      return java.util.Optional.empty();
    }
    return contents.stream()
        .filter(TextContent.class::isInstance)
        .map(TextContent.class::cast)
        .map(TextContent::text)
        .findFirst();
  }
}
