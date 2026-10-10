package io.github.unlocker.archrag.adminconsole.graph;

import io.github.unlocker.archrag.adminconsole.audit.ReadAuditContext;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * Единственная точка, через которую API консоли ходит в граф.
 *
 * <p>Делегирует {@link GraphQueryExecutor} и сообщает ID шаблона, число строк и признак усечения в
 * аудит текущего запроса. Обращение к исполнителю в обход этого класса выпадет из аудита.
 */
@Component
public class GraphReads {

  private final GraphQueryExecutor executor;
  private final QueryLimits limits;

  GraphReads(GraphQueryExecutor executor, QueryLimits limits) {
    this.executor = executor;
    this.limits = limits;
  }

  /** Потолки конфигурации как бюджет запроса. */
  public ResultBudget ceilings() {
    return limits.asBudget();
  }

  /** Исполняет шаблон и учитывает результат в аудите; семантика и исключения — как у исполнителя. */
  public QueryResult execute(String templateId, Map<String, Object> params, ResultBudget budget) {
    QueryResult result = executor.execute(templateId, params, budget);
    var attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null
        && attributes.getAttribute(ReadAuditContext.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)
            instanceof ReadAuditContext context) {
      context.record(result);
    }
    return result;
  }
}
