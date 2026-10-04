package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryResult;
import io.github.unlocker.archrag.graphquerycore.QueryTemplate;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.QueryTimeoutException;
import io.github.unlocker.archrag.graphquerycore.ResultBudget;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.exceptions.ClientException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;

/** Проверяет read-only исполнитель шаблонов на Neo4j Community. */
@Testcontainers
class GraphQueryCoreIT {

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  static final QueryLimits LIMITS = new QueryLimits(6, 500, 50, Duration.ofSeconds(5), 512 * 1024);

  static Driver driver;

  @BeforeAll
  static void connect() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
  }

  @BeforeEach
  void clean() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
  }

  static QueryTemplate template(String id, String cypher, String... params) {
    return new QueryTemplate(id, cypher, Set.of(params), null);
  }

  static GraphQueryExecutor executor(QueryTemplate... templates) {
    return new GraphQueryExecutor(driver, QueryTemplateRegistry.of(List.of(templates), LIMITS));
  }

  static long nodeCount() {
    return driver.executableQuery("MATCH (n) RETURN count(n) AS c").execute().records().get(0).get("c").asLong();
  }

  static ResultBudget budget(int maxDepth, int maxNodes, Duration timeout, long bytes) {
    return new ResultBudget(maxDepth, maxNodes, 50, timeout, bytes);
  }

  @Test
  void writingTemplatesAreNotRegistered() {
    for (String cypher :
        List.of(
            "CREATE (n:X) RETURN n LIMIT $limit",
            "MERGE (n:X {gid: 'a'}) SET n.v = 1 RETURN n LIMIT $limit",
            "MATCH (n) DETACH DELETE n RETURN 1 AS ok LIMIT $limit")) {
      assertThatThrownBy(() -> executor(template("w", cypher)))
          .as(cypher)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("w");
    }
    assertThat(nodeCount()).isZero();
  }

  @Test
  void readSessionConfigRejectsWritesAtDatabaseLevel() {
    try (var session = driver.session(GraphQueryExecutor.READ_SESSION_CONFIG)) {
      assertThatThrownBy(() -> session.executeRead(tx -> tx.run("CREATE (n:X)").consume()))
          .isInstanceOf(ClientException.class)
          .hasMessageContaining("read access mode");
    }
    assertThat(nodeCount()).isZero();
  }

  @Test
  void publicApiHasNoMethodAcceptingCypher() {
    // Метод execute принимает ID шаблона (String); других публичных методов со String нет.
    List<Method> withString =
        Arrays.stream(GraphQueryExecutor.class.getMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getDeclaringClass() == GraphQueryExecutor.class)
            .filter(m -> Arrays.asList(m.getParameterTypes()).contains(String.class))
            .toList();
    assertThat(withString).extracting(Method::getName).containsExactly("execute");
    assertThat(withString.get(0).getParameterTypes()).startsWith(String.class);
    assertThat(withString.get(0).getParameterCount()).isEqualTo(3);
  }

  @Test
  void unknownTemplateAndBadParametersFailBeforeDatabase() {
    GraphQueryExecutor executor = executor(template("byGid", "MATCH (n {gid: $gid}) RETURN n.gid AS gid LIMIT $limit", "gid"));
    assertThatThrownBy(() -> executor.execute("nope", Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> executor.execute("byGid", Map.of(), null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> executor.execute("byGid", Map.of("gid", "a", "x", 1), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> executor.execute("byGid", Map.of("gid", "a", "limit", 1000000), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rowBudgetTruncates() {
    driver.executableQuery("UNWIND range(1, 20) AS i CREATE (:Item {gid: toString(i)})").execute();
    GraphQueryExecutor executor = executor(template("items", "MATCH (n:Item) RETURN n.gid AS gid LIMIT $limit"));

    QueryResult small = executor.execute("items", Map.of(), budget(2, 5, Duration.ofSeconds(5), 100_000));
    assertThat(small.rows()).hasSize(5);
    assertThat(small.rowCount()).isEqualTo(5);
    assertThat(small.truncated()).isTrue();
    assertThat(small.templateId()).isEqualTo("items");

    QueryResult all = executor.execute("items", Map.of(), budget(2, 50, Duration.ofSeconds(5), 100_000));
    assertThat(all.rows()).hasSize(20);
    assertThat(all.truncated()).isFalse();
  }

  @Test
  void byteBudgetTruncates() {
    driver.executableQuery("UNWIND range(1, 20) AS i CREATE (:Item {gid: toString(i)})").execute();
    GraphQueryExecutor executor = executor(template("items", "MATCH (n:Item) RETURN n AS n LIMIT $limit"));

    QueryResult result = executor.execute("items", Map.of(), budget(2, 50, Duration.ofSeconds(5), 150));
    assertThat(result.truncated()).isTrue();
    assertThat(result.rows()).isNotEmpty().hasSizeLessThan(20);
  }

  @Test
  void requestedBudgetIsClampedToConfiguredCeiling() {
    driver.executableQuery("UNWIND range(1, 20) AS i CREATE (:Item {gid: toString(i)})").execute();
    QueryLimits tight = new QueryLimits(6, 3, 50, Duration.ofSeconds(5), 512 * 1024);
    GraphQueryExecutor executor =
        new GraphQueryExecutor(
            driver,
            QueryTemplateRegistry.of(
                List.of(template("items", "MATCH (n:Item) RETURN n.gid AS gid LIMIT $limit")), tight));
    QueryResult result = executor.execute("items", Map.of(), budget(6, 1000, Duration.ofSeconds(5), 100_000));
    assertThat(result.rows()).hasSize(3);
    assertThat(result.truncated()).isTrue();
  }

  @Test
  void timeoutGivesQueryTimeoutException() {
    GraphQueryExecutor executor =
        executor(
            template(
                "heavy",
                "UNWIND range(1, 100000000) AS i WITH i WHERE i % 7 = 0 RETURN sum(i) AS s LIMIT $limit"));
    long start = System.nanoTime();
    assertThatThrownBy(() -> executor.execute("heavy", Map.of(), budget(2, 5, Duration.ofMillis(100), 100_000)))
        .isInstanceOf(QueryTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
  }

  @Test
  void traversalDepthIsBoundedByMaxDepth() {
    driver.executableQuery(
            "CREATE (:N {gid: '0'})-[:NEXT]->(:N {gid: '1'})-[:NEXT]->(:N {gid: '2'})-[:NEXT]->(:N {gid: '3'})")
        .execute();
    GraphQueryExecutor executor =
        executor(
            template(
                "walk",
                "MATCH (a:N {gid: $gid})-[:NEXT*1..{maxDepth}]->(b:N) RETURN b.gid AS gid ORDER BY gid LIMIT $limit",
                "gid"));
    QueryResult depth2 = executor.execute("walk", Map.of("gid", "0"), budget(2, 50, Duration.ofSeconds(5), 100_000));
    assertThat(depth2.rows()).extracting(r -> r.get("gid")).containsExactly("1", "2");
    QueryResult depth3 = executor.execute("walk", Map.of("gid", "0"), budget(3, 50, Duration.ofSeconds(5), 100_000));
    assertThat(depth3.rows()).extracting(r -> r.get("gid")).containsExactly("1", "2", "3");
  }
}
