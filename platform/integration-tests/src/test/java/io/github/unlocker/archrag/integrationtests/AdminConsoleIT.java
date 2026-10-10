package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.adminconsole.AdminConsoleApplication;
import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.Environment;
import io.github.unlocker.archrag.canonicalmodel.node.EnvironmentClass;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.ResultKind;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.ConsoleTemplates;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Админ-консоль на реальном Neo4j Community: API читает граф, засеянный продовым проектором и схемой, только
 * через reader-пользователя и только через именованные шаблоны.
 */
@Testcontainers
class AdminConsoleIT {

  static final String ADMIN = "architecture.admin";
  static final String AUDIENCE = "urn:archrag:console";
  static final String GID_PAY = "11111111-1111-1111-1111-111111111111";
  static final String GID_BILLING = "22222222-2222-2222-2222-222222222222";
  static final String GID_DEP = "33333333-3333-3333-3333-333333333333";
  static final String GID_PROD = "44444444-4444-4444-4444-444444444444";

  @Container static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);

  static Driver admin;
  static Driver reader;
  static McpTestJwt jwt;
  static ConfigurableApplicationContext console;
  static ConfigurableApplicationContext tinyConsole;

  @BeforeAll
  static void start() {
    admin = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    try (var system = admin.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `console-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    Neo4jSchema.apply(admin);
    reader = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("console-reader", "reader-pass"));
    seed();
    jwt = new McpTestJwt();
    console = startConsole(500);
    // Бюджет в три узла: окрестность глубины 2 (четыре узла) обязана усечься.
    tinyConsole = startConsole(3);
  }

  @AfterAll
  static void stop() {
    for (var ctx : List.of(console, tinyConsole)) {
      if (ctx != null) {
        ctx.close();
      }
    }
    if (jwt != null) {
      jwt.close();
    }
    reader.close();
    admin.close();
  }

  /** pay -DECOMPOSED_INTO-> billing -HAS_DEPLOYMENT-> dep -IN_ENVIRONMENT-> prod, плюс закрытый узел. */
  private static void seed() {
    var fixtures = new AssetFixtures(new GraphProjector(admin, AuthorityMatrix.defaults()));
    var t1 = Instant.parse("2026-01-01T10:00:00Z");
    var t2 = Instant.parse("2026-01-02T10:00:00Z");
    var pay = fixtures.node(UUID.fromString(GID_PAY), "system", "EAM-1",
        new ITSystem("Payments Platform", null, null, "handles card payments"), t1);
    var billing = fixtures.node(UUID.fromString(GID_BILLING), "component", "billing",
        new Service("Billing Payments Service", null, null, null), t2);
    var dep = fixtures.node(UUID.fromString(GID_DEP), "deployment", "billing-prod",
        new Deployment("billing-prod", "billing-prod", null, null, null), t1);
    var prod = fixtures.node(UUID.fromString(GID_PROD), "env", "prod",
        new Environment("prod", "Production", EnvironmentClass.PROD), t1);
    fixtures.relation(RelationType.DECOMPOSED_INTO, pay, UUID.fromString(GID_PAY), billing, UUID.fromString(GID_BILLING), null, t1);
    fixtures.relation(RelationType.HAS_DEPLOYMENT, billing, UUID.fromString(GID_BILLING), dep, UUID.fromString(GID_DEP), null, t1);
    fixtures.relation(RelationType.IN_ENVIRONMENT, dep, UUID.fromString(GID_DEP), prod, UUID.fromString(GID_PROD), null, t1);
    // FULLTEXT-индекс обновляется асинхронно.
    try (var session = admin.session()) {
      session.run("CALL db.awaitIndexes(30)").consume();
    }
  }

  /**
   * Свойства передаются аргументами: на classpath несколько {@code application.yml} (ingestion-service,
   * mcp-server, admin-console), какой загрузится, не определено, поэтому всё нужное консоли задано явно.
   */
  private static ConfigurableApplicationContext startConsole(int maxNodes) {
    List<String> args = List.of(
        "--server.port=0",
        // На classpath есть JDBC/Flyway ingestion-сервиса и MCP-автоконфигурация; консоли они не нужны.
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
        // PostgreSQL журнала проверяет SyncConsoleIT; здесь пул ленивый и не используется.
        "--archrag.console-pg.url=jdbc:postgresql://localhost:1/none",
        "--archrag.console-pg.username=archrag_console_ro",
        "--archrag.console-pg.password=unused",
        "--archrag.neo4j.reader.uri=" + NEO4J.getBoltUrl(),
        "--archrag.neo4j.reader.username=console-reader",
        "--archrag.neo4j.reader.password=reader-pass",
        "--archrag.query.limits.max-depth=6",
        "--archrag.query.limits.max-nodes=" + maxNodes,
        "--archrag.query.limits.max-paths=50",
        "--archrag.query.limits.timeout=5s",
        "--archrag.query.limits.max-response-bytes=512KB");
    return new SpringApplicationBuilder(AdminConsoleApplication.class).run(args.toArray(String[]::new));
  }

  private static ResponseEntity<String> get(ConfigurableApplicationContext ctx, String path) {
    int port = ctx.getEnvironment().getProperty("local.server.port", Integer.class);
    return RestClient.builder()
        .defaultStatusHandler(s -> true, (req, res) -> {})
        .build()
        .get()
        .uri(URI.create("http://localhost:" + port + path))
        .headers(h -> h.setBearerAuth(jwt.tokenFor("admin-1", AUDIENCE, ADMIN)))
        .retrieve()
        .toEntity(String.class);
  }

  private static JsonNode body(ResponseEntity<String> res) {
    assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
    return new JsonMapper().readTree(res.getBody());
  }

  private static long nodeCount() {
    return admin.executableQuery("MATCH (n) RETURN count(n) AS c").execute().records().get(0).get("c").asLong();
  }

  @Test
  void statsCountNodesByLabelRelationsByTypeAndLastUpdate() {
    JsonNode stats = body(get(console, "/api/graph/stats"));

    assertThat(count(stats.get("nodes"), "ITSystem")).isEqualTo(1);
    assertThat(count(stats.get("nodes"), "Service")).isEqualTo(1);
    // Метка и тип без данных присутствуют с нулём.
    assertThat(count(stats.get("nodes"), "Team")).isZero();
    assertThat(count(stats.get("relations"), "DEPENDS_ON")).isZero();
    assertThat(count(stats.get("nodes"), "SourceRecord")).isGreaterThanOrEqualTo(4);
    assertThat(count(stats.get("relations"), "HAS_DEPLOYMENT")).isEqualTo(1);
    assertThat(count(stats.get("relations"), "DECOMPOSED_INTO")).isEqualTo(1);
    assertThat(stats.get("lastUpdatedAt").asString()).startsWith("2026-01-0");
  }

  @Test
  void searchFindsByFulltextAndByExactGid() {
    JsonNode fulltext = body(get(console, "/api/graph/search?q=payments&types=ITSystem&types=Service"));
    assertThat(gids(fulltext.get("items"))).containsExactlyInAnyOrder(GID_PAY, GID_BILLING);
    assertThat(fulltext.get("items").get(0).get("matchType").asString()).isEqualTo("FULLTEXT");

    JsonNode exact = body(get(console, "/api/graph/search?q=" + GID_DEP));
    assertThat(exact.get("items").get(0).get("gid").asString()).isEqualTo(GID_DEP);
    assertThat(exact.get("items").get(0).get("matchType").asString()).isEqualTo("EXACT");

    JsonNode limited = body(get(console, "/api/graph/search?q=payments&limit=1"));
    assertThat(limited.get("items")).hasSize(1);
    assertThat(limited.get("truncated").asBoolean()).isTrue();
  }

  @Test
  void cardShowsPropertiesTimesAndAssertingSourceRecords() {
    JsonNode card = body(get(console, "/api/graph/nodes/" + GID_BILLING));

    assertThat(card.get("type").asString()).isEqualTo("Service");
    assertThat(card.get("isCurrent").asBoolean()).isTrue();
    assertThat(card.get("properties").get("name").asString()).isEqualTo("Billing Payments Service");
    assertThat(card.get("firstSeenAt").asString()).startsWith("2026-01-02");
    assertThat(card.get("lastSeenAt").asString()).startsWith("2026-01-02");
    JsonNode record = card.get("sources").get(0);
    assertThat(record.get("sourceId").asString()).isEqualTo("billing");
    assertThat(record.get("authority").asString()).isEqualTo("MASTER");
    assertThat(record.get("active").asBoolean()).isTrue();

    assertThat(get(console, "/api/graph/nodes/99999999-9999-9999-9999-999999999999").getStatusCode().value())
        .isEqualTo(404);
  }

  @Test
  void neighborhoodDepthOneAndTwoWithRelTypeFilter() {
    JsonNode one = body(get(console, "/api/graph/nodes/" + GID_BILLING + "/neighborhood?depth=1"));
    assertThat(gids(one.get("nodes"))).containsExactlyInAnyOrder(GID_BILLING, GID_PAY, GID_DEP);
    assertThat(one.get("edges")).hasSize(2);
    assertThat(one.get("truncated").asBoolean()).isFalse();

    JsonNode two = body(get(console, "/api/graph/nodes/" + GID_PAY + "/neighborhood?depth=2"));
    assertThat(gids(two.get("nodes"))).containsExactlyInAnyOrder(GID_PAY, GID_BILLING, GID_DEP);
    assertThat(distance(two.get("nodes"), GID_DEP)).isEqualTo(2);
    assertThat(distance(two.get("nodes"), GID_PAY)).isZero();

    JsonNode filtered =
        body(get(console, "/api/graph/nodes/" + GID_BILLING + "/neighborhood?depth=2&relTypes=HAS_DEPLOYMENT"));
    assertThat(gids(filtered.get("nodes"))).containsExactlyInAnyOrder(GID_BILLING, GID_DEP);
    assertThat(filtered.get("edges").get(0).get("type").asString()).isEqualTo("HAS_DEPLOYMENT");
  }

  @Test
  void depthThreeIsRejectedBeforeAnyQuery() {
    var res = get(console, "/api/graph/nodes/" + GID_PAY + "/neighborhood?depth=3");

    assertThat(res.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void neighborhoodOverNodeBudgetIsTruncated() {
    JsonNode graph = body(get(tinyConsole, "/api/graph/nodes/" + GID_BILLING + "/neighborhood?depth=2"));

    assertThat(graph.get("truncated").asBoolean()).isTrue();
    assertThat(graph.get("nodes").size()).isLessThanOrEqualTo(3);
  }

  @Test
  void everyConsoleTemplatePassesReadOnlyExplainAndWritingTemplateIsClosed() {
    List<QueryTemplate> templates = new ArrayList<>(ConsoleTemplates.ALL);
    templates.add(AssetTemplates.SEARCH_ASSETS);
    templates.add(AssetTemplates.GET_ASSET);
    templates.add(AssetTemplates.EXPLAIN_PROVENANCE);
    var writing =
        new QueryTemplate(
            "evil_write",
            "CREATE (n:Evil {gid: $gid}) RETURN n.gid AS gid LIMIT $limit",
            Set.of("gid"),
            ResultKind.NODES);
    templates.add(writing);
    // Учётка консоли технически может писать (в Community нет RBAC): защита — проверка EXPLAIN перед исполнением.
    var executor = new GraphQueryExecutor(reader, QueryTemplateRegistry.of(templates, LIMITS));
    long before = nodeCount();

    for (QueryTemplate template : templates) {
      if (template == writing) {
        continue;
      }
      var params = new LinkedHashMap<String, Object>();
      template.parameters().forEach(p -> params.put(p, p.equals("gids") || p.equals("relTypes") || p.equals("types") || p.equals("adapters") ? List.of() : "x"));
      // Исполнение первым делом проверяет EXPLAIN: IllegalStateException означало бы не read-only шаблон.
      executor.execute(template.id(), params, null);
    }
    assertThatThrownBy(() -> executor.execute("evil_write", java.util.Map.of("gid", "evil"), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not read-only");
    assertThat(nodeCount()).isEqualTo(before);
  }

  private static long count(JsonNode counts, String name) {
    for (JsonNode c : counts) {
      if (c.get("name").asString().equals(name)) {
        return c.get("count").asLong();
      }
    }
    throw new AssertionError("no count for " + name);
  }

  private static List<String> gids(JsonNode nodes) {
    List<String> out = new ArrayList<>();
    nodes.forEach(n -> out.add(n.get("gid").asString()));
    return out;
  }

  private static int distance(JsonNode nodes, String gid) {
    for (JsonNode n : nodes) {
      if (n.get("gid").asString().equals(gid)) {
        return n.get("distance").asInt();
      }
    }
    throw new AssertionError("no node " + gid);
  }
}
