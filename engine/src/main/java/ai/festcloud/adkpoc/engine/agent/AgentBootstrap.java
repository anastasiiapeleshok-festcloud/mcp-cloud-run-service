package ai.festcloud.adkpoc.engine.agent;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.memory.BaseMemoryService;
import com.google.adk.memory.InMemoryMemoryService;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Only the state that must survive ACROSS chat requests for the same user (conversation
 * history, artifacts, memory) is wired here as shared singleton beans. The agent
 * hierarchy itself — orchestrator_agent, operaton_agent, its tools, the Runner — is no
 * longer built here: see {@link DynamicAgentFactory}, which builds all of that fresh on
 * every chat request after discovering the real MCP server's actual tool set. Using
 * these same shared service instances across every freshly-built Runner is what lets
 * conversation history persist even though the Runner/agent objects themselves don't.
 */
@Configuration
public class AgentBootstrap {

  @Bean
  public BaseSessionService sessionService() {
    return new InMemorySessionService();
  }

  @Bean
  public BaseArtifactService artifactService() {
    return new InMemoryArtifactService();
  }

  @Bean
  public BaseMemoryService memoryService() {
    return new InMemoryMemoryService();
  }
}
