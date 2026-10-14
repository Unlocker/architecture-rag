package io.github.unlocker.archrag.graphquerycore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StalenessTest {

  private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
  private final Staleness staleness =
      new Staleness(Duration.ofDays(7), Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void freshTimeIsNotStale() {
    assertThat(staleness.isStale("2026-10-05T11:00:00Z")).isFalse();
  }

  @Test
  void exactlyOnThresholdIsStillFreshAndOneSecondOlderIsStale() {
    assertThat(staleness.isStale("2026-09-28T12:00:00Z")).isFalse();
    assertThat(staleness.isStale("2026-09-28T11:59:59Z")).isTrue();
  }

  @Test
  void zoneOffsetIsRespected() {
    assertThat(staleness.isStale("2026-09-28T14:00:00+02:00")).isFalse();
    assertThat(staleness.isStale("2026-09-28T13:59:59+02:00")).isTrue();
  }

  @Test
  void missingOrUnparseableTimeIsStale() {
    assertThat(staleness.isStale(null)).isTrue();
    assertThat(staleness.isStale("")).isTrue();
    assertThat(staleness.isStale("not-a-date")).isTrue();
  }

  @Test
  void noActiveSourceIsStaleEvenWhenTimeIsFresh() {
    assertThat(staleness.isStale("2026-10-05T11:00:00Z", false)).isTrue();
    assertThat(staleness.isStale("2026-10-05T11:00:00Z", true)).isFalse();
  }

  @Test
  void oldTimeIsStaleEvenWithActiveSource() {
    assertThat(staleness.isStale("2026-09-28T11:59:59Z", true)).isTrue();
    assertThat(staleness.isStale(null, true)).isTrue();
  }

  @Test
  void staleAfterIsExposed() {
    assertThat(staleness.staleAfter()).isEqualTo(Duration.ofDays(7));
  }

  @Test
  void nonPositiveThresholdIsRejected() {
    assertThatThrownBy(() -> new Staleness(Duration.ZERO, Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Staleness(Duration.ofDays(-1), Clock.systemUTC())).isInstanceOf(IllegalArgumentException.class);
  }
}
