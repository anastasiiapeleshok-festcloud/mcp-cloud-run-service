package poc.mcp.sandboxservice;

/** What an instance holds while a session is active — either the typed {@link McpBridge}
 *  or the raw {@link StdioRelay}. Only {@code close()} is common to both. */
public interface SandboxSession {
  void close();
}
