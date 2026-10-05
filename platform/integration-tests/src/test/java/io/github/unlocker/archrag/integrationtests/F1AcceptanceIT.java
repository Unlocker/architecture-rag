package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.adaptercore.PollResult;
import io.github.unlocker.archrag.adaptercore.SourceAdapter;
import io.github.unlocker.archrag.assetadapter.AssetAdapter;
import io.github.unlocker.archrag.deploymapadapter.DeploymapAdapter;
import io.github.unlocker.archrag.eamadapter.EamAdapter;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventjournal.S3RawPayloadStore;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.mcpserver.McpServerApplication;
import io.github.unlocker.archrag.mcpserver.tools.GetAssetResult;
import io.github.unlocker.archrag.mcpserver.tools.SearchAssetsResult;
import io.github.unlocker.archrag.scmadapter.ScmAdapter;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSourceServer;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
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
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Приёмка F1 (критерий 5): граф наполняется настоящим конвейером E1 из {@link StubSources#seeded}, затем MCP-клиент
 * по Streamable HTTP с Bearer-токеном вызывает {@code search_assets} → {@code get_asset}. MCP-сервер — второе
 * Spring-приложение в этом же тесте, читает тот же Neo4j через {@code archrag.neo4j.reader.*}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(F1AcceptanceIT.TestJwtDecoder.class)
class F1AcceptanceIT {

  private static final String SECRET = "f1-webhook-secret";
  private static final String BUCKET = "f1-acceptance-it-bucket";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final String READ = "architecture.read";

  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");
  static final GenericContainer<?> S3 = ContainersSmokeIT.s3Container();

  // При PER_CLASS Spring-контекст создаётся раньше расширения Testcontainers: контейнеры стартуют здесь,
  // как в SyncAcceptanceIT; остановит их Ryuk.
  static {
    NEO4J.start();
    POSTGRES.start();
    S3.start();
  }

  /** Декодер токенов ingestion-сервиса: admin-эндпоинты в этом тесте не вызываются. */
  @TestConfiguration
  static class TestJwtDecoder {
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
    // Адреса control-эндпоинтов нужны только для разрешения плейсхолдеров; reconcile здесь не вызывается.
    for (String s : List.of("eam", "scm", "cmdb", "deploymap")) {
      r.add("archrag.adapters." + s + ".control-url", () -> "http://127.0.0.1:1/control/snapshot");
    }
  }

  @Autowired Driver driver;

  private final Map<SourceSystem, SourceAdapter> adapters = new EnumMap<>(SourceSystem.class);
  private final List<StubSourceServer> servers = new ArrayList<>();
  private final McpTestJwt jwt = new McpTestJwt();
  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = new JsonMapper();
  private S3RawPayloadStore store;
  private ConfigurableApplicationContext mcpApp;
  private String mcpUrl;

  /** Граф: полный snapshot четырёх заглушек через адаптеры и работающий диспетчер; затем MCP-приложение. */
  @BeforeAll
  void loadGraphAndStartMcp() throws SQLException {
    Clock clock = Clock.systemUTC();
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    var journal = new PostgresEventJournal(ds);
    store = S3RawPayloadStore.create(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333)),
        ContainersSmokeIT.S3_ACCESS_KEY, ContainersSmokeIT.S3_SECRET_KEY, BUCKET);
    store.ensureBucket();
    StubSources.seeded(clock).forEach((system, stub) -> {
      var server = new StubSourceServer(stub);
      servers.add(server);
      adapters.put(system, switch (system) {
        case EAM -> EamAdapter.create(EamAdapter.config(server.baseUri(), SECRET), journal, store);
        case SCM -> ScmAdapter.create(ScmAdapter.config(server.baseUri(), SECRET), journal, store);
        case CMDB -> AssetAdapter.create(AssetAdapter.config(server.baseUri(), SECRET), journal, store);
        case DEPLOY_MAP -> DeploymapAdapter.create(DeploymapAdapter.config(server.baseUri(), SECRET), journal, store);
      });
    });
    for (SourceSystem s : SourceSystem.values()) {
      assertThat(adapters.get(s).poller().pollOnce().outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    }
    awaitTrue(() -> {
      try {
        return scalarLong("select count(*) from inbox_event where status in "
            + "('RECEIVED','RETRYING','VALIDATED','NORMALIZED','RESOLVED')") == 0
            && count("MATCH (:ITSystem {name: 'Payments Core'}) RETURN count(*) AS c") > 0;
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    });
    assertThat(scalarLong("select count(*) from inbox_event where status = 'QUARANTINED'")).isZero();

    // Поиск идёт по FULLTEXT: ждём, пока все индексы построены.
    awaitTrue(() -> count("SHOW INDEXES YIELD state WHERE state <> 'ONLINE' RETURN count(*) AS c") == 0);

    try (var system = driver.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `mcp-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    mcpApp = startMcp();
    mcpUrl = "http://localhost:" + mcpApp.getBean(Environment.class).getProperty("local.server.port");
  }

  /**
   * Свойства передаются аргументами командной строки: в classpath два {@code application.yml} (ingestion-service
   * и mcp-server), какой из них загрузится, не определено, поэтому всё нужное MCP-серверу задано явно.
   */
  private ConfigurableApplicationContext startMcp() {
    List<String> args = List.of(
        "--server.port=0",
        // На classpath есть JDBC/Flyway ingestion-сервиса; MCP-серверу PostgreSQL не нужен.
        "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
            + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "--management.endpoints.web.exposure.include=health",
        "--spring.application.name=arch-rag",
        "--spring.ai.mcp.server.name=arch-rag",
        "--spring.ai.mcp.server.version=0.1.0",
        "--spring.ai.mcp.server.protocol=STATELESS",
        "--spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp",
        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" + jwt.jwkSetUri(),
        "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + McpTestJwt.RESOURCE,
        "--spring.security.oauth2.resourceserver.jwt.audiences=" + McpTestJwt.RESOURCE,
        "--archrag.mcp.security.resource-uri=" + McpTestJwt.RESOURCE,
        "--archrag.mcp.security.tool-scopes.ping=" + READ,
        "--archrag.mcp.security.tool-scopes.search_assets=" + READ,
        "--archrag.mcp.security.tool-scopes.get_asset=" + READ,
        "--archrag.neo4j.reader.uri=" + NEO4J.getBoltUrl(),
        "--archrag.neo4j.reader.username=mcp-reader",
        "--archrag.neo4j.reader.password=reader-pass",
        "--archrag.query.limits.max-depth=6",
        "--archrag.query.limits.max-nodes=500",
        "--archrag.query.limits.max-paths=50",
        // Первый запрос на холодном Neo4j (прогрев индексов) в CI не укладывается в 5 с продакшен-бюджета.
        "--archrag.query.limits.timeout=30s",
        "--archrag.query.limits.max-response-bytes=512KB");
    return new SpringApplicationBuilder(McpServerApplication.class).run(args.toArray(String[]::new));
  }

  @AfterAll
  void stopAll() {
    if (mcpApp != null) {
      mcpApp.close();
    }
    jwt.close();
    adapters.values().forEach(SourceAdapter::close);
    servers.forEach(StubSourceServer::close);
    if (store != null) {
      store.close();
    }
  }

  // ---- сценарии ----------------------------------------------------------------------------

  @Test
  @Order(1)
  void s1_toolsListContainsSearchAndGet() {
    try (McpSyncClient client = client(jwt.token(READ))) {
      client.initialize();
      assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("search_assets", "get_asset");
    }
  }

  @Test
  @Order(2)
  void s2_searchByNameIsFulltext() {
    SearchAssetsResult result = search(Map.of("query", "Payments Core"));

    assertThat(result.items()).isNotEmpty();
    var first = result.items().getFirst();
    assertThat(first.type()).isEqualTo("ITSystem");
    assertThat(first.name()).isEqualTo("Payments Core");
    assertThat(first.matchType()).isEqualTo("FULLTEXT");
    assertThat(first.gid()).isNotBlank();
    assertThat(first.score()).isPositive();
    assertThat(first.sources()).contains("EAM");
    assertThat(Instant.parse(first.lastSeenAt())).isNotNull();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @Order(3)
  void s3_searchBySourceIdIsExact() {
    String gid = paymentsCoreGid();

    SearchAssetsResult result = search(Map.of("query", "EAM-1042"));

    assertThat(result.items()).isNotEmpty();
    assertThat(result.items().getFirst().matchType()).isEqualTo("EXACT");
    assertThat(result.items().getFirst().gid()).isEqualTo(gid);
  }

  @Test
  @Order(4)
  void s4_typeAndEnvironmentFilter() {
    SearchAssetsResult result = search(Map.of("query", "payments", "types", List.of("Service"), "environment", "prod"));

    assertThat(result.items()).extracting(SearchAssetsResult.AssetHit::name).contains("payments-api");
    assertThat(result.items()).extracting(SearchAssetsResult.AssetHit::type).containsOnly("Service");
  }

  @Test
  @Order(5)
  void s5_limitTruncates() {
    SearchAssetsResult all = search(Map.of("query", "api"));
    assertThat(all.items()).hasSizeGreaterThanOrEqualTo(2);

    SearchAssetsResult limited = search(Map.of("query", "api", "limit", 1));

    assertThat(limited.items()).hasSize(1);
    assertThat(limited.truncated()).isTrue();
  }

  @Test
  @Order(6)
  void s6_getAssetCardWithRelations() {
    String gid = paymentsCoreGid();

    GetAssetResult card = get(Map.of("gid", gid, "includeRelations", true));

    assertThat(card.gid()).isEqualTo(gid);
    assertThat(card.type()).isEqualTo("ITSystem");
    assertThat(card.isCurrent()).isTrue();
    assertThat(card.firstSeenAt()).isNotBlank();
    assertThat(card.lastSeenAt()).isNotBlank();
    assertThat(card.sources()).anySatisfy(s -> {
      assertThat(s.source()).isEqualTo("EAM");
      assertThat(s.sourceId()).isEqualTo("EAM-1042");
      assertThat(s.authority()).isEqualTo("MASTER");
      assertThat(s.active()).isTrue();
    });
    assertThat(card.relations()).anySatisfy(r -> {
      assertThat(r.relationType()).isEqualTo("DECOMPOSED_INTO");
      assertThat(r.direction()).isEqualTo("OUT");
      assertThat(r.type()).isEqualTo("Service");
      assertThat(r.name()).isEqualTo("payments-api");
    });
    assertThat(card.relations()).anySatisfy(r -> {
      assertThat(r.relationType()).isEqualTo("OWNED_BY");
      assertThat(r.name()).isEqualTo("Payments Team");
    });

    // gid соседа пригоден для следующего get_asset.
    var service = card.relations().stream().filter(r -> "payments-api".equals(r.name())).findFirst().orElseThrow();
    GetAssetResult next = get(Map.of("gid", service.gid()));
    assertThat(next.gid()).isEqualTo(service.gid());
    assertThat(next.type()).isEqualTo("Service");
  }

  @Test
  @Order(7)
  void s7_unknownGidIsToolErrorWithoutStacktrace() {
    try (McpSyncClient client = client(jwt.token(READ))) {
      var result = client.callTool(new McpSchema.CallToolRequest("get_asset",
          Map.of("gid", UUID.randomUUID().toString())));

      assertThat(result.isError()).isTrue();
      String text = ((McpSchema.TextContent) result.content().getFirst()).text();
      assertThat(text).isNotBlank().doesNotContain("\tat ").doesNotContain("Exception");
    }
  }

  @Test
  @Order(8)
  void s8_accessWithoutScopeOrTokenIsDenied() throws Exception {
    String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"search_assets\",\"arguments\":{\"query\":\"Payments Core\"}}}";

    HttpResponse<String> noScope = post(call, jwt.token("architecture.other"));
    assertThat(noScope.statusCode()).isEqualTo(403);
    assertThat(noScope.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).contains("insufficient_scope"));

    assertThat(post(call, null).statusCode()).isEqualTo(401);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private McpSyncClient client(String token) {
    return McpClient.sync(HttpClientStreamableHttpTransport.builder(mcpUrl).endpoint("/mcp")
        .httpRequestCustomizer((builder, method, endpoint, body, context) ->
            builder.header("Authorization", "Bearer " + token))
        .build()).requestTimeout(Duration.ofSeconds(60)).build();
  }

  private SearchAssetsResult search(Map<String, Object> args) {
    return call("search_assets", args, SearchAssetsResult.class);
  }

  private GetAssetResult get(Map<String, Object> args) {
    return call("get_asset", args, GetAssetResult.class);
  }

  private <T> T call(String tool, Map<String, Object> args, Class<T> type) {
    try (McpSyncClient client = client(jwt.token(READ))) {
      var result = client.callTool(new McpSchema.CallToolRequest(tool, args));
      String text = ((McpSchema.TextContent) result.content().getFirst()).text();
      assertThat(result.isError()).as(text).isFalse();
      return json.readValue(text, type);
    }
  }

  private String paymentsCoreGid() {
    return search(Map.of("query", "Payments Core")).items().getFirst().gid();
  }

  private HttpResponse<String> post(String body, String token) throws Exception {
    var request = HttpRequest.newBuilder(URI.create(mcpUrl + "/mcp"))
        .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
        .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private long count(String cypher) {
    return driver.executableQuery(cypher).execute().records().getFirst().get("c").asLong();
  }

  private static long scalarLong(String sql) throws SQLException {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var st = c.createStatement(); var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  /** Опрос с таймаутом: Awaitility в проект не входит, новую зависимость в задаче не вводим. */
  private static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TIMEOUT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within " + TIMEOUT);
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
