package ai.festcloud.adkpoc.engine.api;

import ai.festcloud.adkpoc.engine.agent.DynamicAgentFactory;
import ai.festcloud.adkpoc.engine.sandbox.SandboxSessionManager;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Builds the WHOLE agent hierarchy fresh for every request via {@link
 * DynamicAgentFactory} — no fixed Runner bean anymore. The sandbox's sessionKey is
 * generated HERE, before the agent even exists, because tool discovery (tools/list)
 * needs a running sandbox before DynamicAgentFactory can build any tools at all — unlike
 * before, this key no longer comes from an ADK-generated invocationId after the fact.
 */
@RestController
public class ChatController {

  private static final Logger log = LoggerFactory.getLogger(ChatController.class);
  private static final String USER_ID = "poc-user";

  private final DynamicAgentFactory agentFactory;
  private final SandboxSessionManager sandboxSessionManager;

  public ChatController(DynamicAgentFactory agentFactory, SandboxSessionManager sandboxSessionManager) {
    this.agentFactory = agentFactory;
    this.sandboxSessionManager = sandboxSessionManager;
  }

  @PostMapping("/chat")
  public Map<String, Object> chat(@RequestBody Map<String, String> body) throws Exception {
    long t0 = System.currentTimeMillis();
    String message = body.get("message");
    log.info("[TIMING] /chat received: \"{}\"", message);

    // Ours, not ADK's — needed BEFORE the agent exists, since building the agent means
    // discovering tools from an already-running sandbox for this exact key.
    String sandboxKey = "chat:" + UUID.randomUUID();

    long t1 = System.currentTimeMillis();
    Runner runner = agentFactory.buildRunnerForSession(sandboxKey);
    long t2 = System.currentTimeMillis();
    log.info("[TIMING] {} — agent build (sandbox start + tools/list + agent wiring): {} ms",
        sandboxKey, t2 - t1);

    try {
      // New ADK session on every request - no conversation history carries over, so the
      // model can't answer from a previous turn's tool results and every /chat does the
      // full work (tool calls included). Keeps repeated runs comparable.
      String sessionId = createSession(runner);

      Content content = Content.builder()
          .role("user")
          .parts(List.of(Part.fromText(message)))
          .build();

      long t3 = System.currentTimeMillis();
      List<Event> events = runner.runAsync(USER_ID, sessionId, content, RunConfig.builder().build())
          .toList()
          .blockingGet();
      long t4 = System.currentTimeMillis();
      log.info("[TIMING] runner.runAsync() total: {} ms (agent reasoning + any tool calls)", t4 - t3);

      List<String> responses = events.stream().flatMap(this::extractText).toList();

      long total = System.currentTimeMillis() - t0;
      log.info("[TIMING] TOTAL /chat request: {} ms", total);

      return Map.of("sessionId", sessionId, "responses", responses, "timingMs", total);
    } finally {
      // Always torn down, success or failure — sandboxKey is ours, generated up front,
      // so this doesn't depend on inspecting events the way the old invocationId-based
      // teardown did.
      long t5 = System.currentTimeMillis();
      sandboxSessionManager.destroySandbox(sandboxKey);
      log.info("[TIMING] {} — sandbox teardown: {} ms", sandboxKey, System.currentTimeMillis() - t5);
    }
  }

  private String createSession(Runner runner) {
    Session session = runner.sessionService()
        .createSession(runner.appName(), USER_ID)
        .blockingGet();
    return session.id();
  }

  private java.util.stream.Stream<String> extractText(Event event) {
    return event.content().flatMap(Content::parts).stream()
        .flatMap(List::stream)
        .map(Part::text)
        .filter(Optional::isPresent)
        .map(Optional::get);
  }
}
