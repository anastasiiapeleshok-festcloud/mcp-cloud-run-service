package ai.festcloud.adkpoc.engine.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Hardcoded-to-Operaton config, read from env/properties at startup. Identical to
 *  adk-mcp-sandbox — unaffected by the Job-vs-Service transport swap. */
@Configuration
@ConfigurationProperties(prefix = "operaton")
public class OperatonMcpProperties {

  private String baseUrl;
  private String username;
  private String password;

  public String baseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String username() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String password() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }
}
