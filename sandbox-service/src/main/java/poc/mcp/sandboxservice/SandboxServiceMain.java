package poc.mcp.sandboxservice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entrypoint for the Cloud Run SERVICE variant of the sandbox. Where adk-mcp-sandbox's
 * SandboxMain dials OUT to a broker over WebSocket (because a Job accepts no inbound
 * traffic at all), this listens for plain inbound HTTPS calls from Engine directly —
 * Cloud Run routes them here because this container IS a Service. The MCP handling
 * itself is unchanged from the Job variant: real {@link McpBridge}, real
 * {@code com.google.adk.tools.mcp.McpSessionManager}, real {@code McpSyncClient} — only
 * the wire between Engine and this container changed (HTTP request/response instead of
 * a WebSocket message), so {@code /mcp} carries the same "method + params in, result/
 * error out" shape the old CALL_TOOL/CALL_RESULT/CALL_ERROR WebSocket messages did.
 *
 * <p><b>Auth</b> is enforced by Cloud Run IAM (--no-allow-unauthenticated), not this
 * class — a request that reaches this process has already been verified by the platform.
 *
 * <p><b>Concurrency contract:</b> the Service MUST be deployed with
 * {@code --concurrency=1}. The single mutable activeSessionId/activeBridge pair below is
 * only safe because of that — Cloud Run guarantees one request in flight at a time.
 *
 * <p><b>Session-affinity contract:</b> the Service MUST be deployed with
 * {@code --session-affinity}. Cloud Run's frontend sets the cookie and best-effort-routes
 * later calls for the same session back to this instance — this class never touches that
 * cookie itself.
 */
public class SandboxServiceMain {

