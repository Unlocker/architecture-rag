package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.ProcessingResult;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.identityresolution.PostgresSourceConflicts;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Проекция объектов {@code eam-api} (ключ {@code id}) на реальных Neo4j и PostgreSQL: срез
 * {@code ITSystem → Solution → Platform}, сопоставление {@code eam_id → id} для сервиса SCM в любом порядке прихода,
 * смена ссылки, одинаковое {@code name} у разных {@code id}.
 */
@Testcontainers
class EamApiProjectionIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");
  private static final Instant T3 = Instant.parse("2026-10-03T10:00:00Z");
  private static final String COMMIT_A = "9fceb02d0ae598e95dc970b74767f19372d61af8";
  private static final String COMMIT_B = "0123456789abcdef0123456789abcdef01234567";
  private static final String URL = "https://git.example.org/pay/payments-api";

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PostgresEventJournal journal;
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
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor =
        new EventProcessor(
            journal,
            Normalizer.standard(projector::isActive, projector),
            new PostgresIdentityMapping(ds),
            projector,
            (source, run, id, count) -> {},
            new PostgresIdentityCandidates(ds),
            new PostgresSourceConflicts(ds, AuthorityMatrix.defaults()));
  }

  @BeforeEach
  void clearGraph() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static ProcessingResult handle(
      String source, String type, String id, String version, Instant time, Map<String, Object> payload) {
    var event =
        new CanonicalEvent(
            "e-" + UUID.randomUUID(), "urn:corp:" + source, CanonicalEvent.TYPE_ASSET_UPSERTED, type + "/" + id, time,
            "urn:corp:schema:asset-upserted:1", null, new AssetEventData(type, id, new SourceVersion(version), payload));
    journal.append(event, RAW, null);
    return processor.process(event, RAW);
  }

  private static Map<String, Object> archObject(long id, long ver, Object... attrs) {
    Map<String, Object> a = new LinkedHashMap<>();
    for (int i = 0; i < attrs.length; i += 2) {
      a.put((String) attrs[i], attrs[i + 1]);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("ver", ver);
    m.put("datetime", T1.toString());
    m.put("attrs", a);
    return m;
  }

  private static ProcessingResult eam(String type, long id, long ver, Instant time, Object... attrs) {
    return handle("eam", type, Long.toString(id), Long.toString(ver), time, archObject(id, ver, attrs));
  }

  private static ProcessingResult paymentsCore(long ver, Instant time) {
    return eam("IT_SYSTEM", 5101, ver, time, "name", "Payments Core", "eam_id", "EAM-1042", "status", "ACTIVE",
        "criticality", "HIGH", "solution", List.of(7201L));
  }

  private static ProcessingResult paymentsService(long ver, Instant time, String systemCode) {
    return handle("scm", "SERVICE", "svc-payments-api", Long.toString(ver), time,
        Map.of("name", "payments-api", "systemCode", systemCode));
  }

  private static List<Record> query(String cypher, Object... params) {
    Map<String, Object> map = new TreeMap<>();
    for (int i = 0; i < params.length; i += 2) {
      map.put((String) params[i], params[i + 1]);
    }
    return driver.executableQuery(cypher).withParameters(map).execute().records();
  }

  private static List<Record> decomposed() {
    return query("MATCH (i:ITSystem)-[rel:DECOMPOSED_INTO]->(s:Service) WHERE rel.validTo IS NULL "
        + "RETURN i.name AS system, s.name AS service, "
        + "[(r:SourceRecord)-[:ASSERTS]->(i) | r.sourceId][0] AS systemKey");
  }

  private static long pending() {
    return pending(null);
  }

  private static long pending(String type) {
    return query("MATCH (p:PendingRelation) WHERE $type IS NULL OR p.type = $type RETURN count(p) AS c", "type", type)
        .get(0).get("c").asLong();
  }

  // ---- tests -------------------------------------------------------------------------------

  @Test
  void verticalSliceItSystemSolutionPlatformIsBuiltInAnyOrder() {
    // Системы раньше решения и платформы: обе связи откладываются и достраиваются с приходом целей.
    assertThat(paymentsCore(1, T1).status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(eam("SOLUTION", 7201, 1, T1, "name", "Card Processing", "lifecycle", "PRODUCTION",
        "platform", List.of(3101L)).status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(pending("USES_PLATFORM")).isEqualTo(1);
    assertThat(pending("USES_SOLUTION")).isZero();
    assertThat(eam("PLATFORM", 3101, 1, T1, "name", "Kubernetes Platform", "lifecycle", "PRODUCTION").status())
        .isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(query("MATCH (i:ITSystem)-[:USES_SOLUTION]->(s:Solution)-[:USES_PLATFORM]->(p:Platform) "
        + "RETURN i.name AS system, s.name AS solution, p.name AS platform"))
        .singleElement()
        .satisfies(r -> {
          assertThat(r.get("system").asString()).isEqualTo("Payments Core");
          assertThat(r.get("solution").asString()).isEqualTo("Card Processing");
          assertThat(r.get("platform").asString()).isEqualTo("Kubernetes Platform");
        });
    assertThat(pending()).isZero();
    assertThat(query("MATCH (i:ITSystem) RETURN i.eamCode AS code").get(0).get("code").asString()).isEqualTo("EAM-1042");
  }

  @Test
  void scmServiceBeforeEamIsLinkedToItSystemWhenItArrives() {
    assertThat(paymentsService(1, T1, "EAM-1042").status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(decomposed()).isEmpty();
    assertThat(pending("DECOMPOSED_INTO")).isEqualTo(1);

    assertThat(paymentsCore(1, T2).status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(decomposed()).singleElement().satisfies(r -> {
      assertThat(r.get("system").asString()).isEqualTo("Payments Core");
      assertThat(r.get("service").asString()).isEqualTo("payments-api");
      assertThat(r.get("systemKey").asString()).isEqualTo("5101");
    });
    assertThat(pending("DECOMPOSED_INTO")).isZero();
  }

  @Test
  void eamBeforeScmServiceIsLinkedImmediatelyToRealKey() {
    paymentsCore(1, T1);
    assertThat(paymentsService(1, T2, "EAM-1042").status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(decomposed()).singleElement().satisfies(r -> assertThat(r.get("systemKey").asString()).isEqualTo("5101"));
    assertThat(pending("DECOMPOSED_INTO")).isZero();
    // Узла ITSystem для ключа EAM-1042 не создаётся.
    assertThat(query("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'IT_SYSTEM'}) RETURN r.sourceId AS id"))
        .extracting(r -> r.get("id").asString()).containsExactly("5101");
  }

  @Test
  void unknownSystemCodeStaysDeferredAndCreatesNoPhantomNode() {
    paymentsCore(1, T1);
    assertThat(paymentsService(1, T2, "EAM-UNKNOWN").status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(decomposed()).isEmpty();
    assertThat(pending("DECOMPOSED_INTO")).isEqualTo(1);
    assertThat(query("MATCH (i:ITSystem) RETURN count(i) AS c").get(0).get("c").asLong()).isEqualTo(1);
    assertThat(query("MATCH (n:Solution) RETURN count(n) AS c").get(0).get("c").asLong()).isZero();
  }

  @Test
  void changedReferenceClosesOldRelationAndOpensNewOne() {
    eam("PLATFORM", 3102, 1, T1, "name", "PostgreSQL Platform");
    eam("PLATFORM", 3103, 1, T1, "name", "Kafka Platform");
    eam("SOLUTION", 7201, 1, T1, "name", "Card Processing", "platform", List.of(3102L));
    eam("SOLUTION", 7201, 2, T2, "name", "Card Processing", "platform", List.of(3103L));

    assertThat(query("MATCH (:Solution)-[rel:USES_PLATFORM]->(p:Platform) "
        + "RETURN p.name AS platform, rel.validTo IS NULL AS open ORDER BY platform"))
        .extracting(r -> r.get("platform").asString() + ":" + r.get("open").asBoolean())
        .containsExactly("Kafka Platform:true", "PostgreSQL Platform:false");
  }

  @Test
  void repeatedVerIsNoOpAndOlderVerIsIgnored() {
    assertThat(eam("PLATFORM", 3101, 2, T2, "name", "Kubernetes Platform").status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(eam("PLATFORM", 3101, 2, T3, "name", "Kubernetes Platform").status()).isNotEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(eam("PLATFORM", 3101, 1, T1, "name", "Old Name").status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(query("MATCH (p:Platform) RETURN p.name AS name")).singleElement()
        .satisfies(r -> assertThat(r.get("name").asString()).isEqualTo("Kubernetes Platform"));
  }

  @Test
  void sameNameDifferentIdsAreTwoNodes() {
    eam("IT_SYSTEM", 5301, 1, T1, "name", "Payments Gateway", "eam_id", "EAM-3001");
    eam("IT_SYSTEM", 5302, 1, T1, "name", "Payments Gateway", "eam_id", "EAM-3002");
    assertThat(query("MATCH (i:ITSystem {name: 'Payments Gateway'}) RETURN count(i) AS c").get(0).get("c").asLong())
        .isEqualTo(2);
  }

  @Test
  void unknownCriticalityIsStoredRawAndDoesNotOverwriteKnownValue() {
    eam("IT_SYSTEM", 5101, 1, T1, "name", "Payments Core", "criticality", "HIGH");
    eam("IT_SYSTEM", 5101, 2, T2, "name", "Payments Core", "criticality", "SOMETHING-NEW");
    assertThat(query("MATCH (i:ITSystem) RETURN i.criticality AS c, i.criticalityRaw AS raw")).singleElement()
        .satisfies(r -> {
          assertThat(r.get("raw").asString()).isEqualTo("SOMETHING-NEW");
          assertThat(r.get("c").asString()).isEqualTo("HIGH");
        });
  }

  @Test
  void tombstonedPlatformDisappearsFromActiveRecords() {
    eam("PLATFORM", 3102, 1, T1, "name", "PostgreSQL Platform");
    var event = new CanonicalEvent("del-3102", "urn:corp:eam", "architecture.asset.deleted.v1", "platform/3102", T2,
        "urn:corp:schema:asset-deleted:1", null,
        new AssetEventData("PLATFORM", "3102", new SourceVersion("2"), Map.of()));
    journal.append(event, RAW, null);
    assertThat(processor.process(event, RAW).status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(query("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'PLATFORM', sourceId: '3102'}) RETURN r.active AS a")
        .get(0).get("a").asBoolean()).isFalse();
  }
}
