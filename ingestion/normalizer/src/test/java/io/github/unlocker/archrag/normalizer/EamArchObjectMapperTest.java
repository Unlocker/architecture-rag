package io.github.unlocker.archrag.normalizer;

import static io.github.unlocker.archrag.normalizer.Fixtures.RAW;
import static io.github.unlocker.archrag.normalizer.Fixtures.event;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.Criticality;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.Platform;
import io.github.unlocker.archrag.canonicalmodel.node.Solution;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.normalizer.NormalizationResult.Normalized;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Маппинг {@code ArchObject} реального EAM API ({@code PLATFORM}/{@code SOLUTION}/{@code IT_SYSTEM}, ключ {@code id}). */
class EamArchObjectMapperTest {

  private static final String SCHEMA = "urn:corp:schema:asset-upserted:1";

  private final Set<SourceKey> known = new HashSet<>();
  private final Normalizer normalizer = Normalizer.standard(known::contains);

  private static SourceKey key(String type, String id) {
    return new SourceKey(SourceSystemCode.EAM, type, id);
  }

  private static Map<String, Object> archObject(long id, Object... attrs) {
    Map<String, Object> a = new LinkedHashMap<>();
    for (int i = 0; i < attrs.length; i += 2) {
      a.put((String) attrs[i], attrs[i + 1]);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("ver", 1L);
    m.put("datetime", "2026-10-01T09:00:00Z");
    m.put("attrs", a);
    return m;
  }

  private NormalizationResult run(String type, long id, Map<String, Object> payload) {
    return normalizer.normalize(event("urn:corp:eam", SCHEMA, type, Long.toString(id), payload), RAW);
  }

  private Normalized ok(String type, long id, Map<String, Object> payload) {
    var r = run(type, id, payload);
    assertThat(r).isInstanceOf(Normalized.class);
    return (Normalized) r;
  }

  private static List<UpsertRelation> relations(Normalized n) {
    return n.commands().stream().filter(UpsertRelation.class::isInstance).map(UpsertRelation.class::cast).toList();
  }

  @Test
  void platformMapsToPlatformNode() {
    var n = ok("PLATFORM", 3101, archObject(3101, "name", "Kubernetes Platform", "status", "ACTIVE",
        "lifecycle", "PRODUCTION", "description", "Shared container runtime"));
    var node = (UpsertNode) n.commands().get(0);
    assertThat(node.record().key()).isEqualTo(key("PLATFORM", "3101"));
    assertThat(node.data()).isEqualTo(new Platform("Kubernetes Platform", null, null, "PRODUCTION", "Shared container runtime"));
    assertThat(relations(n)).isEmpty();
  }

  @Test
  void solutionMapsToSolutionAndUsesPlatformForEveryReference() {
    known.add(key("PLATFORM", "3101"));
    known.add(key("PLATFORM", "3102"));
    var n = ok("SOLUTION", 7202, archObject(7202, "name", "Ledger Engine", "lifecycle", "PRODUCTION",
        "description", "Double-entry ledger solution", "platform", List.of(3101L, 3102L)));
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Solution("Ledger Engine", "PRODUCTION", "Double-entry ledger solution"));
    assertThat(relations(n)).extracting(UpsertRelation::type, UpsertRelation::from, UpsertRelation::to)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple(RelationType.USES_PLATFORM, key("SOLUTION", "7202"), key("PLATFORM", "3101")),
            org.assertj.core.api.Assertions.tuple(RelationType.USES_PLATFORM, key("SOLUTION", "7202"), key("PLATFORM", "3102")));
    assertThat(n.unresolved()).isEmpty();
  }

  @Test
  void itSystemMapsToItSystemWithEamCodeAndUsesSolution() {
    known.add(key("SOLUTION", "7201"));
    var n = ok("IT_SYSTEM", 5101, archObject(5101, "name", "Payments Core", "eam_id", "EAM-1042", "status", "ACTIVE",
        "criticality", "HIGH", "description", "Payments processing system", "solution", List.of(7201L)));
    var node = (UpsertNode) n.commands().get(0);
    assertThat(node.record().key()).isEqualTo(key("IT_SYSTEM", "5101"));
    assertThat(node.data()).isEqualTo(new ITSystem("Payments Core", "ACTIVE", Criticality.HIGH,
        "Payments processing system", "EAM-1042", null));
    assertThat(relations(n)).singleElement().satisfies(r -> {
      assertThat(r.type()).isEqualTo(RelationType.USES_SOLUTION);
      assertThat(r.from()).isEqualTo(key("IT_SYSTEM", "5101"));
      assertThat(r.to()).isEqualTo(key("SOLUTION", "7201"));
    });
  }

  @Test
  void referenceToNotYetLoadedObjectIsUnresolvedNotAPhantomNode() {
    var n = ok("IT_SYSTEM", 5101, archObject(5101, "name", "A", "solution", List.of(7299L)));
    assertThat(n.commands()).hasSize(1);
    assertThat(n.unresolved()).singleElement().satisfies(u -> {
      assertThat(u.field()).isEqualTo("solution");
      assertThat(u.relationType()).isEqualTo(RelationType.USES_SOLUTION);
      assertThat(u.target()).isEqualTo(key("SOLUTION", "7299"));
    });
  }

  @Test
  void referenceFormsListSingleNumberAndNullAreAccepted() {
    known.add(key("SOLUTION", "7201"));
    assertThat(relations(ok("IT_SYSTEM", 1, archObject(1, "name", "A", "solution", 7201L)))).hasSize(1);
    assertThat(relations(ok("IT_SYSTEM", 2, archObject(2, "name", "A", "solution", List.of(7201L))))).hasSize(1);
    assertThat(relations(ok("IT_SYSTEM", 3, archObject(3, "name", "A", "solution", null)))).isEmpty();
    assertThat(relations(ok("IT_SYSTEM", 4, archObject(4, "name", "A", "solution", List.of())))).isEmpty();
    assertThat(relations(ok("IT_SYSTEM", 5, archObject(5, "name", "A")))).isEmpty();
  }

  @Test
  void changedReferenceYieldsOnlyTheNewRelationSoTheOldOneIsNotAsserted() {
    known.add(key("PLATFORM", "3101"));
    known.add(key("PLATFORM", "3103"));
    var before = ok("SOLUTION", 7201, archObject(7201, "name", "S", "platform", List.of(3101L)));
    var after = ok("SOLUTION", 7201, archObject(7201, "name", "S", "platform", List.of(3103L)));
    assertThat(relations(before)).extracting(UpsertRelation::to).containsExactly(key("PLATFORM", "3101"));
    assertThat(relations(after)).extracting(UpsertRelation::to).containsExactly(key("PLATFORM", "3103"));
  }

  @Test
  void sameNameDifferentIdsAreDifferentKeysNotMerged() {
    var a = ok("IT_SYSTEM", 5301, archObject(5301, "name", "Payments Gateway", "eam_id", "EAM-3001"));
    var b = ok("IT_SYSTEM", 5302, archObject(5302, "name", "Payments Gateway", "eam_id", "EAM-3002"));
    assertThat(((UpsertNode) a.commands().get(0)).record().key()).isNotEqualTo(((UpsertNode) b.commands().get(0)).record().key());
    assertThat(((UpsertNode) a.commands().get(0)).data()).isNotEqualTo(((UpsertNode) b.commands().get(0)).data());
  }

  @Test
  void unknownCriticalityIsKeptRawAndFlaggedNotDropped() {
    var n = ok("IT_SYSTEM", 5101, archObject(5101, "name", "A", "criticality", "BUSINESS-CRITICAL-X"));
    var data = (ITSystem) ((UpsertNode) n.commands().get(0)).data();
    assertThat(data.criticality()).isNull();
    assertThat(data.criticalityRaw()).isEqualTo("BUSINESS-CRITICAL-X");
    assertThat(n.warnings()).containsExactly("UNMAPPED_CRITICALITY");
  }

  @Test
  void knownCriticalityIsCaseInsensitiveAndAbsentCriticalityStaysNull() {
    var low = (ITSystem) ((UpsertNode) ok("IT_SYSTEM", 1, archObject(1, "name", "A", "criticality", "low")).commands().get(0)).data();
    assertThat(low.criticality()).isEqualTo(Criticality.LOW);
    var none = ok("IT_SYSTEM", 2, archObject(2, "name", "A"));
    assertThat(((ITSystem) ((UpsertNode) none.commands().get(0)).data()).criticality()).isNull();
    assertThat(none.warnings()).isEmpty();
  }

  @Test
  void ownerTeamReferenceKeepsOwnedByWithOldTeamKey() {
    known.add(key("TEAM", "TEAM-PAY"));
    var n = ok("IT_SYSTEM", 5101, archObject(5101, "name", "A", "ownerTeam", "TEAM-PAY"));
    assertThat(relations(n)).singleElement().satisfies(r -> {
      assertThat(r.type()).isEqualTo(RelationType.OWNED_BY);
      assertThat(r.to()).isEqualTo(key("TEAM", "TEAM-PAY"));
    });
  }

  @Test
  void nonIntegerReferenceIsQuarantinedWithoutValue() {
    var r = run("IT_SYSTEM", 1, archObject(1, "name", "A", "solution", List.of("SECRET-VALUE")));
    assertThat(r).isInstanceOfSatisfying(NormalizationResult.Quarantined.class, q -> {
      assertThat(q.errorCode()).isEqualTo("INVALID_PAYLOAD");
      assertThat(q.reason()).doesNotContain("SECRET-VALUE");
    });
  }

  @Test
  void archObjectWithoutNameIsQuarantined() {
    assertThat(run("PLATFORM", 1, archObject(1, "status", "ACTIVE")))
        .isInstanceOfSatisfying(NormalizationResult.Quarantined.class, q -> assertThat(q.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD"));
    Map<String, Object> flat = new LinkedHashMap<>();
    flat.put("name", "P");
    assertThat(run("PLATFORM", 2, flat))
        .isInstanceOfSatisfying(NormalizationResult.Quarantined.class, q -> assertThat(q.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD"));
  }

  @Test
  void legacyFlatItSystemGetsNoEamCodeSoAliasCannotHealOntoIt() {
    var flat = new LinkedHashMap<String, Object>();
    flat.put("name", "Payments Core");
    flat.put("criticality", "HIGH");
    var n = ok("IT_SYSTEM", 0, flat);
    var data = (ITSystem) ((UpsertNode) n.commands().get(0)).data();
    assertThat(data.eamCode()).isNull();
    assertThat(data.criticality()).isEqualTo(Criticality.HIGH);
  }
}
