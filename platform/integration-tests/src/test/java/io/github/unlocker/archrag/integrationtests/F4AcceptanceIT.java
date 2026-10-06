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
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.Reconciler;
import io.github.unlocker.archrag.identityresolution.IdentityCandidates;
import io.github.unlocker.archrag.identityresolution.IdentityMapping;
import io.github.unlocker.archrag.identityresolution.SourceConflicts;
import io.github.unlocker.archrag.ingestionservice.IngestionServiceApplication;
import io.github.unlocker.archrag.mcpserver.McpServerApplication;
import io.github.unlocker.archrag.mcpserver.tools.ExplainProvenanceResult;
import io.github.unlocker.archrag.mcpserver.tools.GetAssetResult;
import io.github.unlocker.archrag.mcpserver.tools.SearchAssetsResult;
import io.github.unlocker.archrag.normalizer.CmdbMapper;
import io.github.unlocker.archrag.normalizer.DeployMapMapper;
import io.github.unlocker.archrag.normalizer.EamMapper;
import io.github.unlocker.archrag.normalizer.Normalizer;
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
import java.util.HashMap;
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
import org.neo4j.driver.SessionConfig;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Приёмка F4 (критерий 5): граф наполняется настоящим конвейером E1 (заглушки → адаптеры → журнал → диспетчер →
 * проектор), MCP-клиент по Streamable HTTP с Bearer-токеном вызывает {@code explain_provenance}. Критерий: после
 * tombstone в одном источнике provenance показывает его запись неактивной, а актив остаётся текущим, пока у другого
 * источника есть активная запись. В PoC продуктовые мапперы не пересекаются по типам, поэтому SCM на {@code IT_SYSTEM}
 * подменён тестовым {@link ScmItSystemMapper} через {@code @Primary} {@link EventProcessor}. Crosswalk утверждается
 * через {@code POST /admin/crosswalks} до первого события обеих записей.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = IngestionServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(F4AcceptanceIT.TestWiring.class)
class F4AcceptanceIT {

