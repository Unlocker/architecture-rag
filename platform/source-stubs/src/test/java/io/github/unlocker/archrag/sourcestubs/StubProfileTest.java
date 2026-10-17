package io.github.unlocker.archrag.sourcestubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StubProfileTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void unsetOrBlankIsDefault() {
    assertThat(StubProfile.parse(null)).isEqualTo(StubProfile.DEFAULT);
    assertThat(StubProfile.parse("  ")).isEqualTo(StubProfile.DEFAULT);
    assertThat(StubProfile.parse("default")).isEqualTo(StubProfile.DEFAULT);
  }

  @Test
  void demoIsParsedIgnoringCaseAndSpaces() {
    assertThat(StubProfile.parse(" Demo ")).isEqualTo(StubProfile.DEMO);
  }

  @Test
  void unknownValueIsRejectedWithoutEchoingIt() {
    assertThatThrownBy(() -> StubProfile.parse("prod-secret-name"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("default, demo")
        .hasMessageNotContaining("prod-secret-name");
  }

  @Test
  void defaultProfileKeepsTheCurrentSeed() {
    var sources = StubProfile.DEFAULT.sources(clock);
    var expected = StubSources.seeded(clock);
    for (SourceSystem s : SourceSystem.values()) {
      assertThat(sources.get(s).fetchChanges(null, 1000).changes()).isEqualTo(expected.get(s).fetchChanges(null, 1000).changes());
    }
    assertThat(StubProfile.DEFAULT.eamApi(clock).find(EamApiSeed.IT_SYSTEM_TYPE, 5101)).isPresent();
    assertThat(StubProfile.DEFAULT.eamApi(clock).find(EamApiSeed.IT_SYSTEM_TYPE, 5103)).isEmpty();
  }

  @Test
  void demoProfileCarriesTheDemoData() {
    assertThat(StubProfile.DEMO.sources(clock).get(SourceSystem.SCM).fetchById("SERVICE", "svc-payments-router")).isPresent();
    assertThat(StubProfile.DEMO.eamApi(clock).find(EamApiSeed.IT_SYSTEM_TYPE, 5103)).isPresent();
  }
}
