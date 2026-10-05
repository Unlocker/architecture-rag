package io.github.unlocker.archrag.graphquerycore;

/** Запрос превысил таймаут транзакции; частичного результата нет. */
public class QueryTimeoutException extends RuntimeException {

  private final String templateId;

  public QueryTimeoutException(String templateId, Throwable cause) {
    super("Query template '" + templateId + "' timed out", cause);
    this.templateId = templateId;
  }

  /** ID шаблона, исполнение которого прервано по таймауту. */
  public String templateId() {
    return templateId;
  }
}
