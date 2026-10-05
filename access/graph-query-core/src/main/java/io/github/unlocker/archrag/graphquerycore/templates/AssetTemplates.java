package io.github.unlocker.archrag.graphquerycore.templates;

import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import java.util.List;
import java.util.Set;

/**
 * Шаблоны Cypher для поиска и чтения архитектурных активов.
 *
 * <p>Все значения передаются параметрами; текст шаблона собирается только из констант этого класса.
 */
public final class AssetTemplates {

  /** Метки, по которым ищет {@code search_assets}: allowlist для параметра {@code types}. */
  public static final List<String> SEARCHABLE_TYPES =
      List.of("ITSystem", "Service", "Repository", "Team", "Environment", "Deployment", "ComputeInstance");

  /** Score точного совпадения: всегда выше score FULLTEXT. */
  public static final double EXACT_SCORE = 1000.0;

  private static final String LABELS = String.join("|", SEARCHABLE_TYPES);

  private static final String ENV = "(:Environment {code: $environment})";

  /**
   * Поиск активов: точное совпадение по {@code gid} или {@code sourceId}, затем FULLTEXT {@code asset_text}.
   *
   * <p>Параметры: {@code query} (trim-нутый ввод), {@code text} (Lucene-экранированный ввод или
   * {@code null}), {@code types} и {@code environment} (оба могут быть {@code null}). Возвращает
   * проекции {@code gid, type, name, score, matchType, sources, lastSeenAt}; только {@code isCurrent}.
   */
  public static final QueryTemplate SEARCH_ASSETS =
      new QueryTemplate(
          "search_assets",
          """
          CALL {
            MATCH (n:%s) WHERE n.gid = $query
            RETURN n, %s AS score, 'EXACT' AS matchType
            UNION
            MATCH (:SourceRecord {sourceId: $query, active: true})-[:ASSERTS]->(n)
            RETURN n, %s AS score, 'EXACT' AS matchType
            UNION
            WITH $text AS t WHERE t IS NOT NULL
            CALL db.index.fulltext.queryNodes('asset_text', t) YIELD node AS n, score
            RETURN n, score, 'FULLTEXT' AS matchType
          }
          WITH n, max(score) AS score, collect(matchType) AS matchTypes
          WHERE n.isCurrent = true
            AND ($types IS NULL OR any(l IN labels(n) WHERE l IN $types))
            AND ($environment IS NULL OR (
              (n:Deployment AND EXISTS { (n)-[:IN_ENVIRONMENT]->%s })
              OR (n:Service AND EXISTS { (n)-[:HAS_DEPLOYMENT]->(:Deployment)-[:IN_ENVIRONMENT]->%s })
              OR (n:ITSystem AND EXISTS {
                (n)-[:DECOMPOSED_INTO]->(:Service)-[:HAS_DEPLOYMENT]->(:Deployment)-[:IN_ENVIRONMENT]->%s })
              OR (n:ComputeInstance AND EXISTS {
                (n)<-[ro:RUNS_ON]-(:Deployment)-[:IN_ENVIRONMENT]->%s WHERE ro.validTo IS NULL })))
          RETURN n.gid AS gid,
                 [l IN labels(n) WHERE l IN %s][0] AS type,
                 coalesce(n.name, n.hostname, n.url, n.code) AS name,
                 score,
                 CASE WHEN 'EXACT' IN matchTypes THEN 'EXACT' ELSE 'FULLTEXT' END AS matchType,
                 COLLECT {
                   MATCH (r:SourceRecord)-[:ASSERTS]->(n) WHERE r.active = true
                   RETURN DISTINCT r.source AS source ORDER BY source
                 } AS sources,
                 toString(n.lastSeenAt) AS lastSeenAt
          ORDER BY score DESC, gid
          LIMIT $limit
          """
              .formatted(
                  LABELS,
                  EXACT_SCORE,
                  EXACT_SCORE,
                  ENV,
                  ENV,
                  ENV,
                  ENV,
                  cypherList(SEARCHABLE_TYPES)),
          Set.of("query", "text", "types", "environment"),
          ResultKind.NODES);

  private AssetTemplates() {}

  /** Литерал списка строк из констант allowlist (не из ввода). */
  private static String cypherList(List<String> values) {
    return values.stream().map(v -> "'" + v + "'").toList().toString();
  }
}
