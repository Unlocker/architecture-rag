package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
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

/** Проверяет шаблон runtime_footprint на Neo4j Community с продовыми лимитами (timeout 5s). */
@Testcontainers
class RuntimeFootprintIT {

  @Container static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);

  static Driver driver;
  static Driver readerDriver;

  @BeforeAll
  static void connect() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    try (var system = driver.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `mcp-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    readerDriver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("mcp-reader", "reader-pass"));
  }

  @BeforeEach
  void clean() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
  }

  private static void seed(String cypher) {
    driver.executableQuery(cypher).execute();
  }

  private static QueryResult footprint(String systemGid, String environment) {
    GraphQueryExecutor executor =
        new GraphQueryExecutor(
            readerDriver, QueryTemplateRegistry.of(List.of(RuntimeTemplates.RUNTIME_FOOTPRINT), LIMITS));
    Map<String, Object> params = new HashMap<>();
    params.put("systemGid", systemGid);
    params.put("environment", environment);
    return executor.execute(RuntimeTemplates.RUNTIME_FOOTPRINT_ID, params, null);
  }

  private static final String SYS = "11111111-1111-1111-1111-111111111111";

  /** Свойства узлов — как пишет projector: isCurrent, lastSeenAt (datetime), SourceRecord-ASSERTS. */
  private static void baseGraph() {
    seed(
        """
        CREATE (sys:ITSystem {gid: '11111111-1111-1111-1111-111111111111', name: 'Billing', isCurrent: true,
                              lastSeenAt: datetime('2026-10-05T10:00:00Z')})
        CREATE (svc:Service {gid: 'svc', name: 'billing-api', isCurrent: true, lastSeenAt: datetime('2026-10-05T10:00:00Z')})
        CREATE (sys)-[:DECOMPOSED_INTO]->(svc)
        CREATE (:Environment {gid: 'e-prod', code: 'PROD', isCurrent: true})
        CREATE (:Environment {gid: 'e-test', code: 'TEST', isCurrent: true})
        CREATE (:SourceRecord {source: 'eam', sourceType: 'SYSTEM', sourceId: 'S-1', active: true})-[:ASSERTS {authority: 'MASTER'}]->(sys)
        CREATE (:SourceRecord {source: 'old', sourceType: 'SYSTEM', sourceId: 'S-1', active: false})-[:ASSERTS {authority: 'MASTER'}]->(sys)
        """);
  }

  private static void deployment(String gid, String name, String env) {
    seed(
        "MATCH (svc:Service {gid: 'svc'}), (e:Environment {code: '" + env + "'}) "
            + "CREATE (svc)-[:HAS_DEPLOYMENT {assertedBySource: 'deploymap', assertedByType: 'DEPLOY', assertedById: '"
            + gid + "'}]->(d:Deployment {gid: '" + gid + "', name: '" + name
            + "', status: 'RUNNING', isCurrent: true, lastSeenAt: datetime('2026-10-05T09:00:00Z')})-[:IN_ENVIRONMENT]->(e) "
            + "CREATE (:SourceRecord {source: 'deploymap', sourceType: 'DEPLOY', sourceId: '" + gid
            + "', active: true})-[:ASSERTS {authority: 'MASTER'}]->(d)");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> list(Object o) {
    return (List<Map<String, Object>>) o;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object o) {
    return (Map<String, Object>) o;
  }

  private static List<Object> deploymentGids(QueryResult r) {
    return r.rows().stream().map(m -> m.get("deployment")).filter(java.util.Objects::nonNull)
        .map(d -> map(d).get("gid")).toList();
  }

  @Test
  void severalDeploymentsOfOneServiceInOneEnvironmentAreSeparateRows() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    deployment("d2", "billing-prod-2", "PROD");
    deployment("d3", "billing-prod-3", "PROD");

    QueryResult r = footprint(SYS, null);

    assertThat(r.truncated()).isFalse();
    assertThat(deploymentGids(r)).containsExactly("d1", "d2", "d3");
    assertThat(r.rows()).extracting(m -> map(m.get("deployment")).get("environment")).containsOnly("PROD");
  }

  @Test
  void environmentFilterNarrowsToProdAndServiceWithoutDeploymentsIsKept() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    deployment("d2", "billing-test-1", "TEST");

    assertThat(deploymentGids(footprint(SYS, "PROD"))).containsExactly("d1");
    assertThat(deploymentGids(footprint(SYS, "TEST"))).containsExactly("d2");
    assertThat(deploymentGids(footprint(SYS, null))).containsExactlyInAnyOrder("d1", "d2");
    // Окружение без развёртываний: сервис всё равно в ответе, deployment пуст.
    QueryResult none = footprint(SYS, "DEV");
    assertThat(none.rows()).hasSize(1);
    assertThat(map(none.rows().get(0).get("service")).get("gid")).isEqualTo("svc");
    assertThat(none.rows().get(0).get("deployment")).isNull();
  }

  @Test
  void vmHostedOnPhysicalServerAndDeploymentOnPhysicalServerDirectly() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    deployment("d2", "billing-prod-2", "PROD");
    seed(
        """
        MATCH (d1:Deployment {gid: 'd1'}), (d2:Deployment {gid: 'd2'})
        CREATE (ps:ComputeInstance:PhysicalServer {gid: 'ps', hostname: 'esx-1', isCurrent: true,
                                                   lastSeenAt: datetime('2026-10-05T08:00:00Z')})
        CREATE (vm:ComputeInstance:VirtualMachine {gid: 'vm', hostname: 'vm-1', state: 'RUNNING', isCurrent: true,
                                                   lastSeenAt: datetime('2026-10-05T08:30:00Z')})
        CREATE (vm)-[:HOSTED_ON {assertedBySource: 'asset', assertedByType: 'HOST', assertedById: 'h1'}]->(ps)
        CREATE (d1)-[:RUNS_ON {validFrom: datetime('2026-01-01T00:00:00Z'), assertedBySource: 'deploymap',
                               assertedByType: 'RUN', assertedById: 'r1'}]->(vm)
        CREATE (d2)-[:RUNS_ON {validFrom: datetime('2026-01-01T00:00:00Z')}]->(ps)
        CREATE (:SourceRecord {source: 'asset', sourceType: 'HOST', sourceId: 'vm', active: true})-[:ASSERTS {authority: 'MASTER'}]->(vm)
        """);

    QueryResult r = footprint(SYS, "PROD");

    Map<String, Object> viaVm = r.rows().get(0);
    Map<String, Object> vm = map(viaVm.get("compute"));
    assertThat(vm).containsEntry("type", "VirtualMachine").containsEntry("hostname", "vm-1");
    assertThat(vm.get("lastSeenAt").toString()).startsWith("2026-10-05T08:30");
    assertThat(list(vm.get("sources"))).extracting(m -> m.get("source")).containsExactly("asset");
    assertThat(map(vm.get("runsOn")))
        .containsEntry("source", "deploymap")
        .containsEntry("sourceId", "r1");
    assertThat(map(vm.get("runsOn")).get("validFrom").toString()).startsWith("2026-01-01");
    assertThat(map(viaVm.get("host"))).containsEntry("hostname", "esx-1");
    assertThat(map(map(viaVm.get("host")).get("hostedOn"))).containsEntry("source", "asset");

    Map<String, Object> direct = r.rows().get(1);
    assertThat(map(direct.get("compute"))).containsEntry("type", "PhysicalServer");
    assertThat(direct.get("host")).isNull();
  }

  @Test
  void nodeAndEdgeProvenanceIsReturnedAndInactiveSourcesAreSkipped() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");

    Map<String, Object> row = footprint(SYS, null).rows().get(0);

    Map<String, Object> system = map(row.get("system"));
    assertThat(system.get("lastSeenAt").toString()).startsWith("2026-10-05T10:00");
    assertThat(list(system.get("sources")))
        .hasSize(1)
        .first()
        .satisfies(m -> assertThat(m).containsEntry("source", "eam").containsEntry("authority", "MASTER"));
    Map<String, Object> d = map(row.get("deployment"));
    assertThat(d.get("lastSeenAt").toString()).startsWith("2026-10-05T09:00");
    assertThat(list(d.get("sources"))).extracting(m -> m.get("source")).containsExactly("deploymap");
    assertThat(map(d.get("hasDeployment")))
        .containsEntry("source", "deploymap")
        .containsEntry("sourceType", "DEPLOY")
        .containsEntry("sourceId", "d1");
  }

  @Test
  void runsOnNamespaceIsNotReturned() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    seed(
        """
        MATCH (d:Deployment {gid: 'd1'})
        CREATE (d)-[:RUNS_ON {validFrom: datetime()}]->(:Namespace {gid: 'ns', name: 'billing', isCurrent: true})
        """);

    Map<String, Object> row = footprint(SYS, null).rows().get(0);

    assertThat(map(row.get("deployment")).get("gid")).isEqualTo("d1");
    assertThat(row.get("compute")).isNull();
  }

  @Test
  void closedRunsOnAndNonCurrentNodesAreExcluded() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    deployment("d2", "billing-prod-2", "PROD");
    seed("MATCH (d:Deployment {gid: 'd2'}) SET d.isCurrent = false");
    seed(
        """
        MATCH (d1:Deployment {gid: 'd1'})
        CREATE (old:ComputeInstance:VirtualMachine {gid: 'old', hostname: 'old-vm', isCurrent: true})
        CREATE (gone:ComputeInstance:VirtualMachine {gid: 'gone', hostname: 'gone-vm', isCurrent: false})
        CREATE (d1)-[:RUNS_ON {validFrom: datetime('2020-01-01T00:00:00Z'), validTo: datetime('2021-01-01T00:00:00Z')}]->(old)
        CREATE (d1)-[:RUNS_ON {validFrom: datetime()}]->(gone)
        """);

    QueryResult r = footprint(SYS, null);

    assertThat(deploymentGids(r)).containsExactly("d1");
    assertThat(r.rows().get(0).get("compute")).isNull();

    seed("MATCH (s:Service {gid: 'svc'}) SET s.isCurrent = false");
    QueryResult noService = footprint(SYS, null);
    assertThat(noService.rows()).hasSize(1);
    assertThat(noService.rows().get(0).get("service")).isNull();
  }

  @Test
  void closedUnknownAndNonSystemGidsGiveNoRows() {
    baseGraph();
    assertThat(footprint("svc", null).rows()).isEmpty();
    assertThat(footprint("missing", null).rows()).isEmpty();
    seed("MATCH (s:ITSystem) SET s.isCurrent = false");
    assertThat(footprint(SYS, null).rows()).isEmpty();
  }

  @Test
  void coldFirstCallFitsProductionTimeout() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    long start = System.nanoTime();
    footprint(SYS, "PROD");
    Duration cold = Duration.ofNanos(System.nanoTime() - start);
    // Бюджет 5 с; запас до 3 с означает «упрощай шаблон» (UNLOCKER-216).
    assertThat(cold).isLessThan(Duration.ofSeconds(3));
  }
}
