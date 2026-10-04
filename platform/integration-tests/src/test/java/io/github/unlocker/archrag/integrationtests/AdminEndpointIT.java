package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.assetadapter.AssetAdapter;
import io.github.unlocker.archrag.deploymapadapter.DeploymapAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalKey;
import io.github.unlocker.archrag.eventschemas.JournalQuery;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.ingestionservice.AdminLock;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.ingestionservice.StoredEventReader;
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
 * Админский эндпоинт ingestion-сервиса на реальных Neo4j, PostgreSQL и S3: rebuild на чистой БД даёт эквивалентный
 * граф (критерий 4), replay идемпотентен, crosswalk-конфликт, scope (401/403), аудит и замок (409).
 */
@Testcontainers
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AdminEndpointIT.TestJwt.class)
class AdminEndpointIT {

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

  /** Порт, на котором тест поднимает control-контекст adapter-а eam (адрес нужен до старта приложения). */
  private static final int EAM_CONTROL_PORT = freePort();

  private static int freePort() {
    try (var socket = new java.net.ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
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
    r.add("archrag.s3.bucket", () -> "admin-it-bucket");
    // Декодер токенов тестовый (TestJwt); свойство нужно только чтобы разрешился плейсхолдер application.yml.
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://unused.invalid");
    // Обработкой журнала в этом тесте управляет он сам.
    r.add("archrag.dispatcher.enabled", () -> "false");
    r.add("archrag.adapters.eam.control-url", () -> "http://127.0.0.1:" + EAM_CONTROL_PORT + "/control/snapshot");
    // Порт 1 никто не слушает: адаптер scm «недоступен».
    r.add("archrag.adapters.scm.control-url", () -> "http://127.0.0.1:1/control/snapshot");
  }

  @Value("${local.server.port}") int port;
  @Autowired Driver driver;
  @Autowired EventJournal journal;
  @Autowired JournalReader reader;
  @Autowired RawPayloadStore rawStore;
  @Autowired StoredEventReader events;
  @Autowired EventProcessor processor;
  @Autowired AdminLock lock;

  private static boolean seeded;
  private final HttpClient http = HttpClient.newHttpClient();

  /** Все четыре источника через настоящие адаптеры в журнал и S3, затем обработка в порядке приёма. */
  @BeforeAll
  static void noop() {}

  private synchronized void seed() throws Exception {
    if (seeded) {
      return;
    }
    Clock clock = Clock.systemUTC();
    var eam = StubSources.eam(clock);
    var scm = StubSources.scm(clock);
    var cmdb = StubSources.cmdb(clock);
    var dm = StubSources.deployMap(clock);
    var servers = new ArrayList<StubSourceServer>();
    var adapters = new ArrayList<SourceAdapter>();
    try {
      for (var pair : List.<Object[]>of(
          new Object[] {eam, "eam"}, new Object[] {scm, "scm"}, new Object[] {cmdb, "cmdb"}, new Object[] {dm, "dm"})) {
        StubSource stub = (StubSource) pair[0];
        var server = new StubSourceServer(stub);
        servers.add(server);
        SourceAdapter adapter = switch ((String) pair[1]) {
          case "eam" -> EamAdapter.create(EamAdapter.config(server.baseUri(), "s"), journal, rawStore);
          case "scm" -> ScmAdapter.create(ScmAdapter.config(server.baseUri(), "s"), journal, rawStore);
          case "cmdb" -> AssetAdapter.create(AssetAdapter.config(server.baseUri(), "s"), journal, rawStore);
          default -> DeploymapAdapter.create(DeploymapAdapter.config(server.baseUri(), "s"), journal, rawStore);
        };
        adapters.add(adapter);
        adapter.poller().pollOnce();
      }
      // Вторая версия объекта: граф получает обновление поверх первой версии.
      eam.upsert(StubSources.IT_SYSTEM, "EAM-1042", StubSources.fields("name", "Payments Core v2", "ownerTeam", "TEAM-PAY"));
      adapters.get(0).poller().pollOnce();
    } finally {
      adapters.forEach(SourceAdapter::close);
      servers.forEach(StubSourceServer::close);
    }
    processAllReceived();
    seeded = true;
  }

  private void processAllReceived() {
    JournalQuery q = new JournalQuery(null, null, null, true, null, 100);
    for (List<StoredEvent> page = reader.read(q); !page.isEmpty(); page = reader.read(q)) {
      for (StoredEvent s : page) {
        if (s.entry().status() == ProcessingStatus.RECEIVED && s.entry().payloadRef() != null) {
          CanonicalEvent e = events.toEvent(s, rawStore.get(s.entry().payloadRef()));
          processor.process(e, s.entry().payloadRef());
        } else if (s.entry().status() == ProcessingStatus.RECEIVED) {
          processor.process(
              new CanonicalEvent(s.entry().eventId(), s.entry().source(), s.type(), s.subject(), Instant.now(),
                  s.entry().schemaVersion(), s.entry().correlationId(),
                  new io.github.unlocker.archrag.eventschemas.AssetEventData(s.entry().sourceType(), s.entry().sourceId(),
                      s.entry().sourceVersion(), Map.of())),
              null);
        }
      }
      q = q.after(JournalKey.of(page.get(page.size() - 1).entry()));
    }
  }

  // ---- helpers -----------------------------------------------------------------------------

  private String token(String scope) {
    var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
    var claims = JwtClaimsSet.builder().subject("operator-1").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600));
    if (scope != null) {
      claims.claim("scope", scope);
    }
    return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json")
        .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> admin(String path, String body) throws Exception {
    return post(path, token("architecture.admin"), body);
  }

