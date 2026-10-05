package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.templates.FootprintTemplates;
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

/** Проверяет шаблон find_runtime_footprint на Neo4j Community с продовыми лимитами (timeout 5s). */
@Testcontainers
class FootprintIT {

  @Container static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

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
            readerDriver, QueryTemplateRegistry.of(List.of(FootprintTemplates.FIND_RUNTIME_FOOTPRINT), LIMITS));
    Map<String, Object> params = new HashMap<>();
    params.put("systemGid", systemGid);
    params.put("environment", environment);
    return executor.execute(FootprintTemplates.FIND_RUNTIME_FOOTPRINT_ID, params, null);
  }

  /** Система sys с сервисом svc; окружения PROD и TEST. */
  private static void baseGraph() {
    seed(
        """
        CREATE (sys:ITSystem {gid: 'sys', name: 'Billing', isCurrent: true})
        CREATE (svc:Service {gid: 'svc', name: 'billing-api', isCurrent: true})
        CREATE (sys)-[:DECOMPOSED_INTO]->(svc)
        CREATE (:Environment {gid: 'e-prod', code: 'PROD', isCurrent: true})
        CREATE (:Environment {gid: 'e-test', code: 'TEST', isCurrent: true})
        """);
  }

  private static void deployment(String gid, String name, String env) {
    seed(
        "MATCH (svc:Service {gid: 'svc'}), (e:Environment {code: '" + env + "'}) "
            + "CREATE (svc)-[:HAS_DEPLOYMENT]->(d:Deployment {gid: '" + gid + "', name: '" + name
            + "', isCurrent: true})-[:IN_ENVIRONMENT]->(e)");
  }

  @Test
  void severalDeploymentsOfOneServiceInOneEnvironmentAreSeparateRows() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    deployment("d2", "billing-prod-2", "PROD");
    deployment("d3", "billing-prod-3", "PROD");

    QueryResult r = footprint("sys", null);

    assertThat(r.truncated()).isFalse();
    assertThat(r.rows()).extracting(m -> m.get("name")).containsExactly("billing-prod-1", "billing-prod-2", "billing-prod-3");
    assertThat(r.rows()).extracting(m -> m.get("environment")).containsOnly("PROD");
    assertThat(r.rows()).allSatisfy(m -> assertThat((List<?>) m.get("instances")).isEmpty());
  }

  @Test
  void environmentFilterNarrowsToProd() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    deployment("d2", "billing-test-1", "TEST");

    assertThat(footprint("sys", "PROD").rows()).extracting(m -> m.get("gid")).containsExactly("d1");
    assertThat(footprint("sys", "TEST").rows()).extracting(m -> m.get("gid")).containsExactly("d2");
    assertThat(footprint("sys", null).rows()).extracting(m -> m.get("gid")).containsExactlyInAnyOrder("d1", "d2");
    // Окружение без развёртываний: система есть, развёртываний нет.
    QueryResult none = footprint("sys", "DEV");
    assertThat(none.rows()).hasSize(1);
    assertThat(none.rows().get(0).get("gid")).isNull();
    assertThat(none.rows().get(0).get("systemName")).isEqualTo("Billing");
  }

  @Test
  @SuppressWarnings("unchecked")
  void vmHostedOnPhysicalServerAndDeploymentOnPhysicalServerDirectly() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    deployment("d2", "billing-prod-2", "PROD");
    seed(
        """
        MATCH (d1:Deployment {gid: 'd1'}), (d2:Deployment {gid: 'd2'})
        CREATE (ps:ComputeInstance:PhysicalServer {gid: 'ps', hostname: 'esx-1', serialNumber: 'SN1', isCurrent: true})
        CREATE (vm:ComputeInstance:VirtualMachine {gid: 'vm', hostname: 'vm-1', state: 'RUNNING', isCurrent: true})
        CREATE (vm)-[:HOSTED_ON]->(ps)
        CREATE (d1)-[:RUNS_ON {validFrom: datetime()}]->(vm)
        CREATE (d2)-[:RUNS_ON {validFrom: datetime()}]->(ps)
        """);

    QueryResult r = footprint("sys", "PROD");

    Map<String, Object> viaVm = r.rows().get(0);
    List<Map<String, Object>> vmInstances = (List<Map<String, Object>>) viaVm.get("instances");
    assertThat(vmInstances).hasSize(1);
    assertThat(vmInstances.get(0)).containsEntry("type", "VirtualMachine").containsEntry("hostname", "vm-1");
    assertThat((Map<String, Object>) vmInstances.get(0).get("hostedOn"))
        .containsEntry("hostname", "esx-1")
        .containsEntry("serialNumber", "SN1");
    List<Map<String, Object>> direct = (List<Map<String, Object>>) r.rows().get(1).get("instances");
    assertThat(direct).hasSize(1);
    assertThat(direct.get(0)).containsEntry("type", "PhysicalServer").containsEntry("hostedOn", null);
  }

  @Test
  @SuppressWarnings("unchecked")
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

    QueryResult r = footprint("sys", null);

    assertThat(r.rows()).extracting(m -> m.get("gid")).containsExactly("d1");
    assertThat((List<?>) r.rows().get(0).get("instances")).isEmpty();

    seed("MATCH (s:Service {gid: 'svc'}) SET s.isCurrent = false");
    QueryResult noService = footprint("sys", null);
    assertThat(noService.rows()).hasSize(1);
    assertThat(noService.rows().get(0).get("gid")).isNull();
  }

  @Test
  void closedSystemGivesEmptyFootprintWithIsCurrentFalse() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    seed("MATCH (s:ITSystem {gid: 'sys'}) SET s.isCurrent = false");

    QueryResult r = footprint("sys", null);

    assertThat(r.rows()).hasSize(1);
    assertThat(r.rows().get(0)).containsEntry("systemIsCurrent", false).containsEntry("gid", null);
  }

  @Test
  void nonSystemGidAndUnknownGidGiveNoRows() {
    baseGraph();
    assertThat(footprint("svc", null).rows()).isEmpty();
    assertThat(footprint("missing", null).rows()).isEmpty();
  }

  @Test
  void coldFirstCallFitsProductionTimeout() {
    baseGraph();
    deployment("d1", "billing-prod-1", "PROD");
    long start = System.nanoTime();
    footprint("sys", "PROD");
    Duration cold = Duration.ofNanos(System.nanoTime() - start);
    // Бюджет 5 с; запас до 3 с означает «упрощай шаблон» (UNLOCKER-216).
    assertThat(cold).isLessThan(Duration.ofSeconds(3));
  }
}
