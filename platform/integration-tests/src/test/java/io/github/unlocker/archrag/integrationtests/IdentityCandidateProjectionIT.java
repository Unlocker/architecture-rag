package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.FeatureType;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Сквозной сценарий E3.2: одинаковый hostname у двух записей CMDB даёт кандидата, а не слияние узлов. */
@Testcontainers
class IdentityCandidateProjectionIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PostgresEventJournal journal;
  static PostgresIdentityMapping identity;
  static PostgresIdentityCandidates candidates;
  static EventProcessor processor;

  @BeforeAll
  static void setUp() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    Neo4jSchema.apply(driver);
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    DataSource dataSource = ds;
    JournalMigrations.apply(dataSource);
    journal = new PostgresEventJournal(dataSource);
    identity = new PostgresIdentityMapping(dataSource);
    candidates = new PostgresIdentityCandidates(dataSource);
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor =
        new EventProcessor(
            journal, Normalizer.standard(projector::isActive), identity, projector, (source, run, id, count) -> {}, candidates);
  }

  private static ProcessingStatus computeInstance(String sourceId, String hostname) {
    var event =
        new CanonicalEvent(
            "e-" + UUID.randomUUID(), "urn:corp:cmdb", CanonicalEvent.TYPE_ASSET_UPSERTED, "COMPUTE_INSTANCE/" + sourceId, T1,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData(
                "COMPUTE_INSTANCE", sourceId, new SourceVersion("1"), Map.of("hostname", hostname, "kind", "VIRTUAL_MACHINE")));
    journal.append(event, RAW, null);
    return processor.process(event, RAW).status();
  }

  @Test
  void sameHostnameFromTwoCmdbRecordsKeepsTwoNodesAndRecordsOneCandidate() {
    String hostname = "Web-" + UUID.randomUUID() + ".Corp.";
    String idA = UUID.randomUUID().toString();
    String idB = UUID.randomUUID().toString();

    assertThat(computeInstance(idA, hostname)).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(computeInstance(idB, hostname.toUpperCase())).isEqualTo(ProcessingStatus.PROJECTED);

    UUID ga = identity.find(new SourceKey(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", idA)).orElseThrow();
    UUID gb = identity.find(new SourceKey(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", idB)).orElseThrow();
    assertThat(ga).isNotEqualTo(gb);
    long nodes =
        driver
            .executableQuery("MATCH (n:ComputeInstance) WHERE n.gid IN $gids RETURN count(n) AS c")
            .withParameters(Map.of("gids", java.util.List.of(ga.toString(), gb.toString())))
            .execute()
            .records()
            .get(0)
            .get("c")
            .asLong();
    assertThat(nodes).isEqualTo(2);
    assertThat(candidates.candidatesOf(ga)).singleElement().satisfies(c -> {
      assertThat(c.features()).containsExactly(FeatureType.HOSTNAME);
      assertThat(java.util.Set.of(c.leftGid(), c.rightGid())).containsExactlyInAnyOrder(ga, gb);
    });
  }
}
