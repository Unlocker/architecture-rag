package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.github.unlocker.archrag.adaptercore.PollResult;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.assetadapter.AssetAdapter;
import io.github.unlocker.archrag.deploymapadapter.DeploymapAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.scmadapter.ScmAdapter;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import io.github.unlocker.archrag.sourcestubs.StubWebhookSender;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
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

/**
 * Приёмочный сценарий синхронизации на полном конвейере: заглушки четырёх источников → адаптеры (webhook и polling) →
 * PostgreSQL inbox и S3 → диспетчер журнала → normalizer, identity, projector → Neo4j. Диспетчер работает сам, как в
 * проде; тест только кладёт данные в источники и смотрит в граф.
 *
 * <ul>
 *   <li>критерий 2: webhook-изменение в графе с p95 ≤ 30 с; пропущенное изменение исправляет reconciliation;
 *   <li>критерий 3: повторная и out-of-order доставка не меняют граф;
 *   <li>критерий 4: rebuild из raw storage даёт эквивалентный граф.
 * </ul>
 */
@Testcontainers
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SyncAcceptanceIT.TestJwt.class)
class SyncAcceptanceIT {

  private static final String SOURCE = "urn:corp:eam";
  private static final String SECRET = "acceptance-webhook-secret";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
  /** Порог критерия 2. */
  private static final Duration SLA_P95 = Duration.ofSeconds(30);
  private static final int LATENCY_SAMPLES = 20;

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
    r.add("archrag.s3.bucket", () -> "acceptance-it-bucket");
    // Декодер токенов тестовый (TestJwt); свойство нужно только чтобы разрешился плейсхолдер application.yml.
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://unused.invalid");
    // Диспетчер включён и опрашивает журнал часто: замеряется конвейер, а не интервал опроса.
    r.add("archrag.dispatcher.poll-interval", () -> "100ms");
    r.add("archrag.dispatcher.retry-delay", () -> "1s");
  }

  @Value("${local.server.port}") int port;
  @Autowired Driver driver;
  @Autowired EventJournal journal;
  @Autowired RawPayloadStore rawStore;

  private static final List<StubSourceServer> SERVERS = new ArrayList<>();
  private static final List<SourceAdapter> ADAPTERS = new ArrayList<>();
  private static StubSource eam;
  private static SourceAdapter eamAdapter;
  private static StubWebhookSender sender;
  private static boolean loaded;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeAll
  static void noop() {}

  @AfterAll
  static void stopAll() {
    if (sender != null) {
      sender.close();
    }
    ADAPTERS.forEach(SourceAdapter::close);
    SERVERS.forEach(StubSourceServer::close);
  }

  /** Первичная загрузка среза всеми четырьмя адаптерами; один раз на класс, после Spring-контекста. */
  private synchronized void loadSlice() throws Exception {
    if (loaded) {
      return;
    }
    Clock clock = Clock.systemUTC();
    eam = StubSources.eam(clock);
    var stubs = List.of(eam, StubSources.scm(clock), StubSources.cmdb(clock), StubSources.deployMap(clock));
    for (StubSource stub : stubs) {
      var server = new StubSourceServer(stub);
      SERVERS.add(server);
      SourceAdapter adapter = switch (stub.system()) {
        case EAM -> EamAdapter.create(EamAdapter.config(server.baseUri(), SECRET), journal, rawStore);
        case SCM -> ScmAdapter.create(ScmAdapter.config(server.baseUri(), SECRET), journal, rawStore);
        case CMDB -> AssetAdapter.create(AssetAdapter.config(server.baseUri(), SECRET), journal, rawStore);
        case DEPLOY_MAP -> DeploymapAdapter.create(DeploymapAdapter.config(server.baseUri(), SECRET), journal, rawStore);
      };
      ADAPTERS.add(adapter);
      assertThat(adapter.poller().pollOnce().outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
      if (stub == eam) {
        eamAdapter = adapter;
      }
    }
    var webhookServer = eamAdapter.startWebhook(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
    sender = new StubWebhookSender(webhookServer.endpoint(), SECRET, clock);
    loaded = true;
  }

  @Test
  void initialLoadBuildsTheWholeSliceThroughTheRunningPipeline() throws Exception {
    loadSlice();

    awaitTrue(this::inboxDrained);
    awaitTrue(() -> pathExists("Service", "ComputeInstance"));
    for (String label : List.of("ITSystem", "Service", "Deployment", "Environment", "ComputeInstance", "Repository", "Team")) {
      assertThat(count("MATCH (n:" + label + ") RETURN count(n) AS c")).as(label).isPositive();
    }
    awaitTrue(this::inboxDrained);
    // TODO UNLOCKER-171: SERVICE_DEPENDENCY сейчас уходит в карантин INVALID_COMMANDS, а DECOMPOSED_INTO пропускается
    // (SCM утверждает связь, мастер по матрице EAM): вопрос архитектору в задаче. Остальное должно проецироваться чисто.
    assertThat(scalarLong("select count(*) from inbox_event where status in ('QUARANTINED','RETRYING')"
        + " and source_type <> 'SERVICE_DEPENDENCY'")).isZero();
  }

  @Test
  void webhookChangeReachesGraphWithinSlaP95() throws Exception {
    loadSlice();
    awaitTrue(() -> pathExists("Service", "ComputeInstance"));
    List<Duration> samples = new ArrayList<>();

    for (int i = 0; i < LATENCY_SAMPLES; i++) {
      String name = "Ledger-" + uid();
      eam.upsert(StubSources.IT_SYSTEM, "EAM-2001", StubSources.fields("name", name, "ownerTeam", "TEAM-PAY"));
      WebhookEvent event = eam.webhookEvents().getLast();

      long start = System.nanoTime();
      assertThat(sender.send(event)).isEqualTo(202);
      awaitTrue(() -> nameExists(name));
      samples.add(Duration.ofNanos(System.nanoTime() - start));
    }

    Duration p95 = percentile(samples, 95);
    System.out.printf("webhook→graph latency over %d changes: p50=%s p95=%s max=%s%n", samples.size(),
        percentile(samples, 50), p95, Collections.max(samples));
    assertThat(p95).isLessThanOrEqualTo(SLA_P95);
  }

  @Test
  void reconciliationFixesChangesThatNeverGotAWebhook() throws Exception {
    loadSlice();
    String gone = "T-GONE-" + uid();
    String renamed = "T-REN-" + uid();
    eam.upsert(StubSources.TEAM, gone, StubSources.fields("name", "Gone " + gone));
    eam.upsert(StubSources.TEAM, renamed, StubSources.fields("name", "Before " + renamed));
    eamAdapter.poller().pollOnce();
    awaitTrue(() -> Boolean.TRUE.equals(teamActive(gone)) && nameExists("Before " + renamed));

    // Три изменения, о которых адаптер не узнает: удаление, переименование, новый объект.
    eam.suppressWebhooks(true);
    String created = "T-NEW-" + uid();
    try {
      eam.delete(StubSources.TEAM, gone);
      eam.upsert(StubSources.TEAM, renamed, StubSources.fields("name", "After " + renamed));
      eam.upsert(StubSources.TEAM, created, StubSources.fields("name", "Created " + created));
      Thread.sleep(500);
      assertThat(teamActive(gone)).as("пропуск виден: без reconciliation граф устарел").isTrue();
      assertThat(nameExists("After " + renamed)).isFalse();
    } finally {
      eam.suppressWebhooks(false);
    }

    PollResult run = eamAdapter.poller().snapshotOnce();

    assertThat(run.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    awaitTrue(() -> Boolean.FALSE.equals(teamActive(gone)) && nameExists("After " + renamed)
        && Boolean.TRUE.equals(teamActive(created)));
    assertThat(nameExists("Before " + renamed)).isFalse();
  }

  @Test
  void duplicateAndOutOfOrderDeliveriesDoNotChangeCorrectState() throws Exception {
    loadSlice();
    String id = "T-ORD-" + uid();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "First " + id));
    WebhookEvent first = eam.webhookEvents().getLast();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "Second " + id));
    WebhookEvent second = eam.webhookEvents().getLast();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "Third " + id));
    WebhookEvent third = eam.webhookEvents().getLast();

    // Новая версия приходит раньше старой, затем всё повторяется.
    assertThat(sender.send(third)).isEqualTo(202);
    awaitTrue(() -> nameExists("Third " + id));
    awaitTrue(this::inboxDrained);
    List<String> settled = dump();
    for (WebhookEvent e : List.of(second, first, third, third, first)) {
      assertThat(sender.send(e)).isEqualTo(202);
    }
    awaitTrue(this::inboxDrained);

    assertThat(nameExists("Third " + id)).isTrue();
    assertThat(nameExists("First " + id)).isFalse();
    assertThat(nameExists("Second " + id)).isFalse();
    assertThat(dump()).isEqualTo(settled);
    assertThat(scalarLong("select count(*) from inbox_event where source = ? and event_id = ?", SOURCE, third.eventId()))
        .isEqualTo(1);
  }

  @Test
  void eventWithOlderSourceVersionIsIgnoredAndRepeatedEventIdIsDuplicate() throws Exception {
    loadSlice();
    String id = "T-VER-" + uid();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "Old " + id));
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "Current " + id));
    assertThat(sender.send(eam.webhookEvents().getLast())).isEqualTo(202);
    awaitTrue(() -> nameExists("Current " + id));

    // Запоздавшее событие со старой версией и своим содержимым: журнал принимает его, projector не применяет.
    String stale = "stale-" + uid();
    CanonicalEvent old = new CanonicalEvent(stale, SOURCE, CanonicalEvent.TYPE_ASSET_UPSERTED, "team/" + id, Instant.now(),
        "urn:corp:schema:asset-upserted:1", "corr",
        new AssetEventData("TEAM", id, new SourceVersion("1"), Map.of("name", "Stale " + id)));
    RawPayloadRef raw = rawStore.put(SOURCE, ("{\"operation\":\"UPSERT\",\"completeness\":\"COMPLETE\",\"updatedAt\":\""
        + Instant.now() + "\",\"payload\":{\"name\":\"Stale " + id + "\"}}").getBytes(StandardCharsets.UTF_8));
    journal.append(old, raw, null);

    awaitTrue(() -> status(stale) == ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(nameExists("Stale " + id)).isFalse();
    assertThat(nameExists("Current " + id)).isTrue();

    // Тот же eventId повторно: строка не добавляется, граф не меняется.
    assertThat(journal.append(old, raw, null).status()).isEqualTo(ProcessingStatus.DUPLICATE);
    assertThat(scalarLong("select count(*) from inbox_event where source = ? and event_id = ?", SOURCE, stale)).isEqualTo(1);
    assertThat(nameExists("Stale " + id)).isFalse();
  }

  @Test
  void rebuildFromRawStorageGivesEquivalentGraph() throws Exception {
    loadSlice();
    String id = "T-RB-" + uid();
    eam.upsert(StubSources.TEAM, id, StubSources.fields("name", "Rebuild " + id));
    assertThat(sender.send(eam.webhookEvents().getLast())).isEqualTo(202);
    awaitTrue(() -> nameExists("Rebuild " + id));
    awaitTrue(this::inboxDrained);
    List<String> before = dump();
    assertThat(before).anyMatch(s -> s.startsWith("R "));

    HttpResponse<String> response = post("/admin/rebuild?confirm=true", token("architecture.admin"));

    assertThat(response.statusCode()).isEqualTo(200);
    awaitTrue(this::inboxDrained);
    assertThat(dump()).isEqualTo(before);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static Duration percentile(List<Duration> samples, int p) {
    List<Duration> sorted = samples.stream().sorted().toList();
    int rank = (int) Math.ceil(p / 100.0 * sorted.size());
    return sorted.get(Math.max(rank, 1) - 1);
  }

  private boolean pathExists(String from, String to) {
    // Глубина зафиксирована литералом: срез ITSystem → Service → Deployment → Environment → ComputeInstance короче 6 рёбер.
    return count("MATCH (a:" + from + ")-[*1..6]-(b:" + to + ") RETURN count(*) AS c") > 0;
  }

  private long count(String cypher) {
    return driver.executableQuery(cypher).execute().records().getFirst().get("c").asLong();
  }

  private boolean nameExists(String name) {
    return driver.executableQuery("MATCH (n) WHERE n.name = $name RETURN count(n) AS c")
        .withParameters(Map.of("name", name)).execute().records().getFirst().get("c").asLong() > 0;
  }

  private Boolean teamActive(String id) {
    var rows = driver.executableQuery(
            "MATCH (r:SourceRecord {source: 'EAM', sourceType: 'TEAM', sourceId: $id}) RETURN r.active AS a")
        .withParameters(Map.of("id", id)).execute().records();
    return rows.isEmpty() ? null : rows.getFirst().get("a").asBoolean();
  }

  private ProcessingStatus status(String eventId) {
    return journal.find(SOURCE, eventId).map(JournalEntry::status).orElse(null);
  }

  private boolean inboxDrained() {
    try {
      return scalarLong("select count(*) from inbox_event where status in ('RECEIVED','RETRYING','VALIDATED','NORMALIZED','RESOLVED')") == 0;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Канонический дамп графа: узлы и связи как отсортированные строки без elementId и без служебных временных полей
   * ({@code *At}, {@code validFrom}, {@code validTo}), которые зависят от момента обработки.
   */
  private List<String> dump() {
    List<String> out = new ArrayList<>();
    for (var r : driver.executableQuery("MATCH (n) RETURN labels(n) AS l, properties(n) AS p").execute().records()) {
      out.add("N " + sorted(r.get("l").asList()) + " " + stable(r.get("p").asMap()));
    }
    for (var r : driver.executableQuery(
        "MATCH (a)-[r]->(b) RETURN labels(a) AS al, properties(a) AS ap, type(r) AS t, properties(r) AS rp, labels(b) AS bl, properties(b) AS bp")
        .execute().records()) {
      out.add("R " + sorted(r.get("al").asList()) + stable(r.get("ap").asMap()) + " -" + r.get("t").asString()
          + stable(r.get("rp").asMap()) + "-> " + sorted(r.get("bl").asList()) + stable(r.get("bp").asMap()));
    }
    Collections.sort(out);
    return out;
  }

  private static Map<String, Object> stable(Map<String, Object> props) {
    Map<String, Object> m = new TreeMap<>(props);
    m.keySet().removeIf(k -> k.endsWith("At") || k.equals("validFrom") || k.equals("validTo"));
    return m;
  }

  private static List<Object> sorted(List<Object> l) {
    var c = new ArrayList<>(l);
    c.sort(Comparator.comparing(Object::toString));
    return c;
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

  private String token(String scope) {
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
    var claims = JwtClaimsSet.builder().subject("operator-1").issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(600)).claim("scope", scope);
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
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within 60s");
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
  }
}
