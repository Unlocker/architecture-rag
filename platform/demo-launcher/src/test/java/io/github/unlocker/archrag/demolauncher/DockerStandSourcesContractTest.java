package io.github.unlocker.archrag.demolauncher;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

  private static String read(String relative) throws IOException {
    return Files.readString(DOCKER.resolve(relative));
  }
}
