package io.github.unlocker.archrag.graphquerycore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.summary.QueryType;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

/**
 * Единственный путь чтения графа: исполняет зарегистрированные шаблоны по ID.
 *
 * <p>Инварианты: принимает только ID шаблона, а не текст Cypher; исполняет только через
 * {@code executeRead} в сессии {@link AccessMode#READ}; значения передаются параметрами; перед
 * первым исполнением шаблона {@code EXPLAIN} обязан дать {@code READ_ONLY}, иначе шаблон закрыт
 * навсегда; бюджет режется до потолков, из результата читается не больше {@code maxRows + 1}
 * строк; узлы, связи и пути в результате запрещены. Конструктор в БД не ходит.
 */
public final class GraphQueryExecutor {

  /** Конфигурация сессии исполнителя: только чтение. */
  public static final SessionConfig READ_SESSION_CONFIG =
      SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build();

  private static final String TIMEOUT_CODE = "TransactionTimedOut";

  private final Driver driver;
  private final QueryTemplateRegistry registry;
  // true — проверка пройдена, false — провалена (шаблон закрыт навсегда).
  private final Map<String, Boolean> readOnlyVerdicts = new ConcurrentHashMap<>();

  public GraphQueryExecutor(Driver driver, QueryTemplateRegistry registry) {
    this.driver = driver;
    this.registry = registry;
  }

  /**
   * Исполняет шаблон.
   *
   * @param templateId ID зарегистрированного шаблона
   * @param params значения параметров шаблона; набор ключей должен совпасть с объявленным
   * @param budget запрошенный бюджет или {@code null} (тогда потолки конфигурации)
   * @throws IllegalArgumentException неизвестный шаблон, неизвестный или недостающий параметр
   * @throws IllegalStateException шаблон не read-only или вернул узел, связь либо путь
   * @throws QueryTimeoutException истёк таймаут транзакции
   */
  public QueryResult execute(String templateId, Map<String, Object> params, ResultBudget budget) {
    QueryTemplate template =
        registry
            .find(templateId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateId));
    checkParameters(template, params);
    QueryLimits limits = registry.limits();
    ResultBudget effective = (budget != null ? budget : limits.asBudget()).clampTo(limits);
    String cypher = template.render(effective.maxDepth(), limits.maxDepth());
    int maxRows = template.kind().maxRows(effective);

    Map<String, Object> queryParams = new LinkedHashMap<>(params);
    queryParams.put(QueryTemplate.LIMIT_PARAMETER, maxRows + 1);
    TransactionConfig txConfig = TransactionConfig.builder().withTimeout(effective.timeout()).build();
    BudgetedCollector collector = new BudgetedCollector(maxRows, effective.maxResponseBytes());

    verifyReadOnly(template, cypher);
    Instant start = Instant.now();
    try (var session = driver.session(READ_SESSION_CONFIG)) {
      // Тело транзакции повторяется при транзиентных ошибках: внутри только чтение Neo4j и
      // построение нового результата, внешних побочных эффектов нет.
      BudgetedCollector.Collected collected =
          session.executeRead(
              tx -> {
                Result result = tx.run(cypher, queryParams);
                return collector.collect(new RowIterator(result));
              },
              txConfig);
      return new QueryResult(
          templateId,
          collected.rows(),
          collected.truncated(),
          collected.rows().size(),
          Duration.between(start, Instant.now()));
    } catch (Neo4jException e) {
      throw translate(templateId, e);
    }
  }

  /** EXPLAIN перед первым исполнением; вердикт кэшируется по ID, провал необратим. */
  private void verifyReadOnly(QueryTemplate template, String cypher) {
    Boolean verdict = readOnlyVerdicts.get(template.id());
    if (verdict == null) {
      verdict = explainIsReadOnly(template, cypher);
      readOnlyVerdicts.put(template.id(), verdict);
    }
    if (!verdict) {
      throw new IllegalStateException("Template '" + template.id() + "' is not read-only");
    }
  }

  private boolean explainIsReadOnly(QueryTemplate template, String cypher) {
    Map<String, Object> params = new LinkedHashMap<>();
    template.parameters().forEach(p -> params.put(p, null));
    params.put(QueryTemplate.LIMIT_PARAMETER, 1);
    try (var session = driver.session(READ_SESSION_CONFIG)) {
      // Только EXPLAIN: запрос не исполняется и данных не меняет.
      QueryType type =
          session.executeRead(tx -> tx.run("EXPLAIN " + cypher, params).consume().queryType());
      return type == QueryType.READ_ONLY;
    } catch (Neo4jException e) {
      // Ошибка планирования не вердикт: шаблон не закрываем, пусть повторится.
      throw translate(template.id(), e);
    }
  }

  private static RuntimeException translate(String templateId, Neo4jException e) {
    if (e.code() != null && e.code().contains(TIMEOUT_CODE)) {
      return new QueryTimeoutException(templateId, e);
    }
    return e;
  }

  private static void checkParameters(QueryTemplate template, Map<String, Object> params) {
    for (String name : params.keySet()) {
      if (!template.parameters().contains(name)) {
        throw new IllegalArgumentException(
            "Unknown parameter '" + name + "' for template '" + template.id() + "'");
      }
    }
    for (String name : template.parameters()) {
      if (!params.containsKey(name)) {
        throw new IllegalArgumentException(
            "Missing parameter '" + name + "' for template '" + template.id() + "'");
      }
    }
  }

  /** Лениво превращает записи драйвера в строки; читает ровно столько, сколько запросит сборщик. */
  private static final class RowIterator implements Iterator<Map<String, Object>> {
    private final Result result;

    RowIterator(Result result) {
      this.result = result;
    }

    @Override
    public boolean hasNext() {
      return result.hasNext();
    }

    @Override
    public Map<String, Object> next() {
      Record record = result.next();
      Map<String, Object> row = new LinkedHashMap<>();
      for (String key : record.keys()) {
        row.put(key, check(record.get(key).asObject()));
      }
      return row;
    }
  }

  /** Отвергает узлы, связи и пути, в том числе внутри списков и map. */
  private static Object check(Object o) {
    switch (o) {
      case Node n -> throw forbidden("Node");
      case Relationship r -> throw forbidden("Relationship");
      case Path p -> throw forbidden("Path");
      case Map<?, ?> map -> map.values().forEach(GraphQueryExecutor::check);
      case List<?> list -> new ArrayList<>(list).forEach(GraphQueryExecutor::check);
      case null, default -> {}
    }
    return o;
  }

  private static IllegalStateException forbidden(String type) {
    return new IllegalStateException(
        type + " in result is forbidden: return projections like n {.gid, .name}");
  }
}
