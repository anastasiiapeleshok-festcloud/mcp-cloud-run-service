# adk-mcp-sandbox-service

Same "agents scope" as `adk-mcp-sandbox` (orchestrator_agent -> operaton_agent -> real
`Runner`/`LoggingPlugin`/`McpSessionManager`), swapped onto a **Cloud Run Service**
instead of a **Cloud Run Job**. `engine/agent/*` and `engine/api/ChatController.java` are
unchanged copies — only `engine/sandbox/*` (how the sandbox is provisioned and talked to)
and the sandbox container itself differ. `sandbox-service/McpBridge.java` is an
unmodified copy too — the real MCP client code path never changes between variants.

## What's different from adk-mcp-sandbox, mechanically

| | Job variant (`adk-mcp-sandbox`) | Service variant (this project) |
|---|---|---|
| How the sandbox starts | Engine calls `jobsClient.runJobAsync(...)` | Engine calls `POST {serviceUrl}/session/start` |
| How Engine reaches the sandbox | Sandbox dials OUT to Engine's `/ws/sandbox` (Engine needs a public URL / ngrok tunnel) | Engine dials the Service's own URL directly — no broker, no tunnel |
| Routing a 2nd tool call in the same turn to the same sandbox | The one open WebSocket connection (inherently pinned) | Cloud Run **session-affinity cookie**, `--concurrency=1` |
| "One sandbox per invocation" isolation | `System.exit(0)` when the WS closes | `EXIT_AFTER_SESSION=true` (default) — same effect after `/session/close` |
| Auth | Job execution overrides carry a hand-rolled token, checked in `SandboxWebSocketHandler`'s HELLO | Cloud Run IAM (`--no-allow-unauthenticated` + caller's ID token) — nothing hand-rolled |
| Wire protocol to the sandbox | Custom `{type:"CALL_TOOL", toolName, args}` envelope over the WS | Same shape, over plain HTTP: `POST /mcp` body is `{sessionId, message:{method:"tools/call"|"tools/list", params:{...}}}`, response is `{result}` or `{error}` |
| Streaming / progress | Not supported — one CALL_TOOL, one CALL_RESULT | Same — not supported here either (see note below) |

Deliberately **not** carried over: `SessionRegistry`, `SandboxWebSocketHandler`,
`WebSocketConfig`, `CloudRunSandboxProvisioner`, `BrokerConnection` — none of that exists
for the Service variant, because the Service accepts inbound calls directly. That's the
main complexity reduction you're measuring by building this.

**The real ADK MCP client is used exactly as in the Job variant.**
`sandbox-service/McpBridge.java` is an unmodified copy of `adk-mcp-sandbox`'s: real
`com.google.adk.tools.mcp.McpSessionManager`, real `McpSyncClient`, real stdio
subprocess — nothing about MCP is reimplemented here, only how a caller reaches this
container changed (HTTP request/response instead of a WebSocket message). Verified
locally end to end against `@modelcontextprotocol/server-everything`: `/session/start`
performs the real handshake, `/mcp` with `method:"tools/call"` returns the exact same
flattened `{"text": "..."}` shape `McpBridge.toJsonFriendlyResult()` always produced —
`SandboxSessionManager` on the Engine side parses that same shape, not raw MCP wire
format.

> An earlier pass of this project experimented with a generic, library-free raw
> JSON-RPC relay (`StdioProcessBridge`) plus SSE streaming of progress notifications —
> it worked (verified with `_meta.progressToken`), but moved away from "use google-adk
> stdio as it was for the Job variant," so it was reverted in favor of `McpBridge`. If
> progress streaming is wanted later, it needs a different approach that keeps
> `McpSyncClient` (e.g. a progress-notification hook on the ADK client, not yet
> confirmed to exist) rather than bypassing it.

## Known limitation to go in with eyes open

