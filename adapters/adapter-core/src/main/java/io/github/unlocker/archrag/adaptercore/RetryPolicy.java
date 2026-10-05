package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import java.time.Duration;
import java.util.Random;

/**
 * Политика повторов при недоступности источника: exponential backoff с jitter и учётом {@code
 * Retry-After}.
 *
 * <p>Задержка попытки {@code n} (с 1): {@code ceiling = min(maxDelay, baseDelay * 2^(n-1))}, затем
 * «equal jitter»: {@code ceiling/2 + random[0, ceiling/2]}. Если источник прислал {@code
 * Retry-After}, ждём не меньше него, но не дольше {@code maxDelay}. Число попыток ограничено {@code maxAttempts}.
 *
 * @param maxAttempts максимум обращений к источнику за один вызов, не меньше 1
 * @param baseDelay базовая задержка, положительная
 * @param maxDelay верхняя граница backoff, не меньше {@code baseDelay}
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (baseDelay.isNegative() || baseDelay.isZero() || maxDelay.compareTo(baseDelay) < 0) {
      throw new IllegalArgumentException("baseDelay must be positive and <= maxDelay");
    }
  }

  /** Значения по умолчанию: 5 попыток, от 500 мс до 30 с. */
  public static RetryPolicy defaults() {
    return new RetryPolicy(5, Duration.ofMillis(500), Duration.ofSeconds(30));
  }

  /**
   * Пауза перед следующей попыткой.
   *
   * @param attempt номер только что неудавшейся попытки, с 1
   */
  public Duration delay(int attempt, SourceUnavailableException failure, Random random) {
    int shift = Math.min(Math.max(attempt - 1, 0), 30);
    long ceiling = Math.min(maxDelay.toMillis(), baseDelay.toMillis() << shift);
    long half = ceiling / 2;
    Duration backoff = Duration.ofMillis(half + (long) (random.nextDouble() * (ceiling - half + 1)));
    Duration retryAfter = failure.retryAfter();
    if (retryAfter != null && retryAfter.compareTo(maxDelay) > 0) {
      retryAfter = maxDelay; // недоверенный источник не может усыпить poller надолго
    }
    return retryAfter != null && retryAfter.compareTo(backoff) > 0 ? retryAfter : backoff;
  }
}
