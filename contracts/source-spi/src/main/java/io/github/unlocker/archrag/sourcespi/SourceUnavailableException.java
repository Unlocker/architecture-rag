package io.github.unlocker.archrag.sourcespi;

import java.time.Duration;

/**
 * Источник недоступен или ограничивает частоту (429/5xx/timeout); вызов можно повторить с
 * backoff.
 */
public class SourceUnavailableException extends RuntimeException {

  private final int statusCode;
  private final Duration retryAfter;

  /**
   * @param statusCode HTTP-статус источника, 0 для timeout
   * @param retryAfter значение {@code Retry-After}, либо {@code null}
   */
  public SourceUnavailableException(SourceSystem system, int statusCode, Duration retryAfter) {
    super(system.code() + " unavailable, status=" + statusCode);
    this.statusCode = statusCode;
    this.retryAfter = retryAfter;
  }

  public int statusCode() {
    return statusCode;
  }

  /** Пауза, запрошенная источником; {@code null}, если не указана. */
  public Duration retryAfter() {
    return retryAfter;
  }

  /** {@code true} для 429. */
  public boolean isRateLimited() {
    return statusCode == 429;
  }
}
