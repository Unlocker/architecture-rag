package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.Feature;
import io.github.unlocker.archrag.identityresolution.FeatureKind;
import io.github.unlocker.archrag.identityresolution.IdentityCandidate;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * E3.2 через {@link EventProcessor} на реальных PostgreSQL и Neo4j Community: кандидаты по hostname/имени,
 * без merge узлов, идемпотентность, общий gid, tombstone.
 */
@Testcontainers
class IdentityCandidateIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PostgresEventJournal journal;
  static PostgresIdentityMapping identity;
  static PostgresIdentityCandidates candidates;
  static EventProcessor processor;

  @BeforeAll
  static void setUp() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    Neo4jSchema.apply(driver);
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    JournalMigrations.apply(ds);
    journal = new PostgresEventJournal(ds);
    identity = new PostgresIdentityMapping(ds);
    candidates = new PostgresIdentityCandidates(ds);
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor =
        new EventProcessor(
            journal, Normalizer.standard(projector::isActive), identity, projector, (source, run, id, count) -> {}, candidates);
  }

  private static String uid() {
    return UUID.randomUUID().toString();
  }

  private static ProcessingStatus upsert(String source, String type, String id, String version, Map<String, Object> payload) {
    var event =
        new CanonicalEvent(
            "e-" + uid(), "urn:corp:" + source, CanonicalEvent.TYPE_ASSET_UPSERTED, type + "/" + id, T1,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData(type, id, new SourceVersion(version), payload));
    return handle(event);
  }

  private static ProcessingStatus delete(String source, String type, String id, String version) {
    var event =
        new CanonicalEvent(
            "e-" + uid(), "urn:corp:" + source, "architecture.asset.deleted.v1", type + "/" + id, T1,
            "urn:corp:schema:asset-deleted:1", null, new AssetEventData(type, id, new SourceVersion(version), Map.of()));
    return handle(event);
  }

  private static ProcessingStatus handle(CanonicalEvent event) {
    journal.append(event, RAW, null);
    return processor.process(event, RAW).status();
  }

  private static SourceKey key(SourceSystemCode source, String type, String id) {
    return new SourceKey(source, type, id);
  }

  private static UUID gid(SourceKey key) {
    return identity.find(key).orElseThrow();
  }

  private static ProcessingStatus host(String id, String kind, String hostname, String version) {
    return upsert("cmdb", "COMPUTE_INSTANCE", id, version, Map.of("hostname", hostname, "kind", kind));
  }

  private static ProcessingStatus system(String id, String name, String team) {
    return upsert("eam", "IT_SYSTEM", id, "1", Map.of("name", name, "ownerTeam", team));
  }

  private static ProcessingStatus team(String id) {
    return upsert("eam", "TEAM", id, "1", Map.of("name", "team-" + id));
  }

  private static long nodesWithGids(UUID... gids) {
    return driver
        .executableQuery("MATCH (n) WHERE n.gid IN $gids RETURN count(n) AS c")
        .withParameters(Map.of("gids", java.util.Arrays.stream(gids).map(UUID::toString).toList()))
        .execute()
        .records()
        .get(0)
        .get("c")
        .asLong();
  }

  @Test
  void sameHostnameInTwoRecordsKeepsTwoNodesAndRecordsOneOpenCandidate() {
    String a = uid();
    String b = uid();
    String suffix = uid();

    // Деплоймап не поставляет COMPUTE_INSTANCE: два CI разных подтипов одного семейства — из CMDB.
    assertThat(host(a, "VIRTUAL_MACHINE", "App-01." + suffix + ".corp.", "1")).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(host(b, "PHYSICAL_SERVER", "app-01." + suffix + ".corp", "1")).isEqualTo(ProcessingStatus.PROJECTED);

    var ka = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", a);
    var kb = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", b);
    assertThat(gid(ka)).isNotEqualTo(gid(kb));
    assertThat(nodesWithGids(gid(ka), gid(kb))).isEqualTo(2);
    assertThat(candidates.candidatesOf(ka)).singleElement().satisfies(c -> {
      assertThat(c.status()).isEqualTo("OPEN");
      assertThat(c.labelFamily()).isEqualTo(NodeLabel.COMPUTE_INSTANCE);
      assertThat(c.matched()).containsExactly(new Feature(FeatureKind.HOSTNAME, "app-01." + suffix + ".corp"));
      assertThat(c.score()).isEqualByComparingTo("0.60");
      assertThat(List.of(c.left(), c.right())).containsExactlyInAnyOrder(ka, kb);
    });
    assertThat(candidates.candidatesOf(kb)).hasSize(1);
  }

  @Test
  void sameNameAndOwnerGivesCandidateWithBothFeatures() {
    String t = uid();
    String a = uid();
    String b = uid();
    String name = "Billing " + t;
    team(t);

    system(a, name, t);
    system(b, name.toUpperCase().replace(' ', '_'), t);

    var ka = key(SourceSystemCode.EAM, "IT_SYSTEM", a);
    assertThat(candidates.candidatesOf(ka)).singleElement().satisfies(c -> {
      assertThat(c.matched()).extracting(Feature::kind).containsExactly(FeatureKind.NAME, FeatureKind.OWNER);
      assertThat(c.score()).isEqualByComparingTo("0.60");
    });
  }

  @Test
  void sameOwnerWithDifferentNamesGivesNoCandidate() {
    String t = uid();
    String a = uid();
    String b = uid();
    team(t);

    system(a, "Alpha " + t, t);
    system(b, "Beta " + t, t);

    assertThat(candidates.candidatesOf(key(SourceSystemCode.EAM, "IT_SYSTEM", a))).isEmpty();
    assertThat(candidates.candidatesOf(key(SourceSystemCode.EAM, "IT_SYSTEM", b))).isEmpty();
  }

  @Test
  void itSystemAndServiceWithSameNameAreNotCandidates() {
    String name = "Billing " + uid();
    String sys = uid();
    String svc = uid();

    upsert("eam", "IT_SYSTEM", sys, "1", Map.of("name", name));
    upsert("scm", "SERVICE", svc, "1", Map.of("name", name));

    assertThat(candidates.candidatesOf(key(SourceSystemCode.EAM, "IT_SYSTEM", sys))).isEmpty();
    assertThat(candidates.candidatesOf(key(SourceSystemCode.SCM, "SERVICE", svc))).isEmpty();
  }

  @Test
  void recordsLinkedByCrosswalkShareGidAndGiveNoCandidate() {
    String a = uid();
    String b = uid();
    String hostname = "shared-" + uid();
    var ka = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", a);
    var kb = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", b);
    identity.approve(new Crosswalk(ka, kb, "admin", "same host", Instant.parse("2026-10-04T12:00:00Z")));

    host(a, "VIRTUAL_MACHINE", hostname, "1");
    host(b, "VIRTUAL_MACHINE", hostname, "1");

    assertThat(gid(ka)).isEqualTo(gid(kb));
    assertThat(candidates.candidatesOf(ka)).isEmpty();
  }

  @Test
  void replayKeepsOneCandidateAndFirstSeenAt() {
    String a = uid();
    String b = uid();
    String hostname = "replay-" + uid();
    host(a, "VIRTUAL_MACHINE", hostname, "1");
    host(b, "VIRTUAL_MACHINE", hostname, "1");
    var ka = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", a);
    IdentityCandidate first = candidates.candidatesOf(ka).get(0);

    assertThat(host(a, "VIRTUAL_MACHINE", hostname, "2")).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(host(b, "VIRTUAL_MACHINE", hostname, "2")).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(candidates.candidatesOf(ka)).singleElement().satisfies(c -> {
      assertThat(c.firstSeenAt()).isEqualTo(first.firstSeenAt());
      assertThat(c.status()).isEqualTo("OPEN");
      assertThat(c.matched()).isEqualTo(first.matched());
    });
  }

  @Test
  void tombstonedRecordDoesNotMatchLaterRecord() {
    String a = uid();
    String b = uid();
    String c = uid();
    String hostname = "gone-" + uid();
    host(a, "VIRTUAL_MACHINE", hostname, "1");
    delete("cmdb", "COMPUTE_INSTANCE", a, "2");
    host(b, "VIRTUAL_MACHINE", hostname, "1");
    var kb = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", b);
    assertThat(candidates.candidatesOf(kb)).isEmpty();

    host(c, "VIRTUAL_MACHINE", hostname, "1");

    var kc = key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", c);
    assertThat(candidates.candidatesOf(kc)).singleElement().satisfies(cand -> assertThat(List.of(cand.left(), cand.right())).containsExactlyInAnyOrder(kb, kc));
  }

  @Test
  void popularFeatureAboveLimitCreatesNoCandidates() {
    String hostname = "popular-" + uid();
    for (int i = 0; i <= PostgresIdentityCandidates.MAX_MATCHES; i++) {
      host(uid(), "VIRTUAL_MACHINE", hostname, "1");
    }
    String last = uid();

    host(last, "VIRTUAL_MACHINE", hostname, "1");

    assertThat(candidates.candidatesOf(key(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", last))).isEmpty();
  }
}
