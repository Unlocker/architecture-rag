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
   *
   * <p>Фильтр по environment — один {@code EXISTS} с {@code UNION} по меткам, а не четыре {@code OR EXISTS}:
   * холодное планирование последнего занимало 8–26 с и не укладывалось в таймаут транзакции (UNLOCKER-216).
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
            AND any(l IN labels(n) WHERE l IN %s)
            AND ($types IS NULL OR any(l IN labels(n) WHERE l IN $types))
            AND ($environment IS NULL OR EXISTS {
              MATCH (n:Deployment)-[:IN_ENVIRONMENT]->%s RETURN 1
              UNION
              MATCH (n:Service)-[:HAS_DEPLOYMENT]->(:Deployment)-[:IN_ENVIRONMENT]->%s RETURN 1
              UNION
              MATCH (n:ITSystem)-[:DECOMPOSED_INTO]->(:Service)-[:HAS_DEPLOYMENT]->(:Deployment)-[:IN_ENVIRONMENT]->%s RETURN 1
              UNION
              MATCH (n:ComputeInstance)<-[ro:RUNS_ON]-(:Deployment)-[:IN_ENVIRONMENT]->%s WHERE ro.validTo IS NULL RETURN 1
            })
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
                  cypherList(SEARCHABLE_TYPES),
                  ENV,
                  ENV,
                  ENV,
                  ENV,
                  cypherList(SEARCHABLE_TYPES)),
          Set.of("query", "text", "types", "environment"),
          ResultKind.NODES);

  /**
   * Типы связей, которые {@code get_asset} отдаёт как связи 1 уровня. Служебные ({@code ASSERTS},
   * {@code OWNS_RECORD}, {@code PROCESSED}, {@code PART_OF}, {@code HOSTED_ON}) не входят.
   */
  public static final List<String> RELATION_TYPES =
      List.of("DECOMPOSED_INTO", "IMPLEMENTED_IN", "HAS_DEPLOYMENT", "IN_ENVIRONMENT", "RUNS_ON", "DEPENDS_ON", "OWNED_BY");

  /**
   * Карточка актива по {@code gid}. Метки только из {@link #SEARCHABLE_TYPES}: служебные узлы не читаются.
   *
   * <p>Параметр {@code gid}. Фильтра {@code isCurrent} нет: закрытый узел отдаётся с
   * {@code isCurrent=false}. Возвращает {@code gid, type, properties, firstSeenAt, lastSeenAt, deletedAt,
   * isCurrent, sources}; {@code sources} включает неактивные записи (активные первыми).
   */
  public static final QueryTemplate GET_ASSET =
      new QueryTemplate(
          "get_asset",
          """
          MATCH (n:%s {gid: $gid})
          RETURN n.gid AS gid,
                 [l IN labels(n) WHERE l IN %s][0] AS type,
                 properties(n) AS properties,
                 toString(n.firstSeenAt) AS firstSeenAt,
                 toString(n.lastSeenAt) AS lastSeenAt,
                 toString(n.deletedAt) AS deletedAt,
                 n.isCurrent AS isCurrent,
                 COLLECT {
                   MATCH (r:SourceRecord)-[a:ASSERTS]->(n)
                   RETURN {
                     source: r.source,
                     sourceType: r.sourceType,
                     sourceId: r.sourceId,
                     sourceVersion: r.sourceVersion,
                     fetchedAt: toString(r.fetchedAt),
                     active: r.active,
                     authority: a.authority
                   } AS record
                   ORDER BY r.active DESC, r.source, r.sourceId
                 } AS sources
          LIMIT $limit
          """
              .formatted(LABELS, cypherList(SEARCHABLE_TYPES)),
          Set.of("gid"),
          ResultKind.NODES);

  /**
   * Действующие связи 1 уровня актива в обе стороны. Типы связей из {@link #RELATION_TYPES}, соседи
   * только с метками {@link #SEARCHABLE_TYPES}, закрытые связи ({@code validTo} задан) не возвращаются.
   *
   * <p>Параметр {@code gid}. Возвращает {@code relationType, direction (OUT|IN), gid, type, name,
   * isCurrent, validFrom} соседа.
   */
  public static final QueryTemplate ASSET_RELATIONS =
      new QueryTemplate(
          "asset_relations",
          """
          MATCH (n:%s {gid: $gid})-[rel]-(m:%s)
          WHERE type(rel) IN %s AND rel.validTo IS NULL
          RETURN type(rel) AS relationType,
                 CASE WHEN startNode(rel) = n THEN 'OUT' ELSE 'IN' END AS direction,
                 m.gid AS gid,
                 [l IN labels(m) WHERE l IN %s][0] AS type,
                 coalesce(m.name, m.hostname, m.url, m.code) AS name,
                 m.isCurrent AS isCurrent,
                 toString(rel.validFrom) AS validFrom
          ORDER BY relationType, direction, gid
          LIMIT $limit
          """
              .formatted(LABELS, LABELS, cypherList(RELATION_TYPES), cypherList(SEARCHABLE_TYPES)),
          Set.of("gid"),
          ResultKind.NODES);

  /**
   * Происхождение актива по {@code gid}: все записи источников, утверждающие узел, с параметрами ребра
   * {@code ASSERTS}. Метки только из {@link #SEARCHABLE_TYPES}.
   *
   * <p>Параметр {@code gid}. Фильтра {@code isCurrent} нет: закрытый узел и неактивные (tombstone) записи
   * отдаются. Возвращает {@code gid, type, isCurrent, deletedAt, records}; запись содержит {@code source,
   * sourceType, sourceId, sourceVersion, fetchedAt, contentHash, active, deletedAt, authority, confidence,
   * conflicts} (имена свойств-расхождений с ребра {@code ASSERTS} или {@code null}); активные первыми.
   * Значений свойств в ответе нет.
   */
  public static final QueryTemplate EXPLAIN_PROVENANCE =
      new QueryTemplate(
          "explain_provenance",
          """
          MATCH (n:%s {gid: $gid})
          RETURN n.gid AS gid,
                 [l IN labels(n) WHERE l IN %s][0] AS type,
                 n.isCurrent AS isCurrent,
                 toString(n.deletedAt) AS deletedAt,
                 COLLECT {
                   MATCH (r:SourceRecord)-[a:ASSERTS]->(n)
                   RETURN {
                     source: r.source,
                     sourceType: r.sourceType,
                     sourceId: r.sourceId,
                     sourceVersion: r.sourceVersion,
                     fetchedAt: toString(r.fetchedAt),
                     contentHash: r.contentHash,
                     active: r.active,
                     deletedAt: toString(r.deletedAt),
                     authority: a.authority,
                     confidence: a.confidence,
                     conflicts: a.conflicts
                   } AS record
                   ORDER BY r.active DESC, r.source, r.sourceId
                 } AS records
          LIMIT $limit
          """
              .formatted(LABELS, cypherList(SEARCHABLE_TYPES)),
          Set.of("gid"),
          ResultKind.NODES);

  private AssetTemplates() {}

  /** Литерал списка строк из констант allowlist (не из ввода). */
  private static String cypherList(List<String> values) {
    return values.stream().map(v -> "'" + v + "'").toList().toString();
  }
}
