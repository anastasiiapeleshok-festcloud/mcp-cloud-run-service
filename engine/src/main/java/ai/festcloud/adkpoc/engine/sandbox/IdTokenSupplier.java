package ai.festcloud.adkpoc.engine.sandbox;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mints Google-signed ID tokens for calling a Cloud Run Service with
 * {@code --no-allow-unauthenticated} — the standard service-to-service auth pattern
 * (audience = the target Service's own URL). Uses Application Default Credentials, same
 * as {@code JobsClient.create()} did in adk-mcp-sandbox — same `gcloud auth
 * application-default login` step, just a different Google client library underneath.
 *
 * <p>Only used when {@code sandbox.require-auth-token=true}. Left off by default so the
 * very first end-to-end run doesn't also require granting run.invoker up front — flip it
 * on once the Service is deployed with {@code --no-allow-unauthenticated} (see README).
 */
public class IdTokenSupplier {

  private static final Logger log = LoggerFactory.getLogger(IdTokenSupplier.class);

  private final IdTokenCredentials credentials;

  public IdTokenSupplier(String audience) {
    try {
      GoogleCredentials adc = GoogleCredentials.getApplicationDefault();
      if (!(adc instanceof IdTokenProvider provider)) {
        throw new IllegalStateException(
            "Application Default Credentials don't support ID tokens (unexpected credential type: "
                + adc.getClass() + ")");
      }
      this.credentials = IdTokenCredentials.newBuilder()
          .setIdTokenProvider(provider)
          .setTargetAudience(audience)
          .build();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Failed to build ID token credentials — run 'gcloud auth application-default login' first", e);
    }
  }

  /** Refreshes only if expired — cheap to call before every request. */
  public String bearerToken() {
    try {
      credentials.refreshIfExpired();
      return credentials.getIdToken().getTokenValue();
    } catch (Exception e) {
      log.error("Failed to refresh ID token", e);
      throw new IllegalStateException(e);
    }
  }
}
