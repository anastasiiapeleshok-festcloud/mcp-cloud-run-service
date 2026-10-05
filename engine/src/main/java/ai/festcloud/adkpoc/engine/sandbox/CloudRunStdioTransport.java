package ai.festcloud.adkpoc.engine.sandbox;

import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCRequest;
import io.modelcontextprotocol.spec.McpSchema.JSONRPCResponse;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The one piece that differs from a stock ADK {@code McpToolset}: an MCP client transport
 * whose "stdio process" lives in the Cloud Run Service instead of on this machine.
 *
 * <p>Stock stdio transport = spawn the process, write JSON-RPC to its stdin, read stdout.
 * This one = {@code connect()} asks the Service to spawn the SAME process ({@code POST
 * /session/start}), and each {@code sendMessage()} POSTs the raw JSON-RPC message to {@code
 * /mcp/rpc}, where the Service writes it to the process's stdin and answers with the
 * matching stdout message in the HTTP response. Everything above the transport —
 * {@code McpSyncClient}, {@code McpToolset}, {@code McpTool}, result wrapping — is the
 * unmodified ADK/MCP SDK code.
 *
 * <p>Limits of the request/response wire: the server cannot push messages on its own
 * (logging notifications, sampling requests) — only the reply to each request comes back.
 *
 * <p>{@code tools/list} is cached after the first successful answer: {@code McpToolset}
 * re-lists tools on every LLM request, and the tool set of one sandbox never changes
 * within a turn, so repeating the Cloud Run round trip would only add latency.
 *
 * <p>{@link #closeGracefully()} is deliberately a no-op: the sandbox's lifetime belongs to
 * ChatController's {@code finally}, not to any single MCP client (ADK may build more than
 * one client per sandbox, e.g. after a reconnect).
 */
public final class CloudRunStdioTransport implements McpClientTransport {

  private static final Logger log = LoggerFactory.getLogger(CloudRunStdioTransport.class);

  private final SandboxSessionManager sessions;
  private final String sessionKey;
  private final ServerParameters server;
  private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

  private volatile Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> inboundHandler;
  // The SDK subscribes to connect() without waiting for it and sends `initialize` right away
  // (a local stdio transport just buffers that write). Here connect() is a real HTTP call, so
  // every send must wait for it — cached so the sandbox is started exactly once.
  private volatile Mono<Void> connection;
  private volatile JSONRPCResponse cachedToolsList;

  public CloudRunStdioTransport(SandboxSessionManager sessions, String sessionKey,
      ServerParameters server) {
    this.sessions = sessions;
    this.sessionKey = sessionKey;
    this.server = server;
  }

  @Override
  public Mono<Void> connect(
      Function<Mono<JSONRPCMessage>, Mono<JSONRPCMessage>> handler) {
    this.inboundHandler = handler;
    this.connection = Mono.<Void>fromCallable(() -> {
      sessions.ensureActiveRelaySandbox(sessionKey, server.getCommand(), server.getArgs(), server.getEnv());
      return null;
    }).subscribeOn(Schedulers.boundedElastic()).cache();
    return connection;
  }

  @Override
  public Mono<Void> sendMessage(JSONRPCMessage message) {
    Mono<Void> connected = connection;
    if (connected == null) {
      return Mono.error(new IllegalStateException("MCP transport used before connect()"));
    }
    return connected.then(Mono.<Void>fromCallable(() -> {
      relay(message);
      return null;
    }).subscribeOn(Schedulers.boundedElastic()));
  }

  private void relay(JSONRPCMessage message) throws Exception {
    String method = message instanceof JSONRPCRequest r ? r.method() : null;

    if ("tools/list".equals(method) && cachedToolsList != null) {
      JSONRPCRequest request = (JSONRPCRequest) message;
      deliver(new JSONRPCResponse(McpSchema.JSONRPC_VERSION, request.id(),
          cachedToolsList.result(), null));
      return;
    }

    long t0 = System.currentTimeMillis();
    String reply = sessions.relayRpc(sessionKey, jsonMapper.writeValueAsString(message));
    if (method != null) {
      log.info("[TIMING] {} — MCP '{}' round trip via Cloud Run: {} ms", sessionKey, method,
          System.currentTimeMillis() - t0);
    }
    if (reply == null || reply.isBlank()) {
      return; // notification: nothing comes back
    }

    JSONRPCMessage inbound = McpSchema.deserializeJsonRpcMessage(jsonMapper, reply);
    if ("tools/list".equals(method) && inbound instanceof JSONRPCResponse resp
        && resp.error() == null) {
      cachedToolsList = resp;
    }
    deliver(inbound);
  }

  private void deliver(JSONRPCMessage inbound) {
    inboundHandler.apply(Mono.just(inbound)).subscribe(
        ignored -> { },
        e -> log.warn("{} — MCP client failed to process inbound message: {}", sessionKey,
            e.toString()));
  }

  @Override
  public Mono<Void> closeGracefully() {
    return Mono.empty();
  }

  @Override
  public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
    return jsonMapper.convertValue(data, typeRef);
  }
}
