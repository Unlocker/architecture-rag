package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.adminconsole.AdminConsoleApplication;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * API синхронизации консоли на реальных PostgreSQL (миграции ingestion) и Neo4j: роль {@code archrag_console_ro}
 * только читает, фильтры и пагинация работают, payload в ответах нет.
 */
@Testcontainers
class SyncConsoleIT {

  static final String ADMIN = "architecture.admin";
  static final String AUDIENCE = "urn:archrag:console";
  static final String RO_PASSWORD = "ro-pass";

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
  @Container static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  static Driver admin;
  static McpTestJwt jwt;
  static ConfigurableApplicationContext console;

  @BeforeAll
  static void start() throws SQLException {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    JournalMigrations.apply(ds);
    try (Connection c = ds.getConnection(); var st = c.createStatement()) {
      // Пароль роли задаёт стенд/тест, не миграция.
      st.execute("ALTER ROLE archrag_console_ro PASSWORD '" + RO_PASSWORD + "'");
      seedPostgres(st);
    }

    admin = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    try (var system = admin.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `console-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    Neo4jSchema.apply(admin);
    admin.executableQuery(
            "CREATE (:SyncRun {runId: 'run-old', adapter: 'EAM', status: 'FAILED', startedAt: datetime('2026-01-01T00:00:00Z')}) "
                + "CREATE (:SyncRun {runId: 'run-new', adapter: 'EAM', status: 'SUCCEEDED', startedAt: datetime('2026-01-02T00:00:00Z')})")
        .execute();

    jwt = new McpTestJwt();
    console = startConsole();
  }

  private static void seedPostgres(java.sql.Statement st) throws SQLException {
    String eam = "urn:corp:eam";
    for (int i = 1; i <= 5; i++) {
      event(st, eam, "eam-" + i, i <= 3 ? "PROJECTED" : i == 4 ? "QUARANTINED" : "RECEIVED",
          "2026-01-0" + i + "T10:00:00Z", i == 4 ? "MAPPING_FAILED" : null);
    }
    event(st, "urn:corp:scm", "scm-1", "PROJECTED", "2026-01-03T12:00:00Z", null);
    st.execute("INSERT INTO dlq_entry (source, event_id, reason, error_code, payload_key, created_at)"
        + " VALUES ('urn:corp:eam', 'eam-4', 'cannot map', 'MAPPING_FAILED', 'raw/eam/h4', '2026-01-04T10:01:00Z')");
    st.execute("INSERT INTO consumer_checkpoint (consumer, source, cursor, updated_at)"
        + " VALUES ('projector', 'urn:corp:eam', 'eam-3', '2026-01-03T10:00:05Z')");
    st.execute("INSERT INTO identity_candidate (left_source, left_type, left_id, right_source, right_type, right_id,"
        + " label_family, matched, score, first_seen_at, updated_at) VALUES ('EAM','system','1','CMDB','ci','9',"
        + " 'ITSystem', '[{\"feature\":\"name\",\"value\":\"pay\"}]'::jsonb, 0.90, '2026-01-01T00:00:00Z', '2026-01-02T00:00:00Z')");
    st.execute("INSERT INTO source_conflict (gid, property, dissent_source, dissent_type, dissent_id, dissent_value,"
        + " master_value, master_source, master_type, master_id, status, opened_at, updated_at) VALUES"
        + " ('11111111-1111-1111-1111-111111111111','criticality','CMDB','ci','9','LOW','HIGH','EAM','system','1',"
        + "'OPEN','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z'),"
        + " ('22222222-2222-2222-2222-222222222222','owner','CMDB','ci','8','a','b','EAM','system','2',"
        + "'RESOLVED','2026-01-01T00:00:00Z','2026-01-02T00:00:00Z')");
    st.execute("INSERT INTO admin_audit (operation, actor, request, status, result, started_at, finished_at) VALUES"
        + " ('replay','alice','{\"secret\":\"x\"}'::jsonb,'OK','{\"replayed\":2}'::jsonb,'2026-01-05T00:00:00Z','2026-01-05T00:00:03Z'),"
        + " ('rebuild','bob','{}'::jsonb,'FAILED',NULL,'2026-01-06T00:00:00Z',NULL),"
        + " ('bigop','carol','{}'::jsonb,'OK', jsonb_build_object('blob', repeat('x', 6000)), '2026-01-07T00:00:00Z', NULL)");
  }

  private static void event(java.sql.Statement st, String source, String id, String status, String at, String code)
      throws SQLException {
    st.execute("INSERT INTO inbox_event (source, event_id, type, source_type, source_id, source_version, schema_version,"
        + " status, attempts, error_code, payload_key, payload_hash, received_at, updated_at) VALUES ('" + source + "','"
        + id + "','t','system','" + id + "','1','1','" + status + "', 1, "
        + (code == null ? "NULL" : "'" + code + "'") + ", 'raw/" + id + "', 'hash-" + id + "', '" + at + "', '" + at + "')");
  }

  @AfterAll
  static void stop() {
    if (console != null) {
      console.close();
    }
    if (jwt != null) {
      jwt.close();
    }
    if (admin != null) {
      admin.close();
    }
  }

  private static ConfigurableApplicationContext startConsole() {
    List<String> args = List.of(
        "--server.port=0",
        "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "--spring.ai.mcp.server.enabled=false",
        "--spring.web.resources.add-mappings=false",
        "--management.endpoints.web.exposure.include=health",
        "--spring.application.name=arch-rag-admin-console",
        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + jwt.jwkSetUri(),
        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + McpTestJwt.RESOURCE,
        "--spring.security.oauth2.resourceserver.jwt.audiences=" + AUDIENCE,
        "--archrag.console.resource-uri=" + AUDIENCE,
        "--archrag.console-pg.url=" + POSTGRES.getJdbcUrl(),
        "--archrag.console-pg.username=archrag_console_ro",
        "--archrag.console-pg.password=" + RO_PASSWORD,
        "--archrag.neo4j.reader.uri=" + NEO4J.getBoltUrl(),
        "--archrag.neo4j.reader.username=console-reader",
        "--archrag.neo4j.reader.password=reader-pass",
        "--archrag.query.limits.max-depth=6",
        "--archrag.query.limits.max-nodes=500",
        "--archrag.query.limits.max-paths=50",
        "--archrag.query.limits.timeout=5s",
        "--archrag.query.limits.max-response-bytes=512KB");
    return new SpringApplicationBuilder(AdminConsoleApplication.class).run(args.toArray(String[]::new));
  }

  private static ResponseEntity<String> get(String path, String token) {
    int port = console.getEnvironment().getProperty("local.server.port", Integer.class);
    return RestClient.builder()
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build()
        .get()
        .uri(URI.create("http://localhost:" + port + path))
        .headers(h -> {
          if (token != null) {
            h.setBearerAuth(token);
          }
        })
        .retrieve()
        .toEntity(String.class);
  }

  private static JsonNode ok(String path) {
    var res = get(path, jwt.tokenFor("admin-1", AUDIENCE, ADMIN));
    assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
    return new JsonMapper().readTree(res.getBody());
  }

  private static List<String> ids(JsonNode page, String field) {
    return page.get("items").valueStream().map(n -> n.get(field).asString()).toList();
  }

  // --- роль ---

  @Test
  void consoleRoleCannotWriteOrReadOtherTables() throws SQLException {
    try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "archrag_console_ro", RO_PASSWORD);
        var st = c.createStatement()) {
      assertThat(st.executeQuery("SELECT count(*) FROM inbox_event").next()).isTrue();
      assertThatThrownBy(() -> st.execute("INSERT INTO dlq_entry (source, event_id, reason, error_code, created_at)"
              + " VALUES ('urn:corp:eam','eam-1','r','c', now())"))
          .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> st.execute("UPDATE inbox_event SET status = 'PROJECTED'"))
          .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> st.execute("DELETE FROM admin_audit"))
          .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
      assertThatThrownBy(() -> st.executeQuery("SELECT * FROM identity_mapping"))
          .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
    }
  }

  @Test
  void migrationIsIdempotentForExistingRole() {
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());

    JournalMigrations.apply(ds);
  }

  // --- безопасность ---

  @Test
  void withoutScopeApiIsForbiddenAndWithoutTokenUnauthorized() {
    assertThat(get("/api/sync/events", null).getStatusCode().value()).isEqualTo(401);
    assertThat(get("/api/sync/events", jwt.tokenFor("u", AUDIENCE, "architecture.read")).getStatusCode().value())
        .isEqualTo(403);
    assertThat(get("/api/audit", jwt.tokenFor("u", AUDIENCE, "architecture.read")).getStatusCode().value())
        .isEqualTo(403);
  }

  @Test
  void sqlReadsAreRecordedInReadAudit() {
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("archrag.audit.console");
    logger.addAppender(appender);
    try {
      ok("/api/sync/events?source=eam&size=2");
    } finally {
      logger.detachAppender(appender);
    }

    var kv = new java.util.HashMap<String, Object>();
    appender.list.getLast().getKeyValuePairs().forEach(p -> kv.put(p.key, p.value));
    assertThat(kv.get("endpoint")).isEqualTo("GET /api/sync/events");
    assertThat(kv.get("templates")).isEqualTo(List.of("sql:inbox_event"));
    assertThat(kv.get("rows")).isEqualTo(2L);
  }

  @Test
  void oversizedAuditResultIsNotReturned() {
    JsonNode big = ok("/api/audit?operation=bigop").get("items").get(0);

    assertThat(big.get("result").isNull()).isTrue();
    assertThat(big.get("resultTruncated").asBoolean()).isTrue();
    assertThat(ok("/api/audit?operation=replay").get("items").get(0).get("resultTruncated").asBoolean()).isFalse();
  }

  @Test
  void connectionsDefaultToReadOnlyTransactions() throws SQLException {
    // init-SQL пула консоли: второй рубеж поверх GRANT.
    var ds = console.getBean(javax.sql.DataSource.class);
    try (Connection pooled = ds.getConnection();
        var rs = pooled.createStatement().executeQuery("SHOW default_transaction_read_only")) {
      rs.next();
      assertThat(rs.getString(1)).isEqualTo("on");
    }
  }

  // --- журнал ---

  @Test
  void eventsFilterBySourceStatusAndPeriod() {
    assertThat(ids(ok("/api/sync/events?source=eam"), "eventId"))
        .containsExactly("eam-5", "eam-4", "eam-3", "eam-2", "eam-1");
    assertThat(ids(ok("/api/sync/events?source=scm"), "eventId")).containsExactly("scm-1");
    assertThat(ids(ok("/api/sync/events?status=QUARANTINED"), "eventId")).containsExactly("eam-4");
    assertThat(ids(ok("/api/sync/events?source=eam&from=2026-01-02T00:00:00Z&to=2026-01-04T00:00:00Z"), "eventId"))
        .containsExactly("eam-3", "eam-2");
    assertThat(ok("/api/sync/events?source=cmdb").get("total").asLong()).isZero();
  }

  @Test
  void eventsPaginateAndSizeIsCapped() {
    JsonNode first = ok("/api/sync/events?source=eam&page=0&size=2");
    JsonNode second = ok("/api/sync/events?source=eam&page=1&size=2");
    JsonNode last = ok("/api/sync/events?source=eam&page=2&size=2");

    assertThat(ids(first, "eventId")).containsExactly("eam-5", "eam-4");
    assertThat(ids(second, "eventId")).containsExactly("eam-3", "eam-2");
    assertThat(ids(last, "eventId")).containsExactly("eam-1");
    assertThat(first.get("total").asLong()).isEqualTo(5);
    assertThat(get("/api/sync/events?size=201", jwt.tokenFor("u", AUDIENCE, ADMIN)).getStatusCode().value())
        .isEqualTo(400);
  }

  @Test
  void eventMetadataHasRefAndHashButNoPayload() {
    JsonNode item = ok("/api/sync/events?source=eam&status=QUARANTINED").get("items").get(0);

    assertThat(item.get("payloadRef").asString()).isEqualTo("raw/eam-4");
    assertThat(item.get("contentHash").asString()).isEqualTo("hash-eam-4");
    assertThat(item.get("errorCode").asString()).isEqualTo("MAPPING_FAILED");
    assertThat(item.get("attempts").asInt()).isEqualTo(1);
    assertThat(item.get("receivedAt").asString()).isEqualTo("2026-01-04T10:00:00Z");
    assertThat(item.propertyNames()).doesNotContain("payload", "data");
  }

  @Test
  void eventDetailCarriesDlqHistoryAndUnknownIsNotFound() {
    JsonNode detail = ok("/api/sync/events/eam/eam-4");

    assertThat(detail.get("event").get("status").asString()).isEqualTo("QUARANTINED");
    assertThat(detail.get("dlqEntries")).hasSize(1);
    assertThat(detail.get("dlqEntries").get(0).get("reason").asString()).isEqualTo("cannot map");
    assertThat(get("/api/sync/events/eam/nope", jwt.tokenFor("u", AUDIENCE, ADMIN)).getStatusCode().value())
        .isEqualTo(404);
  }

  @Test
  void dlqListsEntriesWithReason() {
    JsonNode dlq = ok("/api/sync/dlq?source=eam");

    assertThat(dlq.get("total").asLong()).isEqualTo(1);
    assertThat(dlq.get("items").get(0).get("errorCode").asString()).isEqualTo("MAPPING_FAILED");
    assertThat(dlq.get("items").get(0).get("replayedAt").isNull()).isTrue();
    assertThat(ok("/api/sync/dlq?replayed=true").get("total").asLong()).isZero();
  }

  // --- источники ---

  @Test
  void sourcesShowCheckpointCountsLastProjectedLagAndLatestSyncRun() {
    JsonNode sources = ok("/api/sync/sources");

    assertThat(sources).hasSize(4);
    JsonNode eam = sources.get(0);
    assertThat(eam.get("source").asString()).isEqualTo("eam");
    assertThat(eam.get("checkpoints").get(0).get("cursor").asString()).isEqualTo("eam-3");
    assertThat(eam.get("eventsByStatus").get("PROJECTED").asLong()).isEqualTo(3);
    assertThat(eam.get("eventsByStatus").get("QUARANTINED").asLong()).isEqualTo(1);
    assertThat(eam.get("lastProjectedAt").asString()).isEqualTo("2026-01-03T10:00:00Z");
    assertThat(eam.get("lagSeconds").asLong()).isPositive();
    assertThat(eam.get("lastSyncRun").get("runId").asString()).isEqualTo("run-new");
    assertThat(eam.get("lastSyncRun").get("status").asString()).isEqualTo("SUCCEEDED");
    JsonNode deploymap = sources.get(3);
    assertThat(deploymap.get("source").asString()).isEqualTo("deploymap");
    assertThat(deploymap.get("eventsByStatus").get("PROJECTED").asLong()).isZero();
    assertThat(deploymap.get("lagSeconds").isNull()).isTrue();
    assertThat(deploymap.get("lastSyncRun").isNull()).isTrue();
  }

  // --- identity и аудит ---

  @Test
  void conflictsAndCandidatesAreListedAndFiltered() {
    // По умолчанию только OPEN; RESOLVED — явным фильтром.
    assertThat(ok("/api/identity/conflicts").get("total").asLong()).isEqualTo(1);
    assertThat(ok("/api/identity/conflicts?status=RESOLVED").get("total").asLong()).isEqualTo(1);
    JsonNode open = ok("/api/identity/conflicts?status=OPEN");
    assertThat(open.get("total").asLong()).isEqualTo(1);
    assertThat(open.get("items").get(0).get("property").asString()).isEqualTo("criticality");
    assertThat(ok("/api/identity/conflicts?status=RESOLVED&gid=22222222-2222-2222-2222-222222222222").get("total").asLong())
        .isEqualTo(1);

    JsonNode candidates = ok("/api/identity/candidates?status=OPEN");
    assertThat(candidates.get("total").asLong()).isEqualTo(1);
    assertThat(candidates.get("items").get(0).get("matched").get(0).get("feature").asString()).isEqualTo("name");
    assertThat(candidates.get("items").get(0).get("score").asDouble()).isEqualTo(0.9);
    assertThat(ok("/api/identity/candidates?status=REJECTED").get("total").asLong()).isZero();
  }

  @Test
  void auditListsOperationsWithRequestParameters() {
    JsonNode all = ok("/api/audit");
    assertThat(ids(all, "operation")).containsExactly("bigop", "rebuild", "replay");
    assertThat(ids(ok("/api/audit?operation=replay"), "actor")).containsExactly("alice");
    assertThat(ok("/api/audit?status=FAILED").get("total").asLong()).isEqualTo(1);
    JsonNode replay = ok("/api/audit?operation=replay").get("items").get(0);
    assertThat(replay.get("result").get("replayed").asInt()).isEqualTo(2);
    assertThat(replay.get("request").get("secret").asString()).isEqualTo("x");
  }
}
