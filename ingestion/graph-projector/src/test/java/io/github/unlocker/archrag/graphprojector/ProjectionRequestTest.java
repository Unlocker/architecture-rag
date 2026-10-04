package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionRequestTest {

  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");
  private static final SourceKey SVC = new SourceKey(SourceSystemCode.SCM, "SERVICE", "s1");
  private static final SourceKey TEAM = new SourceKey(SourceSystemCode.EAM, "TEAM", "t1");

  private static UpsertNode service() {
    return new UpsertNode(new SourceRecord(SVC, "5", "h", T, true), new Service("pay", null, null, null));
  }

  private static ProjectionRequest request(Map<SourceKey, UUID> gids, GraphCommand... commands) {
    return new ProjectionRequest(SVC, new SourceVersion("5"), T, null, gids, List.of(commands));
  }

  @Test
  void validUpsertIsAccepted() {
    var req = request(Map.of(SVC, UUID.randomUUID()), service());

    assertThat(req.isTombstone()).isFalse();
  }

  @Test
  void tombstoneRequestIsRecognised() {
    var req = request(Map.of(), new TombstoneSourceRecord(SVC, T));

    assertThat(req.isTombstone()).isTrue();
  }

  @Test
  void gidOfRecordIsRequired() {
    assertThatThrownBy(() -> request(Map.of(), service())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void recordVersionMustEqualRequestVersion() {
    var other = new UpsertNode(new SourceRecord(SVC, "6", "h", T, true), new Service("pay", null, null, null));

    assertThatThrownBy(() -> request(Map.of(SVC, UUID.randomUUID()), other))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void relationEndpointNeedsGidAndMustBeAssertedByRequestRecord() {
    var relation =
        new UpsertRelation(RelationType.OWNED_BY, SVC, NodeLabel.SERVICE, TEAM, NodeLabel.TEAM, Map.of(), null, SVC);

    assertThatThrownBy(() -> request(Map.of(SVC, UUID.randomUUID()), service(), relation))
        .isInstanceOf(IllegalArgumentException.class);
    var foreign =
        new UpsertRelation(RelationType.OWNED_BY, SVC, NodeLabel.SERVICE, TEAM, NodeLabel.TEAM, Map.of(), null, TEAM);
    assertThatThrownBy(
            () -> request(Map.of(SVC, UUID.randomUUID(), TEAM, UUID.randomUUID()), service(), foreign))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tombstoneCannotBeMixedWithUpsert() {
    assertThatThrownBy(
            () -> request(Map.of(SVC, UUID.randomUUID()), service(), new TombstoneSourceRecord(SVC, T)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nodePropertiesDropNullsAndConvertTypes() {
    var props = NodeProperties.of(new Team("core", null));

    assertThat(props).containsOnlyKeys("name").containsEntry("name", "core");
    assertThat(NodeProperties.value(T)).isEqualTo(T.atOffset(java.time.ZoneOffset.UTC));
  }
}
