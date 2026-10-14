package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.unlocker.archrag.adaptercore.PollResult;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.scmadapter.ScmAdapter;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.neo4j.driver.Driver;
import org.postgresql.ds.PGSimpleDataSource;
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
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Миграция живого стенда с ключом {@code ITSystem} {@code EAM-1042} на {@code id} {@code 5101} (UNLOCKER-255): журнал
 * содержит события старого адаптера ({@code IT_SYSTEM EAM-1042/EAM-2001} и SCM-сервисы с {@code systemCode}); запускается
 * новый адаптер (checkpoint старого формата), затем reconcile и {@code POST /admin/rebuild}.
 *
 * <p>Проверяется: нет активных {@code ITSystem} со старыми ключами и дублей, сервис SCM связан с системой {@code 5101}
 * через {@code eamCode}, provenance системы отдаёт запись EAM, источники конфликтов не растут, а кандидаты идентичности
 * бывают только между старым ключом и его преемником.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EamKeyMigrationIT.TestJwt.class)
class EamKeyMigrationIT {

  private static final String SECRET = "migration-webhook-secret";
  private static final String BUCKET = "migration-it-bucket";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");

  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

  // При PER_CLASS Spring-контекст создаётся раньше расширения Testcontainers, поэтому контейнеры стартуют здесь.
  static {
    NEO4J.start();
    POSTGRES.start();
    S3.start();
  }

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
    r.add("archrag.s3.bucket", () -> BUCKET);
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://unused.invalid");
    r.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "http://unused.invalid");
    // Адреса control-эндпоинтов нужны только для разрешения плейсхолдеров; reconcile идёт через poller напрямую.
    for (String s : List.of("eam", "scm", "cmdb", "deploymap")) {
      r.add("archrag.adapters." + s + ".control-url", () -> "http://127.0.0.1:1/control/snapshot");
    }
    r.add("archrag.dispatcher.retry-delay", () -> "1s");
  }

  @Value("${local.server.port}") int port;
  @Autowired Driver driver;

  private final HttpClient http = HttpClient.newHttpClient();
  private final List<StubSourceServer> servers = new ArrayList<>();
  private final List<SourceAdapter> adapters = new ArrayList<>();
  private PostgresEventJournal journal;
  private S3RawPayloadStore store;
  private EamApiSupport eamApi;
  private StubSourceServer legacyEam;
  private SourceAdapter scmAdapter;

  @BeforeAll
  void prepare() {
    Clock clock = Clock.systemUTC();
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    journal = new PostgresEventJournal(ds);
    store = S3RawPayloadStore.create(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333)),
        ContainersSmokeIT.S3_ACCESS_KEY, ContainersSmokeIT.S3_SECRET_KEY, BUCKET);
    store.ensureBucket();
    eamApi = new EamApiSupport(clock);
    legacyEam = new StubSourceServer(StubSources.eam(clock));
    var scm = new StubSourceServer(StubSources.scm(clock));
    servers.add(legacyEam);
    servers.add(scm);
    scmAdapter = ScmAdapter.create(ScmAdapter.config(scm.baseUri(), SECRET), journal, store);
    adapters.add(scmAdapter);
  }

  @AfterAll
  void stopAll() {
    adapters.forEach(SourceAdapter::close);
    servers.forEach(StubSourceServer::close);
    if (eamApi != null) {
      eamApi.close();
    }
    if (store != null) {
      store.close();
    }
  }

  // ---- сценарии ----------------------------------------------------------------------------

  /** Стенд до перехода: старый адаптер EAM (ключи {@code EAM-…}) и SCM. */
  @Test
  @Order(1)
  void m1_legacyStandHasItSystemsWithOldKeys() throws SQLException {
    SourceAdapter legacy = EamAdapter.create(EamAdapter.config(legacyEam.baseUri(), SECRET), journal, store);
    adapters.add(legacy);
    assertThat(legacy.poller().pollOnce().outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    assertThat(scmAdapter.poller().pollOnce().outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    awaitJournalDrained();

    assertThat(activeItSystemKeys()).containsExactlyInAnyOrder("EAM-1042", "EAM-2001");
    assertThat(scalarLong("select count(*) from inbox_event where status = 'QUARANTINED'")).isZero();
  }

  /** Новый адаптер подхватывает checkpoint старого формата и читает eam-api без зависания. */
  @Test
  @Order(2)
  void m2_newAdapterReadsEamApiDespiteOldCheckpoint() throws SQLException {
    SourceAdapter composite = eamApi.adapter(legacyEam.baseUri(), SECRET, journal, store);
    adapters.add(composite);

    var result = composite.poller().pollOnce();
    awaitJournalDrained();

    assertThat(result.outcome()).isEqualTo(PollResult.Outcome.ADVANCED);
    // Старые узлы ещё живы до reconcile; новые уже построены, связь SCM достроена через eamCode.
    assertThat(activeItSystemKeys()).contains("5101", "5102");
    assertThat(count("MATCH (:SourceRecord {source: 'EAM', sourceType: 'IT_SYSTEM', sourceId: '5101'})-[:ASSERTS]->(:ITSystem)"
        + "-[:DECOMPOSED_INTO]->(:Service {name: 'payments-api'}) RETURN count(*) AS c")).isEqualTo(1);
    assertThat(scalarLong("select count(*) from inbox_event where status = 'QUARANTINED'")).isZero();
  }

  /** Первый полный snapshot нового адаптера tombstone-ит старые ключи, а TEAM и зависимости остаются. */
  @Test
  @Order(3)
  void m3_reconcileTombstonesOldKeysAndKeepsTeamAndDependencies() throws SQLException {
    SourceAdapter composite = adapters.getLast();

    assertThat(composite.poller().snapshotOnce().outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    awaitTrue(() -> activeItSystemKeys().equals(List.of("5101", "5102")));
    awaitJournalDrained();

    assertThat(count("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'TEAM', sourceId: 'TEAM-PAY'}) WHERE r.active = true "
        + "RETURN count(r) AS c")).isEqualTo(1);
    assertThat(count("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'SERVICE_DEPENDENCY'}) WHERE r.active = true "
        + "RETURN count(r) AS c")).isEqualTo(1);
    assertThat(count("MATCH (:ITSystem {name: 'Payments Core'})-[rel:OWNED_BY]->(:Team) WHERE rel.validTo IS NULL "
        + "RETURN count(rel) AS c")).as("OWNED_BY сохранилась через ownerTeam").isEqualTo(1);
  }

  /** После rebuild старые ключи не оживают, дублей нет, связи и provenance на новом ключе. */
  @Test
  @Order(4)
  void m4_rebuildHasNoItSystemWithOldKeysAndNoDuplicates() throws Exception {
    HttpResponse<String> response = post("/admin/rebuild?confirm=true", token());
    assertThat(response.statusCode()).isEqualTo(200);
    awaitJournalDrained();

    assertThat(activeItSystemKeys()).containsExactly("5101", "5102");
    assertThat(count("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'IT_SYSTEM'}) WHERE r.sourceId STARTS WITH 'EAM-' "
        + "AND r.active = true RETURN count(r) AS c")).isZero();
    assertThat(count("MATCH (n:ITSystem {name: 'Payments Core'}) WHERE n.isCurrent = true RETURN count(n) AS c"))
        .as("ровно один актуальный Payments Core").isEqualTo(1);
    assertThat(count("MATCH (n:ITSystem) WHERE n.isCurrent = true RETURN count(n) AS c")).isEqualTo(2);
    assertThat(count("MATCH (:ITSystem {eamCode: 'EAM-1042'})-[rel:DECOMPOSED_INTO]->(:Service {name: 'payments-api'}) "
        + "WHERE rel.validTo IS NULL RETURN count(rel) AS c")).isEqualTo(1);
    assertThat(count("MATCH (:ITSystem {eamCode: 'EAM-2001'})-[rel:DECOMPOSED_INTO]->(:Service {name: 'ledger-api'}) "
        + "WHERE rel.validTo IS NULL RETURN count(rel) AS c")).isEqualTo(1);
    assertThat(count("MATCH (:ITSystem {name: 'Payments Core'})-[:USES_SOLUTION]->(:Solution {name: 'Card Processing'})"
        + "-[:USES_PLATFORM]->(:Platform {name: 'Kubernetes Platform'}) RETURN count(*) AS c")).isEqualTo(1);
    // Старые узлы остались в архиве (tombstone переиграл reconcile), активных записей у них нет.
    assertThat(count("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'IT_SYSTEM'})-[:ASSERTS]->(n:ITSystem) "
        + "WHERE r.sourceId STARTS WITH 'EAM-' AND (r.active = true OR n.isCurrent = true) RETURN count(n) AS c")).isZero();
  }

  /** Данные, на которых строятся explain_provenance и консоль конфликтов, не сломаны переходом. */
  @Test
  @Order(5)
  void m5_provenanceAndConflictStoresAreConsistent() throws SQLException {
    // explain_provenance читает SourceRecord-[:ASSERTS]->узел: у системы 5101 есть активная MASTER-запись EAM.
    assertThat(count("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'IT_SYSTEM', sourceId: '5101', active: true})"
        + "-[a:ASSERTS]->(n:ITSystem {name: 'Payments Core'}) WHERE n.isCurrent = true AND a.authority = 'MASTER' "
        + "RETURN count(r) AS c")).isEqualTo(1);
    // Конфликтов источников миграция не создаёт; кандидаты идентичности — только между старым ключом и преемником.
    assertThat(scalarLong("select count(*) from source_conflict")).isZero();
    assertThat(scalarLong("select count(*) from identity_candidate where "
        + "not (left_id like 'EAM-%' or right_id like 'EAM-%')")).isZero();
    assertThat(scalarLong("select count(*) from inbox_event where status = 'QUARANTINED'")).isZero();
    assertThat(scalarLong("select count(*) from dlq_entry")).isZero();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private List<String> activeItSystemKeys() {
    return driver.executableQuery("MATCH (r:SourceRecord {source: 'EAM', sourceType: 'IT_SYSTEM'}) WHERE r.active = true "
        + "RETURN r.sourceId AS id ORDER BY id").execute().records().stream().map(r -> r.get("id").asString()).toList();
  }

  private long count(String cypher) {
    return driver.executableQuery(cypher).execute().records().getFirst().get("c").asLong();
  }

  /** Строк в нетерминальных статусах нет: всё принятое обработано. */
  private void awaitJournalDrained() {
    awaitTrue(() -> {
      try {
        return scalarLong("select count(*) from inbox_event where status in "
            + "('RECEIVED','RETRYING','VALIDATED','NORMALIZED','RESOLVED')") == 0;
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  private static long scalarLong(String sql) throws SQLException {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var st = c.createStatement();
        var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String token() {
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
    var claims = JwtClaimsSet.builder().subject("operator-1").issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(600)).claim("scope", "architecture.admin")
        .claim("realm_access", java.util.Map.of("roles", java.util.List.of("archrag-console-admin")));
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
        .getTokenValue();
  }

  private HttpResponse<String> post(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** Опрос с таймаутом: Awaitility в проект не входит, новую зависимость в задаче не вводим. */
  private static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within 90s");
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
  }
}
