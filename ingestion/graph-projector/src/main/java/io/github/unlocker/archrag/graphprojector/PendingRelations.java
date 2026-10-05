package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.neo4j.driver.Record;
import org.neo4j.driver.TransactionContext;

/**
 * Хранилище отложенных связей: узлы {@code (:PendingRelation)} с ребром {@code (SourceRecord)-[:DEFERS]->}.
 *
 * <p>Все методы вызываются внутри транзакции проекции и касаются только Neo4j. Узел сливается по одному
 * ключу {@code key} (SHA-256 от утверждающей записи, типа связи и ключей обоих концов), закреплённому
 * constraint: {@code MERGE} по составному шаблону здесь дал бы дубликаты. Свойства связи лежат одной
 * JSON-строкой ({@link PropertyJson}).
 */
final class PendingRelations {

  private static final String RECORD_KEY = "{source: $source, sourceType: $sourceType, sourceId: $sourceId}";

  private PendingRelations() {}

  /** Отложенная связь, прочитанная из графа. */
  record Pending(String key, UpsertRelation relation, Instant eventTime) {}

  /** Удаляет все отложенные связи записи {@code key}: при каждой применённой проекции они заменяются целиком. */
  static void deleteOf(TransactionContext tx, SourceKey key) {
    tx.run("MATCH (:SourceRecord " + RECORD_KEY + ")-[:DEFERS]->(p:PendingRelation) DETACH DELETE p", keyParams(key))
        .consume();
  }

  /** Удаляет одну отложенную связь после достраивания. */
  static void delete(TransactionContext tx, String key) {
    tx.run("MATCH (p:PendingRelation {key: $key}) DETACH DELETE p", Map.of("key", key)).consume();
  }

  /** Сохраняет связь, ожидающую конечной точки, за записью {@code referrer} (версия {@code referrerVersion}). */
  static void store(
      TransactionContext tx, SourceKey referrer, String referrerVersion, Instant eventTime, UpsertRelation r) {
    Map<String, Object> props = new HashMap<>();
    props.put("type", r.type().name());
    props.put("fromLabel", r.fromLabel().name());
    props.put("toLabel", r.toLabel().name());
    putEnd(props, "from", r.from());
    putEnd(props, "to", r.to());
    props.put("props", PropertyJson.write(r.properties()));
    if (r.validity() != null) {
      props.put("validFrom", NodeProperties.utc(r.validity().validFrom()));
      if (r.validity().validTo() != null) {
        props.put("validTo", NodeProperties.utc(r.validity().validTo()));
      }
    }
    props.put("referrerVersion", referrerVersion);
    props.put("eventTime", NodeProperties.utc(eventTime));
    Map<String, Object> params = keyParams(referrer);
    params.put("key", keyOf(r));
    params.put("props", props);
    tx.run(
            "MATCH (r:SourceRecord " + RECORD_KEY + ") "
                + "MERGE (p:PendingRelation {key: $key}) "
                + "SET p += $props "
                + "MERGE (r)-[:DEFERS]->(p)",
            params)
        .consume();
  }

  /**
   * Отложенные связи, у которых {@code endpoint} — один из концов, а запись-источник активна и имеет ту версию,
   * с которой связь была отложена. Проверку остальных концов делает вызывающий.
   */
  static List<Pending> candidates(TransactionContext tx, SourceKey endpoint) {
    List<Pending> result = new ArrayList<>();
    var rows =
        tx.run(
                "MATCH (r:SourceRecord)-[:DEFERS]->(p:PendingRelation) "
                    + "WHERE ((p.fromSource = $source AND p.fromType = $sourceType AND p.fromId = $sourceId) "
                    + "OR (p.toSource = $source AND p.toType = $sourceType AND p.toId = $sourceId)) "
                    + "AND r.active = true AND r.sourceVersion = p.referrerVersion "
                    + "RETURN p.key AS key, p.type AS type, p.fromLabel AS fromLabel, p.toLabel AS toLabel, "
                    + "p.fromSource AS fromSource, p.fromType AS fromType, p.fromId AS fromId, "
                    + "p.toSource AS toSource, p.toType AS toType, p.toId AS toId, p.props AS props, "
                    + "p.validFrom AS validFrom, p.validTo AS validTo, p.eventTime AS eventTime, "
                    + "r.source AS bySource, r.sourceType AS byType, r.sourceId AS byId "
                    + "ORDER BY p.key",
                keyParams(endpoint))
            .list();
    for (Record row : rows) {
      result.add(read(row));
    }
    return result;
  }

