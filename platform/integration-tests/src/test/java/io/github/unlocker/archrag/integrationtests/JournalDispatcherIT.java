package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adaptercore.AdapterConfig;
import io.github.unlocker.archrag.adaptercore.EventMapper;
import io.github.unlocker.archrag.adaptercore.PollResult;
import io.github.unlocker.archrag.adaptercore.Poller;
import io.github.unlocker.archrag.adaptercore.RetryPolicy;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Диспетчер журнала на реальных PostgreSQL, Neo4j и S3 (batch-size 1, чтобы keyset-переход между страницами
 * выполнялся на каждом событии): события, добавленные в журнал, попадают в граф без ручного вызова; битое событие
 * уходит в карантин и в DLQ, следующие за ним проецируются; маркер snapshot-complete запускает reconciliation, а при
 * неполном журнале ничего не удаляется.
 */
@Testcontainers
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(JournalDispatcherIT.TestJwt.class)
class JournalDispatcherIT {

  private static final String SOURCE = "urn:corp:eam";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

  /** Подписанные тестовым ключом токены вместо внешнего IdP. */
  @TestConfiguration
  static class TestJwt {
    @Bean
    JwtDecoder jwtDecoder() {
      return NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("archrag.neo4j.uri", NEO4J::getBoltUrl);
    r.add("archrag.neo4j.username", () -> "neo4j");
    r.add("archrag.neo4j.password", NEO4J::getAdminPassword);
    r.add("archrag.s3.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(8333));
    r.add("archrag.s3.access-key", () -> ContainersSmokeIT.S3_ACCESS_KEY);
    r.add("archrag.s3.secret-key", () -> ContainersSmokeIT.S3_SECRET_KEY);
    r.add("archrag.s3.bucket", () -> "dispatcher-it-bucket");
    // Декодер токенов тестовый (TestJwt); свойство нужно только чтобы разрешился плейсхолдер application.yml.
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://unused.invalid");
    r.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "http://unused.invalid");
    r.add("archrag.dispatcher.poll-interval", () -> "100ms");
    r.add("archrag.dispatcher.batch-size", () -> "1");
    r.add("archrag.dispatcher.retry-delay", () -> "1s");
  }

  @Autowired Driver driver;
  @Autowired EventJournal journal;
  @Autowired RawPayloadStore rawStore;

  private int runs;

  /** Курсор poller-а хранится по источнику, а все тесты работают с одним: каждому нужен свежий Poller с нуля. */
  @BeforeEach
  void resetCheckpoints() throws SQLException {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var st = c.createStatement()) {
      st.execute("TRUNCATE consumer_checkpoint");
    }
  }

  @Test
  void eventsAppendedToJournalReachGraphWithoutManualCall() {
    StubSource eam = new StubSource(SourceSystem.EAM, Clock.systemUTC());
    String a = "a-" + uid();
    String b = "b-" + uid();
    String c = "c-" + uid();
    eam.upsert("TEAM", a, team("A"));
    eam.upsert("TEAM", b, team("B"));
    eam.upsert("TEAM", c, team("C"));

    poller(eam).pollOnce();

    awaitTrue(() -> Boolean.TRUE.equals(active(a)) && Boolean.TRUE.equals(active(b)) && Boolean.TRUE.equals(active(c)));
  }

  @Test
  void brokenEventIsQuarantinedWithDlqAndFollowingEventsAreProjected() {
    // Событие с битым raw приходит раньше всех остальных.
    RawPayloadRef broken = rawStore.put(SOURCE, "not json SECRET".getBytes(StandardCharsets.UTF_8));
    String badId = "bad-" + uid();
    journal.append(event(badId, "TEAM", "BAD-" + uid(), CanonicalEvent.TYPE_ASSET_UPSERTED, Map.of()),
        broken, null);
    StubSource eam = new StubSource(SourceSystem.EAM, Clock.systemUTC());
    String after1 = "after1-" + uid();
    String after2 = "after2-" + uid();
    eam.upsert("TEAM", after1, team("One"));
    eam.upsert("TEAM", after2, team("Two"));

    poller(eam).pollOnce();

    awaitTrue(() -> status(badId) == ProcessingStatus.QUARANTINED);
    JournalEntry quarantined = journal.find(SOURCE, badId).orElseThrow();
    assertThat(quarantined.errorCode()).isEqualTo("INVALID_RAW_PAYLOAD");
    assertThat(quarantined.errorReason()).doesNotContain("SECRET");
    assertThat(openDlqRows(badId)).isEqualTo(1);
    awaitTrue(() -> Boolean.TRUE.equals(active(after1)) && Boolean.TRUE.equals(active(after2)));
  }

  @Test
  void unsupportedTypeIsQuarantinedWithCode() {
    RawPayloadRef raw = rawStore.put(SOURCE,
        "{\"operation\":\"UPSERT\",\"completeness\":\"COMPLETE\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"payload\":{}}"
            .getBytes(StandardCharsets.UTF_8));
    String id = "unknown-" + uid();
    journal.append(event(id, "TEAM", "X-" + uid(), "architecture.unknown.v1", Map.of()), raw, null);

    awaitTrue(() -> status(id) == ProcessingStatus.QUARANTINED);
    assertThat(journal.find(SOURCE, id).orElseThrow().errorCode()).isEqualTo("UNSUPPORTED_EVENT_TYPE");
  }

