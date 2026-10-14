package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adaptercore.ControlTokenValidator;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.assetadapter.AssetAdapter;
import io.github.unlocker.archrag.deploymapadapter.DeploymapAdapter;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Админский эндпоинт ingestion-сервиса на реальных Neo4j, PostgreSQL и S3: rebuild на чистой БД даёт эквивалентный
 * граф (критерий 4), replay идемпотентен, crosswalk-конфликт, scope (401/403), аудит и замок (409).
 */
@Testcontainers
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminEndpointIT {

  /** Admin-ресурс: issuer и audience токенов. */
  private static final String ADMIN_RESOURCE = McpTestJwt.RESOURCE;

  /** Подписанные тестовым ключом токены; публичный ключ отдаёт локальный JWKS, декодер собирает Spring Boot. */
  private static final McpTestJwt JWT = new McpTestJwt();

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

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
    // Декодер токенов собирает Spring Boot из этих свойств, как в проде: проверяются подпись, issuer и audience.
    r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", JWT::jwkSetUri);
    r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ADMIN_RESOURCE);
    r.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> ADMIN_RESOURCE);
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

  @AfterAll
  static void stopJwks() {
    JWT.close();
    if (eamApi != null) {
      eamApi.close();
    }
  }

  /** Заглушка eam-api живёт до конца класса: snapshot адаптера EAM обязан видеть её объекты, иначе reconcile их снесёт. */
  private static EamApiSupport eamApi;

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
    eamApi = new EamApiSupport(clock);
    try {
      for (var pair : List.<Object[]>of(
          new Object[] {eam, "eam"}, new Object[] {scm, "scm"}, new Object[] {cmdb, "cmdb"}, new Object[] {dm, "dm"})) {
        StubSource stub = (StubSource) pair[0];
        var server = new StubSourceServer(stub);
        servers.add(server);
        SourceAdapter adapter = switch ((String) pair[1]) {
          case "eam" -> eamApi.adapter(server.baseUri(), "s", journal, rawStore);
          case "scm" -> ScmAdapter.create(ScmAdapter.config(server.baseUri(), "s"), journal, rawStore);
          case "cmdb" -> AssetAdapter.create(AssetAdapter.config(server.baseUri(), "s"), journal, rawStore);
          default -> DeploymapAdapter.create(DeploymapAdapter.config(server.baseUri(), "s"), journal, rawStore);
        };
        adapters.add(adapter);
        adapter.poller().pollOnce();
      }
      // Вторая версия объекта: граф получает обновление поверх первой версии.
      eamApi.store.upsert("itsystems", 5101, StubSources.fields("name", "Payments Core v2", "eam_id", "EAM-1042",
          "status", "ACTIVE", "criticality", "HIGH", "solution", List.of(7201L), "ownerTeam", "TEAM-PAY"));
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
    return tokenFor(ADMIN_RESOURCE, scope);
  }

  private String tokenFor(String audience, String scope) {
    return scope == null ? JWT.tokenFor("operator-1", audience) : JWT.tokenFor("operator-1", audience, scope);
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

  /**
   * Канонический дамп графа: узлы и связи как отсортированные строки без elementId. {@code SyncRun} в дамп не
   * входит: итог прогона пишет маркер {@code snapshot-complete}, а replay/rebuild маркеры пропускают.
   */
  private List<String> dump() {
    List<String> out = new ArrayList<>();
    for (var r : driver.executableQuery("MATCH (n) WHERE NOT n:SyncRun RETURN labels(n) AS l, properties(n) AS p").execute().records()) {
      out.add("N " + sorted(r.get("l").asList()) + " " + new java.util.TreeMap<>(r.get("p").asMap()));
    }
    for (var r : driver.executableQuery(
        "MATCH (a)-[r]->(b) WHERE NOT a:SyncRun RETURN labels(a) AS al, properties(a) AS ap, type(r) AS t, properties(r) AS rp, labels(b) AS bl, properties(b) AS bp")
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
  void rebuildReproducesRelationsThatWereDeferredUntilTheirEndpointsArrived() throws Exception {
    seed();
    // Зависимость сервисов приходит в журнал раньше самих сервисов, значит, была отложена и достроена позже.
    assertThat(scalarLong(
        "select count(*) from inbox_event d where d.source_type = 'SERVICE_DEPENDENCY' and not exists ("
            + "select 1 from inbox_event s where s.source_type = 'SERVICE' and s.received_at < d.received_at)"))
        .isPositive();
    assertThat(driver.executableQuery("MATCH ()-[r:DEPENDS_ON]->() RETURN count(r) AS c").execute().records().get(0).get("c").asLong())
        .isPositive();
    assertThat(driver.executableQuery("MATCH (p:PendingRelation) RETURN count(p) AS c").execute().records().get(0).get("c").asLong())
        .isZero();
    List<String> before = dump();

    assertThat(admin("/admin/rebuild?confirm=true", null).statusCode()).isEqualTo(200);

    assertThat(dump()).isEqualTo(before);
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

  /** Копия события eam из журнала под новым id, переведённая в карантин: её raw валиден, повтор пройдёт. */
  private String quarantinedEamEvent() {
    JournalQuery q = new JournalQuery("urn:corp:eam", null, null, true, null, 1);
    StoredEvent stored = reader.read(q).getFirst();
    CanonicalEvent original = events.toEvent(stored, rawStore.get(stored.entry().payloadRef()));
    String id = "dlq-" + UUID.randomUUID();
    CanonicalEvent copy = new CanonicalEvent(id, original.source(), original.type(), original.subject(), original.time(),
        original.dataschema(), original.correlationid(), original.data());
    journal.append(copy, stored.entry().payloadRef(), stored.entry().syncRunId());
    journal.toDlq(copy.source(), id, "TEST_QUARANTINE", "put in quarantine by the test");
    return id;
  }

  private long openDlq(String eventId) throws Exception {
    return scalarLong("select count(*) from dlq_entry where event_id = ? and replayed_at is null", eventId);
  }

  @Test
  void replayClosesDlqOfOriginalEventWhenCopyIsProcessed() throws Exception {
    seed();
    String id = quarantinedEamEvent();
    assertThat(openDlq(id)).isEqualTo(1);

    assertThat(admin("/admin/replay", "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(200);

    assertThat(openDlq(id)).isZero();
    assertThat(scalarLong("select count(*) from dlq_entry where event_id = ? and replayed_at is not null", id))
        .isEqualTo(1);
    // Исходная строка журнала остаётся в карантине: её обработала копия.
    assertThat(journal.find("urn:corp:eam", id).orElseThrow().status()).isEqualTo(ProcessingStatus.QUARANTINED);
  }

  @Test
  void quarantineReleaseReturnsEventToRetryingClosesDlqAndAuditsIt() throws Exception {
    seed();
    String id = quarantinedEamEvent();
    String body = "{\"source\":\"urn:corp:eam\",\"eventId\":\"" + id + "\"}";

    var first = admin("/admin/quarantine/release", body);

    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(first.body()).contains("\"status\":\"RETRYING\"").contains("\"attempts\":0");
    var entry = journal.find("urn:corp:eam", id).orElseThrow();
    assertThat(entry.status()).isEqualTo(ProcessingStatus.RETRYING);
    assertThat(entry.attempts()).isZero();
    assertThat(openDlq(id)).isZero();
    assertThat(scalarLong("select count(*) from admin_audit where operation = 'QUARANTINE_RELEASE'"
        + " and status = 'SUCCEEDED' and actor = 'operator-1'")).isGreaterThanOrEqualTo(1);
    // Дальше событие обрабатывается, как обычный RETRYING (диспетчер в этом тесте выключен, вызываем процессор).
    Instant receivedAt = entry.receivedAt();
    StoredEvent stored = reader.read(new JournalQuery("urn:corp:eam", receivedAt, receivedAt.plusSeconds(1), false, null, 100))
        .stream()
        .filter(e -> e.entry().eventId().equals(id)).findFirst().orElseThrow();
    var result = processor.process(events.toEvent(stored, rawStore.get(stored.entry().payloadRef())),
        stored.entry().payloadRef());
    assertThat(result.status()).isIn(ProcessingStatus.PROJECTED, ProcessingStatus.IGNORED_OLD_VERSION);

    // Повторный вызов: событие уже не в карантине.
    assertThat(admin("/admin/quarantine/release", body).statusCode()).isEqualTo(409);
  }

  @Test
  void quarantineReleaseRejectsUnknownEventBadInputAndMissingRole() throws Exception {
    seed();
    String id = quarantinedEamEvent();
    String body = "{\"source\":\"urn:corp:eam\",\"eventId\":\"" + id + "\"}";

    assertThat(admin("/admin/quarantine/release", "{\"source\":\"urn:corp:eam\",\"eventId\":\"nope\"}").statusCode())
        .isEqualTo(404);
    assertThat(admin("/admin/quarantine/release", "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(400);
    assertThat(post("/admin/quarantine/release", null, body).statusCode()).isEqualTo(401);
    assertThat(post("/admin/quarantine/release", token("architecture.read"), body).statusCode()).isEqualTo(403);
    String noRole = JWT.tokenWithRoles(List.of(McpTestJwt.READER_ROLE), "archrag-demo", "operator-1", ADMIN_RESOURCE,
        "architecture.admin");
    assertThat(post("/admin/quarantine/release", noRole, body).statusCode()).isEqualTo(403);
    // Ни один отказ не тронул событие.
    assertThat(journal.find("urn:corp:eam", id).orElseThrow().status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(openDlq(id)).isEqualTo(1);
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
        // 5101 и 5102 (Payments Core, Ledger) уже на разных gid: второй элемент связывает их через общий ключ, это конфликт
        + "{\"left\":{\"source\":\"EAM\",\"sourceType\":\"IT_SYSTEM\",\"sourceId\":\"5101\"},"
        + "\"right\":{\"source\":\"EAM\",\"sourceType\":\"IT_SYSTEM\",\"sourceId\":\"5102\"},\"reason\":\"merge attempt\"},"
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

  /** Событие CMDB {@code COMPUTE_INSTANCE} с raw в S3 (формат адаптера), обработанное процессором. */
  private void cmdbHost(String id, String hostname) {
    String raw = "{\"operation\":\"UPSERT\",\"completeness\":\"COMPLETE\",\"updatedAt\":\"2026-10-01T10:00:00Z\","
        + "\"payload\":{\"hostname\":\"" + hostname + "\",\"kind\":\"VIRTUAL_MACHINE\"}}";
    var ref = rawStore.put("urn:corp:cmdb", raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var event = new CanonicalEvent("e-" + UUID.randomUUID(), "urn:corp:cmdb", CanonicalEvent.TYPE_ASSET_UPSERTED,
        "COMPUTE_INSTANCE/" + id, Instant.parse("2026-10-01T10:00:00Z"), "urn:corp:schema:asset-upserted:1", null,
        new io.github.unlocker.archrag.eventschemas.AssetEventData("COMPUTE_INSTANCE", id,
            new io.github.unlocker.archrag.eventschemas.SourceVersion("1"),
            Map.of("hostname", hostname, "kind", "VIRTUAL_MACHINE", "_completeness", "COMPLETE")));
    journal.append(event, ref, null);
    assertThat(processor.process(event, ref).status()).isEqualTo(ProcessingStatus.PROJECTED);
  }

  private static String approveBody(String left, String right, String survivor) {
    return "{\"left\":{\"source\":\"CMDB\",\"sourceType\":\"COMPUTE_INSTANCE\",\"sourceId\":\"" + left + "\"},"
        + "\"right\":{\"source\":\"CMDB\",\"sourceType\":\"COMPUTE_INSTANCE\",\"sourceId\":\"" + right + "\"},"
        + (survivor == null ? "" : "\"survivor\":\"" + survivor + "\",") + "\"reason\":\"same host\"}";
  }

  /** Другие записи, утверждающие тот же узел, что и запись {@code sourceId} (сама запись в результат не входит). */
  private List<String> coAssertersOf(String sourceId) {
    return driver.executableQuery(
            "MATCH (:SourceRecord {source: 'CMDB', sourceType: 'COMPUTE_INSTANCE', sourceId: $id})-[:ASSERTS]->(n)"
                + "<-[:ASSERTS]-(o:SourceRecord) RETURN o.sourceId AS id ORDER BY id")
        .withParameters(Map.of("id", sourceId)).execute().records().stream().map(r -> r.get("id").asString()).toList();
  }

  @Test
  void candidateApprovalMovesRecordReprojectsAuditsAndSurvivesRebuild() throws Exception {
    seed();
    String a = "ap-a-" + UUID.randomUUID();
    String b = "ap-b-" + UUID.randomUUID();
    String hostname = "approve-" + UUID.randomUUID() + ".corp";
    cmdbHost(a, hostname);
    cmdbHost(b, hostname);
    String body = approveBody(a, b, "LEFT");

    // Защита: токен, scope, параметры, неизвестный кандидат.
    assertThat(post("/admin/identity/candidates/approve", null, body).statusCode()).isEqualTo(401);
    assertThat(post("/admin/identity/candidates/approve", token("architecture.read"), body).statusCode()).isEqualTo(403);
    assertThat(admin("/admin/identity/candidates/approve", approveBody(a, b, null)).statusCode()).isEqualTo(400);
    assertThat(admin("/admin/identity/candidates/approve", approveBody(a, b, "BOTH")).statusCode()).isEqualTo(400);
    assertThat(admin("/admin/identity/candidates/approve", approveBody(a, a, "LEFT")).statusCode()).isEqualTo(400);
    assertThat(admin("/admin/identity/candidates/approve", approveBody(a, "no-such-" + UUID.randomUUID(), "LEFT")).statusCode())
        .isEqualTo(404);
    assertThat(coAssertersOf(b)).isEmpty();

    var ok = admin("/admin/identity/candidates/approve", body);

    assertThat(ok.statusCode()).isEqualTo(200);
    assertThat(ok.body()).contains("\"status\":\"REPROJECTED\"").contains("\"previousRetired\":true");
    // Запись b сразу утверждает узел a (рядом с ней a); прежний узел b закрыт и указывает на выжившего.
    assertThat(coAssertersOf(b)).containsExactly(a);
    assertThat(driver.executableQuery("MATCH (n) WHERE n.mergedInto IS NOT NULL AND n.isCurrent = false RETURN count(n) AS c")
        .execute().records().get(0).get("c").asLong()).isGreaterThanOrEqualTo(1);
    assertThat(scalarLong("select count(*) from admin_audit where operation = 'IDENTITY_APPROVE' and status = 'SUCCEEDED'"
        + " and actor = 'operator-1'")).isEqualTo(1);
    assertThat(scalarLong("select count(*) from identity_candidate where status = 'RESOLVED' and resolved_by = 'operator-1'"
        + " and (left_id = ? or right_id = ?)", a, a)).isEqualTo(1);
    // Повтор: кандидат уже закрыт.
    assertThat(admin("/admin/identity/candidates/approve", body).statusCode()).isEqualTo(409);

    // Rebuild воспроизводит результат: gid берётся из identity_mapping, отдельной логики нет.
    assertThat(admin("/admin/rebuild?confirm=true", null).statusCode()).isEqualTo(200);
    assertThat(coAssertersOf(b)).containsExactly(a);
    assertThat(driver.executableQuery("MATCH (n) WHERE n.mergedInto IS NOT NULL RETURN count(n) AS c").execute().records()
        .get(0).get("c").asLong()).isZero();
  }

  @Test
  void tokenForForeignAudienceIsUnauthorized() throws Exception {
    String foreign = tokenFor("https://other.test/api", "architecture.admin");

    assertThat(post("/admin/rebuild?confirm=true", foreign, null).statusCode()).isEqualTo(401);
    assertThat(post("/admin/replay", foreign, "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(401);
  }

  @Test
  void tokenOfForeignClientIsUnauthorizedEvenWithAdminAudienceAndScope() throws Exception {
    String consoleToken = JWT.tokenForClient("archrag-admin-console", "operator-1", ADMIN_RESOURCE, "architecture.admin");
    assertThat(post("/admin/rebuild?confirm=true", consoleToken, null).statusCode()).isEqualTo(401);
    assertThat(post("/admin/replay", consoleToken, "{\"source\":\"urn:corp:eam\"}").statusCode()).isEqualTo(401);
    String allowed = JWT.tokenForClient("archrag-demo", "operator-1", ADMIN_RESOURCE, "architecture.admin");
    assertThat(post("/admin/replay", allowed, "{\"source\":\"urn:corp:eam\",\"receivedTo\":\"1970-01-02T00:00:00Z\"}")
        .statusCode()).isEqualTo(200);
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
        var adapter = eamApi.adapter(server.baseUri(), "s", journal, rawStore)) {
      adapter.startWebhook(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), EAM_CONTROL_PORT),
          new ControlTokenValidator(ADMIN_RESOURCE, URI.create(JWT.jwkSetUri()), ADMIN_RESOURCE, McpTestJwt.ADMIN_ROLE));
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
  void adminRoleIsRequiredInAdditionToScope() throws Exception {
    seed();
    String noRole = JWT.tokenWithRoles(List.of(McpTestJwt.READER_ROLE), "archrag-demo", "operator-1", ADMIN_RESOURCE,
        "architecture.admin");
    assertThat(post("/admin/reconcile/eam", noRole, null).statusCode()).isEqualTo(403);
    assertThat(post("/admin/rebuild?confirm=true", noRole, null).statusCode()).isEqualTo(403);
  }

  @Test
  void controlSnapshotRejectsMissingTokenAndMissingRoleButAcceptsAdmin() throws Exception {
    seed();
    var eam = StubSources.eam(Clock.systemUTC());
    try (var server = new StubSourceServer(eam);
        var adapter = eamApi.adapter(server.baseUri(), "s", journal, rawStore)) {
      int controlPort = freePort();
      adapter.startWebhook(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), controlPort),
          new ControlTokenValidator(ADMIN_RESOURCE, URI.create(JWT.jwkSetUri()), ADMIN_RESOURCE, McpTestJwt.ADMIN_ROLE));
      long markers = scalarLong("select count(*) from inbox_event where type = 'architecture.sync.snapshot-complete.v1'");
      URI uri = URI.create("http://127.0.0.1:" + controlPort + "/control/snapshot");

      assertThat(http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
      String noRole = JWT.tokenWithRoles(List.of(McpTestJwt.READER_ROLE), "archrag-demo", "operator-1", ADMIN_RESOURCE,
          "architecture.admin");
      assertThat(http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + noRole)
          .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(403);
      assertThat(scalarLong("select count(*) from inbox_event where type = 'architecture.sync.snapshot-complete.v1'"))
          .isEqualTo(markers);
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
        var adapter = eamApi.adapter(server.baseUri(), "s", journal, rawStore)) {
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
