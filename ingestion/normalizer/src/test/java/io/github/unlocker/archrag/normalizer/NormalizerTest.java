package io.github.unlocker.archrag.normalizer;

import static io.github.unlocker.archrag.normalizer.Fixtures.RAW;
import static io.github.unlocker.archrag.normalizer.Fixtures.T;
import static io.github.unlocker.archrag.normalizer.Fixtures.event;
import static io.github.unlocker.archrag.sourcestubs.StubSources.fields;
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
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.normalizer.NormalizationResult.Normalized;
import io.github.unlocker.archrag.normalizer.NormalizationResult.Quarantined;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NormalizerTest {

  private static final Clock CLOCK = Clock.fixed(T, ZoneOffset.UTC);
  private static final Map<SourceSystem, StubSource> STUBS = StubSources.seeded(CLOCK);

  private final Set<SourceKey> known = new HashSet<>();
  private final Normalizer normalizer = Normalizer.standard(known::contains);

  private static SourceKey key(SourceSystemCode s, String type, String id) {
    return new SourceKey(s, type, id);
  }

  private Normalized normalized(NormalizationResult r) {
    assertThat(r).isInstanceOf(Normalized.class);
    return (Normalized) r;
  }

  private Normalized stub(SourceSystem system, String urn, String type, String id) {
    var change = STUBS.get(system).fetchById(type, id).orElseThrow();
    return normalized(normalizer.normalize(event(urn, change), RAW));
  }

  private static <T extends GraphCommand> List<T> of(Normalized n, Class<T> type) {
    return n.commands().stream().filter(type::isInstance).map(type::cast).toList();
  }

  // ---- EAM

  @Test
  void eamTeamMapsToTeam() {
    var n = stub(SourceSystem.EAM, "urn:corp:eam", "TEAM", "TEAM-PAY");
    var node = (UpsertNode) n.commands().get(0);
    assertThat(node.data()).isEqualTo(new Team("Payments Team", null));
    assertThat(node.record().key()).isEqualTo(key(SourceSystemCode.EAM, "TEAM", "TEAM-PAY"));
    assertThat(node.record().sourceVersion()).isEqualTo("5");
    assertThat(node.record().contentHash()).isEqualTo("abc123");
    assertThat(node.record().fetchedAt()).isEqualTo(T);
  }

  @Test
  void eamItSystemMapsOwnerAndDropsUnsupportedDependsOn() {
    known.add(key(SourceSystemCode.EAM, "TEAM", "TEAM-PAY"));
    var n = stub(SourceSystem.EAM, "urn:corp:eam", "IT_SYSTEM", "EAM-1042");
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new ITSystem("Payments Core", null, null, null));
    var rel = of(n, UpsertRelation.class);
    assertThat(rel).singleElement().satisfies(r -> {
      assertThat(r.type()).isEqualTo(RelationType.OWNED_BY);
      assertThat(r.to()).isEqualTo(key(SourceSystemCode.EAM, "TEAM", "TEAM-PAY"));
      assertThat(r.validity()).isNull();
    });
    assertThat(n.warnings()).containsExactly("DEPENDS_ON_NOT_SUPPORTED");
  }

  // ---- SCM

  @Test
  void scmRepositoryMapsToRepository() {
    var n = stub(SourceSystem.SCM, "urn:corp:scm", "REPOSITORY", "repo-payments-api");
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Repository("https://git.example.org/pay/payments-api", "main", null));
  }

  @Test
  void scmServiceMapsRelationsToSystemAndRepository() {
    known.add(key(SourceSystemCode.EAM, "IT_SYSTEM", "EAM-1042"));
    known.add(key(SourceSystemCode.SCM, "REPOSITORY", "repo-payments-api"));
    var n = stub(SourceSystem.SCM, "urn:corp:scm", "SERVICE", "svc-payments-api");
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Service("payments-api", null, null, null));
    assertThat(of(n, UpsertRelation.class))
        .extracting(UpsertRelation::type, UpsertRelation::from, UpsertRelation::to)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple(
                RelationType.DECOMPOSED_INTO,
                key(SourceSystemCode.EAM, "IT_SYSTEM", "EAM-1042"),
                key(SourceSystemCode.SCM, "SERVICE", "svc-payments-api")),
            org.assertj.core.api.Assertions.tuple(
                RelationType.IMPLEMENTED_IN,
                key(SourceSystemCode.SCM, "SERVICE", "svc-payments-api"),
                key(SourceSystemCode.SCM, "REPOSITORY", "repo-payments-api")));
    assertThat(n.unresolved()).isEmpty();
  }

  @Test
  void brokenReferenceBecomesUnresolvedReferenceWithoutPhantomNode() {
    var scm = StubSources.scm(CLOCK);
    String id = StubSources.addServiceWithBrokenReference(scm);
    var change = scm.fetchById("SERVICE", id).orElseThrow();
    var n = normalized(normalizer.normalize(event("urn:corp:scm", change), RAW));
    assertThat(of(n, UpsertNode.class)).hasSize(1);
    assertThat(of(n, UpsertRelation.class)).isEmpty();
    assertThat(n.unresolved()).singleElement().satisfies(u -> {
      assertThat(u.field()).isEqualTo("systemCode");
      assertThat(u.relationType()).isEqualTo(RelationType.DECOMPOSED_INTO);
      assertThat(u.target()).isEqualTo(key(SourceSystemCode.EAM, "IT_SYSTEM", "EAM-UNKNOWN"));
      assertThat(u.from()).isEqualTo(key(SourceSystemCode.SCM, "SERVICE", id));
    });
  }

  // ---- CMDB

  @Test
  void cmdbComputeInstanceMapsKindAndState() {
    var n = stub(SourceSystem.CMDB, "urn:corp:cmdb", "COMPUTE_INSTANCE", "vm-pay-01");
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(
            new ComputeInstance(
                "vm-pay-01.prod.example.org", ComputeKind.VIRTUAL_MACHINE, null, null, "RUNNING", null, null));
  }

  @Test
  void cmdbKindMismatchIsQuarantined() {
    var e = event("urn:corp:cmdb", "urn:corp:schema:asset-upserted:1", "COMPUTE_INSTANCE", "x",
        fields("hostname", "h", "kind", "PHYSICAL_SERVER", "hypervisorRef", "hv"));
    assertThat(normalizer.normalize(e, RAW))
        .isEqualTo(new Quarantined("INVALID_PAYLOAD", "payload violates canonical model invariants"));
  }

  // ---- deploymap

  @Test
  void deployMapEnvironmentInfersClassFromCode() {
    var n = stub(SourceSystem.DEPLOY_MAP, "urn:corp:deploymap", "ENVIRONMENT", "prod");
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Environment("prod", "Production", EnvironmentClass.PROD));
  }

  @Test
  void deployMapDeploymentMapsServiceEnvironmentAndHosts() {
    known.add(key(SourceSystemCode.SCM, "SERVICE", "svc-payments-api"));
    known.add(key(SourceSystemCode.DEPLOYMAP, "ENVIRONMENT", "prod"));
    known.add(key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", "vm-pay-01"));
    var n = stub(SourceSystem.DEPLOY_MAP, "urn:corp:deploymap", "DEPLOYMENT", "dep-payments-api-prod");
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Deployment("dep-payments-api-prod", "payments-api", "1.4.2", null, T));
    assertThat(of(n, UpsertRelation.class))
        .extracting(UpsertRelation::type)
        .containsExactlyInAnyOrder(
            RelationType.HAS_DEPLOYMENT, RelationType.IN_ENVIRONMENT, RelationType.RUNS_ON);
    assertThat(of(n, UpsertRelation.class))
        .filteredOn(r -> r.type() == RelationType.RUNS_ON)
        .singleElement()
        .satisfies(r -> assertThat(r.validity()).isNull());
  }

  // ---- schema, completeness, validation

  @Test
  void unknownSchemaVersionIsQuarantined() {
    var e = event("urn:corp:eam", "urn:corp:schema:asset-upserted:2", "TEAM", "t", fields("name", "N"));
    assertThat(normalizer.normalize(e, RAW))
        .isEqualTo(new Quarantined("UNKNOWN_SCHEMA_VERSION", "dataschema version is not supported"));
  }

  @Test
  void foreignSchemaUrnIsQuarantined() {
    var e = event("urn:corp:eam", "urn:other:thing:1", "TEAM", "t", fields("name", "N"));
    assertThat(normalizer.normalize(e, RAW)).isInstanceOf(Quarantined.class);
  }

  @Test
  void unknownSourceIsQuarantined() {
    var e = event("urn:corp:hr", "urn:corp:schema:asset-upserted:1", "TEAM", "t", fields("name", "N"));
    assertThat(((Quarantined) normalizer.normalize(e, RAW)).errorCode()).isEqualTo("UNKNOWN_SOURCE");
  }

  @Test
  void unknownSourceTypeIsQuarantined() {
    var e = event("urn:corp:eam", "urn:corp:schema:asset-upserted:1", "WIDGET", "w", fields("name", "N"));
    assertThat(((Quarantined) normalizer.normalize(e, RAW)).errorCode()).isEqualTo("UNKNOWN_SOURCE_TYPE");
  }

  @Test
  void missingRequiredFieldIsQuarantinedWithoutPayloadValues() {
    var e = event("urn:corp:eam", "urn:corp:schema:asset-upserted:1", "IT_SYSTEM", "s",
        fields("name", null, "description", "SECRET-TEXT"));
    var q = (Quarantined) normalizer.normalize(e, RAW);
    assertThat(q.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD");
    assertThat(q.reason()).contains("name").doesNotContain("SECRET-TEXT");
  }

  @Test
  void wrongFieldTypeIsQuarantined() {
    var e = event("urn:corp:eam", "urn:corp:schema:asset-upserted:1", "TEAM", "t", fields("name", 42));
    assertThat(((Quarantined) normalizer.normalize(e, RAW)).errorCode()).isEqualTo("INVALID_PAYLOAD");
  }

  @Test
  void partialRecordDoesNotEmitNullsOrCloseAssertions() {
    var scm = StubSources.scm(CLOCK);
    scm.upsertPartial("SERVICE", "svc-payments-api",
        fields("name", "payments-api", "language", null, "systemCode", null, "repositoryId", null));
    var change = scm.fetchById("SERVICE", "svc-payments-api").orElseThrow();
    var n = normalized(normalizer.normalize(event("urn:corp:scm", change), RAW));
    assertThat(n.commands()).hasSize(1);
    assertThat(((UpsertNode) n.commands().get(0)).data()).isEqualTo(new Service("payments-api", null, null, null));
    assertThat(n.unresolved()).isEmpty();
  }

  @Test
  void blankOptionalValueIsTreatedAsAbsent() {
    var e = event("urn:corp:eam", "urn:corp:schema:asset-upserted:1", "TEAM", "t", fields("name", "N", "type", "  "));
    var n = normalized(normalizer.normalize(e, RAW));
    assertThat(((UpsertNode) n.commands().get(0)).data()).isEqualTo(new Team("N", null));
  }

  @Test
  void unsupportedEventTypeIsQuarantined() {
    var base = event("urn:corp:eam", "urn:corp:schema:asset-upserted:1", "TEAM", "t", fields("name", "N"));
    var e = new io.github.unlocker.archrag.eventschemas.CanonicalEvent(base.id(), base.source(),
        "architecture.asset.deleted.v1", base.subject(), base.time(), base.dataschema(), null, base.data());
    assertThat(((Quarantined) normalizer.normalize(e, RAW)).errorCode()).isEqualTo("UNSUPPORTED_EVENT_TYPE");
  }

  // ---- review: defaults must not overwrite, idempotence, unresolved in deploymap

  @Test
  void absentArchivedStaysNullInsteadOfFalse() {
    var e = event("urn:corp:scm", "urn:corp:schema:asset-upserted:1", "REPOSITORY", "r",
        fields("url", "https://git.example.org/r"));
    var n = normalized(normalizer.normalize(e, RAW));
    assertThat(((UpsertNode) n.commands().get(0)).data())
        .isEqualTo(new Repository("https://git.example.org/r", null, null));
  }

  @Test
  void absentKindIsQuarantinedNotDefaulted() {
    var e = event("urn:corp:cmdb", "urn:corp:schema:asset-upserted:1", "COMPUTE_INSTANCE", "c",
        fields("hostname", "h"));
    assertThat(((Quarantined) normalizer.normalize(e, RAW)).errorCode()).isEqualTo("MISSING_REQUIRED_FIELD");
  }

  @Test
  void absentNameOfEnvironmentAndDeploymentIsQuarantinedNotDefaulted() {
    var env = event("urn:corp:deploymap", "urn:corp:schema:asset-upserted:1", "ENVIRONMENT", "prod",
        fields("class", "PROD"));
    var dep = event("urn:corp:deploymap", "urn:corp:schema:asset-upserted:1", "DEPLOYMENT", "d",
        fields("chart", "payments-api"));
    assertThat(((Quarantined) normalizer.normalize(env, RAW)).errorCode()).isEqualTo("MISSING_REQUIRED_FIELD");
    assertThat(((Quarantined) normalizer.normalize(dep, RAW)).errorCode()).isEqualTo("MISSING_REQUIRED_FIELD");
  }

  @Test
  void invariantViolationReasonDoesNotLeakSourceValues() {
    var e = event("urn:corp:cmdb", "urn:corp:schema:asset-upserted:1", "COMPUTE_INSTANCE", "c",
        fields("hostname", "h", "kind", "SECRET-KIND"));
    var q = (Quarantined) normalizer.normalize(e, RAW);
    assertThat(q.errorCode()).isEqualTo("INVALID_PAYLOAD");
    assertThat(q.reason()).doesNotContain("SECRET-KIND");
  }

  @Test
  void deploymentWithUnknownReferencesYieldsUnresolvedForEach() {
    var n = stub(SourceSystem.DEPLOY_MAP, "urn:corp:deploymap", "DEPLOYMENT", "dep-payments-api-prod");
    var dep = key(SourceSystemCode.DEPLOYMAP, "DEPLOYMENT", "dep-payments-api-prod");
    assertThat(of(n, UpsertRelation.class)).isEmpty();
    assertThat(n.unresolved())
        .extracting(UnresolvedReference::field, UnresolvedReference::relationType,
            UnresolvedReference::from, UnresolvedReference::target)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple("service", RelationType.HAS_DEPLOYMENT, dep,
                key(SourceSystemCode.SCM, "SERVICE", "svc-payments-api")),
            org.assertj.core.api.Assertions.tuple("environment", RelationType.IN_ENVIRONMENT, dep,
                key(SourceSystemCode.DEPLOYMAP, "ENVIRONMENT", "prod")),
            org.assertj.core.api.Assertions.tuple("hosts", RelationType.RUNS_ON, dep,
                key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", "vm-pay-01")));
  }

  @Test
  void validityIsTakenOnlyFromSourceAndIsStableAcrossReplays() {
    known.add(key(SourceSystemCode.EAM, "TEAM", "TEAM-PAY"));
    var payload = fields("name", "S", "ownerTeam", "TEAM-PAY", "ownerSince", "2026-01-01T00:00:00Z");
    var first = event("urn:corp:eam", "urn:corp:schema:asset-upserted:1", "IT_SYSTEM", "s", payload);
    var later = new io.github.unlocker.archrag.eventschemas.CanonicalEvent(first.id(), first.source(), first.type(),
        first.subject(), T.plusSeconds(3600), first.dataschema(), null, first.data());
    var a = normalized(normalizer.normalize(first, RAW));
    var b = normalized(normalizer.normalize(later, RAW));
    assertThat(of(a, UpsertRelation.class)).singleElement()
        .satisfies(r -> assertThat(r.validity().validFrom()).isEqualTo(java.time.Instant.parse("2026-01-01T00:00:00Z")));
    assertThat(of(b, UpsertRelation.class)).isEqualTo(of(a, UpsertRelation.class));
  }

  @Test
  void normalizingSameEventTwiceGivesEqualResult() {
    known.add(key(SourceSystemCode.EAM, "TEAM", "TEAM-PAY"));
    var e = event("urn:corp:eam", STUBS.get(SourceSystem.EAM).fetchById("IT_SYSTEM", "EAM-1042").orElseThrow());
    assertThat(normalizer.normalize(e, RAW)).isEqualTo(normalizer.normalize(e, RAW));
  }
}
