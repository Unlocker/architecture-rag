package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.ProcessingResult;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.identityresolution.PostgresSourceConflicts;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code DEPLOYED_FROM} и привязка {@code Deployment} к репозиторию на реальных Neo4j и PostgreSQL (H.2):
 * смена commit закрывает старое ребро и открывает новое, тот же commit ничего не меняет, отложенная связь
 * достраивается при появлении репозитория, неверный commit уходит в карантин без изменения графа.
 */
@Testcontainers
class DeployedFromIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");
  private static final Instant T3 = Instant.parse("2026-10-03T10:00:00Z");
  private static final String COMMIT_A = "9fceb02d0ae598e95dc970b74767f19372d61af8";
  private static final String COMMIT_B = "0123456789abcdef0123456789abcdef01234567";
  private static final String URL = "https://git.example.org/pay/payments-api";

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PostgresEventJournal journal;
  static EventProcessor processor;

  @BeforeAll
  static void setUp() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    Neo4jSchema.apply(driver);
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    JournalMigrations.apply(ds);
    journal = new PostgresEventJournal(ds);
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor =
        new EventProcessor(
            journal,
            Normalizer.standard(projector::isActive, projector),
            new PostgresIdentityMapping(ds),
            projector,
            (source, run, id, count) -> {},
            new PostgresIdentityCandidates(ds),
            new PostgresSourceConflicts(ds, AuthorityMatrix.defaults()));
  }

  @BeforeEach
  void clearGraph() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static ProcessingResult handle(
      String source, String type, String id, String version, Instant time, Map<String, Object> payload) {
    var event =
        new CanonicalEvent(
            "e-" + UUID.randomUUID(), "urn:corp:" + source, CanonicalEvent.TYPE_ASSET_UPSERTED, type + "/" + id, time,
            "urn:corp:schema:asset-upserted:1", null, new AssetEventData(type, id, new SourceVersion(version), payload));
    journal.append(event, RAW, null);
    return processor.process(event, RAW);
  }

  private static ProcessingResult repository(String version, Instant time) {
    return handle("scm", "REPOSITORY", "repo-payments-api", version, time, Map.of("url", "git@git.example.org:pay/payments-api.git"));
  }

  private static ProcessingResult service(String id, String version, Instant time) {
    return handle("scm", "SERVICE", id, version, time, Map.of("name", id, "repositoryId", "repo-payments-api"));
  }

  private static ProcessingResult deployment(String version, Instant time, String commit, String service) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("format", "deploymap-poc-1");
    p.put("name", "payments-api");
    if (service != null) {
      p.put("service", service);
    }
    p.put(
        "chartMeta",
        Map.of(
            "name", "payments-api", "version", "1.4.2",
            "annotations",
            Map.of(
                "archrag.io/eam-system-id", "5101",
                "archrag.io/git-repo-url", "git@git.example.org:pay/payments-api.git",
                "archrag.io/git-commit", commit)));
    return handle("deploymap", "DEPLOYMENT", "dep-1", version, time, p);
  }

  private static List<Record> query(String cypher, Object... params) {
    Map<String, Object> map = new TreeMap<>();
    for (int i = 0; i < params.length; i += 2) {
      map.put((String) params[i], params[i + 1]);
    }
    return driver.executableQuery(cypher).withParameters(map).execute().records();
  }

  private static List<Record> deployedFrom() {
    return query(
        "MATCH (d:Deployment)-[rel:DEPLOYED_FROM]->(r:Repository) "
            + "RETURN rel.commit AS commit, rel.validFrom AS validFrom, rel.validTo AS validTo, r.url AS url "
            + "ORDER BY rel.validFrom, rel.commit");
  }

  // ---- tests -------------------------------------------------------------------------------

  @Test
  void deploymentIsBoundToRepositoryAndServiceFoundByUrl() {
    assertThat(repository("1", T1).status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(service("svc-payments-api", "1", T1).status()).isEqualTo(ProcessingStatus.PROJECTED);

    // service в payload не указан: сервис находится по репозиторию через IMPLEMENTED_IN
    assertThat(deployment("1", T2, COMMIT_A, null).status()).isEqualTo(ProcessingStatus.PROJECTED);

    var dep = query("MATCH (d:Deployment) RETURN d").get(0).get("d").asNode();
    assertThat(dep.get("sourceCommit").asString()).isEqualTo(COMMIT_A);
    assertThat(dep.get("repositoryUrl").asString()).isEqualTo(URL);
    assertThat(dep.get("eamSystemId").asLong()).isEqualTo(5101L);
    assertThat(deployedFrom()).singleElement().satisfies(r -> {
      assertThat(r.get("commit").asString()).isEqualTo(COMMIT_A);
      assertThat(r.get("url").asString()).isEqualTo(URL);
      assertThat(r.get("validTo").isNull()).isTrue();
    });
    assertThat(query("MATCH (s:Service {name: 'svc-payments-api'})-[rel:HAS_DEPLOYMENT]->(:Deployment) "
            + "WHERE rel.validTo IS NULL RETURN count(rel) AS c").get(0).get("c").asLong())
        .isEqualTo(1);
  }

  @Test
  void newCommitClosesOldDeployedFromAndOpensNewOne() {
    repository("1", T1);
    service("svc-payments-api", "1", T1);
    deployment("1", T2, COMMIT_A, null);

    assertThat(deployment("2", T3, COMMIT_B, null).status()).isEqualTo(ProcessingStatus.PROJECTED);

    var edges = deployedFrom();
    assertThat(edges).hasSize(2);
    var old = edges.stream().filter(r -> r.get("commit").asString().equals(COMMIT_A)).findFirst().orElseThrow();
    var current = edges.stream().filter(r -> r.get("commit").asString().equals(COMMIT_B)).findFirst().orElseThrow();
    assertThat(old.get("validTo").isNull()).isFalse();
    assertThat(old.get("validTo").asOffsetDateTime().toInstant()).isEqualTo(T3);
    assertThat(current.get("validTo").isNull()).isTrue();
    assertThat(query("MATCH (d:Deployment) RETURN d.sourceCommit AS c").get(0).get("c").asString()).isEqualTo(COMMIT_B);
  }

  @Test
  void sameCommitInNewVersionChangesNothing() {
    repository("1", T1);
    service("svc-payments-api", "1", T1);
    deployment("1", T2, COMMIT_A, null);
    var before = deployedFrom();

    assertThat(deployment("2", T3, COMMIT_A, null).status()).isEqualTo(ProcessingStatus.PROJECTED);

    var after = deployedFrom();
    assertThat(after).hasSize(1);
    assertThat(after.get(0).get("validTo").isNull()).isTrue();
    assertThat(after.get(0).get("validFrom")).isEqualTo(before.get(0).get("validFrom"));
  }

  @Test
  void deferredDeployedFromIsBuiltWhenRepositoryArrives() {
    // Deploy map раньше SCM: репозиторий неизвестен, связь откладывается на alias по URL.
    assertThat(deployment("1", T1, COMMIT_A, null).status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(deployedFrom()).isEmpty();
    assertThat(query("MATCH (p:PendingRelation {type: 'DEPLOYED_FROM'}) RETURN count(p) AS c").get(0).get("c").asLong())
        .isEqualTo(1);

    assertThat(repository("1", T2).status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(deployedFrom()).singleElement().satisfies(r -> {
      assertThat(r.get("commit").asString()).isEqualTo(COMMIT_A);
      assertThat(r.get("validTo").isNull()).isTrue();
    });
    assertThat(query("MATCH (p:PendingRelation) RETURN count(p) AS c").get(0).get("c").asLong()).isZero();
  }

  @Test
  void commitChangeWhileRepositoryIsUnknownClosesOldEdge() {
    repository("1", T1);
    deployment("1", T2, COMMIT_A, null);
    assertThat(deployedFrom()).hasSize(1);
    driver.executableQuery("MATCH (r:SourceRecord {source: 'SCM', sourceType: 'REPOSITORY'}) SET r.active = false").execute();

    // Репозиторий снят с активных: новая версия откладывает связь, а старое ребро всё равно закрывается.
    deployment("2", T3, COMMIT_B, null);

    assertThat(deployedFrom()).singleElement().satisfies(r -> {
      assertThat(r.get("commit").asString()).isEqualTo(COMMIT_A);
      assertThat(r.get("validTo").asOffsetDateTime().toInstant()).isEqualTo(T3);
    });
  }

  @Test
  void invalidCommitIsQuarantinedAndGraphIsUntouched() {
    repository("1", T1);
    var before = query("MATCH (n) RETURN count(n) AS c").get(0).get("c").asLong();

    var result = deployment("1", T2, COMMIT_A.toUpperCase(), null);

    assertThat(result.status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(result.errorCode()).isEqualTo("INVALID_PAYLOAD");
    assertThat(query("MATCH (n) RETURN count(n) AS c").get(0).get("c").asLong()).isEqualTo(before);
    assertThat(query("MATCH (d:Deployment) RETURN d")).isEmpty();
  }
}
