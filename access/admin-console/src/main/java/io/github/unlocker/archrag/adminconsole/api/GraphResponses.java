package io.github.unlocker.archrag.adminconsole.api;

import java.util.List;
import java.util.Map;

/** Формы ответов {@code /api/graph/**}. */
public final class GraphResponses {

  private GraphResponses() {}

  /** Количество по ключу (метке узла или типу связи). */
  public record Count(String name, long count) {}

  /**
   * Сводка графа.
   *
   * @param nodes число узлов по меткам
   * @param relations число связей по типам
   * @param lastUpdatedAt максимум {@code fetchedAt} записей источников, ISO-8601 UTC, или {@code null}
   */
  public record Stats(List<Count> nodes, List<Count> relations, String lastUpdatedAt) {}

  /**
   * Результат поиска.
   *
   * @param items найденные активы, лучшие первыми
   * @param truncated результат обрезан лимитом или бюджетом
   */
  public record SearchResponse(List<AssetHit> items, boolean truncated) {}

  /** Найденный актив: {@code matchType} — {@code EXACT} или {@code FULLTEXT}. */
  public record AssetHit(
      String gid,
      String type,
      String name,
      double score,
      String matchType,
      List<String> sources,
      String lastSeenAt) {}

  /**
   * Карточка актива.
   *
   * @param properties свойства узла без служебных; времена в ISO-8601 UTC
   * @param sources записи источников с параметрами ребра {@code ASSERTS}, активные первыми
   */
  public record NodeCard(
      String gid,
      String type,
      Map<String, Object> properties,
      String firstSeenAt,
      String lastSeenAt,
      String deletedAt,
      boolean isCurrent,
      List<Map<String, Object>> sources) {}

  /** Узел окрестности; {@code distance} — число шагов от центра (0 у самого узла). */
  public record GraphNode(String gid, String type, String name, boolean isCurrent, int distance) {}

  /** Связь окрестности между двумя узлами из ответа. */
  public record GraphEdge(String type, String source, String target) {}

  /**
   * Окрестность узла для визуализации.
   *
   * @param truncated узлы или связи обрезаны бюджетом
   */
  public record Neighborhood(String center, List<GraphNode> nodes, List<GraphEdge> edges, boolean truncated) {}
}
