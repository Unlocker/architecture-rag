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
import io.github.unlocker.archrag.mcpserver.tools.SearchAssetsResult;
import io.github.unlocker.archrag.mcpserver.tools.TraceDependenciesResult;
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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.assertj.core.api.InstanceOfAssertFactories;
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
 * Приёмка F3 (критерии 5 и 7): граф наполняется настоящим конвейером E1 из {@link StubSources#seeded}, затем
 * MCP-клиент по Streamable HTTP с Bearer-токеном вызывает {@code trace_dependencies} в режимах trace и impact и
 * замеряет latency на стороне клиента. MCP-сервер — второе Spring-приложение в этом же тесте.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(F3AcceptanceIT.TestJwtDecoder.class)
class F3AcceptanceIT {

  private static final String SECRET = "f3-webhook-secret";
  private static final String BUCKET = "f3-acceptance-it-bucket";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final String READ = "architecture.read";

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
  /** Первый вызов impact после старта MCP-сервера (холодный), для s8. */
  private Duration coldImpact;

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
        "--archrag.mcp.security.tool-scopes.trace_dependencies=" + READ,
        "--archrag.neo4j.reader.uri=" + NEO4J.getBoltUrl(),
        "--archrag.neo4j.reader.username=mcp-reader",
        "--archrag.neo4j.reader.password=reader-pass",
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

  private static final Set<String> TRACE_TYPES =
      Set.of("DEPENDS_ON", "DECOMPOSED_INTO", "HAS_DEPLOYMENT", "RUNS_ON", "HOSTED_ON", "OWNED_BY");
  private static final Duration BUDGET_P95 = Duration.ofSeconds(5);
  private static final Duration BUDGET_COLD = Duration.ofSeconds(15);
  private static final int WARM_CALLS = 10;

  @Test
  @Order(1)
  void s1_toolsListContainsTraceDependencies() {
    try (McpSyncClient client = client(jwt.token(READ))) {
      client.initialize();
      var tool = client.listTools().tools().stream().filter(t -> "trace_dependencies".equals(t.name()))
          .findFirst().orElseThrow();
      assertThat(tool.inputSchema()).extracting(m -> m.get("properties"), InstanceOfAssertFactories.MAP)
          .containsKeys("gid", "mode", "environment", "direction", "maxDepth");
    }
  }

  @Test
  @Order(2)
  void s2_traceDownstreamFromPaymentsCore() {
    TraceDependenciesResult r = trace(Map.of("gid", paymentsCoreGid(), "direction", "downstream"));

    assertThat(r.mode()).isEqualTo("trace");
    assertThat(r.truncated()).isFalse();
    assertThat(r.affected()).isEmpty();
    String vm = gidOf(r, "ComputeInstance", "vm-pay-01.prod.example.org");
    gidOf(r, "Service", "payments-api");
    String ledger = gidOf(r, "Service", "ledger-api");
    assertThat(r.relations()).extracting(TraceDependenciesResult.RelationRef::type).isSubsetOf(TRACE_TYPES);
    var toVm = r.paths().stream().filter(p -> p.nodes().getLast().equals(vm)).findFirst().orElseThrow();
    assertThat(relationTypes(toVm)).containsExactly("DECOMPOSED_INTO", "HAS_DEPLOYMENT", "RUNS_ON");
    var toLedger = r.paths().stream().filter(p -> p.nodes().getLast().equals(ledger)).findFirst().orElseThrow();
    assertThat(relationTypes(toLedger)).endsWith("DEPENDS_ON");
    assertThat(r.relations()).extracting(TraceDependenciesResult.RelationRef::type).doesNotContain("HOSTED_ON");

    // Критерий 7 и в режиме trace: пути идут от старта, у связей есть источник; свежесть — где источник узел.
    assertThat(r.paths()).allSatisfy(p -> assertThat(p.nodes().getFirst()).isEqualTo(r.startGid()));
    assertThat(r.relations()).allSatisfy(x -> assertThat(x.assertedBySource()).isNotBlank());
    assertThat(r.relations().stream().filter(x -> x.sourceFetchedAt() != null))
        .isNotEmpty().allSatisfy(x -> assertThat(x.stale()).isFalse());
  }

  @Test
  @Order(3)
  void s3_traceUpstreamFromVmLimitedByTypeAndDepth() {
    String vm = vmGid();

    TraceDependenciesResult r = trace(Map.of("gid", vm, "direction", "upstream",
        "relationTypes", List.of("RUNS_ON"), "maxDepth", 1));

    assertThat(r.paths()).hasSize(1);
    var path = r.paths().getFirst();
    assertThat(path.nodes()).hasSize(2).first().isEqualTo(vm);
    assertThat(relationTypes(path)).containsExactly("RUNS_ON");
    assertThat(node(r, path.nodes().getLast()).label()).isEqualTo("Deployment");
    assertThat(r.truncated()).isFalse();
  }

  @Test
  @Order(4)
  void s4_impactFromVmReturnsExplainablePaths() {
    String vm = vmGid();

    TraceDependenciesResult r = impact(Map.of("gid", vm));

    assertThat(r.mode()).isEqualTo("impact");
    assertThat(r.direction()).isEqualTo("upstream");
    assertThat(r.truncated()).isFalse();
    String payments = gidOf(r, "Service", "payments-api");
    String core = gidOf(r, "ITSystem", "Payments Core");
    assertThat(r.affected()).contains(payments, core);
    assertThat(r.nodes()).extracting(TraceDependenciesResult.NodeRef::name).doesNotContain("ledger-api");

    // Ответ — пути, а не плоский список: цепочка до Payments Core целиком.
    var toCore = r.paths().stream().filter(p -> p.nodes().getLast().equals(core)).findFirst().orElseThrow();
    assertThat(toCore.nodes()).hasSize(4).first().isEqualTo(vm);
    assertThat(node(r, toCore.nodes().get(1)).label()).isEqualTo("Deployment");
    assertThat(toCore.nodes().get(2)).isEqualTo(payments);
    assertThat(relationTypes(toCore)).containsExactly("RUNS_ON", "HAS_DEPLOYMENT", "DECOMPOSED_INTO");

    // Критерий 7: каждый шаг каждого пути объясним источником и свежестью, узлы несут lastSeenAt.
    for (var path : r.paths()) {
      assertThat(path.relations()).hasSize(path.nodes().size() - 1);
      for (var key : path.relations()) {
        var step = relation(r, key);
        assertThat(step.assertedBySource()).isNotBlank();
        assertThat(step.assertedByType()).isNotBlank();
        assertThat(step.assertedById()).isNotBlank();
      }
      for (String gid : path.nodes()) {
        assertThat(Instant.parse(node(r, gid).lastSeenAt())).isNotNull();
      }
    }
    // stale=false и sourceFetchedAt проверяем только там, где запись-источник — узел (ограничение описания tool).
    var withSourceNode = r.relations().stream().filter(x -> x.sourceFetchedAt() != null).toList();
    assertThat(withSourceNode).isNotEmpty().allSatisfy(x -> {
      assertThat(x.stale()).isFalse();
      assertThat(Instant.parse(x.sourceFetchedAt())).isNotNull();
    });
  }

  @Test
  @Order(5)
  void s5_impactEnvironmentFilter() {
    String vm = vmGid();
    var all = impact(Map.of("gid", vm));

    var prod = impact(Map.of("gid", vm, "environment", "prod"));
    assertThat(prod.affected()).containsExactlyInAnyOrderElementsOf(all.affected());

    var other = impact(Map.of("gid", vm, "environment", "no-such-env"));
    assertThat(other.paths()).isEmpty();
    assertThat(other.affected()).isEmpty();
    assertThat(other.environment()).isEqualTo("no-such-env");
  }

  @Test
  @Order(6)
  void s6_invalidInputIsToolErrorWithoutStacktrace() {
    String vm = vmGid();

    assertToolError(Map.of("gid", UUID.randomUUID().toString()), "asset not found");
    assertToolError(Map.of("gid", "not-a-uuid"), "gid must be a UUID");
    assertToolError(Map.of("gid", vm, "mode", "foo"), "mode must be trace or impact");
    assertToolError(Map.of("gid", vm, "mode", "impact", "direction", "downstream"),
        "direction must be upstream for mode impact");
    assertToolError(Map.of("gid", vm, "maxDepth", 7), "maxDepth must be in 1..6");
    assertToolError(Map.of("gid", vm, "environment", "prod"), "environment is supported only for mode impact");
  }

  @Test
  @Order(7)
  void s7_accessWithoutScopeOrTokenIsDenied() throws Exception {
    String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"trace_dependencies\",\"arguments\":{\"gid\":\"" + UUID.randomUUID() + "\"}}}";

    HttpResponse<String> noScope = post(call, jwt.token("architecture.other"));
    assertThat(noScope.statusCode()).isEqualTo(403);
    assertThat(noScope.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(h -> assertThat(h).contains("insufficient_scope"));

    assertThat(post(call, null).statusCode()).isEqualTo(401);
  }

  @Test
  @Order(8)
  void s8_latencyWithinBudget() {
    // Зависит от s4: холодный вызов impact замеряется там (первый impact после старта MCP).
    String core = paymentsCoreGid();
    String vm = vmGid();
    assertThat(coldImpact).as("cold impact call is measured in s4").isNotNull();

    List<Duration> trace = new ArrayList<>();
    List<Duration> impact = new ArrayList<>();
    for (int i = 0; i < WARM_CALLS; i++) {
      long start = System.nanoTime();
      trace(Map.of("gid", core, "direction", "downstream"));
      trace.add(Duration.ofNanos(System.nanoTime() - start));
      start = System.nanoTime();
      impact(Map.of("gid", vm));
      impact.add(Duration.ofNanos(System.nanoTime() - start));
    }

    System.out.printf("F3 latency cold impact=%s; trace n=%d p50=%s p95=%s max=%s; impact n=%d p50=%s p95=%s max=%s%n",
        coldImpact, trace.size(), percentile(trace, 50), percentile(trace, 95), Collections.max(trace),
        impact.size(), percentile(impact, 50), percentile(impact, 95), Collections.max(impact));
    assertThat(coldImpact).isLessThanOrEqualTo(BUDGET_COLD);
    assertThat(percentile(trace, 95)).isLessThanOrEqualTo(BUDGET_P95);
    assertThat(percentile(impact, 95)).isLessThanOrEqualTo(BUDGET_P95);
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

  private String vmGid() {
    return search(Map.of("query", "vm-pay-01", "types", List.of("ComputeInstance"))).items().getFirst().gid();
  }

  private TraceDependenciesResult trace(Map<String, Object> args) {
    return call("trace_dependencies", args, TraceDependenciesResult.class);
  }

  /** impact через клиента; первый вызов запоминается как холодный. */
  private TraceDependenciesResult impact(Map<String, Object> args) {
    Map<String, Object> full = new java.util.HashMap<>(args);
    full.put("mode", "impact");
    long start = System.nanoTime();
    TraceDependenciesResult result = trace(full);
    if (coldImpact == null) {
      coldImpact = Duration.ofNanos(System.nanoTime() - start);
    }
    return result;
  }

  private static String text(McpSchema.CallToolResult result) {
    return ((McpSchema.TextContent) result.content().getFirst()).text();
  }

  /** Вызов, который должен вернуть tool error без stacktrace. */
  private void assertToolError(Map<String, Object> args, String expectedMessage) {
    try (McpSyncClient client = client(jwt.token(READ))) {
      var result = client.callTool(new McpSchema.CallToolRequest("trace_dependencies", args));

      assertThat(result.isError()).isTrue();
      assertThat(text(result)).contains(expectedMessage).doesNotContain("\tat ").doesNotContain("Exception");
    }
  }

  private static TraceDependenciesResult.NodeRef node(TraceDependenciesResult r, String gid) {
    return r.nodes().stream().filter(n -> n.gid().equals(gid)).findFirst().orElseThrow();
  }

  private static String gidOf(TraceDependenciesResult r, String label, String name) {
    return r.nodes().stream().filter(n -> label.equals(n.label()) && name.equals(n.name())).findFirst()
        .orElseThrow(() -> new AssertionError(label + " " + name + " not in " + r.nodes())).gid();
  }

  private static TraceDependenciesResult.RelationRef relation(TraceDependenciesResult r,
      TraceDependenciesResult.RelationKey key) {
    return r.relations().stream()
        .filter(x -> x.type().equals(key.type()) && x.from().equals(key.from()) && x.to().equals(key.to()))
        .findFirst().orElseThrow();
  }

  private static List<String> relationTypes(TraceDependenciesResult.TracePath path) {
    return path.relations().stream().map(TraceDependenciesResult.RelationKey::type).toList();
  }

  private static Duration percentile(List<Duration> samples, int p) {
    var sorted = samples.stream().sorted().toList();
    return sorted.get((int) Math.ceil(p / 100.0 * sorted.size()) - 1);
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
