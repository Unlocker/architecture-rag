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

/** Шаблоны {@code trace_downstream} и {@code trace_upstream} на Neo4j Community со схемой из {@link Neo4jSchema}. */
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
                List.of(DependencyTemplates.TRACE_DOWNSTREAM, DependencyTemplates.TRACE_UPSTREAM, AssetTemplates.GET_ASSET),
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
}
