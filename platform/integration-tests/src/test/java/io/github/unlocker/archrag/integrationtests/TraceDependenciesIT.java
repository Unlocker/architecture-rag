package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeKind;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
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
import io.github.unlocker.archrag.graphquerycore.templates.DependencyTemplates;
import java.time.Duration;
import java.time.Instant;
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

/** Шаблоны {@code trace_downstream}, {@code trace_upstream} и {@code trace_impact} на Neo4j Community со схемой из {@link Neo4jSchema}. */
@Testcontainers
class TraceDependenciesIT {

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  static final QueryLimits LIMITS =
      new QueryLimits(6, 500, 50, Duration.ofSeconds(Long.getLong("archrag.it.queryTimeoutSeconds", 5)), 512 * 1024);
  static final String ALL = String.join(",", DependencyTemplates.TRACE_RELATION_TYPES);

  static final String GID_SYS = "11111111-1111-1111-1111-111111111111";
  static final String GID_BILLING = "22222222-2222-2222-2222-222222222222";
  static final String GID_AUTH = "33333333-3333-3333-3333-333333333333";
  static final String GID_TEAM = "44444444-4444-4444-4444-444444444444";
  static final String GID_DEP = "55555555-5555-5555-5555-555555555555";
  static final String GID_VM = "66666666-6666-6666-6666-666666666666";
  static final String GID_VM_OLD = "77777777-7777-7777-7777-777777777777";
  static final String GID_SRV = "88888888-8888-8888-8888-888888888888";

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
        new GraphQueryExecutor(
            reader,
            QueryTemplateRegistry.of(
                List.of(
                    DependencyTemplates.TRACE_DOWNSTREAM,
                    DependencyTemplates.TRACE_UPSTREAM,
                    DependencyTemplates.IMPACT_UPSTREAM,
                    AssetTemplates.GET_ASSET),
                LIMITS));
  }

  @BeforeEach
  void seed() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    var fixtures = new AssetFixtures(new GraphProjector(driver, AuthorityMatrix.defaults()));
    var t1 = Instant.parse("2026-01-01T10:00:00Z");
    UUID sys = UUID.fromString(GID_SYS), billing = UUID.fromString(GID_BILLING), auth = UUID.fromString(GID_AUTH);
    UUID team = UUID.fromString(GID_TEAM), dep = UUID.fromString(GID_DEP), vm = UUID.fromString(GID_VM);
    UUID vmOld = UUID.fromString(GID_VM_OLD);

    var sysKey = fixtures.node(sys, "system", "EAM-1", new ITSystem("Payments Platform", null, null, null), t1);
    var billingKey = fixtures.node(billing, "component", "billing", new Service("Billing", null, null, null), t1);
    var authKey = fixtures.node(auth, "component", "auth", new Service("Auth", null, null, null), t1);
    var teamKey = fixtures.node(team, "team", "team-1", new Team("Payments Team", null), t1);
    var depKey = fixtures.node(dep, "deployment", "billing-prod", new Deployment("billing-prod", "billing-prod", null, null, null), t1);
    var vmKey = fixtures.node(vm, "host", "vm-prod-01", new ComputeInstance("vm-prod-01", ComputeKind.VIRTUAL_MACHINE, null, null, null, null, null), t1);
    var vmOldKey = fixtures.node(vmOld, "host", "vm-prod-old", new ComputeInstance("vm-prod-old", ComputeKind.UNSPECIFIED, null, null, null, null, null), t1);

    fixtures.relation(RelationType.DECOMPOSED_INTO, sysKey, sys, billingKey, billing, null, t1);
    fixtures.relation(RelationType.HAS_DEPLOYMENT, billingKey, billing, depKey, dep, null, t1);
    fixtures.relation(RelationType.OWNED_BY, billingKey, billing, teamKey, team, null, t1);
    fixtures.relation(RelationType.RUNS_ON, depKey, dep, vmKey, vm, new Validity(t1, null), t1);
    fixtures.relation(RelationType.RUNS_ON, depKey, dep, vmOldKey, vmOld,
        new Validity(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-06-01T00:00:00Z")), t1);
    // Цикл зависимостей billing -> auth -> billing.
    fixtures.relation(RelationType.DEPENDS_ON, billingKey, billing, authKey, auth, null, t1);
    fixtures.relation(RelationType.DEPENDS_ON, authKey, auth, billingKey, billing, null, t1);
    // HOSTED_ON ни один нормализатор не порождает: связь и PhysicalServer сидятся Cypher'ом.
    driver
        .executableQuery(
            "MATCH (v:ComputeInstance {gid: $vm}) "
                + "CREATE (v)-[:HOSTED_ON {validFrom: datetime('2026-01-01T10:00:00Z')}]->"
                + "(:PhysicalServer:ComputeInstance {gid: $srv, hostname: 'srv-01', isCurrent: true,"
                + " lastSeenAt: datetime('2026-01-01T10:00:00Z')})")
        .withParameters(Map.of("vm", GID_VM, "srv", GID_SRV))
        .execute();
    // Записи-источники связей фикстуры не несут fetchedAt (в проде его пишет запись узла): задаём свежесть шагов.
    driver
        .executableQuery("MATCH (r:SourceRecord) WHERE r.fetchedAt IS NULL SET r.fetchedAt = datetime('2026-01-01T10:00:00Z')")
        .execute();
  }

  private static QueryResult trace(String template, String gid, int depth, int maxPaths, String relationTypes) {
    var budget = new ResultBudget(depth, LIMITS.maxNodes(), maxPaths, LIMITS.timeout(), LIMITS.maxResponseBytes());
    return executor.execute(template, Map.of("gid", gid, "relationTypes", List.of(relationTypes.split(","))), budget);
  }

  @SuppressWarnings("unchecked")
  private static List<String> endGids(QueryResult result) {
    return result.rows().stream()
        .map(r -> ((List<Map<String, Object>>) r.get("nodes")).getLast().get("gid").toString())
        .toList();
  }

  @Test
  @SuppressWarnings("unchecked")
  void downstreamFollowsDecompositionDeploymentRunsOnAndHostedOn() {
    var result = trace("trace_downstream", GID_SYS, 6, 50, ALL);

    assertThat(endGids(result)).contains(GID_BILLING, GID_DEP, GID_VM, GID_SRV, GID_TEAM, GID_AUTH);
    var longest = result.rows().stream().filter(r -> GID_SRV.equals(endGid(r))).findFirst().orElseThrow();
    assertThat(((List<Map<String, Object>>) longest.get("relations")).stream().map(r -> r.get("type")))
        .containsExactly("DECOMPOSED_INTO", "HAS_DEPLOYMENT", "RUNS_ON", "HOSTED_ON");
    assertThat(((List<Map<String, Object>>) longest.get("relations")).getFirst())
        .containsEntry("from", GID_SYS).containsEntry("to", GID_BILLING).containsEntry("assertedBySource", "SCM");
    assertThat(result.truncated()).isFalse();
  }

  @SuppressWarnings("unchecked")
  private static String endGid(Map<String, Object> row) {
    return ((List<Map<String, Object>>) row.get("nodes")).getLast().get("gid").toString();
  }

  @Test
  void upstreamGoesAgainstArrowsFromComputeInstanceToSystem() {
    var result = trace("trace_upstream", GID_VM, 6, 50, ALL);

    // Через цикл billing <- auth путь доходит до billing и системы повторно: проверяем множество достижимых.
    assertThat(endGids(result)).containsOnly(GID_DEP, GID_BILLING, GID_SYS, GID_AUTH);
    assertThat(trace("trace_upstream", GID_SRV, 1, 50, ALL).rows()).hasSize(1);
  }

  @Test
  void dependencyCycleTerminatesAndDoesNotRepeatRelationInPath() {
    var result = trace("trace_downstream", GID_BILLING, 6, 50, "DEPENDS_ON");

    // billing -> auth и billing -> auth -> billing: связь внутри пути не повторяется, обход конечен.
    assertThat(result.rows()).hasSize(2);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void maxPathsBelowPathCountIsTruncated() {
    var all = trace("trace_downstream", GID_SYS, 6, 50, ALL);
    var limited = trace("trace_downstream", GID_SYS, 6, 3, ALL);

    assertThat(all.rows().size()).isGreaterThan(3);
    assertThat(limited.rows()).hasSize(3);
    assertThat(limited.truncated()).isTrue();
  }

  @Test
  void depthBoundsTheTraversal() {
    var result = trace("trace_downstream", GID_SYS, 2, 50, ALL);

    assertThat(endGids(result)).doesNotContain(GID_VM, GID_SRV).contains(GID_DEP);
  }

  @Test
  void closedRelationIsNotTraversed() {
    var result = trace("trace_downstream", GID_DEP, 6, 50, ALL);

    assertThat(endGids(result)).contains(GID_VM).doesNotContain(GID_VM_OLD);
  }

  @Test
  void relationTypesFilterNarrowsTheTraversal() {
    var result = trace("trace_downstream", GID_SYS, 6, 50, "DECOMPOSED_INTO,OWNED_BY");

    assertThat(endGids(result)).containsExactlyInAnyOrder(GID_BILLING, GID_TEAM);
  }

  @Test
  void notCurrentNodeIsNotTraversed() {
    driver.executableQuery("MATCH (n {gid: $g}) SET n.isCurrent = false").withParameters(Map.of("g", GID_DEP)).execute();

    var result = trace("trace_downstream", GID_SYS, 6, 50, ALL);

    assertThat(endGids(result)).doesNotContain(GID_DEP, GID_VM, GID_SRV);
  }

  @Test
  void unknownGidGivesNoPathsAndNoAssetCard() {
    String unknown = "99999999-9999-9999-9999-999999999999";

    assertThat(trace("trace_downstream", unknown, 6, 50, ALL).rows()).isEmpty();
    assertThat(executor.execute("get_asset", Map.of("gid", unknown), null).rows()).isEmpty();
  }

  // ---- impact ----

  static final String GID_DEP_TEST = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  static final String ENV_PROD = "eeeeeeee-0000-0000-0000-000000000001";
  static final String ENV_TEST = "eeeeeeee-0000-0000-0000-000000000002";

  private static QueryResult impact(String gid, String environment, int depth, int maxPaths) {
    var budget = new ResultBudget(depth, LIMITS.maxNodes(), maxPaths, LIMITS.timeout(), LIMITS.maxResponseBytes());
    Map<String, Object> params = new java.util.HashMap<>();
    params.put("gid", gid);
    params.put("relationTypes", DependencyTemplates.IMPACT_RELATION_TYPES);
    params.put("environment", environment);
    return executor.execute("trace_impact", params, budget);
  }

  /** Второе развёртывание billing на той же VM, но в окружении test; обе Deployment привязаны к окружениям. */
  private static void seedEnvironments() {
    driver
        .executableQuery(
            "MATCH (b:Service {gid: $billing}), (v:ComputeInstance {gid: $vm}), (dep:Deployment {gid: $dep}) "
                + "CREATE (e1:Environment {gid: $e1, code: 'prod', isCurrent: true, lastSeenAt: datetime()}), "
                + "(e2:Environment {gid: $e2, code: 'test', isCurrent: true, lastSeenAt: datetime()}), "
                + "(d2:Deployment {gid: $d2, name: 'billing-test', isCurrent: true, lastSeenAt: datetime()}) "
                + "CREATE (b)-[:HAS_DEPLOYMENT {validFrom: datetime()}]->(d2), "
                + "(d2)-[:RUNS_ON {validFrom: datetime()}]->(v), "
                + "(dep)-[:IN_ENVIRONMENT {validFrom: datetime()}]->(e1), "
                + "(d2)-[:IN_ENVIRONMENT {validFrom: datetime()}]->(e2)")
        .withParameters(Map.of("billing", GID_BILLING, "vm", GID_VM, "dep", GID_DEP, "d2", GID_DEP_TEST, "e1", ENV_PROD, "e2", ENV_TEST))
        .execute();
  }

  @SuppressWarnings("unchecked")
  private static boolean anyPathContains(QueryResult result, String gid) {
    return result.rows().stream()
        .anyMatch(r -> ((List<Map<String, Object>>) r.get("nodes")).stream().anyMatch(n -> gid.equals(n.get("gid"))));
  }

  @Test
  @SuppressWarnings("unchecked")
  void stoppingVmReachesServicesAndSystemsWithStepProvenance() {
    var result = impact(GID_VM, null, 6, 50);

    assertThat(endGids(result)).contains(GID_BILLING, GID_SYS).doesNotContain(GID_DEP, GID_VM);
    var toSystem = result.rows().stream().filter(r -> GID_SYS.equals(endGid(r))).findFirst().orElseThrow();
    var steps = (List<Map<String, Object>>) toSystem.get("relations");
    assertThat(steps).extracting(r -> r.get("type")).containsExactly("RUNS_ON", "HAS_DEPLOYMENT", "DECOMPOSED_INTO");
    assertThat(steps).allSatisfy(step -> {
      assertThat(step.get("assertedBySource")).isNotNull();
      assertThat(step.get("assertedById")).isNotNull();
      assertThat(step.get("sourceFetchedAt")).isEqualTo("2026-01-01T10:00:00Z");
      assertThat(step.get("sourceActive")).isEqualTo(true);
    });
    // Путь идёт против стрелок: первая связь Deployment -RUNS_ON-> VM.
    assertThat(steps.getFirst()).containsEntry("from", GID_DEP).containsEntry("to", GID_VM);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void dependsOnConsumerOfAffectedServiceIsAffectedAndOwnershipIsNotFollowed() {
    var result = impact(GID_VM, null, 6, 50);

    assertThat(endGids(result)).contains(GID_AUTH).doesNotContain(GID_TEAM);
  }

  @Test
  void environmentFilterCutsDeploymentsOfOtherEnvironments() {
    seedEnvironments();

    var prod = impact(GID_VM, "prod", 6, 50);
    var unfiltered = impact(GID_VM, null, 6, 50);

    assertThat(endGids(prod)).contains(GID_BILLING, GID_SYS);
    assertThat(anyPathContains(prod, GID_DEP)).isTrue();
    assertThat(anyPathContains(prod, GID_DEP_TEST)).isFalse();
    assertThat(anyPathContains(unfiltered, GID_DEP)).isTrue();
    assertThat(anyPathContains(unfiltered, GID_DEP_TEST)).isTrue();
    assertThat(impact(GID_VM, "no-such-env", 6, 50).rows()).isEmpty();
  }

  @Test
  void environmentFilterIgnoresClosedEnvironmentLink() {
    seedEnvironments();
    driver
        .executableQuery("MATCH (:Deployment {gid: $g})-[e:IN_ENVIRONMENT]->() SET e.validTo = datetime('2026-01-02T00:00:00Z')")
        .withParameters(Map.of("g", GID_DEP))
        .execute();

    assertThat(impact(GID_VM, "prod", 6, 50).rows()).isEmpty();
  }

  @Test
  void pathWithoutDeploymentPassesEnvironmentFilter() {
    seedEnvironments();

    var result = impact(GID_AUTH, "prod", 6, 50);

    assertThat(endGids(result)).contains(GID_BILLING);
  }

  @Test
  void closedRunsOnGivesNoImpact() {
    assertThat(impact(GID_VM_OLD, null, 6, 50).rows()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void freshnessConflictsAndInactiveSourceAreProjected() {
    driver
        .executableQuery("MATCH (n {gid: $g}) SET n.lastSeenAt = datetime('2025-01-01T00:00:00Z')")
        .withParameters(Map.of("g", GID_DEP))
        .execute();
    driver
        .executableQuery("MATCH (:SourceRecord)-[a:ASSERTS]->(n {gid: $g}) SET a.conflicts = ['name']")
        .withParameters(Map.of("g", GID_BILLING))
        .execute();
    driver
        .executableQuery("MATCH (r:SourceRecord {sourceId: $id}) SET r.active = false")
        .withParameters(Map.of("id", "RUNS_ON/" + GID_DEP + "/" + GID_VM))
        .execute();

    var result = impact(GID_VM, null, 6, 50);
    var toBilling = result.rows().stream().filter(r -> GID_BILLING.equals(endGid(r))).findFirst().orElseThrow();
    var nodes = (List<Map<String, Object>>) toBilling.get("nodes");
    var dep = nodes.stream().filter(n -> GID_DEP.equals(n.get("gid"))).findFirst().orElseThrow();
    var billing = nodes.getLast();

    assertThat(dep.get("lastSeenAt")).isEqualTo("2025-01-01T00:00:00Z");
    assertThat(dep.get("conflicts")).isEqualTo(List.of());
    assertThat((List<Map<String, Object>>) billing.get("conflicts"))
        .singleElement()
        .satisfies(c -> {
          assertThat(c).containsEntry("source", "SCM").containsEntry("sourceType", "component").containsEntry("sourceId", "billing");
          assertThat(c.get("properties")).isEqualTo(List.of("name"));
        });
    assertThat(((List<Map<String, Object>>) toBilling.get("relations")).getFirst()).containsEntry("sourceActive", false);
  }

  /** 1 CI, 30 Deployment на ней, 30 Service (по одному на Deployment), DEPENDS_ON «каждый с каждым». */
  private static void seedDenseGraph() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    driver
        .executableQuery(
            "CREATE (ci:ComputeInstance {gid: 'ci', hostname: 'ci', isCurrent: true, lastSeenAt: datetime()}) "
                + "WITH ci UNWIND range(1, 30) AS i "
                + "CREATE (d:Deployment {gid: 'd' + i, name: 'd' + i, isCurrent: true, lastSeenAt: datetime()}), "
                + "(s:Service {gid: 's' + i, name: 's' + i, isCurrent: true, lastSeenAt: datetime()}) "
                + "CREATE (d)-[:RUNS_ON {validFrom: datetime()}]->(ci), (s)-[:HAS_DEPLOYMENT {validFrom: datetime()}]->(d)")
        .execute();
    driver
        .executableQuery(
            "MATCH (a:Service), (b:Service) WHERE a <> b CREATE (a)-[:DEPENDS_ON {validFrom: datetime()}]->(b)")
        .execute();
  }

  @Test
  void denseGraphImpactIsTruncatedWithinTimeout() {
    seedDenseGraph();

    var result = impact("ci", null, 6, LIMITS.maxPaths());

    assertThat(result.truncated()).isTrue();
    assertThat(result.rows()).hasSize(LIMITS.maxPaths());
  }

  @Test
  void denseGraphUpstreamTraceIsTruncatedWithinTimeout() {
    seedDenseGraph();

    var result = trace("trace_upstream", "ci", 6, LIMITS.maxPaths(), ALL);

    assertThat(result.truncated()).isTrue();
    assertThat(result.rows()).hasSize(LIMITS.maxPaths());
  }
}
