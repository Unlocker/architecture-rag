package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.exceptions.ClientException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

/** Проверяет схему Neo4j на Community: идемпотентность, составной UNIQUE, FULLTEXT. */
@Testcontainers
class Neo4jSchemaIT {

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  static Driver driver;

  @BeforeAll
  static void connect() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    Neo4jSchema.apply(driver);
    Neo4jSchema.apply(driver);
  }

  private static List<String> names(String query) {
    List<String> result = new ArrayList<>();
    driver.executableQuery(query).execute().records().forEach(r -> result.add(r.get("name").asString()));
    return result;
  }

  private static void run(String query, Map<String, Object> params) {
    driver.executableQuery(query).withParameters(params).execute();
  }

  @Test
  void applyTwiceCreatesEveryObjectExactlyOnce() {
    List<String> constraints = names("SHOW CONSTRAINTS YIELD name RETURN name");
    List<String> indexes = names("SHOW INDEXES YIELD name RETURN name");
    List<String> expectedConstraints = new ArrayList<>(List.of(
        "it_system_gid", "service_gid", "repository_gid", "team_gid", "environment_gid", "deployment_gid", "compute_instance_gid", "virtual_machine_gid", "physical_server_gid", "namespace_gid", "kubernetes_cluster_gid"));
    expectedConstraints.addAll(List.of("source_record_key", "source_system_code", "sync_run_id", "environment_code"));
    assertThat(constraints).containsExactlyInAnyOrderElementsOf(expectedConstraints);
    assertThat(indexes.stream().filter("asset_text"::equals)).hasSize(1);
    assertThat(indexes).doesNotHaveDuplicates();
  }

  @Test
  void duplicateSourceRecordKeyIsRejectedButOtherSourceTypeAccepted() {
    String create = "CREATE (:SourceRecord {source: $s, sourceType: $t, sourceId: $i})";
    run(create, Map.of("s", "EAM", "t", "IT_SYSTEM", "i", "1042"));
    assertThatThrownBy(() -> run(create, Map.of("s", "EAM", "t", "IT_SYSTEM", "i", "1042")))
        .isInstanceOf(ClientException.class)
        .extracting(e -> ((ClientException) e).code())
        .isEqualTo("Neo.ClientError.Schema.ConstraintValidationFailed");
    run(create, Map.of("s", "EAM", "t", "SERVICE", "i", "1042"));
  }

  @Test
  void duplicateServiceGidAndEnvironmentCodeAreRejected() {
    run("CREATE (:Service {gid: $g})", Map.of("g", "svc-1"));
    assertThatThrownBy(() -> run("CREATE (:Service {gid: $g})", Map.of("g", "svc-1")))
        .isInstanceOf(ClientException.class);
    run("CREATE (:Environment {code: $c})", Map.of("c", "prod"));
    assertThatThrownBy(() -> run("CREATE (:Environment {code: $c})", Map.of("c", "prod")))
        .isInstanceOf(ClientException.class);
  }

  @Test
  void fulltextFindsItSystemByName() {
    run("CREATE (:ITSystem {gid: 'its-1', name: 'Payments', description: 'billing'})", Map.of());
    driver.executableQuery("CALL db.awaitIndexes()").execute();
    var records = driver.executableQuery(
        "CALL db.index.fulltext.queryNodes('asset_text', 'Payments') YIELD node RETURN node.gid AS gid")
        .execute().records();
    assertThat(records).extracting(r -> r.get("gid").asString()).contains("its-1");
  }
}