  private static final String SECRET = "f4-webhook-secret";
  private static final String BUCKET = "f4-acceptance-it-bucket";
  private static final SecretKey KEY = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(), "HmacSHA256");
  private static final Duration TIMEOUT = Duration.ofSeconds(90);
  private static final String READ = "architecture.read";
  private static final String IT_SYSTEM = "IT_SYSTEM";

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

  /** Токены ingestion-сервиса подписаны тестовым ключом; нормализатор знает SCM на {@code IT_SYSTEM}. */
  @TestConfiguration
  static class TestWiring {
    @Bean
    JwtDecoder jwtDecoder() {
      return NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
    }

    /**
     * Те же зависимости, что в {@code IngestionServiceConfiguration#eventProcessor}, но другой набор мапперов.
     * {@code @Primary}: диспетчер и replay получают именно его, bean overriding не включаем.
     */
    @Bean
    @Primary
    EventProcessor f4EventProcessor(
        EventJournal journal,
        GraphProjector projector,
        IdentityMapping identity,
        Reconciler reconciliation,
        IdentityCandidates candidates,
        SourceConflicts conflicts) {
      var normalizer = new Normalizer(
          List.of(new EamMapper(), new ScmItSystemMapper(), new CmdbMapper(), new DeployMapMapper()),
          projector::isActive);
      return new EventProcessor(journal, normalizer, identity, projector, reconciliation, candidates, conflicts);
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

  @Value("${local.server.port}") int port;
  @Autowired Driver driver;

  private final Map<SourceSystem, StubSource> stubs = new EnumMap<>(SourceSystem.class);
  private final Map<SourceSystem, SourceAdapter> adapters = new EnumMap<>(SourceSystem.class);
  private final List<StubSourceServer> servers = new ArrayList<>();
  private final McpTestJwt jwt = new McpTestJwt();
  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = new JsonMapper();
  private S3RawPayloadStore store;
  private ConfigurableApplicationContext mcpApp;
  private String mcpUrl;

  /** Актив из двух источников (s2–s4) и отдельный актив для tombstone мастера (s7). */
  private String twoSourceGid;
  private String masterGid;

  /** Граф: snapshot четырёх заглушек, два актива с crosswalk EAM↔SCM; затем MCP-приложение. */
  @BeforeAll
  void loadGraphAndStartMcp() throws Exception {
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
    awaitIdle();
    // Поиск идёт по FULLTEXT: ждём, пока все индексы построены.
    awaitTrue(() -> count("SHOW INDEXES YIELD state WHERE state <> 'ONLINE' RETURN count(*) AS c") == 0);

    try (var system = driver.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `mcp-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    mcpApp = startMcp();
    mcpUrl = "http://localhost:" + mcpApp.getBean(Environment.class).getProperty("local.server.port");

    // Crosswalk строго до первого события любой из записей, иначе CrosswalkConflictException.
    linkAndLoad("F4-1001", "Billing Hub F4");
    linkAndLoad("F4-1002", "Ledger Archive F4");
    twoSourceGid = gidByName("Billing Hub F4");
    masterGid = gidByName("Ledger Archive F4");
  }

  /** Утверждает crosswalk EAM↔SCM через admin API, затем кладёт записи в обе заглушки и ждёт проекции. */
  private void linkAndLoad(String id, String name) throws Exception {
    String body = "[{\"left\":{\"source\":\"EAM\",\"sourceType\":\"" + IT_SYSTEM + "\",\"sourceId\":\"" + id + "\"},"
        + "\"right\":{\"source\":\"SCM\",\"sourceType\":\"" + IT_SYSTEM + "\",\"sourceId\":\"" + id + "\"},"
        + "\"reason\":\"same system\"}]";
    HttpResponse<String> response = adminPost("/admin/crosswalks", body);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"APPLIED\"");

    stubs.get(SourceSystem.EAM).upsert(IT_SYSTEM, id, Map.of("name", name, "criticality", "HIGH"));
    stubs.get(SourceSystem.SCM).upsert(IT_SYSTEM, id, Map.of("name", name, "criticality", "LOW"));
    pollIncremental(SourceSystem.EAM);
    pollIncremental(SourceSystem.SCM);
    awaitIdle();
    assertThat(count("MATCH (:ITSystem {name: '" + name + "'}) RETURN count(*) AS c")).isEqualTo(1);
  }

  private void pollIncremental(SourceSystem system) {
    assertThat(adapters.get(system).poller().pollOnce().outcome()).isNotEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
  }

  /** Все события журнала обработаны и ни одно не в карантине. */
  private void awaitIdle() {
    awaitTrue(() -> scalarLong("select count(*) from inbox_event where status in "
        + "('RECEIVED','RETRYING','VALIDATED','NORMALIZED','RESOLVED')") == 0);
    assertThat(scalarLong("select count(*) from inbox_event where status = 'QUARANTINED'")).isZero();
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
        // Tool без записи отклоняется fail-closed: scope задан явно, иначе тест доступа пройдёт по неверной причине.
        "--archrag.mcp.security.tool-scopes.ping=" + READ,
        "--archrag.mcp.security.tool-scopes.search_assets=" + READ,
        "--archrag.mcp.security.tool-scopes.get_asset=" + READ,
        "--archrag.mcp.security.tool-scopes.explain_provenance=" + READ,
        "--archrag.neo4j.reader.uri=" + NEO4J.getBoltUrl(),
        "--archrag.neo4j.reader.username=mcp-reader",
        "--archrag.neo4j.reader.password=reader-pass",
        "--archrag.query.limits.max-depth=6",
        "--archrag.query.limits.max-nodes=500",
        "--archrag.query.limits.max-paths=50",
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
  void s1_toolsListContainsExplainProvenance() {
    try (McpSyncClient client = client(jwt.token(READ))) {
      client.initialize();
      assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("explain_provenance");
    }
  }

  @Test
  @Order(2)
  void s2_assetFromTwoSourcesShowsMasterAndConflict() {
    ExplainProvenanceResult r = explain(twoSourceGid, null);

    assertThat(r.gid()).isEqualTo(twoSourceGid);
    assertThat(r.type()).isEqualTo("ITSystem");
    assertThat(r.isCurrent()).isTrue();
    assertThat(r.authorities()).contains("EAM");
    assertThat(r.records()).extracting(ExplainProvenanceResult.RecordRef::source).containsExactlyInAnyOrder("EAM", "SCM");
    assertThat(r.records()).allSatisfy(rec -> {
      assertThat(rec.active()).isTrue();
      assertThat(rec.sourceType()).isEqualTo(IT_SYSTEM);
      assertThat(rec.sourceVersion()).isNotBlank();
      assertThat(Instant.parse(rec.fetchedAt())).isNotNull();
      assertThat(rec.contentHash()).isNotBlank();
      assertThat(rec.authority()).isNotBlank();
      assertThat(rec.confidence()).isNotNull();
    });
    var eam = record(r, "EAM");
    assertThat(eam.authoritative()).isTrue();
    assertThat(eam.authority()).isEqualTo("MASTER");
    assertThat(eam.sourceId()).isEqualTo("F4-1001");
    var scm = record(r, "SCM");
    assertThat(scm.authoritative()).isFalse();
    assertThat(scm.authority()).isEqualTo("SUPPLEMENTARY");
    assertThat(r.conflictState()).isEqualTo("OPEN");
    assertThat(scm.conflictProperties()).contains("criticality");

    // Значения свойств контракт provenance не отдаёт: значение мастера сверяем через get_asset.
    GetAssetResult card = get(Map.of("gid", twoSourceGid));
    assertThat(card.properties()).containsEntry("criticality", "HIGH");
  }

  @Test
  @Order(3)
  void s3_propertyFilterKeepsOnlyThatPropertyConflict() {
    ExplainProvenanceResult criticality = explain(twoSourceGid, "criticality");
    assertThat(criticality.property()).isEqualTo("criticality");
    assertThat(criticality.conflictState()).isEqualTo("OPEN");
    assertThat(record(criticality, "SCM").conflictProperties()).containsExactly("criticality");

    ExplainProvenanceResult name = explain(twoSourceGid, "name");
    assertThat(name.conflictState()).isEqualTo("NONE");
    assertThat(record(name, "SCM").conflictProperties()).isEmpty();
  }

  @Test
  @Order(4)
  void s4_tombstoneInOneSourceKeepsAssetCurrent() {
    // Критерий: tombstone в SCM (supplementary) через delete-путь инкрементального опроса.
    stubs.get(SourceSystem.SCM).delete(IT_SYSTEM, "F4-1001");
    pollIncremental(SourceSystem.SCM);
    awaitIdle();

    awaitTrue(() -> !record(explain(twoSourceGid, null), "SCM").active());
    ExplainProvenanceResult r = explain(twoSourceGid, null);

    assertThat(r.records()).hasSize(2);
    var scm = record(r, "SCM");
    assertThat(scm.active()).isFalse();
    assertThat(scm.deletedAt()).isNotBlank();
    assertThat(scm.conflictProperties()).isEmpty();
    var eam = record(r, "EAM");
    assertThat(eam.active()).isTrue();
    assertThat(eam.deletedAt()).isNull();
    assertThat(r.isCurrent()).isTrue();
    assertThat(r.deletedAt()).isNull();
    assertThat(r.conflictState()).isEqualTo("NONE");
    assertThat(r.records().getFirst().source()).isEqualTo("EAM");
    // Актив по-прежнему находится и открывается.
    assertThat(search(Map.of("query", "Billing Hub F4")).items()).extracting(SearchAssetsResult.AssetHit::gid)
        .contains(twoSourceGid);
    GetAssetResult card = get(Map.of("gid", twoSourceGid));
    assertThat(card.isCurrent()).isTrue();
  }

  @Test
  @Order(5)
  void s5_unknownGidIsToolErrorWithoutStacktrace() {
    try (McpSyncClient client = client(jwt.token(READ))) {
      var result = client.callTool(new McpSchema.CallToolRequest("explain_provenance",
          Map.of("gid", UUID.randomUUID().toString())));

      assertThat(result.isError()).isTrue();
      String text = ((McpSchema.TextContent) result.content().getFirst()).text();
      assertThat(text).contains("asset not found").doesNotContain("\tat ").doesNotContain("Exception");
    }
  }

  @Test
  @Order(6)
  void s6_accessWithoutScopeOrTokenIsDenied() throws Exception {
    String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"explain_provenance\",\"arguments\":{\"gid\":\"" + twoSourceGid + "\"}}}";

    HttpResponse<String> noScope = post(call, jwt.token("architecture.other"));
    assertThat(noScope.statusCode()).isEqualTo(403);
    assertThat(noScope.headers().firstValue("WWW-Authenticate"))
        .hasValueSatisfying(h -> assertThat(h).contains("insufficient_scope"));

    assertThat(post(call, null).statusCode()).isEqualTo(401);
  }

  @Test
  @Order(7)
  void s7_tombstoneOfMasterClosesAssetEvenWithActiveSupplementary() {
    // Фиксирует правило проектора: узел закрывается, когда не осталось активных MASTER-утверждений.
    assertThat(explain(masterGid, null).isCurrent()).isTrue();

    stubs.get(SourceSystem.EAM).delete(IT_SYSTEM, "F4-1002");
    pollIncremental(SourceSystem.EAM);
    awaitIdle();

    awaitTrue(() -> !explain(masterGid, null).isCurrent());
    ExplainProvenanceResult r = explain(masterGid, null);
    assertThat(r.deletedAt()).isNotBlank();
    assertThat(record(r, "EAM").active()).isFalse();
    assertThat(record(r, "SCM").active()).isTrue();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private McpSyncClient client(String token) {
    return McpClient.sync(HttpClientStreamableHttpTransport.builder(mcpUrl).endpoint("/mcp")
        .httpRequestCustomizer((builder, method, endpoint, body, context) ->
            builder.header("Authorization", "Bearer " + token))
        .build()).requestTimeout(Duration.ofSeconds(60)).build();
  }

  private ExplainProvenanceResult explain(String gid, String property) {
    Map<String, Object> args = new HashMap<>();
    args.put("gid", gid);
    if (property != null) {
      args.put("property", property);
    }
    return call("explain_provenance", args, ExplainProvenanceResult.class);
  }

  private static ExplainProvenanceResult.RecordRef record(ExplainProvenanceResult r, String source) {
    return r.records().stream().filter(x -> x.source().equals(source)).findFirst().orElseThrow();
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

  private String gidByName(String name) {
    awaitTrue(() -> !search(Map.of("query", name)).items().isEmpty());
    return search(Map.of("query", name)).items().getFirst().gid();
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

  /** Admin API ingestion-сервиса с токеном {@code architecture.admin}, подписанным тестовым ключом. */
  private HttpResponse<String> adminPost(String path, String body) throws Exception {
    var claims = JwtClaimsSet.builder().subject("operator-1").issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(600)).claim("scope", "architecture.admin");
    String token = new NimbusJwtEncoder(new ImmutableSecret<>(KEY))
        .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").header("Authorization", "Bearer " + token)
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private long count(String cypher) {
    return driver.executableQuery(cypher).execute().records().getFirst().get("c").asLong();
  }

  private static long scalarLong(String sql) {
    try (var c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var st = c.createStatement(); var rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
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
