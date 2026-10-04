package io.github.unlocker.archrag.integrationtests;

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
import org.testcontainers.utility.DockerImageName;

/** Проверяет, что Neo4j Community, PostgreSQL и MinIO поднимаются и отвечают. */
@Testcontainers
class ContainersSmokeIT {

  /**
   * Образ S3-эмулятора. Репозиторий {@code minio/minio} исчез с Docker Hub (404), поэтому берём
   * {@code chainguard/minio}, закреплённый по digest: у него есть только плавающий {@code latest}.
   */
  static final DockerImageName MINIO_IMAGE = DockerImageName
      .parse("chainguard/minio@sha256:4cf4831a2bbcf13ddca09c1cbcc9faff716dd3c4247e0babc32864b8ee8e0034")
      .asCompatibleSubstituteFor("minio/minio");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final MinIOContainer MINIO = new MinIOContainer(MINIO_IMAGE);

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
