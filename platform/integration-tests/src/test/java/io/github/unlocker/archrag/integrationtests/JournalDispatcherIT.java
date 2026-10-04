package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.assetadapter.AssetAdapter;
import io.github.unlocker.archrag.deploymapadapter.DeploymapAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalKey;
import io.github.unlocker.archrag.eventschemas.JournalQuery;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.scmadapter.ScmAdapter;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Диспетчер журнала на реальных PostgreSQL, Neo4j и S3: событие, добавленное в журнал, появляется в графе без ручного
 * вызова {@code EventProcessor}; битое событие уходит в карантин и не блокирует следующие.
 */
@Testcontainers
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(JournalDispatcherIT.TestJwt.class)
class JournalDispatcherIT {

  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

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
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://unused.invalid");
    r.add("archrag.dispatcher.pause", () -> "100ms");
  }

  @Autowired Driver driver;
  @Autowired EventJournal journal;
  @Autowired RawPayloadStore rawStore;

  @Test
  void eventAppendedToJournalReachesGraphWithoutManualCall() throws Exception {
    var eam = StubSources.eam(Clock.systemUTC());
    try (var server = new StubSourceServer(eam);
        SourceAdapter adapter = EamAdapter.create(EamAdapter.config(server.baseUri(), "s"), journal, rawStore)) {
      adapter.poller().pollOnce();
    }

    awaitTrue(() -> recordActive("IT_SYSTEM", "EAM-1042"));
    assertThat(journal.find("urn:corp:eam", firstEventId("EAM-1042")).orElseThrow().status())
        .isEqualTo(ProcessingStatus.PROJECTED);
  }

  @Test
  void failedEventDoesNotBlockFollowingOnes() throws Exception {
    // Событие с битым raw приходит раньше всех остальных.
    RawPayloadRef broken = rawStore.put("urn:corp:scm", "not json".getBytes());
    CanonicalEvent bad = new CanonicalEvent(
        "bad-1", "urn:corp:scm", CanonicalEvent.TYPE_ASSET_UPSERTED, "service/BAD", Instant.now(),
        "urn:corp:schema:asset-upserted:1", "corr-bad",
        new AssetEventData("SERVICE", "BAD", new SourceVersion("1"), Map.of()));
    journal.append(bad, broken, null);

    var scm = StubSources.scm(Clock.systemUTC());
    try (var server = new StubSourceServer(scm);
        SourceAdapter adapter = ScmAdapter.create(ScmAdapter.config(server.baseUri(), "s"), journal, rawStore)) {
      adapter.poller().pollOnce();
    }

    awaitTrue(() -> journal.find("urn:corp:scm", "bad-1").orElseThrow().status() == ProcessingStatus.QUARANTINED);
    var quarantined = journal.find("urn:corp:scm", "bad-1").orElseThrow();
    assertThat(quarantined.errorCode()).isEqualTo("RAW_INVALID");
    awaitTrue(() -> countProjected("urn:corp:scm") > 0);
    assertThat(quarantined.errorReason()).doesNotContain("not json");
  }

  @Test
  void unsupportedTypeIsQuarantinedWithCode() throws Exception {
    RawPayloadRef raw = rawStore.put("urn:corp:eam", "{\"operation\":\"UPSERT\",\"completeness\":\"FULL\",\"updatedAt\":\"2026-01-01T00:00:00Z\",\"payload\":{}}".getBytes());
    CanonicalEvent unknownType = new CanonicalEvent(
        "unknown-type-1", "urn:corp:eam", "architecture.unknown.v1", "x/1", Instant.now(),
        "urn:corp:schema:asset-upserted:1", "corr-x",
        new AssetEventData("IT_SYSTEM", "X-1", new SourceVersion("1"), Map.of()));
    journal.append(unknownType, raw, null);
    awaitTrue(() -> journal.find("urn:corp:eam", "unknown-type-1").orElseThrow().status() == ProcessingStatus.QUARANTINED);
    assertThat(journal.find("urn:corp:eam", "unknown-type-1").orElseThrow().errorCode())
        .isEqualTo("UNSUPPORTED_EVENT_TYPE");
  }

  // ---- helpers -----------------------------------------------------------------------------

  private String firstEventId(String sourceId) {
    try (var c = java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement(
            "SELECT event_id FROM inbox_event WHERE source_id = ? ORDER BY received_at LIMIT 1")) {
      ps.setString(1, sourceId);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean recordActive(String type, String id) {
    return !driver.executableQuery(
            "MATCH (r:SourceRecord {sourceType: $t, sourceId: $id}) WHERE r.active RETURN r")
        .withParameters(Map.of("t", type, "id", id)).execute().records().isEmpty();
  }

  private long countProjected(String source) {
    try (var c = java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement("SELECT count(*) FROM inbox_event WHERE source = ? AND status = 'PROJECTED'")) {
      ps.setString(1, source);
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within 30s");
      }
      Thread.sleep(100);
    }
  }
}