  @Test
  void snapshotMarkerTombstonesObjectMissingFromRun() {
    StubSource eam = new StubSource(SourceSystem.EAM, Clock.systemUTC());
    String keep = "keep-" + uid();
    String gone = "gone-" + uid();
    eam.upsert("TEAM", keep, team("Keep"));
    eam.upsert("TEAM", gone, team("Gone"));
    Poller poller = poller(eam);
    poller.pollOnce();
    awaitTrue(() -> Boolean.TRUE.equals(active(keep)) && Boolean.TRUE.equals(active(gone)));

    // Удаление без webhook: узнать о нём можно только из полного snapshot.
    eam.suppressWebhooks(true);
    eam.delete("TEAM", gone);
    PollResult run = poller.snapshotOnce();

    assertThat(run.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    awaitTrue(() -> Boolean.FALSE.equals(active(gone)));
    assertThat(active(keep)).isTrue();
    awaitTrue(() -> status("snapshot-complete:" + run.syncRunId()) == ProcessingStatus.PROJECTED);
  }

  @Test
  void markerClaimingMoreObjectsThanJournalHoldsDeletesNothing() throws Exception {
    StubSource eam = new StubSource(SourceSystem.EAM, Clock.systemUTC());
    String victim = "victim-" + uid();
    eam.upsert("TEAM", victim, team("Victim"));
    poller(eam).pollOnce();
    awaitTrue(() -> Boolean.TRUE.equals(active(victim)));

    // Адаптер записал 5 событий прогона, в журнале их 0: прогон неполон.
    String runId = "incomplete-" + uid();
    CanonicalEvent marker = EventMapper.snapshotComplete(SourceSystem.EAM, runId, Instant.now(), 5L);
    RawPayloadRef raw = rawStore.put(SOURCE, ("{\"syncRunId\":\"" + runId + "\",\"objectCount\":5,\"updatedAt\":\""
        + Instant.now() + "\"}").getBytes(StandardCharsets.UTF_8));
    journal.append(marker, raw, runId);

    awaitTrue(() -> status(marker.id()) == ProcessingStatus.RETRYING);
    Thread.sleep(1500);
    assertThat(journal.find(SOURCE, marker.id()).orElseThrow().errorCode()).isEqualTo("RECONCILIATION_TRIGGER_FAILED");
    assertThat(active(victim)).isTrue();
  }

  @Test
  void markerWithoutRawGoesToDlq() {
    String runId = "noraw-" + uid();
    CanonicalEvent marker = EventMapper.snapshotComplete(SourceSystem.EAM, runId, Instant.now(), 0L);
    journal.append(marker, null, runId);

    awaitTrue(() -> status(marker.id()) == ProcessingStatus.QUARANTINED);
    assertThat(journal.find(SOURCE, marker.id()).orElseThrow().errorCode()).isEqualTo("MARKER_NO_RAW");
    assertThat(openDlqRows(marker.id())).isEqualTo(1);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private Poller poller(StubSource eam) {
    var config = new AdapterConfig(SourceSystem.EAM, URI.create("http://unused"), "s", Duration.ofMinutes(5), 2,
        Duration.ofSeconds(1), Duration.ofHours(1), new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(2)));
    String prefix = uid();
    return new Poller(config, eam, journal, rawStore, Clock.systemUTC(), d -> {}, new Random(1),
        () -> prefix + "-run-" + (++runs));
  }

  private static CanonicalEvent event(String id, String sourceType, String sourceId, String type, Map<String, Object> payload) {
    return new CanonicalEvent(id, SOURCE, type, "x/" + sourceId, Instant.now(), "urn:corp:schema:asset-upserted:1",
        "corr", new AssetEventData(sourceType, sourceId, new SourceVersion("1"), payload));
  }

  private static Map<String, Object> team(String name) {
    return Map.of("name", name);
  }

  private static String uid() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private ProcessingStatus status(String eventId) {
    return journal.find(SOURCE, eventId).map(JournalEntry::status).orElse(null);
  }

  private Boolean active(String id) {
    var rows = driver.executableQuery(
            "MATCH (r:SourceRecord {source: 'EAM', sourceType: 'TEAM', sourceId: $id}) RETURN r.active AS a")
        .withParameters(Map.of("id", id)).execute().records();
    return rows.isEmpty() ? null : rows.getFirst().get("a").asBoolean();
  }

  private static int openDlqRows(String eventId) {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement(
            "SELECT count(*) FROM dlq_entry WHERE source = ? AND event_id = ? AND replayed_at IS NULL")) {
      ps.setString(1, SOURCE);
      ps.setString(2, eventId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Опрос с таймаутом: Awaitility в проект не входит, новую зависимость в задаче не вводим. */
  private static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within 30s");
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
  }
}
