package io.github.unlocker.archrag.normalizer;

/**
 * Ошибка нормализации с кодом для {@code errorCode} журнала. Сообщение содержит имена полей, но не
 * значения из источника (тексты источника недоверенные и не попадают в логи).
 */
public final class NormalizationException extends RuntimeException {

  private final String code;

  public NormalizationException(String code, String reason) {
    super(reason);
    this.code = code;
  }

  /** Стабильный код ошибки, например {@code MISSING_REQUIRED_FIELD}. */
  public String code() {
    return code;
  }
}
