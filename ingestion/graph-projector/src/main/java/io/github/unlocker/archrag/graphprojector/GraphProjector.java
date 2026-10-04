package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.command.CloseAssertion;
import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.NodeData;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;

/**
 * Применяет {@link ProjectionRequest} к Neo4j одной транзакцией {@code executeWrite}.
 *
 * <p>Инварианты:
 * <ul>
 *   <li>внутри транзакции только Neo4j: ни PostgreSQL, ни S3, ни журнала (функция транзакции повторяется
 *       драйвером при транзиентных ошибках, ловушка №7); {@code gid} приходят готовыми в запросе;</li>
 *   <li>{@code MERGE} только по ключам, закреплённым constraint: {@code (source, sourceType, sourceId)} у
 *       {@code SourceRecord}, {@code gid} у узла, {@code code}/{@code runId} у {@code SourceSystem}/{@code SyncRun};
 *       связи сливаются между двумя уже найденными по ключу узлами;</li>
 *   <li>версия сравняется внутри той же транзакции после явной блокировки записи, поэтому параллельные
 *       проекции одного объекта не обгоняют друг друга; равная версия ничего не пишет;</li>
 *   <li>свойства узла пишутся только если источник авторитетен для них по {@link AuthorityMatrix}, связи —
 *       только если источник авторитетен для типа; остальное пропускается и возвращается в результате;
 *       {@code null} никогда не записывается;</li>
 *   <li>удаления физического нет: tombstone ставит {@code active=false}, а каноничный узел архивируется
 *       ({@code deletedAt}, {@code isCurrent=false}), только если нет других активных MASTER-утверждений;</li>
 *   <li>время хранится как {@code datetime} в UTC.</li>
 * </ul>
 *
 * <p>Дополнительные метки подтипов, свойства связей и метки в тексте Cypher берутся только из enum
 * {@link NodeLabel}/{@link RelationType}, а не из данных источника.
 */
public final class GraphProjector implements GraphProjection {

  private static final String RECORD_KEY = "{source: $source, sourceType: $sourceType, sourceId: $sourceId}";

  private final Driver driver;
  private final AuthorityMatrix matrix;

  public GraphProjector(Driver driver, AuthorityMatrix matrix) {
    this.driver = Objects.requireNonNull(driver, "driver");
    this.matrix = Objects.requireNonNull(matrix, "matrix");
  }

  @Override
  public ProjectionResult project(ProjectionRequest request) {
    try (Session session = driver.session()) {
      return session.executeWrite(tx -> write(tx, request));
    }
  }

  @Override
  public Optional<AppliedRecord> applied(SourceKey key) {
    try (Session session = driver.session()) {
      return session.executeRead(
          tx -> {
            var rows =
                tx.run(
                        "MATCH (r:SourceRecord " + RECORD_KEY + ") WHERE r.sourceVersion IS NOT NULL "
                            + "RETURN r.sourceVersion AS version, r.active AS active",
                        keyParams(key))
                    .list();
            return rows.isEmpty()
                ? Optional.<AppliedRecord>empty()
                : Optional.of(applied(rows.get(0)));
          });
    }
  }

  @Override
  public boolean isActive(SourceKey key) {
    try (Session session = driver.session()) {
      return session.executeRead(
          tx ->
              tx.run(
                      "MATCH (r:SourceRecord " + RECORD_KEY + ") WHERE r.active = true RETURN count(r) AS c",
                      keyParams(key))
                  .single()
                  .get("c")
                  .asLong()
                  > 0);
    }
  }

  // Читает все активные записи источника в память: для PoC (сотни–тысячи объектов) допустимо; для большого
  // источника нужно постраничное чтение по sourceId.
  @Override
  public List<ActiveRecord> activeRecords(SourceSystemCode source) {
    try (Session session = driver.session()) {
      return session.executeRead(
          tx ->
              tx.run(
                      "MATCH (r:SourceRecord {source: $source}) WHERE r.active = true AND r.sourceVersion IS NOT NULL "
                          + "RETURN r.sourceType AS sourceType, r.sourceId AS sourceId, r.sourceVersion AS version",
                      Map.of("source", source.name()))
                  .list(
                      row ->
                          new ActiveRecord(
                              new SourceKey(source, row.get("sourceType").asString(), row.get("sourceId").asString()),
                              new SourceVersion(row.get("version").asString()))));
    }
  }

