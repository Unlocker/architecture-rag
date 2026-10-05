package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeInstance;
import io.github.unlocker.archrag.canonicalmodel.node.ComputeKind;
import io.github.unlocker.archrag.canonicalmodel.node.Deployment;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.node.Team;
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

/** Шаблоны {@code get_asset} и {@code asset_relations} на Neo4j Community со схемой из {@link Neo4jSchema}. */
@Testcontainers
class GetAssetIT {

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(Long.getLong("archrag.it.queryTimeoutSeconds", 5)), 512 * 1024);
  static final String GID_PAY = "11111111-1111-1111-1111-111111111111";
  static final String GID_BILLING = "22222222-2222-2222-2222-222222222222";
  static final String GID_TEAM = "33333333-3333-3333-3333-333333333333";
  static final String GID_OLD = "44444444-4444-4444-4444-444444444444";
  static final String GID_VM = "55555555-5555-5555-5555-555555555555";
  static final String GID_VM_OLD = "66666666-6666-6666-6666-666666666666";
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
        new GraphQueryExecutor(
            reader,
            QueryTemplateRegistry.of(List.of(AssetTemplates.GET_ASSET, AssetTemplates.ASSET_RELATIONS), LIMITS));
  }

  @BeforeEach
  void seed() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    fixtures = new AssetFixtures(new GraphProjector(driver, AuthorityMatrix.defaults()));
    var t1 = Instant.parse("2026-01-01T10:00:00Z");
    var t2 = Instant.parse("2026-01-02T10:00:00Z");

    var pay = fixtures.node(UUID.fromString(GID_PAY), "system", "EAM-1", new ITSystem("Payments Platform", null, null, null), t1);
    var billing = fixtures.node(UUID.fromString(GID_BILLING), "component", "billing", new Service("Billing", null, null, null), t1);
    fixtures.nodeFrom(SourceSystemCode.EAM, UUID.fromString(GID_BILLING), "service", "EAM-9", new Service("Billing", null, null, null), t2);
    var team = fixtures.node(UUID.fromString(GID_TEAM), "team", "team-1", new Team("Payments Team", null), t1);
    var dep = fixtures.node(UUID.fromString(GID_DEP), "deployment", "billing-prod", new Deployment("billing-prod", "billing-prod", null, null, null), t1);
    var vm = fixtures.node(UUID.fromString(GID_VM), "host", "vm-prod-01", new ComputeInstance("vm-prod-01", ComputeKind.VIRTUAL_MACHINE, null, null, null, null, null), t1);
    var vmOld = fixtures.node(UUID.fromString(GID_VM_OLD), "host", "vm-prod-old", new ComputeInstance("vm-prod-old", ComputeKind.UNSPECIFIED, null, null, null, null, null), t1);
    // Закрытый узел: единственная мастер-запись tombstone-ится.
    var old = fixtures.node(UUID.fromString(GID_OLD), "component", "legacy", new Service("Legacy", null, null, null), t1);
    fixtures.tombstone(old, UUID.fromString(GID_OLD), t2);

    fixtures.relation(RelationType.DECOMPOSED_INTO, pay, UUID.fromString(GID_PAY), billing, UUID.fromString(GID_BILLING), null, t1);
    fixtures.relation(RelationType.HAS_DEPLOYMENT, billing, UUID.fromString(GID_BILLING), dep, UUID.fromString(GID_DEP), null, t1);
    fixtures.relation(RelationType.OWNED_BY, billing, UUID.fromString(GID_BILLING), team, UUID.fromString(GID_TEAM), null, t1);
    fixtures.relation(RelationType.RUNS_ON, dep, UUID.fromString(GID_DEP), vm, UUID.fromString(GID_VM), new Validity(t1, null), t1);
    fixtures.relation(RelationType.RUNS_ON, dep, UUID.fromString(GID_DEP), vmOld, UUID.fromString(GID_VM_OLD),
        new Validity(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-06-01T00:00:00Z")), t1);
  }

  private static QueryResult run(String template, String gid, int maxNodes) {
    var budget = new ResultBudget(1, maxNodes, 1, LIMITS.timeout(), LIMITS.maxResponseBytes());
    return executor.execute(template, Map.of("gid", gid), budget);
  }

  @Test
  @SuppressWarnings("unchecked")
  void cardHasPropertiesTimesAndSourcesWithAuthority() {
    var result = run("get_asset", GID_BILLING, 10);

    assertThat(result.rows()).singleElement().satisfies(row -> {
      assertThat(row).containsEntry("gid", GID_BILLING).containsEntry("type", "Service").containsEntry("isCurrent", true);
      assertThat(row.get("lastSeenAt").toString()).startsWith("2026-01-02T10:00");
      assertThat(row.get("firstSeenAt").toString()).startsWith("2026-01-01T10:00");
      assertThat(row.get("deletedAt")).isNull();
      assertThat((Map<String, Object>) row.get("properties")).containsEntry("name", "Billing");
      var sources = (List<Map<String, Object>>) row.get("sources");
      assertThat(sources).hasSize(2).allSatisfy(s -> assertThat(s).containsEntry("active", true).doesNotContainKey("contentHash"));
      assertThat(sources).extracting(s -> s.get("source") + ":" + s.get("authority"))
          .containsExactlyInAnyOrder("SCM:MASTER", "EAM:SUPPLEMENTARY");
    });
  }

  @Test
  @SuppressWarnings("unchecked")
  void closedNodeIsReturnedNotCurrentWithInactiveRecord() {
    var result = run("get_asset", GID_OLD, 10);

    assertThat(result.rows()).singleElement().satisfies(row -> {
      assertThat(row).containsEntry("isCurrent", false);
      assertThat(row.get("deletedAt")).isNotNull();
      assertThat((List<Map<String, Object>>) row.get("sources"))
          .singleElement()
          .satisfies(s -> assertThat(s).containsEntry("active", false));
    });
  }

  @Test
  void unknownGidAndServiceNodeGidReturnNothing() {
    assertThat(run("get_asset", "99999999-9999-9999-9999-999999999999", 10).rows()).isEmpty();
    // gid служебного узла (SourceRecord) не читается: шаблон ограничен метками активов.
    try (var session = driver.session()) {
      String recordGid = session.run("MATCH (r:SourceRecord) RETURN coalesce(r.gid, 'none') AS g LIMIT 1").single().get("g").asString();
      assertThat(run("get_asset", recordGid, 10).rows()).isEmpty();
    }
  }

  @Test
  void relationsAreCurrentOneLevelBothDirectionsFromAllowlist() {
    var result = run("asset_relations", GID_BILLING, 10);

    assertThat(result.rows())
        .extracting(r -> r.get("relationType") + ":" + r.get("direction") + ":" + r.get("gid"))
        .containsExactly(
            "DECOMPOSED_INTO:IN:" + GID_PAY,
            "HAS_DEPLOYMENT:OUT:" + GID_DEP,
            "OWNED_BY:OUT:" + GID_TEAM);
    assertThat(result.rows().getFirst()).containsEntry("type", "ITSystem").containsEntry("name", "Payments Platform").containsEntry("isCurrent", true);
  }

  @Test
  void closedRelationIsNotReturned() {
    var result = run("asset_relations", GID_DEP, 10);

    assertThat(result.rows())
        .extracting(r -> r.get("relationType") + ":" + r.get("gid"))
        .contains("RUNS_ON:" + GID_VM)
        .doesNotContain("RUNS_ON:" + GID_VM_OLD);
  }

  @Test
  void relationsRespectNodeBudget() {
    var result = run("asset_relations", GID_BILLING, 1);

    assertThat(result.rows()).hasSize(1);
    assertThat(result.truncated()).isTrue();
  }
}