  private static final Logger log = LoggerFactory.getLogger(SandboxServiceMain.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Object LOCK = new Object();

  private static volatile String activeSessionId;
  private static volatile McpBridge activeBridge;
  // Updated on every /session/start, /mcp, and /session/close touching the active
  // session — lets a /session/start conflict self-heal instead of rejecting forever.
  // See handleSessionStart: without this, a session whose /session/close call suffers a
  // session-affinity miss (lands on a DIFFERENT instance than the one running its
  // sandbox) leaves THIS instance stuck with a stale activeSessionId indefinitely —
  // Cloud Run has no reason to recycle a healthy instance just because our own app-level
  // state is wrong, especially with min-instances keeping it alive regardless of traffic.
  private static volatile long lastActivityAtMillis = System.currentTimeMillis();

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    boolean exitAfterSession = Boolean.parseBoolean(
        System.getenv().getOrDefault("EXIT_AFTER_SESSION", "true"));
    long staleSessionTimeoutMillis = Long.parseLong(
        System.getenv().getOrDefault("STALE_SESSION_TIMEOUT_MS", "300000")); // 5 min default

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext("/healthz", exchange -> writeJson(exchange, 200, Map.of("status", "ok")));
    server.createContext("/session/start",
        exchange -> handleSessionStart(exchange, staleSessionTimeoutMillis));
    server.createContext("/mcp", SandboxServiceMain::handleMcp);
    server.createContext("/session/close", exchange -> handleSessionClose(exchange, exitAfterSession));

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      log.info("SIGTERM received — closing any active MCP session");
      synchronized (LOCK) {
        if (activeBridge != null) {
          activeBridge.close();
        }
      }
    }));

    server.start();
    log.info("sandbox-service listening on :{} (exitAfterSession={}, staleSessionTimeoutMs={})",
        port, exitAfterSession, staleSessionTimeoutMillis);
  }

  private static void handleSessionStart(HttpExchange exchange, long staleSessionTimeoutMillis)
      throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }
    JsonNode body = MAPPER.readTree(exchange.getRequestBody());
    String sessionId = body.path("sessionId").asText();
    String mcpCommand = body.path("mcpCommand").asText();
    List<String> mcpArgs = MAPPER.convertValue(body.path("mcpArgs"), List.class);
    Map<String, String> mcpEnv = MAPPER.convertValue(body.path("mcpEnv"), Map.class);

    synchronized (LOCK) {
      if (activeBridge != null) {
        long idleMillis = System.currentTimeMillis() - lastActivityAtMillis;
        if (idleMillis < staleSessionTimeoutMillis) {
          // Should not happen under concurrency=1 unless a previous session's
          // /session/close never reached THIS instance (e.g. a session-affinity miss on
          // the close call itself — landed on a different instance than the one running
          // the sandbox) — surfaced loudly rather than silently reusing a stranger's MCP
          // subprocess for a new session.
          log.warn("Rejecting /session/start for {} — instance already has active session {} "
                  + "(idle {} ms, under the {} ms stale threshold)",
              sessionId, activeSessionId, idleMillis, staleSessionTimeoutMillis);
          writeJson(exchange, 409, Map.of(
              "error", "instance already has an active session: " + activeSessionId));
          return;
        }
        // Self-healing: the stuck session has been untouched longer than the stale
        // threshold — almost certainly an orphan from a misrouted/failed close, not a
        // real in-progress turn (a real turn's tool calls keep refreshing
        // lastActivityAtMillis). Force it closed instead of rejecting forever — this is
        // the answer to "when does this error disappear": after staleSessionTimeoutMillis
        // of inactivity, not on its own before that.
        log.warn("Active session {} idle for {} ms (> {} ms stale threshold) — force-closing "
                + "it to accept new session {}",
            activeSessionId, idleMillis, staleSessionTimeoutMillis, sessionId);
        activeBridge.close();
        activeBridge = null;
        activeSessionId = null;
      }

      McpBridge bridge = new McpBridge();
      try {
        bridge.start(mcpCommand, mcpArgs, mcpEnv);
      } catch (Exception e) {
        log.error("Failed to start MCP subprocess for session {}", sessionId, e);
        writeJson(exchange, 500, Map.of("error", e.getMessage() != null ? e.getMessage() : e.toString()));
        return;
      }
      activeBridge = bridge;
      activeSessionId = sessionId;
      lastActivityAtMillis = System.currentTimeMillis();
    }

    writeJson(exchange, 200, Map.of("ok", true));
  }

  /**
   * Body is {@code {"sessionId": "...", "message": {"jsonrpc":"2.0","id":...,
   * "method":"tools/call"|"tools/list","params":{...}}}} — same "method + params in,
   * result/error out" contract the Job variant's WebSocket CALL_TOOL/LIST_TOOLS messages
   * carried, just addressed as a plain HTTP call instead. Dispatches straight to the
   * real ADK McpBridge — no protocol reimplementation here, exactly like the Job variant.
   */
  private static void handleMcp(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }
    JsonNode body = MAPPER.readTree(exchange.getRequestBody());
    String sessionId = body.path("sessionId").asText();
    JsonNode message = body.path("message");
    String id = message.path("id").asText();
    String method = message.path("method").asText();

    McpBridge bridge = activeBridge;
    if (bridge == null || !sessionId.equals(activeSessionId)) {
      // Session-affinity miss (instance evicted/scaled down mid-turn) or the session was
      // never started on the instance we landed on. Engine treats 409 as "sandbox lost",
      // same failure mode as the Job variant's "sandbox never connected" timeout.
      log.warn("Rejecting /mcp for {} — this instance's active session is {}", sessionId, activeSessionId);
      writeJson(exchange, 409, Map.of("error", "no active session " + sessionId + " on this instance"));
      return;
    }

    lastActivityAtMillis = System.currentTimeMillis();
    try {
      Map<String, Object> result = switch (method) {
        case "tools/call" -> {
          JsonNode params = message.path("params");
          String toolName = params.path("name").asText();
          Map<String, Object> toolArgs = MAPPER.convertValue(params.path("arguments"), Map.class);
          log.info("Executing real MCP tool call: {} {}", toolName, toolArgs);
          yield bridge.callTool(toolName, toolArgs);
        }
        case "tools/list" -> {
          List<Map<String, Object>> tools = bridge.listTools();
          log.info("Discovered {} real MCP tools", tools.size());
          yield Map.of("tools", tools);
        }
        default -> throw new IllegalArgumentException("Unsupported method: " + method);
      };

      // toJsonFriendlyResult()'s "error" key means a tool-level error (the real MCP
      // server answered, but CallToolResult.isError() was true) — kept inside "result",
      // same as the Job variant's CALL_RESULT for that case. A thrown exception below
      // (transport failure, unknown method, ...) is the protocol-level "error" instead —
      // same distinction the Job variant made between CALL_RESULT and CALL_ERROR.
      writeJson(exchange, 200, Map.of("jsonrpc", "2.0", "id", id, "result", result));
    } catch (Exception e) {
      log.error("MCP call '{}' failed", method, e);
      writeJson(exchange, 200, Map.of("jsonrpc", "2.0", "id", id, "error", Map.of(
          "message", e.getMessage() != null ? e.getMessage() : e.toString())));
    }
  }

  private static void handleSessionClose(HttpExchange exchange, boolean exitAfterSession) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }
    JsonNode body = MAPPER.readTree(exchange.getRequestBody());
    String sessionId = body.path("sessionId").asText();

    boolean closedOwnSession;
    synchronized (LOCK) {
      // Verify sessionId before closing anything — a misrouted close for a DIFFERENT,
      // already-finished session must never tear down whatever this instance is
      // currently, legitimately serving (possible whenever a close call's own
      // session-affinity cookie misses, landing here instead of on the instance that
      // actually holds that session).
      if (activeBridge != null && sessionId.equals(activeSessionId)) {
        log.info("Closing session {}", activeSessionId);
        activeBridge.close();
        activeBridge = null;
        activeSessionId = null;
        lastActivityAtMillis = System.currentTimeMillis();
        closedOwnSession = true;
      } else if (activeBridge != null) {
        log.warn("Ignoring /session/close for {} — this instance's active session is {} "
            + "(mismatch, not closing it)", sessionId, activeSessionId);
        closedOwnSession = false;
      } else {
        closedOwnSession = false; // nothing was active here for anyone — harmless no-op
      }
    }
    writeJson(exchange, 200, Map.of("ok", true));

    // Only self-exit if THIS instance actually closed the session the caller meant —
    // exiting on every close call regardless (the old behavior) would kill an instance
    // out from under a different, still-live session just because an unrelated stray
    // close call happened to land here first.
    if (exitAfterSession && closedOwnSession) {
      // Exit AFTER the response above is written — same lesson adk-mcp-sandbox's
      // BrokerConnection.onClose() encoded: the caller needs to see a clean 200 first.
      new Thread(() -> {
        try {
          Thread.sleep(200);
        } catch (InterruptedException ignored) {
          Thread.currentThread().interrupt();
        }
        log.info("EXIT_AFTER_SESSION=true — exiting so Cloud Run must cold-start a fresh "
            + "instance for the next session");
        System.exit(0);
      }).start();
    }
  }

  private static void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
    byte[] bytes = MAPPER.writeValueAsBytes(payload);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
