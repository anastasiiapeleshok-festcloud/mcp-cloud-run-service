package poc.mcp.sandboxservice;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCNotification;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A dumb pipe to the stdio MCP process: the caller's own MCP client (in Engine) runs the
 * protocol — initialize, tools/list, tools/call — and this class only moves its raw JSON-RPC
 * messages to the process's stdin and hands back the matching stdout message. No typing, no
 * flattening, so nothing the real server says is lost on the way (unlike {@link McpBridge}).
 *
 * <p>The process is single-tenant per instance (concurrency=1), but one Engine turn may open
 * more than one MCP client against it (sync + reconnect), so a repeated {@code initialize} is
 * answered from the first reply instead of being sent to a process that already handshook, and
 * only the first {@code notifications/initialized} is forwarded.
 *
 * <p>Server-initiated messages (logging notifications, sampling requests) have no HTTP channel
 * back and are logged and dropped.
 */
public class StdioRelay implements SandboxSession {

  private static final Logger log = LoggerFactory.getLogger(StdioRelay.class);
  private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(120);

  private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
  private final ConcurrentMap<String, CompletableFuture<JSONRPCResponse>> pending =
      new ConcurrentHashMap<>();

  private StdioClientTransport transport;
  private JSONRPCResponse initializeReply;
  private boolean initializedNotificationForwarded;

  public McpJsonMapper jsonMapper() {
    return jsonMapper;
  }

  /** Spawns the stdio process. Does NOT perform any MCP handshake — the caller does. */
  public void start(String command, List<String> args, Map<String, String> env) {
    ServerParameters params = ServerParameters.builder(command).args(args).env(env).build();
    log.info("Spawning stdio MCP server (raw relay): {} {}", command, args);
    long t0 = System.currentTimeMillis();

    transport = new StdioClientTransport(params, jsonMapper);
    transport.setStdErrorHandler(line -> log.info("[mcp stderr] {}", line));
    transport.connect(inbound -> inbound.doOnNext(this::onInbound).then(Mono.<JSONRPCMessage>empty()))
        .block();
    log.info("[TIMING] stdio process spawn: {} ms", System.currentTimeMillis() - t0);
  }

  /** Forwards one client message. Returns the process's reply, or null when there is none. */
  public synchronized JSONRPCResponse relay(JSONRPCMessage message) throws Exception {
    if (message instanceof JSONRPCRequest request) {
      return relayRequest(request);
    }
    if (message instanceof JSONRPCNotification n
        && "notifications/initialized".equals(n.method())) {
      if (initializedNotificationForwarded) {
        return null;
      }
      initializedNotificationForwarded = true;
    }
    transport.sendMessage(message).block();
    return null;
  }

  private JSONRPCResponse relayRequest(JSONRPCRequest request) throws Exception {
    boolean isInitialize = "initialize".equals(request.method());
    if (isInitialize && initializeReply != null) {
      return new JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
          initializeReply.result(), initializeReply.error());
    }

    String key = String.valueOf(request.id());
    CompletableFuture<JSONRPCResponse> reply = new CompletableFuture<>();
    pending.put(key, reply);
    try {
      transport.sendMessage(request).block();
      JSONRPCResponse response = reply.get(REPLY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (isInitialize && response.error() == null) {
        initializeReply = response;
      }
      return response;
    } finally {
      pending.remove(key);
    }
  }

  private void onInbound(JSONRPCMessage message) {
    if (message instanceof JSONRPCResponse response) {
      CompletableFuture<JSONRPCResponse> waiter = pending.get(String.valueOf(response.id()));
      if (waiter != null) {
        waiter.complete(response);
        return;
      }
    }
    log.info("Dropping server-initiated/unmatched MCP message (no channel back to the "
        + "caller over plain HTTP): {}", message);
  }

  @Override
  public void close() {
    if (transport != null) {
      transport.closeGracefully().block();
    }
  }
}
