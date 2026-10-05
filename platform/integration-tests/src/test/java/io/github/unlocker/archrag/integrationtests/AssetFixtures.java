package io.github.unlocker.archrag.integrationtests;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.NodeData;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.ProjectionOutcome;
import io.github.unlocker.archrag.graphprojector.ProjectionRequest;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Засев графа для IT через {@link GraphProjector}: формы {@code lastSeenAt}, {@code isCurrent},
 * {@code SourceRecord.active} и {@code ASSERTS.authority} совпадают с продовыми. {@code gid} задаёт вызывающий
 * (в проде их выдаёт IdentityMapping). Узел и связь утверждает мастер-источник по {@link AuthorityMatrix#defaults()}.
 * Переиспользуется IT поиска и карточки актива.
 */
final class AssetFixtures {

  private static final String VERSION = "1";
  private static final AuthorityMatrix MATRIX = AuthorityMatrix.defaults();

  private final GraphProjector projector;
  private final Map<SourceKey, NodeLabel> labels = new HashMap<>();

  AssetFixtures(GraphProjector projector) {
    this.projector = projector;
  }

  /** Узел с записью от мастер-источника; {@code at} задаёт {@code lastSeenAt}. */
  SourceKey node(UUID gid, String sourceType, String sourceId, NodeData data, Instant at) {
    SourceSystemCode master = MATRIX.nodeAuthorities(data.label()).iterator().next();
    return nodeFrom(master, gid, sourceType, sourceId, data, at);
  }

  /** Узел с записью от заданного источника: для дополнительных {@code SourceRecord} того же узла. */
  SourceKey nodeFrom(SourceSystemCode source, UUID gid, String sourceType, String sourceId, NodeData data, Instant at) {
    var key = new SourceKey(source, sourceType, sourceId);
    labels.put(key, data.label());
    project(
        new ProjectionRequest(
            key,
            new SourceVersion(VERSION),
            at,
            null,
            Map.of(key, gid),
            List.of(new UpsertNode(new SourceRecord(key, VERSION, "sha256:" + sourceId, at, true), data))));
    return key;
  }

  /** Закрывает запись источника (tombstone): запись неактивна, узел без других мастеров перестаёт быть текущим. */
  void tombstone(SourceKey key, UUID gid, Instant at) {
    project(
        new ProjectionRequest(
            key, new SourceVersion("2"), at, null, Map.of(key, gid), List.of(new TombstoneSourceRecord(key, at))));
  }

  /** Связь между уже засеянными узлами; утверждает мастер типа связи. {@code validity} — только для RUNS_ON. */
  void relation(
      RelationType type, SourceKey from, UUID fromGid, SourceKey to, UUID toGid, Validity validity, Instant at) {
    SourceSystemCode master = MATRIX.relationAuthorities(type).iterator().next();
    var assertedBy = new SourceKey(master, "relation", type + "/" + fromGid + "/" + toGid);
    NodeLabel fromLabel = labels.get(from);
    NodeLabel toLabel = labels.get(to);
    project(
        new ProjectionRequest(
            assertedBy,
            new SourceVersion(VERSION),
            at,
            null,
            Map.of(from, fromGid, to, toGid),
            List.of(
                new UpsertRelation(
                    type, from, fromLabel, to, toLabel, Map.of(), validity, assertedBy))));
  }

  private void project(ProjectionRequest request) {
    ProjectionOutcome outcome = projector.project(request).outcome();
    if (outcome != ProjectionOutcome.APPLIED) {
      throw new IllegalStateException("Fixture was not applied: " + outcome);
    }
  }
}
