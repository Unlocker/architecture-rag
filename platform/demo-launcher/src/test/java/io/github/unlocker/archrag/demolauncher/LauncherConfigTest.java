package io.github.unlocker.archrag.demolauncher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LauncherConfigTest {

  private static Map<String, String> fullEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("ARCHRAG_PG_URL", "jdbc:postgresql://postgres:5432/archrag");
    env.put("ARCHRAG_PG_USER", "archrag");
    env.put("ARCHRAG_PG_PASSWORD", "pg-secret-value");
    env.put("ARCHRAG_S3_ENDPOINT", "http://seaweedfs:8333");
    env.put("ARCHRAG_S3_ACCESS_KEY", "ak");
    env.put("ARCHRAG_S3_SECRET_KEY", "s3-secret-value");
    env.put("ARCHRAG_S3_BUCKET", "raw");
    env.put("ARCHRAG_SOURCE_URL", "http://stub-eam:8080");
    env.put("ARCHRAG_WEBHOOK_SECRET", "hook-secret-value");
    return env;
  }

  @Test
  void adapterDefaults() {
    var c = LauncherConfig.adapter(SourceSystem.EAM, fullEnv());
    assertThat(c.webhookPort()).isEqualTo(8080);
    assertThat(c.healthPort()).isEqualTo(8090);
    assertThat(c.pollInterval()).isEqualTo(Duration.ofSeconds(30));
    assertThat(c.reconcileInterval()).isEqualTo(Duration.ofHours(6));
    assertThat(c.sourceUrl()).hasToString("http://stub-eam:8080");
  }

  @Test
  void adapterOverrides() {
    var env = fullEnv();
    env.put("ARCHRAG_WEBHOOK_PORT", "9000");
    env.put("ARCHRAG_POLL_INTERVAL_SECONDS", "5");
    var c = LauncherConfig.adapter(SourceSystem.SCM, env);
    assertThat(c.webhookPort()).isEqualTo(9000);
    assertThat(c.pollInterval()).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void missingRequiredFailsFastNamingVariablesOnly() {
    var env = fullEnv();
    env.remove("ARCHRAG_PG_PASSWORD");
    env.put("ARCHRAG_WEBHOOK_SECRET", "  ");
    assertThatThrownBy(() -> LauncherConfig.adapter(SourceSystem.EAM, env))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ARCHRAG_PG_PASSWORD")
        .hasMessageContaining("ARCHRAG_WEBHOOK_SECRET")
        .hasMessageNotContaining("s3-secret-value");
  }

  @Test
  void invalidPortAndIntervalAreRejected() {
    var badPort = fullEnv();
    badPort.put("ARCHRAG_WEBHOOK_PORT", "70000");
    assertThatThrownBy(() -> LauncherConfig.adapter(SourceSystem.EAM, badPort))
        .hasMessageContaining("ARCHRAG_WEBHOOK_PORT");
    var badInterval = fullEnv();
    badInterval.put("ARCHRAG_POLL_INTERVAL_SECONDS", "0");
    assertThatThrownBy(() -> LauncherConfig.adapter(SourceSystem.EAM, badInterval))
        .hasMessageContaining("ARCHRAG_POLL_INTERVAL_SECONDS");
    var notNumber = fullEnv();
    notNumber.put("ARCHRAG_HEALTH_PORT", "abc");
    assertThatThrownBy(() -> LauncherConfig.adapter(SourceSystem.EAM, notNumber))
        .hasMessageContaining("ARCHRAG_HEALTH_PORT");
  }

  @Test
  void toStringHidesSecrets() {
    String text = LauncherConfig.adapter(SourceSystem.EAM, fullEnv()).toString();
    assertThat(text).doesNotContain("pg-secret-value", "s3-secret-value", "hook-secret-value");
  }

  @Test
  void unknownSourceIsRejected() {
    assertThatThrownBy(() -> LauncherConfig.parseSystem("JIRA")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LauncherConfig.parseSystem(null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(LauncherConfig.parseSystem("DEPLOY_MAP")).isEqualTo(SourceSystem.DEPLOY_MAP);
  }

  @Test
  void stubNeedsNoSecretsAndHasDefaultPort() {
    assertThat(LauncherConfig.stub(SourceSystem.CMDB, Map.of()).port()).isEqualTo(8080);
    assertThat(LauncherConfig.stub(SourceSystem.CMDB, Map.of("ARCHRAG_STUB_PORT", "8181")).port()).isEqualTo(8181);
  }

  @Test
  void eamApiStubRequiresTokenAndHidesItInToString() {
    assertThatThrownBy(() -> LauncherConfig.eamApiStub(Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ARCHRAG_EAM_API_TOKEN");
    var c = LauncherConfig.eamApiStub(Map.of("ARCHRAG_EAM_API_TOKEN", "tok-secret", "ARCHRAG_STUB_PORT", "9100"));
    assertThat(c.port()).isEqualTo(9100);
    assertThat(c.token()).isEqualTo("tok-secret");
    assertThat(c.toString()).doesNotContain("tok-secret");
  }

  @Test
  void stubWebhookTargetRequiresSecret() {
    var env = Map.of("ARCHRAG_WEBHOOK_TARGET", "http://adapter-eam:8080/webhook");
    assertThatThrownBy(() -> LauncherConfig.stub(SourceSystem.EAM, env))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ARCHRAG_WEBHOOK_SECRET");
  }

  @Test
  void stubWebhookTargetWithSecretIsParsedAndSecretIsHidden() {
    var c = LauncherConfig.stub(SourceSystem.EAM,
        Map.of("ARCHRAG_WEBHOOK_TARGET", "http://adapter-eam:8080/webhook", "ARCHRAG_WEBHOOK_SECRET", "topsecret"));
    assertThat(c.webhookTarget()).hasToString("http://adapter-eam:8080/webhook");
    assertThat(c.webhookSecret()).isEqualTo("topsecret");
    assertThat(c.toString()).doesNotContain("topsecret");
  }

  @Test
  void stubWithoutTargetHasNoWebhook() {
    var c = LauncherConfig.stub(SourceSystem.EAM, Map.of("ARCHRAG_WEBHOOK_SECRET", "x"));
    assertThat(c.webhookTarget()).isNull();
  }
}