  private ProjectionResult write(TransactionContext tx, ProjectionRequest request) {
    SourceKey key = request.key();
    // MERGE по ключу, закреплённому constraint, затем фиктивная запись свойства: блокировка записи до чтения версии.
    Record locked =
        tx.run(
                "MERGE (r:SourceRecord " + RECORD_KEY + ") SET r._lock = true REMOVE r._lock "
                    + "RETURN r.sourceVersion AS version, r.active AS active",
                keyParams(key))
            .single();
    AppliedRecord applied = locked.get("version").isNull() ? null : applied(locked);
    VersionDecision decision =
        request.isTombstone()
            ? VersionDecision.forTombstone(
                request.version(),
                applied == null ? null : applied.version(),
                applied != null && applied.active())
            : VersionDecision.forUpsert(request.version(), applied == null ? null : applied.version());
    if (decision != VersionDecision.APPLY) {
      return ProjectionResult.of(
          decision == VersionDecision.SAME
              ? ProjectionOutcome.NOOP_SAME_VERSION
              : ProjectionOutcome.IGNORED_OLD_VERSION);
    }
    List<String> skippedProperties = new ArrayList<>();
    List<String> skippedRelations = new ArrayList<>();
    for (GraphCommand command : request.commands()) {
      switch (command) {
        case UpsertNode upsert -> upsertNode(tx, request, upsert, skippedProperties);
        case TombstoneSourceRecord tombstone -> tombstone(tx, request, tombstone);
        case UpsertRelation relation -> upsertRelation(tx, request, relation, skippedRelations);
        case CloseAssertion close -> closeAssertion(tx, request, close);
      }
    }
    linkSyncRun(tx, request);
    return new ProjectionResult(ProjectionOutcome.APPLIED, skippedProperties, skippedRelations);
  }

  private void upsertNode(
      TransactionContext tx, ProjectionRequest request, UpsertNode upsert, List<String> skipped) {
    NodeData data = upsert.data();
    NodeLabel label = data.label();
    var key = upsert.record().key();
    boolean master = matrix.isAuthoritative(label, key.source());
    Map<String, Object> props = new LinkedHashMap<>();
    NodeProperties.of(data)
        .forEach(
            (name, value) -> {
              if (matrix.isAuthoritative(label, name, key.source())) {
                props.put(name, value);
              } else {
                skipped.add(label.label() + "." + name);
              }
            });
    Map<String, Object> params = new HashMap<>(keyParams(key));
    params.put("version", upsert.record().sourceVersion());
    params.put("hash", upsert.record().contentHash());
    params.put("fetchedAt", NodeProperties.utc(upsert.record().fetchedAt()));
    params.put("at", NodeProperties.utc(request.eventTime()));
    params.put("gid", request.gids().get(key).toString());
    params.put("props", props);
    params.put("master", master);
    params.put("authority", master ? "MASTER" : "SUPPLEMENTARY");
    // Метки из enum NodeLabel (allowlist). MERGE идёт по корневой метке: подтип ComputeInstance может
    // смениться (UNSPECIFIED -> VirtualMachine), а gid-constraint у каждой метки свой, иначе получился бы дубль.
    NodeLabel root = root(label);
    String subtypeLabels = label == root ? "" : " SET n:" + label.label();
    String staleLabels = staleSubtypes(root, label);
    tx.run(
            "MATCH (r:SourceRecord " + RECORD_KEY + ") "
                + "SET r.sourceVersion = $version, r.contentHash = $hash, r.fetchedAt = $fetchedAt, r.active = true "
                + "REMOVE r.deletedAt "
                + "MERGE (sys:SourceSystem {code: $source}) "
                + "MERGE (sys)-[:OWNS_RECORD]->(r) "
                + "MERGE (n:" + root.label() + " {gid: $gid}) "
                + "ON CREATE SET n.isCurrent = true "
                + "SET n += $props"
                + subtypeLabels
                + staleLabels
                + " SET n.firstSeenAt = CASE WHEN n.firstSeenAt IS NULL OR n.firstSeenAt > $at THEN $at ELSE n.firstSeenAt END, "
                + "n.lastSeenAt = CASE WHEN n.lastSeenAt IS NULL OR n.lastSeenAt < $at THEN $at ELSE n.lastSeenAt END "
                + "FOREACH (x IN CASE WHEN $master THEN [1] ELSE [] END | SET n.isCurrent = true REMOVE n.deletedAt) "
                + "MERGE (r)-[a:ASSERTS]->(n) "
                + "SET a.confidence = 1.0, a.authority = $authority",
            params)
        .consume();
  }

