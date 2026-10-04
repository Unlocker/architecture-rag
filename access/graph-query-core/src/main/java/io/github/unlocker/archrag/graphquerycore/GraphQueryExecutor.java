package io.github.unlocker.archrag.graphquerycore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionConfig;
import org.neo4j.driver.Value;
import org.neo4j.driver.exceptions.Neo4jException;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Единственный путь чтения графа: исполняет зарегистрированные шаблоны по ID.
 *
 * <p>Инварианты: принимает только ID шаблона, а не текст Cypher; исполняет только через
 * {@code executeRead} в сессии {@link AccessMode#READ}; значения передаются параметрами; бюджет
 * режется до потолков; результат ограничен по строкам и байтам.
 */
public final class GraphQueryExecutor {

  /** Конфигурация сессии исполнителя: только чтение. */
  public static final SessionConfig READ_SESSION_CONFIG =
      SessionConfig.builder().withDefaultAccessMode(AccessMode.READ).build();

  private static final String TIMEOUT_CODE = "TransactionTimedOut";

  private final Driver driver;
  private final QueryTemplateRegistry registry;
  private final JsonMapper mapper = JsonMapper.builder().build();

  /**
   * Создаёт исполнитель и проверяет, что все шаблоны реестра read-only.
   *
   * @throws IllegalStateException если какой-то шаблон не прошёл проверку {@code EXPLAIN}
   */
  public GraphQueryExecutor(Driver driver, QueryTemplateRegistry registry) {
    this.driver = driver;
    this.registry = registry;
    registry.verifyReadOnly(driver);
  }

  /**
   * Исполняет шаблон.
   *
   * @param templateId ID зарегистрированного шаблона
   * @param params значения параметров шаблона; набор ключей должен совпасть с объявленным
   * @param budget запрошенный бюджет или {@code null} (тогда бюджет шаблона/потолки)
   * @throws IllegalArgumentException неизвестный шаблон, неизвестный или недостающий параметр
   * @throws QueryTimeoutException истёк таймаут транзакции
   */
  public QueryResult execute(String templateId, Map<String, Object> params, ResultBudget budget) {
    QueryTemplate template =
        registry
            .find(templateId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown template: " + templateId));
    checkParameters(template, params);
    QueryLimits limits = registry.limits();
    ResultBudget requested =
        budget != null ? budget : template.defaults() != null ? template.defaults() : limits.asBudget();
    ResultBudget effective = requested.clampTo(limits);
    String cypher = template.render(effective.maxDepth(), limits.maxDepth());

    Map<String, Object> queryParams = new LinkedHashMap<>(params);
    queryParams.put(QueryTemplate.LIMIT_PARAMETER, effective.maxNodes() + 1);
    TransactionConfig txConfig = TransactionConfig.builder().withTimeout(effective.timeout()).build();

    Instant start = Instant.now();
    try (var session = driver.session(READ_SESSION_CONFIG)) {
      // Тело транзакции повторяется при транзиентных ошибках: внутри только чтение Neo4j и
      // построение нового списка, внешних побочных эффектов нет.
      List<Map<String, Object>> fetched =
          session.executeRead(
              tx -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                tx.run(cypher, queryParams).forEachRemaining(r -> rows.add(toRow(r)));
                return rows;
              },
              txConfig);
      return applyBudget(templateId, fetched, effective, Duration.between(start, Instant.now()));
    } catch (Neo4jException e) {
      if (e.code() != null && e.code().contains(TIMEOUT_CODE)) {
        throw new QueryTimeoutException(templateId, e);
      }
      throw e;
    }
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

  private QueryResult applyBudget(
      String templateId, List<Map<String, Object>> fetched, ResultBudget budget, Duration elapsed) {
    boolean truncated = fetched.size() > budget.maxNodes();
    List<Map<String, Object>> rows = new ArrayList<>();
    long bytes = 0;
    for (Map<String, Object> row : fetched.subList(0, Math.min(fetched.size(), budget.maxNodes()))) {
      bytes += serializedSize(row);
      if (bytes > budget.maxResponseBytes()) {
        truncated = true;
        break;
      }
      rows.add(row);
    }
    return new QueryResult(templateId, rows, truncated, rows.size(), elapsed);
  }

  private long serializedSize(Map<String, Object> row) {
    try {
      return mapper.writeValueAsBytes(row).length;
    } catch (JacksonException e) {
      throw new IllegalStateException("Cannot serialize result row", e);
    }
  }

  private static Map<String, Object> toRow(Record record) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (String key : record.keys()) {
      row.put(key, convert(record.get(key)));
    }
    return row;
  }

  /** Приводит значения драйвера к простым структурам, пригодным для JSON. */
  private static Object convert(Value value) {
    return convert(value.asObject());
  }

  private static Object convert(Object o) {
    return switch (o) {
      case null -> null;
      case Node n -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", n.labels());
        m.put("properties", convert(n.asMap()));
        yield m;
      }
      case Relationship r -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", r.type());
        m.put("properties", convert(r.asMap()));
        yield m;
      }
      case Path p -> {
        List<Object> nodes = new ArrayList<>();
        p.nodes().forEach(n -> nodes.add(convert(n)));
        yield nodes;
      }
      case Map<?, ?> map -> {
        Map<String, Object> m = new LinkedHashMap<>();
        map.forEach((k, v) -> m.put(String.valueOf(k), convert(v)));
        yield m;
      }
      case Iterable<?> it -> {
        List<Object> list = new ArrayList<>();
        it.forEach(v -> list.add(convert(v)));
        yield list;
      }
      case String s -> s;
      case Number n -> n;
      case Boolean b -> b;
      default -> o.toString();
    };
  }
}