Cloud Run session affinity is **best-effort**, not a hard guarantee (unlike the Job
variant's held-open WebSocket, which is pinned by construction). If an instance is
evicted mid-turn, the second tool call's `/tool/call` gets routed elsewhere and comes
back `409` — `SandboxSessionManager` surfaces that as a failed tool call the same way the
Job variant's "sandbox never connected" timeout does. Worth specifically testing: fire a
prompt that makes `operaton_agent` call the tool twice in one turn and confirm both calls
land on the same instance (same `[TIMING] ... reused already-running sandbox ... 0 ms`
log line) rather than each paying a fresh cold start.

## Steps — yours to run, in order

### 1. Deploy the sandbox-service image to real Cloud Run

Open `deploy-service.sh`, fill in `PROJECT_ID`, then:
```bash
./deploy-service.sh
```
Creates real billed resources (Artifact Registry repo, Cloud Run Service) under your
account. Note the printed `SERVICE_URL` — you need it for step 4.

By default the script deploys with `--no-allow-unauthenticated`. It prints the
`gcloud run services add-iam-policy-binding` command to run next — do that (or redeploy
once with `--allow-unauthenticated` instead, for the fastest possible first smoke test,
and tighten it afterward).

### 2. Let Engine's own calls authenticate as you (only if using IAM auth)

```bash
gcloud auth application-default login
```
Picked up automatically by `IdTokenSupplier` when `sandbox.require-auth-token=true`.
Skip this and leave `SANDBOX_REQUIRE_AUTH` unset if you deployed with
`--allow-unauthenticated` for the first pass.

### 3. Nothing to expose on Engine's side

Unlike the Job variant, Engine does **not** need a public URL, a tunnel, or an open
inbound port for the sandbox to reach — Engine is the one making outbound calls, to the
Service's own https URL. Run Engine anywhere with outbound internet access (including
straight from your laptop).

### 4. Set the environment and run Engine

```bash
export SANDBOX_SERVICE_URL="https://<your-service-url>.a.run.app"
export SANDBOX_REQUIRE_AUTH=true   # only if you did step 2; omit/false otherwise
export OPERATON_BASE_URL="https://camunda-7-operaton-dev.festcloud.ai/engine-rest"
export OPERATON_USERNAME=<your username>
export OPERATON_PASSWORD=<your password>
export GOOGLE_API_KEY=<your Gemini API key>

mvn -pl engine -am clean package -DskipTests
java -jar engine/target/engine.jar
```
(Runs on port 8082 by default — different from `adk-mcp-sandbox`'s 8081, so you can run
both engines side by side for a direct timing comparison.)

### 5. Fire a real message through the real chain

```bash
curl -s -X POST http://localhost:8082/chat \
  -H 'Content-Type: application/json' \
  -d '{"message": "запусти тестову довготривалу операцію на 8 секунд, 4 кроки"}' | jq .
```

Watch the logs for the same `[TIMING]` lines the Job variant has (`server spin-up`,
`tool call round trip`, `sandbox teardown`, `TOTAL /chat request`) — that's the basis for
comparing the two variants' latency directly. Also watch for the sandbox-service's own
Cloud Run execution logs (`gcloud run services logs read mcp-sandbox-service`) to see the
`[TIMING] MCP spawn + handshake` and `[TIMING] real MCP tools/call` lines from inside the
container.

## Known gaps in this pass (intentionally deferred — same spirit as adk-mcp-sandbox)

- No retry on a session-affinity miss (`409` from `/mcp`) — it just fails the tool call
  and tears the session down, same failure mode as the Job variant's connect timeout.
- No progress-notification streaming (see note above) — same limitation the Job variant
  had, not a regression.
- Single hardcoded tool declared to Gemini (`SandboxMcpTool`), not a full multi-tool
  surface, same as the Job variant.
- No cancellation propagation into an in-flight tool call.
- `EXIT_AFTER_SESSION=true` is the default for isolation parity with the Job variant. Flip
  it to `false` (redeploy the Service with that env var set) to measure the cheaper/faster
  "warm reuse across sessions" trade-off discussed separately — same container stays up
  and reuses itself for the next, unrelated session instead of cold-starting fresh.
- Engine itself still runs locally, same as adk-mcp-sandbox's current state.
