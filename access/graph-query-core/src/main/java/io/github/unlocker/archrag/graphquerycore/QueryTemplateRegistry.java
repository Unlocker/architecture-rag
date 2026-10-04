package io.github.unlocker.archrag.graphquerycore;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.neo4j.driver.Driver;
import org.neo4j.driver.summary.QueryType;

/**
 * Неизменяемый реестр шаблонов.
 *
 * <p>Инварианты: ID уникальны; глубина литеральных границ не превышает потолок. Проверка, что
 * каждый шаблон только читает, выполняется {@link #verifyReadOnly(Driver)}.
 */
public final class QueryTemplateRegistry {

  private final Map<String, QueryTemplate> templates;
  private final QueryLimits limits;

  private QueryTemplateRegistry(Map<String, QueryTemplate> templates, QueryLimits limits) {
    this.templates = Map.copyOf(templates);
    this.limits = limits;
  }

  /**
   * Создаёт реестр.
   *
   * @throws IllegalArgumentException при дубликате ID
   */
  public static QueryTemplateRegistry of(Collection<QueryTemplate> templates, QueryLimits limits) {
    Map<String, QueryTemplate> byId = new HashMap<>();
    for (QueryTemplate template : templates) {
      if (byId.putIfAbsent(template.id(), template) != null) {
        throw new IllegalArgumentException("Duplicate template id: " + template.id());
      }
    }
    return new QueryTemplateRegistry(byId, limits);
  }

  /** Шаблон по ID. */
  public Optional<QueryTemplate> find(String id) {
    return Optional.ofNullable(templates.get(id));
  }

  /** ID зарегистрированных шаблонов. */
  public Set<String> ids() {
    return new HashSet<>(templates.keySet());
  }

  /** Потолки, с которыми создан реестр. */
  public QueryLimits limits() {
    return limits;
  }

  /**
   * Проверяет через {@code EXPLAIN}, что каждый шаблон имеет тип {@code READ_ONLY}.
   *
   * <p>EXPLAIN запрос не исполняет. Без шаблонов обращения к БД нет.
   *
   * @throws IllegalStateException если шаблон не только читает или не удалось его проверить
   */
  public void verifyReadOnly(Driver driver) {
    for (QueryTemplate template : templates.values()) {
      QueryType type;
      try (var session = driver.session(GraphQueryExecutor.READ_SESSION_CONFIG)) {
        Map<String, Object> params = new HashMap<>();
        template.parameters().forEach(p -> params.put(p, null));
        params.put(QueryTemplate.LIMIT_PARAMETER, 1);
        String cypher = template.render(limits.maxDepth(), limits.maxDepth());
        type = session.run("EXPLAIN " + cypher, params).consume().queryType();
      } catch (RuntimeException e) {
        throw new IllegalStateException(
            "Template '" + template.id() + "' failed EXPLAIN check: " + e.getMessage(), e);
      }
      if (type != QueryType.READ_ONLY) {
        throw new IllegalStateException(
            "Template '" + template.id() + "' is not read-only: " + type);
      }
    }
  }
}
