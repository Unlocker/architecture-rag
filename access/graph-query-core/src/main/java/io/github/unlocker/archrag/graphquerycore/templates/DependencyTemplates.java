package io.github.unlocker.archrag.graphquerycore.templates;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.List;
import java.util.Set;

/**
 * Шаблоны Cypher для трассировки зависимостей ({@code trace_dependencies}, режимы {@code trace} и {@code impact}).
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

  /**
   * Проекция пути. Без {@code ORDER BY}: Neo4j останавливает перечисление путей на первом {@code LIMIT}, а сортировка
   * по {@code (длина, gid узлов)} делается в Java. Свежесть шага берётся из {@code SourceRecord} по ключу
   * {@code (source, sourceType, sourceId)}, который проиндексирован; конфликты узла лежат на рёбрах {@code ASSERTS}.
   */
  private static final String PROJECTION =
      """
      WITH p LIMIT $limit
      CALL {
        WITH p
        UNWIND relationships(p) AS r
        OPTIONAL MATCH (sr:SourceRecord {source: r.assertedBySource, sourceType: r.assertedByType, sourceId: r.assertedById})
        RETURN collect({
                 type: type(r),
                 from: startNode(r).gid,
                 to: endNode(r).gid,
                 validFrom: toString(r.validFrom),
                 assertedBySource: r.assertedBySource,
                 assertedByType: r.assertedByType,
                 assertedById: r.assertedById,
                 sourceFetchedAt: toString(sr.fetchedAt),
                 sourceActive: sr.active
               }) AS relations
      }
      RETURN [n IN nodes(p) | {
                gid: n.gid,
                label: [l IN labels(n) WHERE l IN %s][0],
                name: coalesce(n.name, n.hostname, n.url, n.code),
                lastSeenAt: toString(n.lastSeenAt),
                conflicts: [(rec:SourceRecord)-[a:ASSERTS]->(n) WHERE a.conflicts IS NOT NULL | {
                  source: rec.source, sourceType: rec.sourceType, sourceId: rec.sourceId, properties: a.conflicts}]
              }] AS nodes,
             relations
      LIMIT $limit
      """
          .formatted(cypherList(TRACE_LABELS));

  /**
   * Пути от актива по направлению стрелок связей ({@code ->}) глубиной {@code 1..{maxDepth}}.
   *
   * <p>Параметры {@code gid}, {@code relationTypes}. Только открытые связи ({@code validTo IS NULL})
   * и только текущие узлы. Строка — один путь: {@code nodes} ({@code gid, label, name, lastSeenAt}) и
   * {@code relations} ({@code type, from, to, validFrom, assertedBySource/Type/Id, sourceFetchedAt,
   * sourceActive}) в порядке обхода. Узел несёт {@code conflicts}: записи источников с маркером конфликта.
   * Cypher не повторяет связь внутри пути, поэтому цикл не делает обход бесконечным. Порядка строк нет:
   * при усечении по {@code LIMIT} состав путей может меняться от вызова к вызову.
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

  /** Типы связей impact-обхода: allowlist параметра {@code relationTypes} в режиме {@code impact}. */
  public static final List<String> IMPACT_RELATION_TYPES =
      List.of("RUNS_ON", "HOSTED_ON", "HAS_DEPLOYMENT", "DECOMPOSED_INTO", "DEPENDS_ON");

  /** Метки, на которых заканчивается impact-путь. */
  public static final List<String> IMPACT_TARGET_LABELS = List.of("Service", "ITSystem");

  /**
   * Impact: пути от актива против стрелок ({@code <-}), заканчивающиеся на {@code Service} или {@code ITSystem}.
   *
   * <p>Параметры {@code gid}, {@code relationTypes}, {@code environment} (может быть {@code null}). Если
   * {@code environment} задан, каждый {@code Deployment} на пути обязан иметь открытую связь
   * {@code IN_ENVIRONMENT} на {@code Environment} с этим {@code code}; путь без {@code Deployment} фильтр проходит.
   * Остальное, включая отсутствие порядка и усечение, как у {@link #TRACE_UPSTREAM}.
   */
  public static final QueryTemplate IMPACT_UPSTREAM =
      new QueryTemplate(
          "trace_impact",
          ("MATCH p = (s:%s {gid: $gid})<-[*1..{maxDepth}]-(t:%s)\n".formatted(LABELS, String.join("|", IMPACT_TARGET_LABELS))
              + FILTER
              + """
                  AND all(n IN nodes(p) WHERE NOT n:Deployment OR $environment IS NULL
                    OR EXISTS { (n)-[e:IN_ENVIRONMENT]->(:Environment {code: $environment}) WHERE e.validTo IS NULL })
                """
              + PROJECTION),
          Set.of("gid", "relationTypes", "environment"),
          ResultKind.PATHS);

  private DependencyTemplates() {}

  /** Литерал списка строк из констант allowlist (не из ввода). */
  private static String cypherList(List<String> values) {
    return values.stream().map(v -> "'" + v + "'").toList().toString();
  }
}