  /** Канонический дамп графа: узлы и связи как отсортированные строки без elementId. */
  private List<String> dump() {
    List<String> out = new ArrayList<>();
    for (var r : driver.executableQuery("MATCH (n) RETURN labels(n) AS l, properties(n) AS p").execute().records()) {
      out.add("N " + sorted(r.get("l").asList()) + " " + new java.util.TreeMap<>(r.get("p").asMap()));
    }
    for (var r : driver.executableQuery(
        "MATCH (a)-[r]->(b) RETURN labels(a) AS al, properties(a) AS ap, type(r) AS t, properties(r) AS rp, labels(b) AS bl, properties(b) AS bp")
        .execute().records()) {
      out.add("R " + sorted(r.get("al").asList()) + new java.util.TreeMap<>(r.get("ap").asMap()) + " -" + r.get("t").asString()
          + new java.util.TreeMap<>(r.get("rp").asMap()) + "-> " + sorted(r.get("bl").asList())
          + new java.util.TreeMap<>(r.get("bp").asMap()));
    }
    java.util.Collections.sort(out);
    return out;
  }

  private static List<Object> sorted(List<Object> l) {
    var c = new ArrayList<>(l);
    c.sort(java.util.Comparator.comparing(Object::toString));
    return c;
  }

  private long scalarLong(String sql, String... args) throws Exception {
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

  // ---- tests -------------------------------------------------------------------------------

  @Test
  void rebuildOnWipedGraphGivesEquivalentGraphAndAuditsIt() throws Exception {
    seed();
    List<String> before = dump();
    assertThat(before).anyMatch(s -> s.startsWith("N") && s.contains("Payments Core v2"));
    assertThat(before.stream().filter(s -> s.startsWith("R")).count()).isPositive();

    var response = admin("/admin/rebuild?confirm=true", null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(dump()).isEqualTo(before);
    assertThat(scalarLong("select count(*) from admin_audit where operation = 'REBUILD' and status = 'SUCCEEDED'"))
        .isGreaterThanOrEqualTo(1);
    // Исходные строки журнала не менялись, replay добавил свои.
    assertThat(scalarLong("select count(*) from inbox_event where event_id like 'replay:%'")).isPositive();
  }

  @Test
  void rebuildWithoutConfirmIsRejectedAndGraphIsUntouched() throws Exception {
    seed();
    List<String> before = dump();

    assertThat(admin("/admin/rebuild", null).statusCode()).isEqualTo(400);
    assertThat(admin("/admin/rebuild?confirm=false", null).statusCode()).isEqualTo(400);

    assertThat(dump()).isEqualTo(before);
  }

  @Test
  void replayIsIdempotentForSameReplayIdAndRestoresLostProjection() throws Exception {
    seed();
    List<String> before = dump();
    String replayId = UUID.randomUUID().toString();
    String body = "{\"source\":\"urn:corp:eam\",\"replayId\":\"" + replayId + "\"}";

    var first = admin("/admin/replay", body);
    assertThat(first.statusCode()).isEqualTo(200);
    long rows = scalarLong("select count(*) from inbox_event where event_id like ?", "replay:" + replayId + ":%");
    assertThat(rows).isPositive();
    assertThat(first.body()).contains(replayId);
    assertThat(dump()).isEqualTo(before);

    var second = admin("/admin/replay", body);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(scalarLong("select count(*) from inbox_event where event_id like ?", "replay:" + replayId + ":%"))
        .isEqualTo(rows);
    assertThat(dump()).isEqualTo(before);
    assertThat(scalarLong("select count(*) from admin_audit where operation = 'REPLAY' and replay_id = ?", replayId))
        .isEqualTo(2);

    // Потерянная проекция восстанавливается replay под новым replayId.
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    assertThat(admin("/admin/replay", "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(200);
    assertThat(admin("/admin/replay", "{\"source\":\"urn:corp:scm\"}").statusCode()).isEqualTo(200);
    assertThat(admin("/admin/replay", "{\"source\":\"urn:corp:cmdb\"}").statusCode()).isEqualTo(200);
    assertThat(admin("/admin/replay", "{\"source\":\"urn:corp:deploymap\"}").statusCode()).isEqualTo(200);
    assertThat(dump()).isEqualTo(before);
  }

  @Test
  void replayOfEmptyRangeDoesNothing() throws Exception {
    seed();
    long before = scalarLong("select count(*) from inbox_event");

    var r = admin("/admin/replay",
        "{\"source\":\"urn:corp:eam\",\"receivedFrom\":\"2000-01-01T00:00:00Z\",\"receivedTo\":\"2000-01-02T00:00:00Z\"}");

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).contains("\"total\":0");
    assertThat(scalarLong("select count(*) from inbox_event")).isEqualTo(before);
  }

  @Test
  void crosswalkAppliesValidItemAndReportsConflictAndInvalidPerItem() throws Exception {
    seed();
    String body = "["
        + "{\"left\":{\"source\":\"EAM\",\"sourceType\":\"IT_SYSTEM\",\"sourceId\":\"xw-a\"},"
        + "\"right\":{\"source\":\"SCM\",\"sourceType\":\"CATALOG_SYSTEM\",\"sourceId\":\"xw-b\"},\"reason\":\"same system\"},"
        // EAM-1042 и EAM-2001 уже на разных gid: второй элемент связывает их через общий ключ, это конфликт
        + "{\"left\":{\"source\":\"EAM\",\"sourceType\":\"IT_SYSTEM\",\"sourceId\":\"EAM-1042\"},"
        + "\"right\":{\"source\":\"EAM\",\"sourceType\":\"IT_SYSTEM\",\"sourceId\":\"EAM-2001\"},\"reason\":\"merge attempt\"},"
        + "{\"left\":{\"source\":\"NOPE\",\"sourceType\":\"X\",\"sourceId\":\"1\"},"
        + "\"right\":{\"source\":\"SCM\",\"sourceType\":\"X\",\"sourceId\":\"2\"},\"reason\":\"bad source\"},"
        + "{\"left\":{\"source\":\"EAM\",\"sourceType\":\"IT_SYSTEM\",\"sourceId\":\"xw-c\"},"
        + "\"right\":{\"source\":\"SCM\",\"sourceType\":\"CATALOG_SYSTEM\",\"sourceId\":\"xw-d\"},\"reason\":\" \"}]";

    var r = admin("/admin/crosswalks", body);

    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body())
        .contains("\"index\":0,\"status\":\"APPLIED\"")
        .contains("\"index\":1,\"status\":\"CONFLICT\"")
        .contains("\"index\":2,\"status\":\"INVALID\"")
        .contains("\"index\":3,\"status\":\"INVALID\"");
    assertThat(scalarLong("select count(*) from admin_audit where operation = 'CROSSWALK' and actor = 'operator-1'"))
        .isGreaterThanOrEqualTo(4);
    // Повтор того же crosswalk идемпотентен.
    assertThat(admin("/admin/crosswalks", body).body()).contains("\"index\":0,\"status\":\"APPLIED\"");
  }

  @Test
  void scopeIsEnforced() throws Exception {
    assertThat(post("/admin/rebuild?confirm=true", null, null).statusCode()).isEqualTo(401);
    assertThat(post("/admin/rebuild?confirm=true", token(null), null).statusCode()).isEqualTo(403);
    assertThat(post("/admin/rebuild?confirm=true", token("architecture.read"), null).statusCode()).isEqualTo(403);
    assertThat(post("/admin/replay", token("architecture.read"), "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(403);
    assertThat(post("/admin/crosswalks", token("architecture.read"), "[]").statusCode()).isEqualTo(403);
    assertThat(post("/admin/rebuild?confirm=true", "garbage", null).statusCode()).isEqualTo(401);
  }

  @Test
  void concurrentAdminOperationIsRejectedWith409() throws Exception {
    seed();
    try (var ignored = lock.acquire()) {
      assertThat(admin("/admin/rebuild?confirm=true", null).statusCode()).isEqualTo(409);
      assertThat(admin("/admin/replay", "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(409);
    }
    assertThat(admin("/admin/replay", "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(200);
  }

  @Test
  void reconcileTombstoneSurvivesRebuild() throws Exception {
    seed();
    Clock clock = Clock.systemUTC();
    var eam = StubSources.eam(clock);
    eam.upsert(StubSources.TEAM, "TEAM-GONE", StubSources.fields("name", "Doomed Team"));
    // Прогон 1: объект есть в источнике; прогон 2: источник его уже не отдаёт, маркер запускает Reconciler.
    runSnapshot(eam);
    assertThat(active("TEAM-GONE")).isTrue();
    eam.delete(StubSources.TEAM, "TEAM-GONE");
    runSnapshot(eam);
    assertThat(active("TEAM-GONE")).as("tombstone applied by Reconciler").isFalse();
    assertThat(scalarLong("select count(*) from inbox_event where event_id like 'reconcile:%:TEAM/TEAM-GONE'"
        + " and status = 'PROJECTED'")).isEqualTo(1);
    List<String> before = dump();

    assertThat(admin("/admin/rebuild?confirm=true", null).statusCode()).isEqualTo(200);

    assertThat(dump()).isEqualTo(before);
    assertThat(active("TEAM-GONE")).isFalse();
  }

  @Test
  void reconcileProxiesToAdapterControlContext() throws Exception {
    seed();
    var eam = StubSources.eam(Clock.systemUTC());
    try (var server = new StubSourceServer(eam);
        var adapter = EamAdapter.create(EamAdapter.config(server.baseUri(), "s"), journal, rawStore)) {
      adapter.startWebhook(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), EAM_CONTROL_PORT));
      long markers = scalarLong("select count(*) from inbox_event where type = 'architecture.sync.snapshot-complete.v1'");

      var r = admin("/admin/reconcile/eam", null);

      assertThat(r.statusCode()).isEqualTo(202);
      assertThat(r.body()).contains("SNAPSHOT_COMPLETED");
      assertThat(scalarLong("select count(*) from inbox_event where type = 'architecture.sync.snapshot-complete.v1'"))
          .isEqualTo(markers + 1);
      assertThat(scalarLong("select count(*) from admin_audit where operation = 'RECONCILE' and status = 'SUCCEEDED'"))
          .isGreaterThanOrEqualTo(1);
    }
  }

  @Test
  void reconcileUnknownSourceIs404UnreachableAdapterIs502AndScopeIsEnforced() throws Exception {
    seed();
    assertThat(admin("/admin/reconcile/nope", null).statusCode()).isEqualTo(404);
    assertThat(post("/admin/reconcile/eam", token("architecture.read"), null).statusCode()).isEqualTo(403);
    assertThat(post("/admin/reconcile/eam", null, null).statusCode()).isEqualTo(401);
    assertThat(admin("/admin/reconcile/scm", null).statusCode()).isEqualTo(502);
  }

  /** Полный snapshot заглушки адаптером eam и обработка записанного, включая маркер. */
  private void runSnapshot(StubSource eam) throws Exception {
    try (var server = new StubSourceServer(eam);
        var adapter = EamAdapter.create(EamAdapter.config(server.baseUri(), "s"), journal, rawStore)) {
      adapter.poller().snapshotOnce();
    }
    processAllReceived();
  }

  private boolean active(String teamId) {
    return driver.executableQuery(
            "MATCH (r:SourceRecord {source: 'EAM', sourceType: 'TEAM', sourceId: $id}) RETURN r.active AS a")
        .withParameters(Map.of("id", teamId)).execute().records().stream().anyMatch(r -> r.get("a").asBoolean(false));
  }
}
