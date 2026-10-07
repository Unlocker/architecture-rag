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

  /**
   * Контекст текущего запроса или {@code null} вне запроса.
   */
  public static ReadAuditContext current() {
    var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
    return attributes != null
            && attributes.getAttribute(
                    ATTRIBUTE, org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST)
                instanceof ReadAuditContext context
        ? context
        : null;
  }

  /** Учитывает SQL-чтение: в {@code templates} попадает {@code sql:<queryId>}, строки суммируются. */
  public void recordSql(String queryId, long rowCount) {
    templates.add("sql:" + queryId);
    rows += rowCount;
  }

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