  /**
   * {@code gid} обоих концов из графа ({@code SourceRecord-[:ASSERTS]->канонический узел}) либо пусто, если
   * хотя бы у одного конца нет активной записи с утверждённым узлом нужной метки.
   */
  static Optional<UUID[]> endpointGids(TransactionContext tx, UpsertRelation r) {
    // Метки из enum NodeLabel (allowlist), не из данных.
    String statement =
        "MATCH (:SourceRecord {source: $fromSource, sourceType: $fromType, sourceId: $fromId, active: true})"
            + "-[:ASSERTS]->(a:" + GraphProjector.root(r.fromLabel()).label() + ") "
            + "MATCH (:SourceRecord {source: $toSource, sourceType: $toType, sourceId: $toId, active: true})"
            + "-[:ASSERTS]->(b:" + GraphProjector.root(r.toLabel()).label() + ") "
            + "RETURN a.gid AS from, b.gid AS to LIMIT 1";
    Map<String, Object> params = new HashMap<>();
    params.put("fromSource", r.from().source().name());
    params.put("fromType", r.from().sourceType());
    params.put("fromId", r.from().sourceId());
    params.put("toSource", r.to().source().name());
    params.put("toType", r.to().sourceType());
    params.put("toId", r.to().sourceId());
    var rows = tx.run(statement, params).list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Record row = rows.get(0);
    return Optional.of(
        new UUID[] {UUID.fromString(row.get("from").asString()), UUID.fromString(row.get("to").asString())});
  }

  private static Pending read(Record row) {
    SourceKey from = keyOf(row, "from");
    SourceKey to = keyOf(row, "to");
    SourceKey by = keyOf(row, "by");
    Validity validity = null;
    if (!row.get("validFrom").isNull()) {
      OffsetDateTime validTo = row.get("validTo").isNull() ? null : row.get("validTo").asOffsetDateTime();
      validity =
          new Validity(
              row.get("validFrom").asOffsetDateTime().toInstant(), validTo == null ? null : validTo.toInstant());
    }
    UpsertRelation relation =
        new UpsertRelation(
            RelationType.valueOf(row.get("type").asString()),
            from,
            NodeLabel.valueOf(row.get("fromLabel").asString()),
            to,
            NodeLabel.valueOf(row.get("toLabel").asString()),
            PropertyJson.read(row.get("props").asString()),
            validity,
            by);
    return new Pending(row.get("key").asString(), relation, row.get("eventTime").asOffsetDateTime().toInstant());
  }

  private static SourceKey keyOf(Record row, String prefix) {
    return new SourceKey(
        SourceSystemCode.valueOf(row.get(prefix + "Source").asString()),
        row.get(prefix + "Type").asString(),
        row.get(prefix + "Id").asString());
  }

  private static void putEnd(Map<String, Object> props, String prefix, SourceKey key) {
    props.put(prefix + "Source", key.source().name());
    props.put(prefix + "Type", key.sourceType());
    props.put(prefix + "Id", key.sourceId());
  }

  /** Ключ узла: хэш полей с префиксом длины, чтобы разные наборы не давали одну строку. */
  static String keyOf(UpsertRelation r) {
    StringBuilder sb = new StringBuilder();
    for (String part :
        List.of(
            r.assertedBy().source().name(),
            r.assertedBy().sourceType(),
            r.assertedBy().sourceId(),
            r.type().name(),
            r.from().source().name(),
            r.from().sourceType(),
            r.from().sourceId(),
            r.to().source().name(),
            r.to().sourceType(),
            r.to().sourceId())) {
      sb.append(part.length()).append(':').append(part).append('|');
    }
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static Map<String, Object> keyParams(SourceKey key) {
    return new HashMap<>(
        Map.of("source", key.source().name(), "sourceType", key.sourceType(), "sourceId", key.sourceId()));
  }
}
