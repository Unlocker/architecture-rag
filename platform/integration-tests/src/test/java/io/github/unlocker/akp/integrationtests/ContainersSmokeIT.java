package io.github.unlocker.akp.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Проверяет, что Neo4j Community, PostgreSQL и MinIO поднимаются и отвечают. */
@Testcontainers
class ContainersSmokeIT {

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-18T13-15-44Z");

  @Test
  void neo4jAnswersCypher() {
    try (Driver driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()))) {
      assertThat(driver.executableQuery("RETURN 1 AS n").execute().records().get(0).get("n").asInt()).isEqualTo(1);
    }
  }

  @Test
  void postgresAnswersSelect() throws Exception {
    try (var conn = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
         var rs = conn.createStatement().executeQuery("select 1")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(1);
    }
  }

  @Test
  void minioIsRunning() {
    assertThat(MINIO.isRunning()).isTrue();
  }
}
