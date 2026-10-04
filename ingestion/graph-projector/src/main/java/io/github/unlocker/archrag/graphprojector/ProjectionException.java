package io.github.unlocker.archrag.graphprojector;

/**
 * Проекция невозможна; {@code code} уходит в {@code errorCode} журнала. Сообщение не содержит значений
 * из источника.
 */
public final class ProjectionException extends RuntimeException {

  /** Конец связи отсутствует в графе (например, гонка с другой проекцией); событие можно повторить. */
  public static final String UNRESOLVED_ENDPOINT = "UNRESOLVED_ENDPOINT";

  private final String code;

  public ProjectionException(String code, String message) {
    super(message);
    this.code = code;
  }

  /** Стабильный код ошибки. */
  public String code() {
    return code;
  }
}
