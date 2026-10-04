package io.github.unlocker.archrag.graphquerycore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ResultBudgetTest {

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 1000);

  @Test
  void requestedBudgetIsClampedToLimits() {
    ResultBudget huge = new ResultBudget(100, 10_000, 1000, Duration.ofMinutes(5), 1_000_000);
    assertThat(huge.clampTo(LIMITS)).isEqualTo(LIMITS.asBudget());
  }

  @Test
  void smallerBudgetIsKept() {
    ResultBudget small = new ResultBudget(2, 5, 3, Duration.ofMillis(100), 10);
    assertThat(small.clampTo(LIMITS)).isEqualTo(small);
  }

  @Test
  void nonPositiveValuesAreRejected() {
    assertThatThrownBy(() -> new ResultBudget(0, 1, 1, Duration.ofSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResultBudget(1, 0, 1, Duration.ofSeconds(1), 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResultBudget(1, 1, 1, Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ResultBudget(1, 1, 1, Duration.ofSeconds(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
