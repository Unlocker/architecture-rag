package io.github.unlocker.archrag.mcpserver.graph;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.mcpserver.audit.ToolCallContext;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Единственная точка, через которую MCP tools ходят в граф.
 *
 * <p>Делегирует {@link GraphQueryExecutor} и сообщает ID шаблона, число строк и признак усечения
 * в аудит текущего вызова. Tool, который обратится к {@link GraphQueryExecutor} напрямую, выпадет из
 * аудита: так делать нельзя.
 */
@Component
public class GraphQueries {

  private final GraphQueryExecutor executor;

  GraphQueries(GraphQueryExecutor executor) {
    this.executor = executor;
  }

  /** Исполняет шаблон и учитывает результат в аудите; семантика и исключения — как у исполнителя. */
  public QueryResult execute(String templateId, Map<String, Object> params, ResultBudget budget) {
    QueryResult result = executor.execute(templateId, params, budget);
    ToolCallContext.record(result);
    return result;
  }
}
