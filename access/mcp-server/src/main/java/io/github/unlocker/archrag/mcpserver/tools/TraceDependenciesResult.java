package io.github.unlocker.archrag.mcpserver.tools;

import java.util.List;

/**
 * Ответ {@code trace_dependencies}.
 *
 * <p>Узлы и связи дедуплицированы; {@code paths} ссылаются на них по {@code gid} и по ключу связи
 * {@code (type, from, to)}. Шаг пути {@code i} объясняется связью {@code relations[i]} пути: типом, записью-источником
 * и её свежестью.
 *
 * @param startGid gid актива, от которого шёл обход
 * @param mode {@code trace} или {@code impact}
 * @param direction {@code upstream} или {@code downstream}; в режиме {@code impact} всегда {@code upstream}
 * @param environment {@code Environment.code}, по которому отфильтрованы {@code Deployment} (только {@code impact}), иначе null
 * @param staleAfter порог устаревания, ISO-8601 (например {@code PT168H})
 * @param nodes уникальные узлы всех путей, в порядке первого появления
 * @param relations уникальные связи всех путей, в порядке первого появления
 * @param paths пути от стартового узла, по возрастанию длины, затем по gid узлов
 * @param affected {@code impact}: уникальные gid концов путей (затронутые Service/ITSystem) по порядку первого появления;
 *     в режиме {@code trace} пуст
 * @param truncated часть путей отброшена из-за {@code maxPaths} или бюджета ответа; тогда состав путей
 *     может отличаться от вызова к вызову
 */
public record TraceDependenciesResult(
    String startGid,
    String mode,
    String direction,
    String environment,
    String staleAfter,
    List<NodeRef> nodes,
    List<RelationRef> relations,
    List<TracePath> paths,
    List<String> affected,
    boolean truncated) {

  /**
   * Узел пути.
   *
   * @param gid глобальный идентификатор узла
   * @param label каноническая метка
   * @param name отображаемое имя (name, hostname, url или code)
   * @param lastSeenAt время последнего наблюдения, ISO-8601 UTC
   * @param stale {@code lastSeenAt} не задан или старше порога
   * @param conflicts записи источников, расходящиеся по свойствам узла; значения свойств не отдаются
   */
  public record NodeRef(String gid, String label, String name, String lastSeenAt, boolean stale, List<Conflict> conflicts) {}

  /**
   * Маркер конфликта: запись источника, значения которой по перечисленным свойствам не совпали с мастером.
   *
   * @param source система-источник
   * @param sourceType тип записи источника
   * @param sourceId идентификатор записи источника
   * @param properties имена спорных свойств
   */
  public record Conflict(String source, String sourceType, String sourceId, List<String> properties) {}

  /**
   * Связь пути; ключ связи — {@code (type, from, to)}.
   *
   * @param type тип связи
   * @param from gid начала связи (по направлению стрелки)
   * @param to gid конца связи
   * @param validFrom начало действия связи, ISO-8601 UTC
   * @param assertedBySource система, утвердившая связь
   * @param assertedByType тип записи источника
   * @param assertedById идентификатор записи источника
   * @param sourceFetchedAt когда запись-источник получена (свежесть шага), ISO-8601 UTC; null, если записи нет
   * @param sourceActive запись-источник активна; null, если записи нет
   * @param stale запись не найдена, неактивна или {@code sourceFetchedAt} старше порога
   */
  public record RelationRef(
      String type,
      String from,
      String to,
      String validFrom,
      String assertedBySource,
      String assertedByType,
      String assertedById,
      String sourceFetchedAt,
      Boolean sourceActive,
      boolean stale) {}

  /**
   * Путь от стартового узла.
   *
   * @param nodes gid узлов по порядку обхода, первый — стартовый
   * @param relations ключи связей по порядку обхода; связь {@code i} соединяет узлы {@code i} и {@code i+1}
   */
  public record TracePath(List<String> nodes, List<RelationKey> relations) {}

  /** Ключ связи в {@link TracePath}. */
  public record RelationKey(String type, String from, String to) {}
}
