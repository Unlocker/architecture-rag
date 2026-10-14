package io.github.unlocker.archrag.ingestionservice;

/**
 * Перепроекция после подтверждения кандидата невозможна. К этому моменту PostgreSQL уже зафиксирован (кандидат
 * {@code RESOLVED}, запись на {@code gid} выжившего), граф догонит rebuild. {@code code} стабилен, сообщение не
 * содержит значений из источника.
 */
public class ReprojectionException extends RuntimeException {

  private final String code;

  public ReprojectionException(String code, String message) {
    super(message);
    this.code = code;
  }

  /** Стабильный код причины. */
  public String code() {
    return code;
  }
}
