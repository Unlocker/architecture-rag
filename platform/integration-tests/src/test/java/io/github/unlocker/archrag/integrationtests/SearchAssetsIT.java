package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.FulltextQuery;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  // Таймаут с запасом: проверяется результат шаблона, а не его скорость (таймауты покрывает GraphQueryCoreIT).
  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(60), 512 * 1024);
  static final String GID_PAY = "11111111-1111-1111-1111-111111111111";
  static final String GID_BILLING = "22222222-2222-2222-2222-222222222222";
  static final String GID_REPO = "33333333-3333-3333-3333-333333333333";
  static final String GID_OLD = "44444444-4444-4444-4444-444444444444";
  static final String GID_VM = "55555555-5555-5555-5555-555555555555";
  static final String GID_VM_OLD = "66666666-6666-6666-6666-666666666666";

  static Driver driver;
  static GraphQueryExecutor executor;

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
    driver.executableQuery(
            """
            CREATE (sys:ITSystem {gid: $pay, name: 'Payments Platform', description: 'handles card payments',
                    isCurrent: true, lastSeenAt: datetime('2026-01-01T10:00:00Z')})
            CREATE (svc:Service {gid: $billing, name: 'Billing Service', description: 'invoices and payments',
                    isCurrent: true, lastSeenAt: datetime('2026-01-02T10:00:00Z')})
            CREATE (repo:Repository {gid: $repo, url: 'https://git.example/billing', isCurrent: true,
                    lastSeenAt: datetime('2026-01-03T10:00:00Z')})
            CREATE (old:Service {gid: $old, name: 'Legacy Payments Service', description: 'payments',
                    isCurrent: false, lastSeenAt: datetime('2025-01-01T10:00:00Z')})
            CREATE (prod:Environment {gid: '77777777-7777-7777-7777-777777777777', code: 'prod', name: 'Production',
                    isCurrent: true, lastSeenAt: datetime('2026-01-01T10:00:00Z')})
            CREATE (dep:Deployment {gid: '88888888-8888-8888-8888-888888888888', deploymentKey: 'billing-prod',
                    name: 'billing-prod', isCurrent: true, lastSeenAt: datetime('2026-01-01T10:00:00Z')})
            CREATE (vm:ComputeInstance:VirtualMachine {gid: $vm, hostname: 'vm-prod-01', isCurrent: true,
                    lastSeenAt: datetime('2026-01-01T10:00:00Z')})
            CREATE (vmOld:ComputeInstance {gid: $vmOld, hostname: 'vm-prod-old', isCurrent: true,
                    lastSeenAt: datetime('2026-01-01T10:00:00Z')})
            CREATE (sys)-[:DECOMPOSED_INTO]->(svc)
            CREATE (svc)-[:HAS_DEPLOYMENT]->(dep)
            CREATE (dep)-[:IN_ENVIRONMENT]->(prod)
            CREATE (dep)-[:RUNS_ON {validFrom: datetime('2026-01-01T00:00:00Z')}]->(vm)
            CREATE (dep)-[:RUNS_ON {validFrom: datetime('2025-01-01T00:00:00Z'),
                    validTo: datetime('2025-06-01T00:00:00Z')}]->(vmOld)
            CREATE (rSys:SourceRecord {source: 'EAM', sourceType: 'system', sourceId: 'EAM-1', active: true})
            CREATE (rSvc:SourceRecord {source: 'SCM', sourceType: 'component', sourceId: 'billing', active: true})
            CREATE (rSvc2:SourceRecord {source: 'EAM', sourceType: 'service', sourceId: 'EAM-9', active: true})
            CREATE (rSvcOff:SourceRecord {source: 'ASSET', sourceType: 'x', sourceId: 'gone', active: false})
            CREATE (rSys)-[:ASSERTS]->(sys)
            CREATE (rSvc)-[:ASSERTS]->(svc)
            CREATE (rSvc2)-[:ASSERTS]->(svc)
            CREATE (rSvcOff)-[:ASSERTS]->(svc)
            """)
        .withParameters(
            Map.of("pay", GID_PAY, "billing", GID_BILLING, "repo", GID_REPO, "old", GID_OLD, "vm", GID_VM,
                "vmOld", GID_VM_OLD))
        .execute();
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
    assertThat(exact.rows().getFirst().get("name")).isEqualTo("Billing Service");
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
    driver.executableQuery("MATCH (r:SourceRecord {sourceId: 'EAM-9'}) SET r.sourceId = 'billing service'").execute();

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
}
