package io.github.unlocker.archrag.identityresolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.command.GraphCommand;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeKind;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.Environment;
import io.github.unlocker.archrag.canonicalmodel.node.EnvironmentClass;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.NodeData;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureExtractorTest {

  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");
  private static final SourceKey KEY = new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", "s1");
  private static final SourceKey TEAM_KEY = new SourceKey(SourceSystemCode.EAM, "TEAM", "t1");
  private static final UUID TEAM_GID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private static UpsertNode node(SourceKey key, NodeData data) {
    return new UpsertNode(new SourceRecord(key, "1", "sha256:a", T, true), data);
  }

  private static UpsertRelation ownedBy(SourceKey from, SourceKey team) {
    return new UpsertRelation(RelationType.OWNED_BY, from, NodeLabel.IT_SYSTEM, team, NodeLabel.TEAM, Map.of(), null, from);
  }

  private static Set<Feature> extract(SourceKey key, List<GraphCommand> commands, Map<SourceKey, UUID> gids) {
    return FeatureExtractor.extract(key, commands, gids);
  }

  @Test
  void featuresPerLabel() {
    var vm = new ComputeInstance("Web-01.", ComputeKind.VIRTUAL_MACHINE, null, null, null, null, null);
    assertThat(extract(KEY, List.of(node(KEY, vm)), Map.of())).containsExactly(new Feature(FeatureKind.HOSTNAME, "web-01"));
    assertThat(extract(KEY, List.of(node(KEY, new Repository("git@h:O/x.git", null, null))), Map.of()))
        .containsExactly(new Feature(FeatureKind.REPOSITORY_URL, "h/o/x"));
    assertThat(extract(KEY, List.of(node(KEY, new Team("Core Team", null))), Map.of()))
        .containsExactly(new Feature(FeatureKind.NAME, "core team"));
    assertThat(extract(KEY, List.of(node(KEY, new Service("API", null, null, null))), Map.of()))
        .containsExactly(new Feature(FeatureKind.NAME, "api"));
    assertThat(extract(KEY, List.of(node(KEY, new ITSystem("Billing", null, null, null))), Map.of()))
        .containsExactly(new Feature(FeatureKind.NAME, "billing"));
  }

  @Test
  void ownerComesFromOwnedByWithGidFromResolvedMap() {
    var commands = List.<GraphCommand>of(node(KEY, new ITSystem("Billing", null, null, null)), ownedBy(KEY, TEAM_KEY));

    assertThat(extract(KEY, commands, Map.of(KEY, UUID.randomUUID(), TEAM_KEY, TEAM_GID)))
        .containsExactlyInAnyOrder(
            new Feature(FeatureKind.NAME, "billing"), new Feature(FeatureKind.OWNER, TEAM_GID.toString()));
  }

  @Test
  void noOwnerWithoutOwnedByOrWithoutTeamGid() {
    var plain = List.<GraphCommand>of(node(KEY, new ITSystem("Billing", null, null, null)));
    var owned = List.<GraphCommand>of(node(KEY, new ITSystem("Billing", null, null, null)), ownedBy(KEY, TEAM_KEY));

    assertThat(extract(KEY, plain, Map.of(TEAM_KEY, TEAM_GID))).extracting(Feature::kind).containsExactly(FeatureKind.NAME);
    assertThat(extract(KEY, owned, Map.of())).extracting(Feature::kind).containsExactly(FeatureKind.NAME);
  }

  @Test
  void extractorDoesNotChangeGids() {
    var gids = Map.of(KEY, UUID.randomUUID(), TEAM_KEY, TEAM_GID);
    var copy = Map.copyOf(gids);

    extract(KEY, List.of(node(KEY, new ITSystem("Billing", null, null, null)), ownedBy(KEY, TEAM_KEY)), gids);

    assertThat(gids).isEqualTo(copy);
  }

  @Test
  void noUpsertNodeOfKeyMeansNoFeatures() {
    var other = new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", "other");

    assertThat(extract(KEY, List.of(node(other, new ITSystem("Billing", null, null, null))), Map.of())).isEmpty();
    assertThat(extract(KEY, List.of(ownedBy(KEY, TEAM_KEY)), Map.of(TEAM_KEY, TEAM_GID))).isEmpty();
    assertThat(FeatureExtractor.labelOf(KEY, List.of(ownedBy(KEY, TEAM_KEY)))).isEmpty();
  }

  @Test
  void environmentAndDeploymentGiveNoFeatures() {
    var env = new Environment("prod", "Production", EnvironmentClass.PROD);
    var deployment = new Deployment("svc@prod", "svc", "1.0", "RUNNING", T);

    assertThat(extract(KEY, List.of(node(KEY, env)), Map.of())).isEmpty();
    assertThat(extract(KEY, List.of(node(KEY, deployment)), Map.of())).isEmpty();
  }

  @Test
  void ownerIsNotStrongAndScoreIsCappedSum() {
    assertThat(FeatureKind.OWNER.strong()).isFalse();
    assertThat(FeatureKind.NAME.strong() && FeatureKind.HOSTNAME.strong() && FeatureKind.REPOSITORY_URL.strong()).isTrue();
    assertThat(FeatureKind.score(Set.of(FeatureKind.HOSTNAME))).isEqualByComparingTo(new BigDecimal("0.60"));
    assertThat(FeatureKind.score(Set.of(FeatureKind.NAME, FeatureKind.OWNER))).isEqualByComparingTo(new BigDecimal("0.60"));
    assertThat(FeatureKind.score(Set.of(FeatureKind.HOSTNAME, FeatureKind.REPOSITORY_URL, FeatureKind.NAME)))
        .isEqualByComparingTo(BigDecimal.ONE);
  }

  @Test
  void labelFamilyGroupsComputeInstanceSubtypes() {
    assertThat(LabelFamily.of(NodeLabel.VIRTUAL_MACHINE)).isEqualTo(NodeLabel.COMPUTE_INSTANCE);
    assertThat(LabelFamily.of(NodeLabel.PHYSICAL_SERVER)).isEqualTo(NodeLabel.COMPUTE_INSTANCE);
    assertThat(LabelFamily.of(NodeLabel.IT_SYSTEM)).isNotEqualTo(LabelFamily.of(NodeLabel.SERVICE));
  }
}
