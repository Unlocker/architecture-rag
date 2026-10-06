package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeKind;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.Environment;
import io.github.unlocker.archrag.canonicalmodel.node.EnvironmentClass;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.Namespace;
import io.github.unlocker.archrag.canonicalmodel.node.Repository;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.relation.Validity;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.FulltextQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

/** Шаблон {@code search_assets} на Neo4j Community со схемой из {@link Neo4jSchema}. */
@Testcontainers
class SearchAssetsIT {

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(Long.getLong("archrag.it.queryTimeoutSeconds", 5)), 512 * 1024);
  static final String GID_PAY = "11111111-1111-1111-1111-111111111111";
  static final String GID_BILLING = "22222222-2222-2222-2222-222222222222";
  static final String GID_REPO = "33333333-3333-3333-3333-333333333333";
  static final String GID_OLD = "44444444-4444-4444-4444-444444444444";
  static final String GID_VM = "55555555-5555-5555-5555-555555555555";
  static final String GID_VM_OLD = "66666666-6666-6666-6666-666666666666";
  static final String GID_PROD = "77777777-7777-7777-7777-777777777777";
  static final String GID_DEP = "88888888-8888-8888-8888-888888888888";

  static Driver driver;
  static GraphQueryExecutor executor;
  AssetFixtures fixtures;

  @BeforeAll
  static void connect() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    try (var system = driver.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `mcp-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    Neo4jSchema.apply(driver);
    var reader = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("mcp-reader", "reader-pass"));
    executor =
        new GraphQueryExecutor(reader, QueryTemplateRegistry.of(List.of(AssetTemplates.SEARCH_ASSETS), LIMITS));
  }

  @BeforeEach
  void seed() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    fixtures = new AssetFixtures(new GraphProjector(driver, AuthorityMatrix.defaults()));
    var t1 = Instant.parse("2026-01-01T10:00:00Z");
    var t2 = Instant.parse("2026-01-02T10:00:00Z");
    var t3 = Instant.parse("2026-01-03T10:00:00Z");

    var pay = fixtures.node(UUID.fromString(GID_PAY), "system", "EAM-1",
        new ITSystem("Payments Platform", null, null, "handles card payments"), t1);
    var billing = fixtures.node(UUID.fromString(GID_BILLING), "component", "billing",
        new Service("Billing Payments Service", null, null, null), t2);
    // Вторая активная запись и закрытая запись того же узла: в sources попадут только активные.
    fixtures.nodeFrom(SourceSystemCode.EAM, UUID.fromString(GID_BILLING), "service", "EAM-9",
        new Service("Billing Payments Service", null, null, null), t2);
    var gone = fixtures.nodeFrom(SourceSystemCode.MANUAL, UUID.fromString(GID_BILLING), "x", "gone",
        new Service("Billing Payments Service", null, null, null), t2);
    fixtures.tombstone(gone, UUID.fromString(GID_BILLING), t2);
    fixtures.node(UUID.fromString(GID_REPO), "repo", "repo-1",
        new Repository("https://git.example/billing", null, null), t3);
    // Закрытый узел: единственная мастер-запись tombstone-ится, isCurrent=false.
    var old = fixtures.node(UUID.fromString(GID_OLD), "component", "legacy",
        new Service("Legacy Payments Service", null, null, null), t1);
    fixtures.tombstone(old, UUID.fromString(GID_OLD), t2);
    var prod = fixtures.node(UUID.fromString(GID_PROD), "env", "prod",
        new Environment("prod", "Production", EnvironmentClass.PROD), t1);
    var dep = fixtures.node(UUID.fromString(GID_DEP), "deployment", "billing-prod",
        new Deployment("billing-prod", "billing-prod", null, null, null), t1);
    var vm = fixtures.node(UUID.fromString(GID_VM), "host", "vm-prod-01",
        new ComputeInstance("vm-prod-01", ComputeKind.VIRTUAL_MACHINE, null, null, null, null, null), t1);
    var vmOld = fixtures.node(UUID.fromString(GID_VM_OLD), "host", "vm-prod-old",
        new ComputeInstance("vm-prod-old", ComputeKind.UNSPECIFIED, null, null, null, null, null), t1);

    fixtures.relation(RelationType.DECOMPOSED_INTO, pay, UUID.fromString(GID_PAY), billing, UUID.fromString(GID_BILLING), null, t1);
    fixtures.relation(RelationType.HAS_DEPLOYMENT, billing, UUID.fromString(GID_BILLING), dep, UUID.fromString(GID_DEP), null, t1);
    fixtures.relation(RelationType.IN_ENVIRONMENT, dep, UUID.fromString(GID_DEP), prod, UUID.fromString(GID_PROD), null, t1);
    fixtures.relation(RelationType.RUNS_ON, dep, UUID.fromString(GID_DEP), vm, UUID.fromString(GID_VM),
        new Validity(t1, null), t1);
    fixtures.relation(RelationType.RUNS_ON, dep, UUID.fromString(GID_DEP), vmOld, UUID.fromString(GID_VM_OLD),
        new Validity(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-06-01T00:00:00Z")), t1);
    // FULLTEXT-индекс обновляется асинхронно: ждём, пока он увидит засеянные узлы.
    try (var session = driver.session()) {
      session.run("CALL db.awaitIndexes(30)").consume();
    }
  }

  private static QueryResult search(String query, List<String> types, String environment, Integer limit) {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("query", query);
    params.put("text", FulltextQuery.escape(query));
    params.put("types", types);
    params.put("environment", environment);
    ResultBudget budget =
        new ResultBudget(1, limit == null ? 10 : limit, 1, LIMITS.timeout(), LIMITS.maxResponseBytes());
    return executor.execute("search_assets", params, budget);
  }

  private static List<Object> gids(QueryResult r) {
    return r.rows().stream().map(row -> row.get("gid")).toList();
  }

  @Test
  void exactGidMatchComesFirstAboveFulltextHits() {
    // 'payments' находит fulltext-ом оба узла; gid Billing Service — точное совпадение.
    var exact = search(GID_BILLING, null, null, null);
    assertThat(exact.rows().getFirst()).containsEntry("gid", GID_BILLING).containsEntry("matchType", "EXACT");
    assertThat(((Number) exact.rows().getFirst().get("score")).doubleValue()).isEqualTo(AssetTemplates.EXACT_SCORE);
    assertThat(exact.rows().getFirst().get("name")).isEqualTo("Billing Payments Service");
    assertThat(exact.rows().getFirst().get("type")).isEqualTo("Service");
    assertThat(exact.rows().getFirst().get("lastSeenAt").toString()).startsWith("2026-01-02T10:00");
  }

  @Test
  void exactSourceIdMatchRanksAboveFulltextAndListsActiveSourcesOnly() {
    var result = search("billing", null, null, null);

    // sourceId 'billing' -> Billing Service (EXACT); Repository 'billing' не находится fulltext-ом.
    assertThat(result.rows().getFirst()).containsEntry("gid", GID_BILLING).containsEntry("matchType", "EXACT");
    assertThat(result.rows().getFirst().get("sources")).isEqualTo(List.of("EAM", "SCM"));
  }

  @Test
  void fulltextFindsBySearchableTextAndExactStillWins() {
    var result = search("payments", null, null, null);

    assertThat(gids(result)).containsExactlyInAnyOrder(GID_PAY, GID_BILLING);
    assertThat(result.rows()).allSatisfy(r -> assertThat(r).containsEntry("matchType", "FULLTEXT"));
    assertThat(((Number) result.rows().getFirst().get("score")).doubleValue()).isLessThan(AssetTemplates.EXACT_SCORE);
  }

  @Test
  void nodeFoundByExactAndFulltextAppearsOnceWithExactType() {
    // Имя узла совпадает с sourceId одной из его записей: найден обеими ветками.
    fixtures.nodeFrom(SourceSystemCode.EAM, UUID.fromString(GID_BILLING), "service", "billing service",
        new Service("Billing Payments Service", null, null, null), Instant.parse("2026-01-02T10:00:00Z"));

    var result = search("billing service", null, null, null);

    assertThat(gids(result).stream().filter(GID_BILLING::equals)).hasSize(1);
    assertThat(result.rows().getFirst()).containsEntry("gid", GID_BILLING).containsEntry("matchType", "EXACT");
  }

  @Test
  void typeFilterRestrictsResults() {
    assertThat(gids(search("payments", List.of("ITSystem"), null, null))).containsExactly(GID_PAY);
    assertThat(gids(search("payments", List.of("Repository"), null, null))).isEmpty();
  }

  @Test
  void repositoryIsFoundOnlyByExactMatch() {
    assertThat(gids(search(GID_REPO, null, null, null))).containsExactly(GID_REPO);
    assertThat(gids(search("git.example", null, null, null))).isEmpty();
  }

  @Test
  void environmentFilterFollowsRelationsAndExcludesOthers() {
    assertThat(gids(search("payments", null, "prod", null))).containsExactlyInAnyOrder(GID_PAY, GID_BILLING);
    assertThat(gids(search("payments", null, "stage", null))).isEmpty();
    // Repository при заданном environment отфильтровывается.
    assertThat(gids(search(GID_REPO, null, "prod", null))).isEmpty();
    // ComputeInstance — только через открытую RUNS_ON.
    assertThat(gids(search(GID_VM, null, "prod", null))).containsExactly(GID_VM);
    assertThat(gids(search(GID_VM_OLD, null, "prod", null))).isEmpty();
  }

  @Test
  void limitAndTruncated() {
    var one = search("payments", null, null, 1);
    assertThat(one.rows()).hasSize(1);
    assertThat(one.truncated()).isTrue();

    var all = search("payments", null, null, 10);
    assertThat(all.truncated()).isFalse();
  }

  @Test
  void nonCurrentNodeIsNotReturned() {
    assertThat(gids(search("payments", null, null, null))).doesNotContain(GID_OLD);
    assertThat(gids(search(GID_OLD, null, null, null))).isEmpty();
  }

  @Test
  void luceneSpecialCharactersDoNotFail() {
    for (String q : List.of("foo AND (bar", "a:b", "\"x\"", "\\", "payments OR", "*", "~", "!", "[a TO b]", "-")) {
      var result = search(q, null, null, null);
      assertThat(result.templateId()).isEqualTo("search_assets");
    }
  }

  @Test
  void nonTextInputRunsOnlyExactBranches() {
    // text = null: fulltext-ветка не вызывает процедуру, точное совпадение работает.
    assertThat(FulltextQuery.escape("()")).isNull();
    assertThat(gids(search("()", null, null, null))).isEmpty();
    assertThat(gids(search(GID_PAY, null, null, null))).containsExactly(GID_PAY);
  }

  @Test
  void nodeOutsideSearchableTypesIsNotReturnedByExactSourceId() {
    var ns = fixtures.node(UUID.fromString("99999999-9999-9999-9999-999999999999"), "namespace", "ns-1",
        new Namespace("payments-ns"), Instant.parse("2026-01-01T10:00:00Z"));

    assertThat(ns).isNotNull();
    assertThat(gids(search("ns-1", null, null, null))).isEmpty();
    assertThat(gids(search("99999999-9999-9999-9999-999999999999", null, null, null))).isEmpty();
  }
}
