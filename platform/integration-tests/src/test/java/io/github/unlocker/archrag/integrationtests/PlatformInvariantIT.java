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
import java.sql.SQLException;
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
 * Инвариант платформы ИТ-системы EAM (H.7, ADR 0031) на реальных Neo4j и PostgreSQL: {@code itsystems.platform_id}
 * обязан совпадать с {@code platform_id} решения; расхождение открывает RELATION-конфликт при любом порядке прихода
 * системы и решения, граф строится по решению. Данные повторяют fixtures {@code platform-mismatch/} и {@code baseline/}.
 */
@Testcontainers
class PlatformInvariantIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");
  private static final Instant T3 = Instant.parse("2026-10-03T10:00:00Z");
  private static final Instant T4 = Instant.parse("2026-10-04T10:00:00Z");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PGSimpleDataSource dataSource;
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
    dataSource = ds;
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
            new PostgresSourceConflicts(ds, AuthorityMatrix.defaults()),
            projector,
            projector);
  }

  @BeforeEach
  void clearGraph() throws SQLException {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    try (var c = dataSource.getConnection(); var st = c.createStatement()) {
      st.executeUpdate("DELETE FROM source_conflict");
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static ProcessingResult handle(String type, String id, String version, Instant time, Map<String, Object> attrs) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", Long.parseLong(id));
    m.put("ver", Long.parseLong(version));
    m.put("datetime", time.toString());
    m.put("attrs", attrs);
    return send(type, id, version, time, m);
  }

  private static ProcessingResult send(String type, String id, String version, Instant time, Map<String, Object> payload) {
    var event =
        new CanonicalEvent(
            "e-" + UUID.randomUUID(), "urn:corp:eam", CanonicalEvent.TYPE_ASSET_UPSERTED, type + "/" + id, time,
            "urn:corp:schema:asset-upserted:1", null, new AssetEventData(type, id, new SourceVersion(version), payload));
    journal.append(event, RAW, null);
    return processor.process(event, RAW);
  }

  private static ProcessingResult platform(String id) {
    return handle("PLATFORM", id, "1", T1, Map.of("name", "Platform " + id));
  }

  private static ProcessingResult solution(String version, Instant time, long eamPlatformId) {
    return handle("SOLUTION", "7201", version, time, Map.of("name", "Card Processing", "platform_id", eamPlatformId));
  }

  private static ProcessingResult system(String id, String version, Instant time, long solutionId, long eamPlatformId) {
    return handle(
        "IT_SYSTEM", id, version, time,
        Map.of("name", "Cards Hub " + id, "eam_id", "EAM-" + id, "product_id", solutionId, "platform_id", eamPlatformId));
  }

  private static ProcessingResult deleteSystem(String id, String version, Instant time) {
    var event =
        new CanonicalEvent(
            "del-" + UUID.randomUUID(), "urn:corp:eam", "architecture.asset.deleted.v1", "IT_SYSTEM/" + id, time,
            "urn:corp:schema:asset-deleted:1", null, new AssetEventData("IT_SYSTEM", id, new SourceVersion(version), Map.of()));
    journal.append(event, RAW, null);
    return processor.process(event, RAW);
  }

  /** Строки {@code source_conflict} вида RELATION: {@code status|property|dissent_id|dissent_value|master_id|master_value}. */
  private static List<String> relationConflicts() throws SQLException {
    List<String> rows = new java.util.ArrayList<>();
    try (var c = dataSource.getConnection();
        var st = c.createStatement();
        var rs =
            st.executeQuery(
                "SELECT status, property, dissent_id, dissent_value, master_id, master_value FROM source_conflict"
                    + " WHERE kind = 'RELATION' ORDER BY opened_at, dissent_id")) {
      while (rs.next()) {
        rows.add(String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)));
      }
    }
    return rows;
  }

  private static List<Record> query(String cypher) {
    return driver.executableQuery(cypher).execute().records();
  }

  /** Поток fixture: платформы, затем решение и система с расходящимися платформами (3101 / 3102). */
  private static void loadPlatforms() {
    assertThat(platform("3101").status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(platform("3102").status()).isEqualTo(ProcessingStatus.PROJECTED);
  }

  private static final String MISMATCH = "OPEN|platform_id|5104|3102|7201|3101";

  // ---- tests -------------------------------------------------------------------------------

  @Test
  void solutionBeforeSystemOpensConflict() throws SQLException {
    loadPlatforms();
    assertThat(solution("1", T1, 3101).status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(system("5104", "1", T2, 7201, 3102).status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(relationConflicts()).containsExactly(MISMATCH);
  }

  @Test
  void systemBeforeSolutionOpensConflictWhenSolutionArrives() throws SQLException {
    loadPlatforms();
    assertThat(system("5104", "1", T1, 7201, 3102).status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(relationConflicts()).isEmpty();

    assertThat(solution("1", T2, 3101).status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(relationConflicts()).containsExactly(MISMATCH);
  }

  @Test
  void newSystemVersionWithCorrectedPlatformResolvesConflict() throws SQLException {
    loadPlatforms();
    solution("1", T1, 3101);
    system("5104", "1", T2, 7201, 3102);

    system("5104", "2", T3, 7201, 3101);

    assertThat(relationConflicts()).containsExactly("RESOLVED|platform_id|5104|3102|7201|3101");
  }

  @Test
  void newSolutionVersionWithSystemPlatformResolvesConflict() throws SQLException {
    loadPlatforms();
    solution("1", T1, 3101);
    system("5104", "1", T2, 7201, 3102);

    solution("2", T3, 3102);

    assertThat(relationConflicts()).containsExactly("RESOLVED|platform_id|5104|3102|7201|3101");
  }

  @Test
  void systemTombstoneResolvesConflict() throws SQLException {
    loadPlatforms();
    solution("1", T1, 3101);
    system("5104", "1", T2, 7201, 3102);

    assertThat(deleteSystem("5104", "2", T3).status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(relationConflicts()).singleElement().satisfies(row -> assertThat(row).startsWith("RESOLVED|platform_id|"));
  }

  @Test
  void graphIsBuiltBySolutionAndHasNoDirectSystemToPlatformEdge() throws SQLException {
    loadPlatforms();
    solution("1", T1, 3101);
    system("5104", "1", T2, 7201, 3102);

    assertThat(query("MATCH (:ITSystem)-[:USES_SOLUTION]->(:Solution)-[:USES_PLATFORM]->(p:Platform) RETURN p.name AS name"))
        .singleElement()
        .satisfies(r -> assertThat(r.get("name").asString()).isEqualTo("Platform 3101"));
    assertThat(query("MATCH (:ITSystem)-[r]->(:Platform) RETURN r")).isEmpty();
    assertThat(query("MATCH (s:ITSystem) RETURN s.eamPlatformId AS p").get(0).get("p").asLong()).isEqualTo(3102L);
    assertThat(query("MATCH (s:Solution) RETURN s.eamPlatformId AS p").get(0).get("p").asLong()).isEqualTo(3101L);
  }

  @Test
  void baselineHasNoPlatformConflicts() throws SQLException {
    loadPlatforms();
    handle("SOLUTION", "7201", "1", T1, Map.of("name", "Payments", "platform_id", 3101L));
    handle("SOLUTION", "7202", "1", T1, Map.of("name", "Ledger", "platform_id", 3102L));
    system("5101", "1", T2, 7201, 3101);
    system("5102", "1", T2, 7202, 3102);

    assertThat(relationConflicts()).isEmpty();
  }
}
