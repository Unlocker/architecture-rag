package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Проверяет, что Neo4j Community, PostgreSQL и S3-совместимое хранилище (SeaweedFS) поднимаются и отвечают. */
@Testcontainers
class ContainersSmokeIT {

  /**
   * Образ S3-совместимого хранилища для тестов и стенда. MinIO больше не публикует образы, а
   * {@code chainguard/minio} по digest хрупок (у бесплатного образа только плавающий {@code latest}),
   * поэтому используем SeaweedFS (Apache 2.0), закреплённый тегом. E1.1 и E5.1 берут образ отсюда.
   */
  static final DockerImageName S3_IMAGE = DockerImageName.parse("chrislusf/seaweedfs:4.48");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  @Container
  static final GenericContainer<?> S3 = new GenericContainer<>(S3_IMAGE)
      .withCommand("server", "-s3", "-dir=/data")
      .withExposedPorts(8333)
      .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching(c -> c < 500))
      .withStartupTimeout(Duration.ofSeconds(120));

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
  void s3StorageAnswers() throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://" + S3.getHost() + ":" + S3.getMappedPort(8333) + "/smoke-bucket"))
        .PUT(HttpRequest.BodyPublishers.noBody())
        .build();
    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isBetween(200, 299);
  }
}
