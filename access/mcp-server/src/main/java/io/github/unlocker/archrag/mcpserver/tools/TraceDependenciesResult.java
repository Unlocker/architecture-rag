package io.github.unlocker.archrag.mcpserver.tools;

import java.util.List;

/**
 * Ответ {@code trace_dependencies}.
 *
 * <p>Узлы и связи дедуплицированы; {@code paths} ссылаются на них по {@code gid} и по ключу связи
 * {@code (type, from, to)}.
 *
 * @param startGid gid актива, от которого шёл обход
 * @param direction {@code upstream} или {@code downstream}
 * @param nodes уникальные узлы всех путей, в порядке первого появления
 * @param relations уникальные связи всех путей, в порядке первого появления
 * @param paths пути от стартового узла, по возрастанию длины
 * @param truncated часть путей отброшена из-за {@code maxPaths} или бюджета ответа
 */
public record TraceDependenciesResult(
    String startGid,
    String direction,
    List<NodeRef> nodes,
    List<RelationRef> relations,
    List<TracePath> paths,
    boolean truncated) {

  /**
   * Узел пути.
   *
   * @param gid глобальный идентификатор узла
   * @param label каноническая метка
   * @param name отображаемое имя (name, hostname, url или code)
   * @param lastSeenAt время последнего наблюдения, ISO-8601 UTC
   */
  public record NodeRef(String gid, String label, String name, String lastSeenAt) {}

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
   */
  public record RelationRef(
      String type,
      String from,
      String to,
      String validFrom,
      String assertedBySource,
      String assertedByType,
      String assertedById) {}

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
