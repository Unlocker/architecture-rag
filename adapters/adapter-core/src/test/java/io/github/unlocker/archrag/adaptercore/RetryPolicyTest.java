package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private final RetryPolicy policy =
      new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(8));

  private static SourceUnavailableException failure(Duration retryAfter) {
    return new SourceUnavailableException(SourceSystem.EAM, 503, retryAfter);
  }

  @Test
  void backoffGrowsExponentiallyWithJitterInsideBounds() {
    Random random = new Random(7);
    for (int attempt = 1; attempt <= 4; attempt++) {
      long ceiling = Math.min(8000, 1000L << (attempt - 1));
      for (int i = 0; i < 50; i++) {
        long ms = policy.delay(attempt, failure(null), random).toMillis();
        assertThat(ms).isBetween(ceiling / 2, ceiling);
      }
    }
  }

  @Test
  void backoffIsCappedByMaxDelay() {
    assertThat(policy.delay(30, failure(null), new Random(1)))
        .isLessThanOrEqualTo(Duration.ofSeconds(8));
  }

  @Test
  void retryAfterRaisesTheDelayButNeverLowersIt() {
    Random random = new Random(3);
    assertThat(policy.delay(1, failure(Duration.ofSeconds(20)), random))
        .isEqualTo(Duration.ofSeconds(20));
    assertThat(policy.delay(4, failure(Duration.ofMillis(10)), random))
        .isGreaterThanOrEqualTo(Duration.ofSeconds(4));
  }

  @Test
  void rejectsInvalidParameters() {
    assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RetryPolicy(1, Duration.ofSeconds(3), Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void configToStringHidesWebhookSecret() {
    AdapterConfig c = AdapterConfig.of(SourceSystem.EAM, java.net.URI.create("http://x"), "s3cr3t-value");
    assertThat(c.toString()).doesNotContain("s3cr3t-value");
  }
}