  private void tombstone(TransactionContext tx, ProjectionRequest request, TombstoneSourceRecord tombstone) {
    Map<String, Object> params = new HashMap<>(keyParams(tombstone.key()));
    params.put("version", request.version().value());
    params.put("at", NodeProperties.utc(tombstone.deletedAt()));
    params.put("temporalTypes", temporalTypes());
    tx.run(
            "MATCH (r:SourceRecord " + RECORD_KEY + ") "
                + "SET r.sourceVersion = $version, r.active = false, r.deletedAt = $at "
                + "MERGE (sys:SourceSystem {code: $source}) "
                + "MERGE (sys)-[:OWNS_RECORD]->(r)",
            params)
        .consume();
    // Открытые временные связи, утверждённые этой записью, закрываются на момент удаления.
    tx.run(
            "MATCH (r:SourceRecord " + RECORD_KEY + ")-[:ASSERTS]->(n)-[rel]-() "
                + "WHERE type(rel) IN $temporalTypes AND rel.validTo IS NULL "
                + "AND rel.assertedBySource = $source AND rel.assertedByType = $sourceType "
                + "AND rel.assertedById = $sourceId "
                + "SET rel.validTo = CASE WHEN rel.validFrom > $at THEN rel.validFrom ELSE $at END",
            params)
        .consume();
    // Каноничный узел закрывается только без других активных MASTER-утверждений.
    tx.run(
            "MATCH (r:SourceRecord " + RECORD_KEY + ")-[:ASSERTS]->(n) "
                + "WHERE NOT EXISTS { MATCH (o:SourceRecord {active: true})-[:ASSERTS {authority: 'MASTER'}]->(n) "
                + "WHERE o <> r } "
                + "SET n.isCurrent = false, n.deletedAt = coalesce(n.deletedAt, $at)",
            params)
        .consume();
  }

  private void upsertRelation(
      TransactionContext tx, ProjectionRequest request, UpsertRelation relation, List<String> skipped) {
    RelationType type = relation.type();
    if (!matrix.isAuthoritative(type, relation.assertedBy().source())) {
      skipped.add(type.name());
      return;
    }
    Map<String, Object> params = relationParams(request, relation.from(), relation.to(), relation.assertedBy());
    params.put("props", NodeProperties.of(relation.properties()));
    String temporal = "";
    if (type.temporal()) {
      Validity validity = relation.validity();
      params.put("validFrom", validity == null ? null : NodeProperties.utc(validity.validFrom()));
      params.put("validTo", validity == null || validity.validTo() == null ? null : NodeProperties.utc(validity.validTo()));
      // Новая или закрытая связь открывается заново (validFrom источника, иначе время события);
      // открытая сохраняет validFrom. validTo = null здесь осознанно: повторное утверждение открывает связь.
      temporal =
          " SET rel.validFrom = CASE WHEN $validFrom IS NOT NULL THEN $validFrom WHEN opening THEN $at "
              + "ELSE rel.validFrom END, rel.validTo = $validTo";
    }
    String ends =
        "MATCH (a:" + root(relation.fromLabel()).label() + " {gid: $from}), (b:"
            + root(relation.toLabel()).label() + " {gid: $to}) ";
    String statement =
        type.temporal()
            ? ends + "MERGE (a)-[rel:" + type.name() + "]->(b) "
                + "WITH rel, (rel.validFrom IS NULL OR rel.validTo IS NOT NULL) AS opening "
            : ends + "MERGE (a)-[rel:" + type.name() + "]->(b) WITH rel ";
    var rows =
        tx.run(
                statement
                    + "SET rel += $props, rel.assertedBySource = $source, rel.assertedByType = $sourceType, "
                    + "rel.assertedById = $sourceId"
                    + temporal
                    + " RETURN 1 AS ok",
                params)
            .list();
    if (rows.isEmpty()) {
      throw new ProjectionException(ProjectionException.UNRESOLVED_ENDPOINT, "relation endpoint is not in the graph");
    }
  }

