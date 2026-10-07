package io.github.unlocker.archrag.graphquerycore.templates;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Шаблоны Cypher админ-консоли: сводка графа и окрестность узла.
 *
 * <p>Поиск и карточка актива берутся из {@link AssetTemplates}. Текст шаблонов собирается только из
 * констант этого класса, значения передаются параметрами.
 */
public final class ConsoleTemplates {

  /** Метки, по которым сводка считает узлы: allowlist, а не ввод. */
  public static final List<String> STATS_LABELS;

  static {
    List<String> labels = new ArrayList<>(DependencyTemplates.TRACE_LABELS);
    labels.add("SourceRecord");
    STATS_LABELS = List.copyOf(labels);
  }

  /** Типы связей, по которым сводка считает связи. */
  public static final List<String> STATS_RELATION_TYPES;

  static {
    List<String> types = new ArrayList<>(AssetTemplates.RELATION_TYPES);
    types.add("HOSTED_ON");
    types.add("ASSERTS");
    STATS_RELATION_TYPES = List.copyOf(types);
  }

  /** Типы связей, по которым консоль строит окрестность: allowlist для параметра {@code relTypes}. */
  public static final List<String> NEIGHBORHOOD_RELATION_TYPES = concat(AssetTemplates.RELATION_TYPES, "HOSTED_ON");

  private static final String NEIGHBOR_LABELS = String.join("|", DependencyTemplates.TRACE_LABELS);

  /**
   * Число узлов по меткам из {@link #STATS_LABELS}. Возвращает {@code label, count}; метки без узлов
   * присутствуют с нулём (агрегат считается до проекции литерала, иначе пустой вход не даёт строки). Считает по
   * счётчикам хранилища, а не обходом графа.
   */
  public static final QueryTemplate GRAPH_STATS_NODES =
      new QueryTemplate(
          "graph_stats_nodes",
          """
          CALL {
            %s
          }
          RETURN label, count
          ORDER BY label
          LIMIT $limit
          """
              .formatted(
                  STATS_LABELS.stream()
                      .map(l -> "MATCH (n:" + l + ") WITH count(n) AS count RETURN '" + l + "' AS label, count")
                      .collect(Collectors.joining("\n  UNION\n  "))),
          Set.of(),
          ResultKind.NODES);

  /** Число связей по типам из {@link #STATS_RELATION_TYPES}. Возвращает {@code type, count}; типы без связей присутствуют с нулём. */
  public static final QueryTemplate GRAPH_STATS_RELATIONS =
      new QueryTemplate(
          "graph_stats_relations",
          """
          CALL {
            %s
          }
          RETURN type, count
          ORDER BY type
          LIMIT $limit
          """
              .formatted(
                  STATS_RELATION_TYPES.stream()
                      .map(t -> "MATCH ()-[r:" + t + "]->() WITH count(r) AS count RETURN '" + t + "' AS type, count")
                      .collect(Collectors.joining("\n  UNION\n  "))),
          Set.of(),
          ResultKind.NODES);

  /**
   * Время последнего обновления графа: максимум {@code fetchedAt} записей источников. Возвращает одну
   * строку {@code lastUpdatedAt} (ISO-8601 UTC или {@code null}, если записей нет).
   */
  public static final QueryTemplate GRAPH_LAST_UPDATE =
      new QueryTemplate(
          "graph_last_update",
          """
          MATCH (r:SourceRecord)
          RETURN toString(max(r.fetchedAt)) AS lastUpdatedAt
          LIMIT $limit
          """,
          Set.of(),
          ResultKind.NODES);

  /**
   * Узлы окрестности: сам узел (расстояние 0) и действующие узлы до глубины {@code {maxDepth}} по связям
   * из {@code relTypes}. Закрытые связи ({@code validTo}) и закрытые узлы на пути отсекаются.
   *
   * <p>Параметры: {@code gid}, {@code relTypes}. Возвращает {@code gid, type, name, isCurrent, distance}.
   */
  public static final QueryTemplate NEIGHBORHOOD_NODES =
      new QueryTemplate(
          "console_neighborhood_nodes",
          """
          CALL {
            MATCH (c:%s {gid: $gid})
            RETURN c AS n, 0 AS distance
            UNION
            MATCH p = (c:%s {gid: $gid})-[*1..{maxDepth}]-(n:%s)
            WHERE n <> c
              AND all(r IN relationships(p) WHERE type(r) IN $relTypes AND r.validTo IS NULL)
              AND all(x IN nodes(p) WHERE x.isCurrent = true)
            RETURN n, min(length(p)) AS distance
          }
          RETURN n.gid AS gid,
                 [l IN labels(n) WHERE l IN %s][0] AS type,
                 coalesce(n.name, n.hostname, n.url, n.code) AS name,
                 n.isCurrent AS isCurrent,
                 distance
          ORDER BY distance, gid
          LIMIT $limit
          """
              .formatted(
                  NEIGHBOR_LABELS,
                  NEIGHBOR_LABELS,
                  NEIGHBOR_LABELS,
                  cypherList(DependencyTemplates.TRACE_LABELS)),
          Set.of("gid", "relTypes"),
          ResultKind.NODES);

  /**
   * Действующие связи между узлами из списка {@code gids}. Параметры: {@code gids}, {@code relTypes}.
   * Возвращает {@code type, source, target} (gid концов).
   */
  public static final QueryTemplate NEIGHBORHOOD_EDGES =
      new QueryTemplate(
          "console_neighborhood_edges",
          """
          MATCH (a:%s)-[r]->(b:%s)
          WHERE a.gid IN $gids AND b.gid IN $gids
            AND type(r) IN $relTypes AND r.validTo IS NULL
          RETURN type(r) AS type, a.gid AS source, b.gid AS target
          ORDER BY type, source, target
          LIMIT $limit
          """
              .formatted(NEIGHBOR_LABELS, NEIGHBOR_LABELS),
          Set.of("gids", "relTypes"),
          ResultKind.NODES);

  /** Все шаблоны, которые консоль регистрирует сверх шаблонов {@link AssetTemplates}. */
  public static final List<QueryTemplate> ALL =
      List.of(
          GRAPH_STATS_NODES,
          GRAPH_STATS_RELATIONS,
          GRAPH_LAST_UPDATE,
          NEIGHBORHOOD_NODES,
          NEIGHBORHOOD_EDGES);

  private ConsoleTemplates() {}

  private static List<String> concat(List<String> base, String extra) {
    List<String> out = new ArrayList<>(base);
    out.add(extra);
    return List.copyOf(out);
  }

  /** Литерал списка строк из констант allowlist (не из ввода). */
  private static String cypherList(List<String> values) {
    return values.stream().map(v -> "'" + v + "'").toList().toString();
  }
}
