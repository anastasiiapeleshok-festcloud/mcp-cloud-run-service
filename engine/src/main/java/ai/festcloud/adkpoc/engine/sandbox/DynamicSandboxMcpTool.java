package ai.festcloud.adkpoc.engine.sandbox;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import io.reactivex.rxjava3.core.Single;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One MCP tool, discovered dynamically at request time via {@link
 * SandboxSessionManager#listTools} against the real sandboxed MCP server — no hardcoded
 * name, description, or schema anywhere. {@code DynamicAgentFactory} builds one instance
 * of this per tool the live server actually declares, on every chat request, so the
 * agent always sees exactly what the real MCP server currently exposes (all of it, not
 * a hand-picked subset).
 *
 * <p>Unlike the old, retired {@code SandboxMcpTool}, this class does not start or stop
 * the sandbox itself — by the time any instance of this class exists, {@code
 * DynamicAgentFactory} has already called {@code ensureActiveSandbox} (that's how it got
 * the schema this instance was built from), and {@code ChatController} tears the sandbox
 * down after the turn. This tool only ever calls an already-running sandbox.
 */
public final class DynamicSandboxMcpTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(DynamicSandboxMcpTool.class);

  private final SandboxSessionManager sessionManager;
  private final String sessionKey;
  private final String toolName;
  private final Map<String, Object> inputSchema;

  public DynamicSandboxMcpTool(SandboxSessionManager sessionManager, String sessionKey,
      String toolName, String description, Map<String, Object> inputSchema) {
    super(toolName, description);
    this.sessionManager = sessionManager;
    this.sessionKey = sessionKey;
    this.toolName = toolName;
    this.inputSchema = inputSchema;
  }

  @Override
  public Optional<FunctionDeclaration> declaration() {
    return Optional.of(
        FunctionDeclaration.builder()
            .name(toolName)
            .description(description())
            .parametersJsonSchema(inputSchema)
            .build());
  }

  @Override
  public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
    return Single.fromCallable(
        () -> {
          log.info("Calling real MCP tool {} with args {}", toolName, args);
          return sessionManager.callToolBlocking(sessionKey, toolName, args);
        });
  }
}
