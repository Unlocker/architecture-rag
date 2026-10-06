package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.unlocker.archrag.adaptercore.PollResult;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.assetadapter.AssetAdapter;
import io.github.unlocker.archrag.deploymapadapter.DeploymapAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.scmadapter.ScmAdapter;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import io.github.unlocker.archrag.sourcestubs.StubWebhookSender;
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
 * Приёмка синхронизации (E1) на стенде, как в проде: заглушки четырёх источников → подписанный webhook или polling →
 * адаптеры → {@code inbox_event} + S3 → работающий диспетчер журнала → Neo4j. Диспетчер на значениях по умолчанию
 * ({@code poll-interval=1s}, {@code batch-size=100}), чтобы замер задержки был честным. Стенд один на класс,
 * сценарии идут по порядку; каждый фиксирует своё исходное состояние сам и опирается только на загруженный срез.
 *
 * <table>
 *   <caption>Критерий готовности → метод</caption>
 *   <tr><td>1 (срез)</td><td>{@link #k1_sliceIsLoaded}, {@link #k1_decomposedIntoLinksSystemToService}, {@link #k1_dependsOnLinksServices}, {@link #k1_noQuarantine}</td></tr>
 *   <tr><td>2 (p95 ≤ 30 с)</td><td>{@link #k2a_webhookLatencyP95}</td></tr>
 *   <tr><td>2 (reconciliation)</td><td>{@link #k2b_missedChangesAreFixedByReconcile}</td></tr>
 *   <tr><td>3 (повтор, out-of-order)</td><td>{@link #k3a_repeatedEventChangesNothing}, {@link #k3b_reversedWebhooks}, {@link #k3c_reorderedPolling}</td></tr>
 *   <tr><td>4 (rebuild)</td><td>{@link #k4_rebuildGivesEquivalentCanonicalGraph}</td></tr>
 * </table>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SyncAcceptanceIT.TestJwt.class)
class SyncAcceptanceIT {

  private static final String SECRET = "acceptance-webhook-secret";
  private static final String BUCKET = "acceptance-it-bucket";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
  /** Порог критерия 2. */
  private static final Duration SLA_P95 = Duration.ofSeconds(30);
  private static final int LATENCY_SAMPLES = 20;
  /** Интервал опроса диспетчера (значение по умолчанию); пауза «ничего не изменилось» в К2б кратна ему. */
  private static final Duration DISPATCHER_POLL = Duration.ofSeconds(1);

  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

  // При PER_CLASS Spring-контекст создаётся вместе с экземпляром, раньше расширения Testcontainers, поэтому
  // контейнеры стартуют здесь; остановит их Ryuk по завершении JVM.
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

  /** Управляющие порты адаптеров выбираются заранее: адрес нужен admin-сервису до старта самих адаптеров. */
  private static final Map<SourceSystem, Integer> PORTS = new EnumMap<>(SourceSystem.class);

  static {
    for (SourceSystem s : SourceSystem.values()) {
      PORTS.put(s, freePort());
    }
  }

  private static int freePort() {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String controlUrl(SourceSystem s) {
    return "http://127.0.0.1:" + PORTS.get(s) + "/control/snapshot";
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
    // Декодер токенов тестовый (TestJwt); свойство нужно только чтобы разрешился плейсхолдер application.yml.
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://unused.invalid");
    r.add("archrag.adapters.eam.control-url", () -> controlUrl(SourceSystem.EAM));
    r.add("archrag.adapters.scm.control-url", () -> controlUrl(SourceSystem.SCM));
    r.add("archrag.adapters.cmdb.control-url", () -> controlUrl(SourceSystem.CMDB));
    r.add("archrag.adapters.deploymap.control-url", () -> controlUrl(SourceSystem.DEPLOY_MAP));
    // Остальные параметры диспетчера по умолчанию (poll-interval 1s, batch-size 100).
    r.add("archrag.dispatcher.retry-delay", () -> "1s");
  }

  @Value("${local.server.port}") int port;
  @Autowired Driver driver;

  private final Map<SourceSystem, StubSource> stubs = new EnumMap<>(SourceSystem.class);
  private final Map<SourceSystem, SourceAdapter> adapters = new EnumMap<>(SourceSystem.class);
  private final List<StubSourceServer> servers = new ArrayList<>();
  private final Map<SourceSystem, StubWebhookSender> senders = new EnumMap<>(SourceSystem.class);
  private S3RawPayloadStore store;
  private final HttpClient http = HttpClient.newHttpClient();

  /** Четыре адаптера над своими заглушками, webhook и control на заранее выбранных портах. Polling не запускаем. */
  @BeforeAll
  void startAdapters() {
    Clock clock = Clock.systemUTC();
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    var journal = new PostgresEventJournal(ds);
    store = S3RawPayloadStore.create(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333)),
        ContainersSmokeIT.S3_ACCESS_KEY, ContainersSmokeIT.S3_SECRET_KEY, BUCKET);
    store.ensureBucket();
    stubs.putAll(StubSources.seeded(clock));
    stubs.forEach((system, stub) -> {
      var server = new StubSourceServer(stub);
      servers.add(server);
      SourceAdapter adapter = switch (system) {
        case EAM -> EamAdapter.create(EamAdapter.config(server.baseUri(), SECRET), journal, store);
        case SCM -> ScmAdapter.create(ScmAdapter.config(server.baseUri(), SECRET), journal, store);
        case CMDB -> AssetAdapter.create(AssetAdapter.config(server.baseUri(), SECRET), journal, store);
        case DEPLOY_MAP -> DeploymapAdapter.create(DeploymapAdapter.config(server.baseUri(), SECRET), journal, store);
      };
      adapters.put(system, adapter);
      var webhook = adapter.startWebhook(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORTS.get(system)));
      senders.put(system, new StubWebhookSender(webhook.endpoint(), SECRET, clock));
    });
  }

  @AfterAll
  void stopAll() {
    senders.values().forEach(StubWebhookSender::close);
    adapters.values().forEach(SourceAdapter::close);
    servers.forEach(StubSourceServer::close);
    if (store != null) {
      store.close();
    }
  }

  // ---- К1: срез ----------------------------------------------------------------------------

  @Test
  @Order(1)
  void k1_sliceIsLoaded() {
    // Первый pollOnce каждого адаптера делает полный snapshot.
    for (SourceSystem s : SourceSystem.values()) {
      assertThat(adapters.get(s).poller().pollOnce().outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    }
    awaitJournalDrained();

    // Направления связей — как в RelationType и GraphProjector.
    assertThat(count("MATCH (:Service {name: 'payments-api'})-[:HAS_DEPLOYMENT]->(d:Deployment)-[:IN_ENVIRONMENT]->(:Environment) RETURN count(d) AS c"))
        .isPositive();
    assertThat(count("MATCH (:Deployment)-[:RUNS_ON]->(c:ComputeInstance) RETURN count(c) AS c")).isPositive();
    assertThat(count("MATCH (:Service)-[:IMPLEMENTED_IN]->(r:Repository) RETURN count(r) AS c")).isPositive();
    assertThat(count("MATCH (:ITSystem)-[:OWNED_BY]->(t:Team) RETURN count(t) AS c")).isPositive();
    assertThat(count("MATCH (r:SourceRecord)-[:ASSERTS]->() RETURN count(DISTINCT r.source) AS c"))
        .as("ASSERTS приходят минимум от трёх SourceSystem").isGreaterThanOrEqualTo(3);
  }

  /** Связь системы с сервисом, часть цепочки среза ITSystem → Service → Deployment → Environment → ComputeInstance. */
  @Test
  @Order(2)
  void k1_decomposedIntoLinksSystemToService() {
    assertThat(count("MATCH (:ITSystem)-[:DECOMPOSED_INTO]->(s:Service) RETURN count(s) AS c")).isPositive();
    // Полная цепочка среза; глубина зафиксирована литералом.
    assertThat(count("MATCH (:ITSystem {name: 'Payments Core'})-[:DECOMPOSED_INTO]->(:Service)-[:HAS_DEPLOYMENT]->(:Deployment)"
        + "-[:RUNS_ON]->(c:ComputeInstance) RETURN count(c) AS c")).isPositive();
  }

  @Test
  @Order(3)
  void k1_dependsOnLinksServices() {
    assertThat(count("MATCH (:Service {name: 'payments-api'})-[:DEPENDS_ON]->(s:Service {name: 'ledger-api'}) RETURN count(s) AS c"))
        .isEqualTo(1);
  }

  @Test
  @Order(4)
  void k1_noQuarantine() throws SQLException {
    assertThat(scalarLong("select count(*) from inbox_event where status = 'QUARANTINED'")).isZero();
  }

  // ---- К2: задержка и reconciliation ---------------------------------------------------------

  @Test
  @Order(5)
  void k2a_webhookLatencyP95() throws SQLException {
    StubSource eam = stubs.get(SourceSystem.EAM);
    StubSource scm = stubs.get(SourceSystem.SCM);
    List<Duration> samples = new ArrayList<>();

    // Изменения шлём по одному: меряется задержка конвейера, а не очередь.
    for (int i = 0; i < LATENCY_SAMPLES; i++) {
      String name = (i % 2 == 0 ? "Ledger-" : "ledger-api-") + uid();
      boolean viaEam = i % 2 == 0;
      StubSource stub = viaEam ? eam : scm;
      if (viaEam) {
        eam.upsert(StubSources.IT_SYSTEM, "EAM-2001", StubSources.fields("name", name, "ownerTeam", "TEAM-PAY"));
      } else {
        scm.upsert(StubSources.SERVICE, "svc-ledger-api",
            StubSources.fields("name", name, "systemCode", "EAM-2001"));
      }
      WebhookEvent event = stub.webhookEvents().getLast();
      String label = viaEam ? "ITSystem" : "Service";

      long start = System.nanoTime();
      assertThat(senders.get(stub.system()).send(event)).isEqualTo(202);
      awaitTrue(() -> count("MATCH (n:" + label + " {name: '" + name + "'}) RETURN count(n) AS c") > 0, Duration.ofSeconds(60));
      samples.add(Duration.ofNanos(System.nanoTime() - start));
    }

    Duration p95 = percentile(samples, 95);
    System.out.printf("SLA webhook→graph: n=%d p50=%s p95=%s max=%s; journal updated_at-received_at (PROJECTED): %s%n",
        samples.size(), percentile(samples, 50), p95, Collections.max(samples), journalLag());
    assertThat(p95).isLessThanOrEqualTo(SLA_P95);
  }

  @Test
  @Order(6)
  void k2b_missedChangesAreFixedByReconcile() throws Exception {
    StubSource eam = stubs.get(SourceSystem.EAM);
    String gone = "T-GONE-" + uid();
    String fresh = "T-NEW-" + uid();
    eam.upsert(StubSources.TEAM, gone, StubSources.fields("name", "Gone " + gone));
    assertThat(senders.get(SourceSystem.EAM).send(eam.webhookEvents().getLast())).isEqualTo(202);
    awaitTrue(() -> Boolean.TRUE.equals(teamActive(gone)), Duration.ofSeconds(60));
    awaitJournalDrained();
    List<String> before = CanonicalGraph.dump(driver);

    // Изменения без webhook: адаптер о них не знает.
    eam.suppressWebhooks(true);
    try {
      eam.delete(StubSources.TEAM, gone);
      eam.upsert(StubSources.TEAM, fresh, StubSources.fields("name", "Fresh " + fresh));
      // Пауза часть проверки: за два интервала опроса диспетчера граф не должен измениться сам.
      Thread.sleep(DISPATCHER_POLL.multipliedBy(2).toMillis());
    } finally {
      eam.suppressWebhooks(false);
    }
    assertThat(CanonicalGraph.dump(driver)).isEqualTo(before);

    assertThat(post("/admin/reconcile/eam", token()).statusCode()).isEqualTo(202);

    awaitTrue(() -> Boolean.FALSE.equals(teamActive(gone)) && Boolean.TRUE.equals(teamActive(fresh)),
        Duration.ofSeconds(60));
    assertThat(count("MATCH (:SourceRecord {source: 'EAM', sourceType: 'TEAM', sourceId: '" + gone
        + "'})-[:ASSERTS]->(t:Team) WHERE t.isCurrent = false RETURN count(t) AS c")).isEqualTo(1);
  }

  // ---- К3: повтор и out-of-order -------------------------------------------------------------

  @Test
  @Order(7)
  void k3a_repeatedEventChangesNothing() throws Exception {
    StubSource eam = stubs.get(SourceSystem.EAM);
    String id = "T-DUP-" + uid();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "Dup " + id));
    WebhookEvent event = eam.webhookEvents().getLast();
    assertThat(senders.get(SourceSystem.EAM).send(event)).isEqualTo(202);
    awaitTrue(() -> Boolean.TRUE.equals(teamActive(id)), Duration.ofSeconds(60));
    awaitJournalDrained();
    List<String> before = CanonicalGraph.dump(driver);

    assertThat(senders.get(SourceSystem.EAM).send(event)).isEqualTo(202);
    awaitJournalDrained();

    assertThat(scalarLong("select count(*) from inbox_event where event_id = ?", event.eventId())).isEqualTo(1);
    assertThat(CanonicalGraph.dump(driver)).isEqualTo(before);
  }

  @Test
  @Order(8)
  void k3b_reversedWebhooks() throws Exception {
    StubSource eam = stubs.get(SourceSystem.EAM);
    String id = "T-REV-" + uid();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "V1 " + id));
    WebhookEvent first = eam.webhookEvents().getLast();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "V2 " + id));
    WebhookEvent second = eam.webhookEvents().getLast();

    // Уведомления в обратном порядке; обработчик читает объект по id, поэтому v1 в графе не появится.
    assertThat(senders.get(SourceSystem.EAM).send(second)).isEqualTo(202);
    assertThat(senders.get(SourceSystem.EAM).send(first)).isEqualTo(202);
    awaitJournalDrained();

    assertThat(count("MATCH (t:Team {name: 'V2 " + id + "'}) RETURN count(t) AS c")).isEqualTo(1);
    assertThat(count("MATCH (t:Team {name: 'V1 " + id + "'}) RETURN count(t) AS c")).isZero();
    assertThat(scalarLong("select count(*) from inbox_event where source_id = ? and status = 'DUPLICATE'", id))
        .isGreaterThanOrEqualTo(1);
    assertThat(scalarLong("select count(*) from inbox_event where source_id = ? and status = 'QUARANTINED'", id)).isZero();
  }

  @Test
  @Order(9)
  void k3c_reorderedPolling() throws Exception {
    StubSource scm = stubs.get(SourceSystem.SCM);
    // Курсор incremental polling сдвигаем сначала к концу журнала, чтобы страница содержала только наши изменения.
    adapters.get(SourceSystem.SCM).poller().pollOnce();
    awaitJournalDrained();
    String id = "svc-reorder-" + uid();
    scm.suppressWebhooks(true);
    try {
      scm.upsert(StubSources.SERVICE, id, StubSources.fields("name", "Old " + id));
      scm.upsert(StubSources.SERVICE, id, StubSources.fields("name", "New " + id));
      scm.reorderNextPage();

      adapters.get(SourceSystem.SCM).poller().pollOnce();
    } finally {
      scm.suppressWebhooks(false);
    }
    awaitJournalDrained();

    assertThat(count("MATCH (s:Service {name: 'New " + id + "'}) RETURN count(s) AS c")).isEqualTo(1);
    assertThat(count("MATCH (s:Service {name: 'Old " + id + "'}) RETURN count(s) AS c")).isZero();
    assertThat(scalarLong("select count(*) from inbox_event where source_id = ? and status in ('IGNORED_OLD_VERSION','DUPLICATE')", id))
        .isGreaterThanOrEqualTo(1);
    assertThat(scalarLong("select count(*) from inbox_event where source_id = ? and status = 'QUARANTINED'", id)).isZero();
  }

  // ---- К4: rebuild ---------------------------------------------------------------------------

  @Test
  @Order(10)
  void k4_rebuildGivesEquivalentCanonicalGraph() throws Exception {
    awaitJournalDrained();
    List<String> before = CanonicalGraph.dump(driver);
    assertThat(before).anyMatch(s -> s.startsWith("R "));

    HttpResponse<String> response = post("/admin/rebuild?confirm=true", token());

    assertThat(response.statusCode()).isEqualTo(200);
    awaitJournalDrained();
    // Временные поля тоже воспроизводятся (исключений нет); граф включает tombstone из К2б и версии из К3.
    assertThat(CanonicalGraph.dump(driver)).isEqualTo(before);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static Duration percentile(List<Duration> samples, int p) {
    List<Duration> sorted = samples.stream().sorted().toList();
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }

  /** Служебная латентность журнала: от приёма до последнего перехода в PROJECTED (p50/max, секунды). */
  private String journalLag() throws SQLException {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var st = c.createStatement();
        var rs = st.executeQuery("select percentile_cont(0.5) within group (order by extract(epoch from updated_at - received_at)),"
            + " max(extract(epoch from updated_at - received_at)) from inbox_event where status = 'PROJECTED'")) {
      rs.next();
      return "p50=%.3fs max=%.3fs".formatted(rs.getDouble(1), rs.getDouble(2));
    }
  }

  private long count(String cypher) {
    return driver.executableQuery(cypher).execute().records().getFirst().get("c").asLong();
  }

  private Boolean teamActive(String id) {
    var rows = driver.executableQuery(
            "MATCH (r:SourceRecord {source: 'EAM', sourceType: 'TEAM', sourceId: $id}) RETURN r.active AS a")
        .withParameters(Map.of("id", id)).execute().records();
    return rows.isEmpty() ? null : rows.getFirst().get("a").asBoolean();
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
    }, Duration.ofSeconds(60));
  }

  private static long scalarLong(String sql, String... args) throws SQLException {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setString(i + 1, args[i]);
      }
      try (var rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String token() {
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
    var claims = JwtClaimsSet.builder().subject("operator-1").issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(600)).claim("scope", "architecture.admin");
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
        .getTokenValue();
  }

  private HttpResponse<String> post(String path, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String uid() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  /** Опрос с таймаутом: Awaitility в проект не входит, новую зависимость в задаче не вводим. */
  private static void awaitTrue(BooleanSupplier condition) {
    awaitTrue(condition, Duration.ofSeconds(60));
  }

  private static void awaitTrue(BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within " + timeout);
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