  private void closeAssertion(TransactionContext tx, ProjectionRequest request, CloseAssertion close) {
    if (!matrix.isAuthoritative(close.type(), close.assertedBy().source())) {
      return;
    }
    Map<String, Object> params = relationParams(request, close.from(), close.to(), close.assertedBy());
    params.put("validTo", NodeProperties.utc(close.validTo()));
    // Нет подходящей открытой связи — ничего не меняется: повтор закрытия идемпотентен.
    tx.run(
            "MATCH (a:" + roots(close.type().sources()) + " {gid: $from})-[rel:" + close.type().name()
                + "]->(b:" + roots(close.type().targets()) + " {gid: $to}) "
                + "WHERE rel.assertedBySource = $source AND rel.assertedByType = $sourceType "
                + "AND rel.assertedById = $sourceId AND rel.validTo IS NULL "
                + "SET rel.validTo = CASE WHEN rel.validFrom > $validTo THEN rel.validFrom ELSE $validTo END",
            params)
        .consume();
  }

  private void linkSyncRun(TransactionContext tx, ProjectionRequest request) {
    if (request.syncRunId() == null) {
      return;
    }
    Map<String, Object> params = new HashMap<>(keyParams(request.key()));
    params.put("runId", request.syncRunId());
    params.put("at", NodeProperties.utc(request.eventTime()));
    // Счётчики SyncRun не трогаем: инкремент внутри повторяемой транзакции удваивался бы при повторе.
    tx.run(
            "MATCH (r:SourceRecord " + RECORD_KEY + ") "
                + "MERGE (s:SyncRun {runId: $runId}) "
                + "ON CREATE SET s.adapter = $source, s.status = 'RUNNING' "
                + "SET s.startedAt = CASE WHEN s.startedAt IS NULL OR s.startedAt > $at THEN $at ELSE s.startedAt END "
                + "MERGE (s)-[:PROCESSED]->(r)",
            params)
        .consume();
  }

  private static Map<String, Object> relationParams(
      ProjectionRequest request, SourceKey from, SourceKey to, SourceKey assertedBy) {
    Map<String, Object> params = new HashMap<>(keyParams(assertedBy));
    params.put("from", request.gids().get(from).toString());
    params.put("to", request.gids().get(to).toString());
    params.put("at", NodeProperties.utc(request.eventTime()));
    return params;
  }

  private static Map<String, Object> keyParams(SourceKey key) {
    return new HashMap<>(
        Map.of("source", key.source().name(), "sourceType", key.sourceType(), "sourceId", key.sourceId()));
  }

  private static AppliedRecord applied(Record row) {
    return new AppliedRecord(
        new SourceVersion(row.get("version").asString()),
        !row.get("active").isNull() && row.get("active").asBoolean());
  }

  /** Корневые метки через {@code |} (из enum): позволяют планировщику взять gid-индекс вместо AllNodesScan. */
  private static String roots(java.util.Set<NodeLabel> labels) {
    return labels.stream().map(l -> root(l).label()).distinct().sorted().reduce((x, y) -> x + "|" + y).orElseThrow();
  }

  private static NodeLabel root(NodeLabel label) {
    NodeLabel current = label;
    while (current.supertype() != null) {
      current = current.supertype();
    }
    return current;
  }

  /** Фрагмент Cypher, снимающий метки других подтипов того же корня. */
  private static String staleSubtypes(NodeLabel root, NodeLabel label) {
    String stale =
        Arrays.stream(NodeLabel.values())
            .filter(l -> l != label && l.supertype() == root)
            .map(l -> ":" + l.label())
            .reduce("", String::concat);
    return stale.isEmpty() ? "" : " REMOVE n" + stale;
  }

  private static List<String> temporalTypes() {
    return Arrays.stream(RelationType.values()).filter(RelationType::temporal).map(Enum::name).toList();
  }
}
