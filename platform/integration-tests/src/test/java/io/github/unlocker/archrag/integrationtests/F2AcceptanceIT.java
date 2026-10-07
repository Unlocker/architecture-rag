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
import io.github.unlocker.archrag.mcpserver.tools.FindRuntimeFootprintResult;
import io.github.unlocker.archrag.mcpserver.tools.FindRuntimeFootprintResult.ComputeFootprint;
import io.github.unlocker.archrag.mcpserver.tools.FindRuntimeFootprintResult.DeploymentFootprint;
import io.github.unlocker.archrag.mcpserver.tools.FindRuntimeFootprintResult.ServiceFootprint;
import io.github.unlocker.archrag.scmadapter.ScmAdapter;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.DeployMapFormat;
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
import java.util.function.Predicate;
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
 * Приёмка F2 (критерий 5): граф наполняется настоящим конвейером E1 из {@link StubSources#seeded}, MCP-клиент
 * по Streamable HTTP с Bearer-токеном вызывает {@code find_runtime_footprint}; затем запись deploy map меняется
 * в заглушке, после {@code pollOnce} ответ tool меняется. MCP-сервер — второе Spring-приложение в этом же тесте,
 * читает тот же Neo4j через {@code archrag.neo4j.reader.*}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(F2AcceptanceIT.TestJwtDecoder.class)
class F2AcceptanceIT {

  private static final String SECRET = "f2-webhook-secret";
  private static final String BUCKET = "f2-acceptance-it-bucket";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final String READ = "architecture.read";
  private static final String TOOL = "find_runtime_footprint";
  private static final String PROD = "prod";
  private static final String DEPLOYMENT = "dep-payments-api-prod";

  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);
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
    r.add("spring.security.oauth2.resourceserver.jwt.audiences", () -> "http://unused.invalid");
    // Адреса control-эндпоинтов нужны только для разрешения плейсхолдеров; reconcile здесь не вызывается.
    for (String s : List.of("eam", "scm", "cmdb", "deploymap")) {
      r.add("archrag.adapters." + s + ".control-url", () -> "http://127.0.0.1:1/control/snapshot");
    }
  }

  @Autowired Driver driver;

  private final Map<SourceSystem, SourceAdapter> adapters = new EnumMap<>(SourceSystem.class);
  private final Map<SourceSystem, StubSource> stubs = new EnumMap<>(SourceSystem.class);
  private final List<StubSourceServer> servers = new ArrayList<>();
  private final McpTestJwt jwt = new McpTestJwt();
  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = new JsonMapper();
  private S3RawPayloadStore store;
  private ConfigurableApplicationContext mcpApp;
  private String mcpUrl;
  private String systemGid;

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
      stubs.put(system, stub);
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
    awaitPipelineIdle();
    assertThat(scalarLong("select count(*) from inbox_event where status = 'QUARANTINED'")).isZero();
    assertThat(count("MATCH (:ITSystem {name: 'Payments Core'}) RETURN count(*) AS c")).isPositive();
    awaitTrue(() -> count("SHOW INDEXES YIELD state WHERE state <> 'ONLINE' RETURN count(*) AS c") == 0);

    // gid системы читаем из графа: search_assets в этом эпике нет. Это чтение, не запись.
    systemGid = driver.executableQuery("MATCH (s:ITSystem {name: 'Payments Core'}) RETURN s.gid AS gid")
        .execute().records().getFirst().get("gid").asString();

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
        "--archrag.mcp.security.tool-scopes." + TOOL + "=" + READ,
        "--archrag.neo4j.reader.uri=" + NEO4J.getBoltUrl(),
        "--archrag.neo4j.reader.username=mcp-reader",
        "--archrag.neo4j.reader.password=reader-pass",
        "--archrag.query.stale-after=P7D",
        "--archrag.query.limits.max-depth=6",
        "--archrag.query.limits.max-nodes=500",
        "--archrag.query.limits.max-paths=50",
        // Продовый бюджет: application.yml mcp-server на classpath теста не гарантирован, поэтому задан явно.
        "--archrag.query.limits.timeout=5s",
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
  void s1_toolsListContainsFindRuntimeFootprint() {
    try (McpSyncClient client = client(jwt.token(READ))) {
      client.initialize();
      assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains(TOOL);
    }
  }

  @Test
  @Order(2)
  void s2_footprintOfSeededProd() {
    FindRuntimeFootprintResult result = footprint(systemGid, PROD);

    assertThat(result.systemGid()).isEqualTo(systemGid);
    assertThat(result.systemName()).isEqualTo("Payments Core");
    assertThat(result.systemStale()).isFalse();
    assertThat(result.warnings()).isEmpty();
    assertThat(result.truncated()).isFalse();
    assertThat(result.systemSources()).extracting(FindRuntimeFootprintResult.FactSource::source).contains("EAM");
    assertThat(Instant.parse(result.systemLastSeenAt())).isNotNull();

    ServiceFootprint service = service(result, "payments-api");
    assertThat(Instant.parse(service.lastSeenAt())).isNotNull();
    assertThat(service.sources()).isNotEmpty();
    assertThat(service.stale()).isFalse();
    DeploymentFootprint deployment = onlyDeployment(service);
    assertThat(deployment.name()).isEqualTo("payments-api");
    assertThat(deployment.version()).isEqualTo("1.4.2");
    assertThat(Instant.parse(deployment.lastSeenAt())).isNotNull();
    assertThat(deployment.sources()).extracting(FindRuntimeFootprintResult.FactSource::source)
        .contains("DEPLOYMAP");
    assertThat(deployment.hasDeployment().source()).isNotBlank();
    assertThat(deployment.hasDeployment().sourceId()).isNotBlank();

    assertThat(deployment.compute()).hasSize(1);
    ComputeFootprint vm = deployment.compute().getFirst();
    assertThat(vm.type()).isEqualTo("VirtualMachine");
    assertThat(vm.hostname()).isEqualTo("vm-pay-01.prod.example.org");
    assertThat(Instant.parse(vm.lastSeenAt())).isNotNull();
    assertThat(vm.sources()).extracting(FindRuntimeFootprintResult.FactSource::source).contains("CMDB");
    assertThat(vm.runsOn().source()).isNotBlank();
    assertThat(vm.runsOn().sourceId()).isEqualTo(DEPLOYMENT);
  }

  @Test
  @Order(3)
  void s3_environmentWithoutDeploymentsKeepsServiceWithEmptyDeployments() {
    FindRuntimeFootprintResult result = footprint(systemGid, "test");

    assertThat(service(result, "payments-api").deployments()).isEmpty();
    assertThat(result.truncated()).isFalse();
  }

  @Test
  @Order(4)
  void s4_deployMapChangeIsVisibleAfterSync() {
    String initialSeenAt = onlyDeployment(service(footprint(systemGid, PROD), "payments-api")).lastSeenAt();
    // Новый хост сначала попадает в CMDB, затем deploy map на него ссылается.
    stubs.get(SourceSystem.CMDB).upsert(StubSources.COMPUTE_INSTANCE, "vm-pay-02", StubSources.fields(
        "hostname", "vm-pay-02.prod.example.org", "kind", "VIRTUAL_MACHINE", "state", "RUNNING",
        "environment", "prod"));
    poll(SourceSystem.CMDB);
    awaitPipelineIdle();

    stubs.get(SourceSystem.DEPLOY_MAP).upsert(DeployMapFormat.DEPLOYMENT, DEPLOYMENT, StubSources.fields(
        "format", DeployMapFormat.FORMAT,
        "name", "payments-api",
        "service", "svc-payments-api",
        "environment", "prod",
        "chart", "payments-api",
        "chartVersion", "1.5.0",
        "hosts", List.of("vm-pay-02")));
    poll(SourceSystem.DEPLOY_MAP);

    FindRuntimeFootprintResult changed = awaitFootprint(PROD, r -> {
      var deployments = service(r, "payments-api").deployments();
      return deployments.size() == 1 && "1.5.0".equals(deployments.getFirst().version())
          && deployments.getFirst().compute().stream()
              .anyMatch(c -> "vm-pay-02.prod.example.org".equals(c.hostname()));
    });

    DeploymentFootprint deployment = onlyDeployment(service(changed, "payments-api"));
    assertThat(deployment.version()).isEqualTo("1.5.0");
    assertThat(deployment.compute()).extracting(ComputeFootprint::hostname)
        .contains("vm-pay-02.prod.example.org");
    assertThat(Instant.parse(deployment.lastSeenAt())).isAfter(Instant.parse(initialSeenAt));
    assertThat(changed.warnings()).isEmpty();
    assertThat(changed.truncated()).isFalse();
  }

  /** Дефект E1: смена {@code hosts} не закрывает прежний {@code RUNS_ON}; сценарий не ослаблен, а выделен. */
  @Test
  @Order(5)
  void s4b_replacedHostIsNoLongerReturned() {
    DeploymentFootprint deployment = onlyDeployment(service(footprint(systemGid, PROD), "payments-api"));

    assertThat(deployment.compute()).extracting(ComputeFootprint::hostname)
        .containsExactly("vm-pay-02.prod.example.org");
  }

  @Test
  @Order(6)
  void s5_deletedDeploymentDisappearsButServiceStays() {
    stubs.get(SourceSystem.DEPLOY_MAP).delete(DeployMapFormat.DEPLOYMENT, DEPLOYMENT);
    poll(SourceSystem.DEPLOY_MAP);

    FindRuntimeFootprintResult result = awaitFootprint(PROD, r -> service(r, "payments-api").deployments().isEmpty());

    assertThat(service(result, "payments-api").deployments()).isEmpty();
    assertThat(result.systemGid()).isEqualTo(systemGid);
  }

  @Test
  @Order(7)
  void s6_invalidAndUnknownGidAreToolErrorsWithoutEchoOrStacktrace() {
    try (McpSyncClient client = client(jwt.token(READ))) {
      var notUuid = client.callTool(new McpSchema.CallToolRequest(TOOL, Map.of("systemGid", "not-a-uuid-secret")));
      assertThat(notUuid.isError()).isTrue();
      String text = ((McpSchema.TextContent) notUuid.content().getFirst()).text();
      assertThat(text).contains("systemGid must be a UUID").doesNotContain("not-a-uuid-secret")
          .doesNotContain("\tat ").doesNotContain("Exception");

      String unknown = UUID.randomUUID().toString();
      var missing = client.callTool(new McpSchema.CallToolRequest(TOOL, Map.of("systemGid", unknown)));
      assertThat(missing.isError()).isTrue();
      text = ((McpSchema.TextContent) missing.content().getFirst()).text();
      assertThat(text).contains("asset not found").doesNotContain(unknown)
          .doesNotContain("\tat ").doesNotContain("Exception");
    }
  }

  @Test
  @Order(8)
  void s7_accessWithoutScopeOrTokenIsDenied() throws Exception {
    String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"" + TOOL + "\",\"arguments\":{\"systemGid\":\"" + systemGid + "\"}}}";

    HttpResponse<String> noScope = post(call, jwt.token("architecture.other"));
    assertThat(noScope.statusCode()).isEqualTo(403);
    assertThat(noScope.headers().firstValue("WWW-Authenticate"))
        .hasValueSatisfying(h -> assertThat(h).contains("insufficient_scope"));

    assertThat(post(call, null).statusCode()).isEqualTo(401);
  }

  // ---- helpers -----------------------------------------------------------------------------

  private void poll(SourceSystem system) {
    assertThat(adapters.get(system).poller().pollOnce().outcome()).isEqualTo(PollResult.Outcome.ADVANCED);
  }

  private static ServiceFootprint service(FindRuntimeFootprintResult result, String name) {
    return result.services().stream().filter(s -> name.equals(s.name())).findFirst()
        .orElseThrow(() -> new AssertionError("service " + name + " not in " + result.services()));
  }

  private static DeploymentFootprint onlyDeployment(ServiceFootprint service) {
    assertThat(service.deployments()).hasSize(1);
    return service.deployments().getFirst();
  }

  private McpSyncClient client(String token) {
    return McpClient.sync(HttpClientStreamableHttpTransport.builder(mcpUrl).endpoint("/mcp")
        .httpRequestCustomizer((builder, method, endpoint, body, context) ->
            builder.header("Authorization", "Bearer " + token))
        .build()).requestTimeout(Duration.ofSeconds(60)).build();
  }

  private FindRuntimeFootprintResult footprint(String gid, String environment) {
    try (McpSyncClient client = client(jwt.token(READ))) {
      var result = client.callTool(new McpSchema.CallToolRequest(TOOL,
          Map.of("systemGid", gid, "environment", environment)));
      String text = ((McpSchema.TextContent) result.content().getFirst()).text();
      assertThat(result.isError()).as(text).isFalse();
      return json.readValue(text, FindRuntimeFootprintResult.class);
    }
  }

  /** Опрос tool с таймаутом до выполнения условия; возвращает ответ, на котором оно выполнилось. */
  private FindRuntimeFootprintResult awaitFootprint(String environment, Predicate<FindRuntimeFootprintResult> cond) {
    FindRuntimeFootprintResult[] last = new FindRuntimeFootprintResult[1];
    awaitTrue(() -> {
      last[0] = footprint(systemGid, environment);
      return cond.test(last[0]);
    });
    return last[0];
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

  private void awaitPipelineIdle() {
    awaitTrue(() -> {
      try {
        return scalarLong("select count(*) from inbox_event where status in "
            + "('RECEIVED','RETRYING','VALIDATED','NORMALIZED','RESOLVED')") == 0;
      } catch (SQLException e) {
        throw new IllegalStateException(e);
      }
    });
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
