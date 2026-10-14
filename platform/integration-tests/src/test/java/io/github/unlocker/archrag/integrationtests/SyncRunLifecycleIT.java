package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.provenance.SyncRunStatus;
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
import io.github.unlocker.archrag.graphprojector.ReconciliationTrigger;
import io.github.unlocker.archrag.graphprojector.SyncRunCloser;
import io.github.unlocker.archrag.graphprojector.SyncRunCounts;
import io.github.unlocker.archrag.graphprojector.SyncRunWriter;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.identityresolution.PostgresSourceConflicts;
import io.github.unlocker.archrag.ingestionservice.AdminProperties;
import io.github.unlocker.archrag.ingestionservice.HousekeepingJob;
import io.github.unlocker.archrag.ingestionservice.SyncRunProperties;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Жизненный цикл {@code SyncRun} (UNLOCKER-280) на реальных PostgreSQL и Neo4j: закрытие по маркеру
 * {@code snapshot-complete}, счётчики, финальность {@code SUCCEEDED}, зависшие прогоны и retention.
 */
@Testcontainers
class SyncRunLifecycleIT {

  private static final String SOURCE = "urn:corp:eam";
  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");

  @Container static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PGSimpleDataSource dataSource;
  static JdbcTemplate jdbc;
  static PostgresEventJournal journal;
  static SyncRunWriter writer;
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
    jdbc = new JdbcTemplate(ds);
    journal = new PostgresEventJournal(ds);
    writer = new SyncRunWriter(driver);
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor =
        new EventProcessor(
            journal,
            Normalizer.standard(projector::isActive, projector),
            new PostgresIdentityMapping(ds),
            projector,
            ReconciliationTrigger.NOOP,
            new PostgresIdentityCandidates(ds),
            new PostgresSourceConflicts(ds, AuthorityMatrix.defaults()),
            projector,
            projector,
            (source, warnings) -> {},
            new SyncRunCloser(journal, writer, Clock.systemUTC()));
  }

  @BeforeEach
  void clean() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    jdbc.update("TRUNCATE dlq_entry, inbox_event, consumer_checkpoint");
    jdbc.update("DELETE FROM admin_audit");
  }

  // ---- закрытие по маркеру -----------------------------------------------------------------

  @Test
  void regularSnapshotClosesRunAsSucceededWithCounters() {
    String run = newRun();
    send(run, "p1", "1");
    send(run, "p2", "1");

    var result = marker(run, 2L);

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    var node = syncRun(run);
    assertThat(node.get("status")).isEqualTo("SUCCEEDED");
    assertThat(node.get("adapter")).isEqualTo("EAM");
    assertThat(node.get("endedAt")).isNotNull();
    assertThat(node).containsEntry("fetched", 2L).containsEntry("applied", 2L).containsEntry("failed", 0L);
  }

  @Test
  void replayingMarkerDoesNotChangeCounters() {
    String run = newRun();
    send(run, "p1", "1");
    marker(run, 1L);
    var before = syncRun(run);

    // Повторное закрытие того же прогона (replay маркера): SUCCEEDED финален, значения не накапливаются.
    writer.close(
        io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode.EAM, run, SyncRunStatus.SUCCEEDED,
        Instant.now().plusSeconds(60), new SyncRunCounts(99, 99, 99));
    new SyncRunCloser(journal, writer, Clock.systemUTC())
        .close(SOURCE, run, "snapshot-complete:" + run, SyncRunStatus.SUCCEEDED, java.util.OptionalLong.of(1));

    assertThat(syncRun(run)).isEqualTo(before);
  }

  @Test
  void succeededRunIsNotOverwrittenByFailed() {
    String run = newRun();
    send(run, "p1", "1");
    marker(run, 1L);

    writer.close(
        io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode.EAM, run, SyncRunStatus.FAILED,
        Instant.now(), new SyncRunCounts(0, 0, 1));

    assertThat(syncRun(run)).containsEntry("status", "SUCCEEDED").containsEntry("failed", 0L);
  }

  @Test
  void markerWithoutObjectCountClosesRunAsFailedAndFailedCanBecomeSucceeded() {
    String run = newRun();
    send(run, "p1", "1");

    var result = marker(run, null);

    assertThat(result.status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(syncRun(run)).containsEntry("status", "FAILED").containsEntry("fetched", 0L).containsEntry("applied", 1L);
    assertThat(syncRun(run).get("endedAt")).isNotNull();

    // Выход из карантина: тот же прогон закрывается как SUCCEEDED.
    new SyncRunCloser(journal, writer, Clock.systemUTC())
        .close(SOURCE, run, "snapshot-complete:" + run, SyncRunStatus.SUCCEEDED, java.util.OptionalLong.of(1));
    assertThat(syncRun(run)).containsEntry("status", "SUCCEEDED").containsEntry("fetched", 1L);
  }

  @Test
  void quarantinedEventsAreCountedAsFailed() {
    String run = newRun();
    send(run, "p1", "1");
    // Версия не число: нормализация отвергает событие.
    var bad = event(run + "-bad", "PLATFORM", "bad", "1", Map.of("id", "x"));
    journal.append(bad, RAW, run);
    var badResult = processor.process(bad, RAW);
    assertThat(badResult.status()).isEqualTo(ProcessingStatus.QUARANTINED);

    marker(run, 2L);

    assertThat(syncRun(run)).containsEntry("applied", 1L).containsEntry("failed", 1L).containsEntry("fetched", 2L);
  }

  // ---- housekeeping ------------------------------------------------------------------------

  @Test
  void staleRunningRunBecomesFailed() {
    String stale = newRun();
    String fresh = newRun();
    send(stale, "p1", "1");
    send(fresh, "p2", "1");
    // startedAt прогона берётся из времени событий (2026-10-01), поэтому возраст задаётся явно.
    driver.executableQuery("MATCH (s:SyncRun {runId: $id}) SET s.startedAt = datetime() - duration('PT7H')")
        .withParameters(Map.of("id", stale))
        .execute();
    driver.executableQuery("MATCH (s:SyncRun {runId: $id}) SET s.startedAt = datetime() - duration('PT1H')")
        .withParameters(Map.of("id", fresh))
        .execute();

    job(20, Duration.ofDays(90)).run();

    assertThat(syncRun(stale)).containsEntry("status", "FAILED").containsEntry("fetched", 1L)
        .containsEntry("applied", 1L).containsEntry("failed", 0L);
    assertThat(syncRun(stale).get("endedAt")).isNotNull();
    assertThat(syncRun(fresh)).containsEntry("status", "RUNNING");
  }

  @Test
  void staleRunContinuedByLateMarkerEndsSucceeded() {
    String run = newRun();
    send(run, "p1", "1");
    driver.executableQuery("MATCH (s:SyncRun {runId: $id}) SET s.startedAt = datetime() - duration('PT7H')")
        .withParameters(Map.of("id", run))
        .execute();
    job(20, Duration.ofDays(90)).run();
    assertThat(syncRun(run)).containsEntry("status", "FAILED");

    marker(run, 1L);

    assertThat(syncRun(run)).containsEntry("status", "SUCCEEDED");
  }

  @Test
  void retentionKeepsLatestTerminalRunsPerAdapterAndNeverDeletesRunning() {
    for (int i = 1; i <= 4; i++) {
      driver.executableQuery(
              "CREATE (s:SyncRun {runId: $id, adapter: 'EAM', status: $st, startedAt: datetime($t), endedAt: datetime($t)}) "
                  + "CREATE (s)-[:PROCESSED]->(:SourceRecord {source: 'EAM', sourceType: 'T', sourceId: $id})")
          .withParameters(Map.of("id", "eam-" + i, "st", i % 2 == 0 ? "FAILED" : "SUCCEEDED", "t", "2026-01-0" + i + "T00:00:00Z"))
          .execute();
    }
    driver.executableQuery("CREATE (:SyncRun {runId: 'scm-1', adapter: 'SCM', status: 'SUCCEEDED', startedAt: datetime('2026-01-01T00:00:00Z'), endedAt: datetime('2026-01-01T00:00:00Z')})").execute();
    driver.executableQuery("CREATE (:SyncRun {runId: 'eam-running', adapter: 'EAM', status: 'RUNNING', startedAt: datetime()})").execute();

    job(2, Duration.ofDays(90)).run();

    var runs = driver.executableQuery("MATCH (s:SyncRun) RETURN s.runId AS id ORDER BY id").execute().records();
    assertThat(runs.stream().map(r -> r.get("id").asString()).toList())
        .containsExactly("eam-3", "eam-4", "eam-running", "scm-1");
    long edges = driver.executableQuery("MATCH (:SyncRun)-[p:PROCESSED]->() RETURN count(p) AS n").execute()
        .records().get(0).get("n").asLong();
    assertThat(edges).isEqualTo(2);
  }

  @Test
  void retentionDeletesBeyondOneBatch() {
    driver.executableQuery(
            "UNWIND range(1, 25) AS i CREATE (:SyncRun {runId: 'r' + i, adapter: 'EAM', status: 'SUCCEEDED', "
                + "startedAt: datetime() - duration({days: i}), endedAt: datetime() - duration({days: i})})")
        .execute();

    job(3, Duration.ofDays(90)).run();

    long left = driver.executableQuery("MATCH (s:SyncRun) RETURN count(s) AS n").execute().records().get(0).get("n").asLong();
    assertThat(left).isEqualTo(3);
  }

  @Test
  void auditOlderThanRetentionIsDeletedAndAbandonedStartedBecomesFailed() throws SQLException {
    audit("old-ok", "SUCCEEDED", "now() - interval '100 days'");
    audit("recent-ok", "SUCCEEDED", "now() - interval '1 day'");
    audit("abandoned", "STARTED", "now() - interval '8 hours'");
    audit("running", "STARTED", "now() - interval '1 minute'");

    job(20, Duration.ofDays(90)).run();

    Map<String, String> byOp = new LinkedHashMap<>();
    jdbc.query("SELECT operation, status, error FROM admin_audit ORDER BY id", rs -> {
      byOp.put(rs.getString(1), rs.getString(2) + "/" + rs.getString(3));
    });
    assertThat(byOp)
        .containsOnlyKeys("recent-ok", "abandoned", "running")
        .containsEntry("abandoned", "FAILED/abandoned")
        .containsEntry("running", "STARTED/null");
  }

  @Test
  void abandonedStartedOlderThanRetentionIsDeletedToo() {
    audit("ancient", "STARTED", "now() - interval '200 days'");

    job(20, Duration.ofDays(90)).run();

    assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit", Long.class)).isZero();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static HousekeepingJob job(int keep, Duration auditRetention) {
    return new HousekeepingJob(
        driver, journal, jdbc, new SyncRunProperties(Duration.ofHours(6), keep, Duration.ofHours(1), true),
        new AdminProperties(List.of("c"), auditRetention), Clock.systemUTC());
  }

  private static void audit(String operation, String status, String startedAtSql) {
    jdbc.update("INSERT INTO admin_audit (operation, actor, request, status, started_at) VALUES (?, 'a', '{}'::jsonb, ?, "
        + startedAtSql + ")", operation, status);
  }

  private static String newRun() {
    return "run-" + UUID.randomUUID().toString().substring(0, 8);
  }

  private static CanonicalEvent event(String id, String type, String objectId, String version, Map<String, Object> payload) {
    return new CanonicalEvent(
        id, SOURCE, CanonicalEvent.TYPE_ASSET_UPSERTED, type + "/" + objectId, T1, "urn:corp:schema:asset-upserted:1",
        null, new AssetEventData(type, objectId, new SourceVersion(version), payload));
  }

  /** Событие snapshot-прогона: платформа с данными в формате источника EAM. */
  private static ProcessingResult send(String run, String objectId, String version) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", Math.abs((long) objectId.hashCode()));
    m.put("ver", Long.parseLong(version));
    m.put("datetime", T1.toString());
    m.put("attrs", Map.of("name", "Platform " + objectId));
    var e = event("snap:" + run + ":" + objectId, "PLATFORM", String.valueOf(m.get("id")), version, m);
    journal.append(e, RAW, run);
    return processor.process(e, RAW);
  }

  private static ProcessingResult marker(String run, Long objectCount) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("syncRunId", run);
    if (objectCount != null) {
      payload.put("objectCount", objectCount);
    }
    var e = new CanonicalEvent(
        "snapshot-complete:" + run, SOURCE, EventProcessorTypes.SNAPSHOT_COMPLETE, "sync-run/" + run, T1,
        "urn:corp:schema:snapshot-complete:1", null, new AssetEventData("SYNC_RUN", run, new SourceVersion("1"), payload));
    journal.append(e, RAW, run);
    return processor.process(e, RAW);
  }

  private static Map<String, Object> syncRun(String run) {
    var records =
        driver.executableQuery("MATCH (s:SyncRun {runId: $id}) RETURN s").withParameters(Map.of("id", run)).execute().records();
    assertThat(records).hasSize(1);
    return records.get(0).get("s").asNode().asMap();
  }

  /** Тип события-маркера (константа в пакете проектора закрыта). */
  private static final class EventProcessorTypes {
    static final String SNAPSHOT_COMPLETE = "architecture.sync.snapshot-complete.v1";
  }
}
