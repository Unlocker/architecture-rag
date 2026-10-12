package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.DependencyTemplates;
import io.github.unlocker.archrag.graphquerycore.templates.RuntimeTemplates;
import java.time.Duration;
import java.util.HashMap;
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

/**
 * Чтение источника кода развёртывания (H.3): {@code find_runtime_footprint}, {@code get_asset} и
 * {@code trace_dependencies} отдают commit и репозиторий, закрытая {@code DEPLOYED_FROM} не видна,
 * связь не размножает строки footprint. Граф засеян Cypher'ом в форме, которую пишет projector.
 */
@Testcontainers
class DeploymentCommitReadIT {

  @Container static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(Long.getLong("archrag.it.queryTimeoutSeconds", 5)), 512 * 1024);
  static final String SYS = "11111111-1111-1111-1111-111111111111";
  static final String DEP = "dep-1";
  static final String REPO = "repo-1";
  static final String COMMIT_A = "9fceb02d0ae598e95dc970b74767f19372d61af8";
  static final String COMMIT_B = "0123456789abcdef0123456789abcdef01234567";
  static final String URL = "https://git.example.org/pay/payments-api";

  static Driver driver;
  static GraphQueryExecutor executor;

  @BeforeAll
  static void connect() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    try (var system = driver.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `mcp-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    // Схема ставится тем же кодом, что и в проде: уникальность gid проверяется самим сидированием.
    Neo4jSchema.apply(driver);
    var reader = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("mcp-reader", "reader-pass"));
    var templates = List.of(
        RuntimeTemplates.RUNTIME_FOOTPRINT, AssetTemplates.ASSET_RELATIONS, DependencyTemplates.TRACE_DOWNSTREAM);
    executor = new GraphQueryExecutor(reader, QueryTemplateRegistry.of(templates, LIMITS));
    // Прогрев холодного Neo4j (планирование запросов) с запасом по времени: проверяемый таймаут 5 с остаётся.
    var warmLimits = new QueryLimits(6, 500, 50, Duration.ofSeconds(120), 512 * 1024);
    var warm = new GraphQueryExecutor(reader, QueryTemplateRegistry.of(templates, warmLimits));
    var none = new HashMap<String, Object>();
    none.put("systemGid", SYS);
    none.put("environment", null);
    warm.execute(RuntimeTemplates.RUNTIME_FOOTPRINT_ID, none, null);
    var budget = new ResultBudget(2, 100, 50, warmLimits.timeout(), warmLimits.maxResponseBytes());
    warm.execute("asset_relations", Map.of("gid", DEP), budget);
    warm.execute("trace_downstream", Map.of("gid", DEP, "relationTypes", List.of("DEPLOYED_FROM")), budget);
  }

  @BeforeEach
  void seed() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    driver
        .executableQuery(
            """
            CREATE (sys:ITSystem {gid: $sys, name: 'Payments', isCurrent: true, lastSeenAt: datetime('2026-10-05T10:00:00Z')})
            CREATE (svc:Service {gid: 'svc', name: 'payments-api', isCurrent: true, lastSeenAt: datetime('2026-10-05T10:00:00Z')})
            CREATE (env:Environment {gid: 'e-prod', code: 'PROD', isCurrent: true})
            CREATE (sys)-[:DECOMPOSED_INTO {validFrom: datetime('2026-10-01T00:00:00Z')}]->(svc)
            CREATE (svc)-[:HAS_DEPLOYMENT {validFrom: datetime('2026-10-01T00:00:00Z'), assertedBySource: 'deploymap',
                                           assertedByType: 'DEPLOYMENT', assertedById: 'dep-1'}]->
                   (d:Deployment {gid: 'dep-1', name: 'payments-api', status: 'RUNNING', isCurrent: true,
                                  sourceCommit: $b, repositoryUrl: $url, lastSeenAt: datetime('2026-10-05T10:00:00Z')})
            CREATE (d)-[:IN_ENVIRONMENT {validFrom: datetime('2026-10-01T00:00:00Z')}]->(env)
            CREATE (r:Repository {gid: 'repo-1', url: $url, isCurrent: true, lastSeenAt: datetime('2026-10-05T10:00:00Z')})
            CREATE (d)-[:DEPLOYED_FROM {commit: $a, validFrom: datetime('2026-10-01T00:00:00Z'),
                                        validTo: datetime('2026-10-03T00:00:00Z'), assertedBySource: 'deploymap',
                                        assertedByType: 'DEPLOYMENT', assertedById: 'dep-1'}]->(r)
            CREATE (d)-[:DEPLOYED_FROM {commit: $b, validFrom: datetime('2026-10-03T00:00:00Z'),
                                        assertedBySource: 'deploymap', assertedByType: 'DEPLOYMENT',
                                        assertedById: 'dep-1'}]->(r)
            """)
        .withParameters(Map.of("sys", SYS, "a", COMMIT_A, "b", COMMIT_B, "url", URL))
        .execute();
  }

  private static QueryResult footprint() {
    Map<String, Object> params = new HashMap<>();
    params.put("systemGid", SYS);
    params.put("environment", null);
    return executor.execute(RuntimeTemplates.RUNTIME_FOOTPRINT_ID, params, null);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  @Test
  void footprintReturnsCurrentCommitRepositoryAndEdgeProvenanceInOneRow() {
    QueryResult r = footprint();

    // Закрытая DEPLOYED_FROM на COMMIT_A не размножает строки и не попадает в ответ.
    assertThat(r.rows()).hasSize(1);
    Map<String, Object> d = map(r.rows().get(0).get("deployment"));
    assertThat(d).containsEntry("sourceCommit", COMMIT_B).containsEntry("repositoryUrl", URL);
    Map<String, Object> source = map(d.get("source"));
    assertThat(map(source.get("repository"))).containsEntry("gid", REPO).containsEntry("url", URL);
    assertThat(map(source.get("deployedFrom")))
        .containsEntry("commit", COMMIT_B)
        .containsEntry("source", "deploymap")
        .containsEntry("sourceId", "dep-1");
  }

  @Test
  void deploymentWithoutDeployedFromGivesNullFieldsAndStillOneRow() {
    driver.executableQuery("MATCH ()-[df:DEPLOYED_FROM]->() DELETE df").execute();
    driver.executableQuery("MATCH (d:Deployment) REMOVE d.sourceCommit, d.repositoryUrl").execute();

    QueryResult r = footprint();

    assertThat(r.rows()).hasSize(1);
    Map<String, Object> d = map(r.rows().get(0).get("deployment"));
    assertThat(d.get("sourceCommit")).isNull();
    assertThat(d.get("source")).isNull();
  }

  @Test
  void getAssetRelationsShowOnlyOpenDeployedFromBothDirections() {
    var budget = new ResultBudget(1, 50, 1, LIMITS.timeout(), LIMITS.maxResponseBytes());

    QueryResult fromDeployment = executor.execute("asset_relations", Map.of("gid", DEP), budget);
    QueryResult fromRepository = executor.execute("asset_relations", Map.of("gid", REPO), budget);

    assertThat(fromDeployment.rows())
        .filteredOn(row -> "DEPLOYED_FROM".equals(row.get("relationType")))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("direction", "OUT").containsEntry("gid", REPO).containsEntry("type", "Repository"));
    assertThat(fromRepository.rows())
        .filteredOn(row -> "DEPLOYED_FROM".equals(row.get("relationType")))
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("direction", "IN").containsEntry("gid", DEP));
  }

  @Test
  @SuppressWarnings("unchecked")
  void traceDownstreamReachesRepositoryOverOpenDeployedFromOnly() {
    var budget = new ResultBudget(2, 100, 50, LIMITS.timeout(), LIMITS.maxResponseBytes());

    QueryResult r =
        executor.execute(
            "trace_downstream", Map.of("gid", DEP, "relationTypes", List.of("DEPLOYED_FROM")), budget);

    // Один путь: закрытое ребро (COMMIT_A) фильтром validTo IS NULL отсекается.
    assertThat(r.rows()).hasSize(1);
    var relations = (List<Map<String, Object>>) r.rows().get(0).get("relations");
    assertThat(relations).singleElement().satisfies(rel -> assertThat(rel).containsEntry("type", "DEPLOYED_FROM").containsEntry("to", REPO));
  }
}
