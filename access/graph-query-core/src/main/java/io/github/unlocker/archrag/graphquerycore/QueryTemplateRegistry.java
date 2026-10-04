package io.github.unlocker.archrag.graphquerycore;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Неизменяемый реестр шаблонов.
 *
 * <p>Инварианты: ID уникальны; литеральная глубина обхода не превышает потолок. Конструктор делает
 * только статические проверки, без обращения к БД; read-only проверка идёт в {@link
 * GraphQueryExecutor} перед первым исполнением шаблона.
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
   * @throws IllegalArgumentException при дубликате ID или литеральной глубине выше потолка
   */
  public static QueryTemplateRegistry of(Collection<QueryTemplate> templates, QueryLimits limits) {
    Map<String, QueryTemplate> byId = new HashMap<>();
    for (QueryTemplate template : templates) {
      if (template.maxLiteralDepth() > limits.maxDepth()) {
        throw new IllegalArgumentException(
            "Template '" + template.id() + "': literal depth " + template.maxLiteralDepth()
                + " exceeds limit " + limits.maxDepth());
      }
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
}
