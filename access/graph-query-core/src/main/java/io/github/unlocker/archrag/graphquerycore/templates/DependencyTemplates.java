package io.github.unlocker.archrag.graphquerycore.templates;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.List;
import java.util.Set;

/**
 * Шаблоны Cypher для трассировки зависимостей ({@code trace_dependencies}, режим {@code trace}).
 *
 * <p>Два шаблона, а не один: направление обхода и верхняя граница пути в Cypher параметром не
 * передаются. Типы связей приходят параметром {@code relationTypes}, а вызывающий обязан сначала
 * проверить их по {@link #TRACE_RELATION_TYPES}.
 */
public final class DependencyTemplates {

  /** Типы связей, по которым разрешён обход: allowlist для параметра {@code relationTypes}. */
  public static final List<String> TRACE_RELATION_TYPES =
      List.of("DEPENDS_ON", "DECOMPOSED_INTO", "HAS_DEPLOYMENT", "RUNS_ON", "HOSTED_ON", "OWNED_BY");

  /** Метки узлов, из которых может начинаться обход и которые попадают в проекцию. */
  public static final List<String> TRACE_LABELS =
      List.of(
          "ITSystem", "Service", "Repository", "Team", "Environment", "Deployment", "ComputeInstance",
          "VirtualMachine", "PhysicalServer", "Namespace", "KubernetesCluster");

  private static final String LABELS = String.join("|", TRACE_LABELS);

  private static final String FILTER =
      """
      WHERE all(r IN relationships(p) WHERE type(r) IN $relationTypes AND r.validTo IS NULL)
        AND all(n IN nodes(p) WHERE n.isCurrent = true)
      """;

  private static final String PROJECTION =
      """
      RETURN [n IN nodes(p) | {
                gid: n.gid,
                label: [l IN labels(n) WHERE l IN %s][0],
                name: coalesce(n.name, n.hostname, n.url, n.code),
                lastSeenAt: toString(n.lastSeenAt)
              }] AS nodes,
             [r IN relationships(p) | {
                type: type(r),
                from: startNode(r).gid,
                to: endNode(r).gid,
                validFrom: toString(r.validFrom),
                assertedBySource: r.assertedBySource,
                assertedByType: r.assertedByType,
                assertedById: r.assertedById
              }] AS relations
      ORDER BY length(p), [n IN nodes(p) | n.gid]
      LIMIT $limit
      """
          .formatted(cypherList(TRACE_LABELS));

  /**
   * Пути от актива по направлению стрелок связей ({@code ->}) глубиной {@code 1..{maxDepth}}.
   *
   * <p>Параметры {@code gid}, {@code relationTypes}. Только открытые связи ({@code validTo IS NULL})
   * и только текущие узлы. Строка — один путь: {@code nodes} ({@code gid, label, name, lastSeenAt}) и
   * {@code relations} ({@code type, from, to, validFrom, assertedBySource/Type/Id}) в порядке обхода.
   * Cypher не повторяет связь внутри пути, поэтому цикл не делает обход бесконечным.
   */
  public static final QueryTemplate TRACE_DOWNSTREAM =
      new QueryTemplate(
          "trace_downstream",
          "MATCH p = (s:%s {gid: $gid})-[*1..{maxDepth}]->(t)\n".formatted(LABELS) + FILTER + PROJECTION,
          Set.of("gid", "relationTypes"),
          ResultKind.PATHS);

  /** Пути от актива против направления стрелок ({@code <-}); остальное — как {@link #TRACE_DOWNSTREAM}. */
  public static final QueryTemplate TRACE_UPSTREAM =
      new QueryTemplate(
          "trace_upstream",
          "MATCH p = (s:%s {gid: $gid})<-[*1..{maxDepth}]-(t)\n".formatted(LABELS) + FILTER + PROJECTION,
          Set.of("gid", "relationTypes"),
          ResultKind.PATHS);

  private DependencyTemplates() {}

  /** Литерал списка строк из констант allowlist (не из ввода). */
  private static String cypherList(List<String> values) {
    return values.stream().map(v -> "'" + v + "'").toList().toString();
  }
}
