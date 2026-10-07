package io.github.unlocker.archrag.adminconsole.audit;

import io.github.unlocker.archrag.graphquerycore.QueryResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Накопитель аудита одного запроса: ID шаблонов, число строк, признак усечения. Живёт в атрибуте
 * запроса, обработка запроса идёт в одном потоке.
 */
public final class ReadAuditContext {

  /** Имя атрибута запроса, в котором лежит контекст. */
  public static final String ATTRIBUTE = ReadAuditContext.class.getName();

  private final List<String> templates = new ArrayList<>();
  private long rows;
  private boolean truncated;

  /** Учитывает результат исполнения шаблона. */
  public void record(QueryResult result) {
    templates.add(result.templateId());
    rows += result.rowCount();
    truncated |= result.truncated();
  }

  List<String> templates() {
    return List.copyOf(templates);
  }

  long rows() {
    return rows;
  }

  boolean truncated() {
    return truncated;
  }
}
