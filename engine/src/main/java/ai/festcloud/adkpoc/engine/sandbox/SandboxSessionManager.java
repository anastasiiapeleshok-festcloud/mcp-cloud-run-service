package ai.festcloud.adkpoc.engine.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What SandboxMcpTool.runAsync() actually talks to — same collaborator role as
 * adk-mcp-sandbox's SandboxSessionManager, completely different insides:
 *
 * <p><b>Job variant:</b> trigger a Cloud Run Job execution (Admin API), wait for it to
 * dial an outbound WebSocket back to us, push CALL_TOOL messages over that socket.
 *
 * <p><b>Service variant (this one):</b> call the Cloud Run Service's URL directly with a
 * plain synchronous HTTPS POST. No broker, no WebSocket, no inbound port on our side at
 * all. The thing that replaces "the sandbox dialed back to prove which instance is
 * mine" is Cloud Run's own session-affinity cookie: the Service's first response
 * (/session/start) carries a Set-Cookie, and every later call for the same sessionKey
 * echoes it back so Cloud Run's frontend best-effort-routes us to the SAME warm
 * instance — the one that already has this session's MCP subprocess running.
 *
 * <p>The sandbox's MCP handling itself is unchanged from the Job variant — real
 * {@code com.google.adk.tools.mcp.McpSessionManager}, real {@code McpSyncClient}, real
 * stdio subprocess (see sandbox-service's McpBridge, an unmodified copy of the Job
 * variant's). Only the wire between here and there changed: a plain HTTP POST to
 * {@code /mcp} carrying {@code {method, params}} instead of a WebSocket CALL_TOOL
 * message, and a plain HTTP response carrying {@code {result, error}} instead of
 * CALL_RESULT/CALL_ERROR. "result" is McpBridge's already-flattened
 * {@code {"text": ...}} / {@code {"error": ...}} map — the exact shape the Job variant's
 * CALL_RESULT carried too.
 *
 * <p>Requires the Service to be deployed with {@code --concurrency=1
 * --session-affinity}. concurrency=1 is what makes "best-effort" good enough here: an
 * instance can only ever be mid-session for ONE sessionKey at a time, so there's no
 * scenario where affinity routes us to an instance that's busy running someone else's
 * MCP process.
 */
@Component
public class SandboxSessionManager {

  private static final Logger log = LoggerFactory.getLogger(SandboxSessionManager.class);

  // Generous headroom for a cold instance: Cloud Run cold start + npx resolving the MCP
  // package from the registry with an empty local cache (same "60s, not 25s" lesson
  // adk-mcp-sandbox's provisioner learned the hard way) + the real MCP initialize
  // handshake — all synchronous inside this one call, see sandbox-service's
  // /session/start handler. A warm instance reused via the affinity cookie should
  // return far under this.
  private static final Duration START_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120); // tool budget (<=30s) + margin
  private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(120))
      .build();

  // Whether ensureActiveSandbox() has successfully run for this key yet this turn.
  private final ConcurrentMap<String, Boolean> provisionedSessions = new ConcurrentHashMap<>();
  // The ONLY thing that makes "same session, second tool call" land on the same
  // instance as the first — captured from /session/start's Set-Cookie, replayed on
  // every later call for this sessionKey.
  private final ConcurrentMap<String, String> affinityCookieBySessionKey = new ConcurrentHashMap<>();

  @Value("${sandbox.service-url}")
  private String sandboxServiceUrl;

  @Value("${sandbox.require-auth-token:false}")
  private boolean requireAuthToken;

  private volatile IdTokenSupplier idTokenSupplier; // built lazily, only if requireAuthToken

  /** One sandbox per user question (one runAsync() invocation) — identical contract to
   *  adk-mcp-sandbox. First call in a turn blocks on the Service actually starting the
   *  MCP subprocess; later calls in the same turn are a same-map-key no-op. */
  public void ensureActiveSandbox(String sessionKey, String mcpCommand, List<String> mcpArgs,
      Map<String, String> mcpEnv) throws Exception {
    boolean[] wasAlreadyUp = {true};
    Exception[] failure = new Exception[1];
    long t0 = System.currentTimeMillis();

    provisionedSessions.computeIfAbsent(sessionKey, key -> {
      wasAlreadyUp[0] = false;
      try {
        startSession(key, mcpCommand, mcpArgs, mcpEnv);
        return true;
      } catch (Exception e) {
        failure[0] = e; // returning null below means computeIfAbsent won't record this key —
        return null;    // next call for the same key will retry from scratch.
      }
    });

    if (failure[0] != null) {
      throw failure[0];
    }

    if (wasAlreadyUp[0]) {
      log.info("[TIMING] {} — reused already-running sandbox from earlier in this turn, "
          + "0 ms spin-up (same Cloud Run instance via session-affinity cookie)", sessionKey);
      return;
    }

    log.info("[TIMING] {} — server spin-up (Cloud Run cold start + MCP handshake, end to end): {} ms",
        sessionKey, System.currentTimeMillis() - t0);
  }

  public Map<String, Object> callToolBlocking(String sessionKey, String toolName, Map<String, Object> args)
      throws Exception {
    // A real JSON-RPC 2.0 "tools/call" request — the sandbox's /mcp endpoint doesn't
    // interpret this at all, it's forwarded verbatim to the subprocess's stdin.
    ObjectNode message = objectMapper.createObjectNode();
    message.put("jsonrpc", "2.0");
    message.put("id", UUID.randomUUID().toString());
    message.put("method", "tools/call");
    ObjectNode params = message.putObject("params");
    params.put("name", toolName);
    params.set("arguments", objectMapper.valueToTree(args));

    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("sessionId", sessionKey);
    envelope.set("message", message);

    long t0 = System.currentTimeMillis();
    HttpResponse<String> response = send("/mcp", envelope.toString(), sessionKey, CALL_TIMEOUT);
    if (response.statusCode() == 409) {
      // Affinity missed (instance evicted/scaled down mid-turn) or the session was never
      // started on the instance we landed on — best-effort routing's one failure mode.
      throw new IllegalStateException(
          "Sandbox session " + sessionKey + " not found on the routed instance "
              + "(session-affinity miss) — HTTP 409: " + response.body());
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Sandbox tool call failed: HTTP " + response.statusCode() + " — " + response.body());
    }

    JsonNode rpcResponse = objectMapper.readTree(response.body());
    log.info("[TIMING] {} — tool call '{}' round trip: {} ms", sessionKey, toolName,
        System.currentTimeMillis() - t0);

    if (rpcResponse.has("error")) {
      // Protocol-level failure (transport error, unsupported method, ...) — the
      // sandbox's dispatch itself threw, same as the Job variant's CALL_ERROR.
      throw new RuntimeException(
          rpcResponse.path("error").path("message").asText("sandbox tool call failed"));
    }
    // "result" here is exactly McpBridge.toJsonFriendlyResult()'s flattened shape
    // ({"text": "..."} or {"error": "..."} or {"content": "..."}) — the same map the Job
    // variant carried inside its WebSocket CALL_RESULT message. A tool-level error (the
    // real MCP server answered, but CallToolResult.isError() was true) shows up as the
    // "error" key INSIDE this map, not as a protocol-level error above.
    JsonNode result = rpcResponse.path("result");
    if (result.has("error")) {
      throw new RuntimeException(result.path("error").asText("tool execution failed"));
    }
    return objectMapper.convertValue(result, Map.class);
  }

  /** Real MCP {@code tools/list} against the already-started sandbox — no hardcoded
   *  tool name/description/schema anywhere. DynamicAgentFactory calls this once per
   *  chat request, right after {@link #ensureActiveSandbox}, to build the agent's tool
   *  set from whatever the live MCP server actually declares. */
  public List<Map<String, Object>> listTools(String sessionKey) throws Exception {
    ObjectNode message = objectMapper.createObjectNode();
    message.put("jsonrpc", "2.0");
    message.put("id", UUID.randomUUID().toString());
    message.put("method", "tools/list");
    message.set("params", objectMapper.createObjectNode());

    ObjectNode envelope = objectMapper.createObjectNode();
    envelope.put("sessionId", sessionKey);
    envelope.set("message", message);

    long t0 = System.currentTimeMillis();
    HttpResponse<String> response = send("/mcp", envelope.toString(), sessionKey, CALL_TIMEOUT);
    if (response.statusCode() == 409) {
      throw new IllegalStateException(
          "Sandbox session " + sessionKey + " not found on the routed instance "
              + "(session-affinity miss) while listing tools — HTTP 409: " + response.body());
    }
    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Sandbox tools/list failed: HTTP " + response.statusCode() + " — " + response.body());
    }

    JsonNode rpcResponse = objectMapper.readTree(response.body());
    if (rpcResponse.has("error")) {
      throw new RuntimeException(
          rpcResponse.path("error").path("message").asText("sandbox tools/list failed"));
    }

    List<Map<String, Object>> tools = new ArrayList<>();
    for (JsonNode tool : rpcResponse.path("result").path("tools")) {
      tools.add(objectMapper.convertValue(tool, Map.class));
    }
    log.info("[TIMING] {} — tools/list discovered {} real MCP tools in {} ms", sessionKey,
        tools.size(), System.currentTimeMillis() - t0);
    return tools;
  }

  public void destroySandbox(String sessionKey) {
    if (!provisionedSessions.containsKey(sessionKey)) {
      return; // this turn never touched the sandbox — nothing to tear down
    }
    try {
      ObjectNode body = objectMapper.createObjectNode();
      body.put("sessionId", sessionKey);
      send("/session/close", body.toString(), sessionKey, CLOSE_TIMEOUT);
    } catch (Exception e) {
      // Best effort, same spirit as adk-mcp-sandbox's destroy(): if this fails, the
      // instance still self-terminates once EXIT_AFTER_SESSION notices its MCP process
      // is gone, or Cloud Run reclaims it on its own idle schedule regardless.
      log.warn("Failed to close sandbox session {} cleanly: {}", sessionKey, e.getMessage());
    } finally {
      provisionedSessions.remove(sessionKey);
      affinityCookieBySessionKey.remove(sessionKey);
    }
  }

  private void startSession(String sessionKey, String mcpCommand, List<String> mcpArgs,
      Map<String, String> mcpEnv) throws Exception {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("sessionId", sessionKey);
    body.put("mcpCommand", mcpCommand);
    body.set("mcpArgs", objectMapper.valueToTree(mcpArgs));
    body.set("mcpEnv", objectMapper.valueToTree(mcpEnv));

    long t0 = System.currentTimeMillis();
    HttpRequest request = requestBuilder("/session/start", START_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200) {
      throw new IllegalStateException(
          "Sandbox failed to start for " + sessionKey + ": HTTP " + response.statusCode()
              + " — " + response.body());
    }

    captureAffinityCookie(sessionKey, response);
    log.info("[TIMING] {} — POST /session/start returned in {} ms (Cloud Run cold start + npx "
            + "spawn + real MCP initialize handshake, all synchronous on this one call)",
        sessionKey, System.currentTimeMillis() - t0);
  }

  private HttpResponse<String> send(String path, String jsonBody, String sessionKey, Duration timeout)
      throws Exception {
    HttpRequest.Builder builder = requestBuilder(path, timeout)
        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    String cookie = affinityCookieBySessionKey.get(sessionKey);
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest.Builder requestBuilder(String path, Duration timeout) {
    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(sandboxServiceUrl + path))
        .timeout(timeout)
        .header("Content-Type", "application/json");
    if (requireAuthToken) {
      builder.header("Authorization", "Bearer " + idTokenSupplier().bearerToken());
    }
    return builder;
  }

  private void captureAffinityCookie(String sessionKey, HttpResponse<String> response) {
    response.headers().allValues("Set-Cookie").stream().findFirst().ifPresentOrElse(
        setCookie -> {
          // Only "name=value" belongs in a request's Cookie header — strip attributes
          // (Path=, Max-Age=, ...).
          String nameValue = setCookie.split(";", 2)[0].trim();
          affinityCookieBySessionKey.put(sessionKey, nameValue);
          log.debug("{} — captured session-affinity cookie: {}", sessionKey, nameValue);
        },
        () -> log.warn("{} — Service returned no Set-Cookie; session-affinity for follow-up "
            + "calls in this turn is not guaranteed (check --session-affinity is enabled "
            + "on the Cloud Run Service)", sessionKey));
  }

  private IdTokenSupplier idTokenSupplier() {
    IdTokenSupplier local = idTokenSupplier;
    if (local == null) {
      synchronized (this) {
        if (idTokenSupplier == null) {
          idTokenSupplier = new IdTokenSupplier(sandboxServiceUrl);
        }
        local = idTokenSupplier;
      }
    }
    return local;
  }
}
