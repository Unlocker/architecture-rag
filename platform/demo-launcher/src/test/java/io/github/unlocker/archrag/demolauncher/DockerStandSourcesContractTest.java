package io.github.unlocker.archrag.demolauncher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Источники стенда ({@code platform/docker}) покрывают каждый {@link SourceSystem}: список там задан вручную. */
class DockerStandSourcesContractTest {

  private static final Path DOCKER = Path.of("../docker");

  @ParameterizedTest
  @EnumSource(SourceSystem.class)
  void replaySourcesListsEverySource(SourceSystem source) throws IOException {
    String lib = read("scripts/lib.sh");
    assertThat(lib).contains("urn:corp:" + source.code());
  }

  @ParameterizedTest
  @EnumSource(SourceSystem.class)
  void composeHasControlUrlAndAdapterCommand(SourceSystem source) throws IOException {
    String compose = read("docker-compose.yml");
    assertThat(compose)
        .contains("ARCHRAG_" + source.code().toUpperCase(Locale.ROOT) + "_CONTROL_URL:")
        .contains("command: [\"adapter\", \"" + source.name() + "\"]");
  }

  @ParameterizedTest
  @EnumSource(SourceSystem.class)
  void nginxProxiesWebhook(SourceSystem source) throws IOException {
    assertThat(read("proxy/nginx.conf")).contains("location = /webhooks/" + source.code() + " {");
  }

  /** Профиль данных задаётся всем пяти заглушкам разом, иначе профили смешаются; по умолчанию — {@code default}. */
  @ParameterizedTest
  @ValueSource(strings = {"stub-eam", "stub-scm", "stub-cmdb", "stub-deploymap", "eam-api-stub"})
  void everyStubGetsTheStubProfile(String service) throws IOException {
    String compose = read("docker-compose.yml");
    int start = compose.indexOf("\n  " + service + ":\n");
    assertThat(start).isGreaterThanOrEqualTo(0);
    int end = compose.indexOf("\n  # ", start + 1);
    assertThat(compose.substring(start, end < 0 ? compose.length() : end))
        .contains("ARCHRAG_STUB_PROFILE: ${ARCHRAG_STUB_PROFILE:-default}");
  }

  @org.junit.jupiter.api.Test
  void seedScriptLoadsSourcesInTheDocumentedOrder() throws IOException {
    assertThat(read("demo/lib.sh")).contains("SEED_SOURCES=(eam scm cmdb deploymap)")
        .contains("export ARCHRAG_STUB_PROFILE=demo");
    assertThat(read("demo/seed.sh")).contains("SEED_SOURCES").contains("run_reconcile");
  }

  private static String read(String relative) throws IOException {
    return Files.readString(DOCKER.resolve(relative));
  }
}
