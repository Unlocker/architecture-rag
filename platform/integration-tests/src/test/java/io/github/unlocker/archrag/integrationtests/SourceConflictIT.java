package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.node.Criticality;
import io.github.unlocker.archrag.canonicalmodel.node.ITSystem;
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
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.identityresolution.PostgresSourceConflicts;
import io.github.unlocker.archrag.identityresolution.SourceConflict;
import io.github.unlocker.archrag.normalizer.CanonicalMapper;
import io.github.unlocker.archrag.normalizer.CmdbMapper;
import io.github.unlocker.archrag.normalizer.DeployMapMapper;
import io.github.unlocker.archrag.normalizer.EamMapper;
import io.github.unlocker.archrag.normalizer.Normalizer;
import io.github.unlocker.archrag.normalizer.ScmMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

/**
 * E3.3 через {@link EventProcessor} на реальных PostgreSQL и Neo4j Community: конфликт по authority matrix
 * не меняет canonical-значение, хранится в PostgreSQL и отмечается маркером на ребре {@code ASSERTS}.
 *
 * <p>Стандартный {@code CmdbMapper} принимает только {@code COMPUTE_INSTANCE}, поэтому сценарий «CMDB присылает
 * свойство ITSystem» воспроизводится тестовым маппером CMDB для {@code IT_SYSTEM}.
 */
@Testcontainers
class SourceConflictIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PostgresEventJournal journal;
  static PostgresIdentityMapping identity;
  static PostgresSourceConflicts conflicts;
  static EventProcessor processor;

  /** CMDB, присылающий ITSystem: в PoC такого пути в нормализаторе нет. */
  private static final class CmdbSystemMapper implements CanonicalMapper {
    private final CmdbMapper standard = new CmdbMapper();

    @Override
    public SourceSystemCode source() {
      return SourceSystemCode.CMDB;
    }

    @Override
    public Set<String> supportedSchemaVersions() {
      return standard.supportedSchemaVersions();
    }

    @Override
    public void map(Input input, io.github.unlocker.archrag.normalizer.CommandSink sink) {
      if (!"IT_SYSTEM".equals(input.key().sourceType())) {
        standard.map(input, sink);
        return;
      }
      sink.add(
          new UpsertNode(
              input.record(),
              new ITSystem(
                  (String) input.payload().get("name"),
                  null,
                  Criticality.valueOf((String) input.payload().get("criticality")),
                  null)));
    }
  }

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
    identity = new PostgresIdentityMapping(ds);
    conflicts = new PostgresSourceConflicts(ds, AuthorityMatrix.defaults());
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    var normalizer =
        new Normalizer(
            List.of(new EamMapper(), new ScmMapper(), new CmdbSystemMapper(), new DeployMapMapper()), projector::isActive);
    processor =
        new EventProcessor(
            journal, normalizer, identity, projector, (source, run, id, count) -> {}, new PostgresIdentityCandidates(ds), conflicts);
  }

  private static String uid() {
    return UUID.randomUUID().toString();
  }

  private static ProcessingStatus send(String source, String id, String version, Map<String, Object> payload) {
    var event =
        new CanonicalEvent(
            "e-" + uid(), "urn:corp:" + source, CanonicalEvent.TYPE_ASSET_UPSERTED, "IT_SYSTEM/" + id, T1,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData("IT_SYSTEM", id, new SourceVersion(version), payload));
    journal.append(event, RAW, null);
    return processor.process(event, RAW).status();
  }

  private static ProcessingStatus eam(String id, String version, String criticality) {
    return send("eam", id, version, Map.of("name", "Billing", "criticality", criticality));
  }

  private static ProcessingStatus cmdb(String id, String version, String criticality) {
    return send("cmdb", id, version, Map.of("name", "Billing", "criticality", criticality));
  }

  private static SourceKey eamKey(String id) {
    return new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", id);
  }

  private static SourceKey cmdbKey(String id) {
    return new SourceKey(SourceSystemCode.CMDB, "IT_SYSTEM", id);
  }

  /** Crosswalk до первого события любой из записей: иначе approve даст CrosswalkConflictException. */
  private static UUID link(String id) {
    return identity.approve(new Crosswalk(eamKey(id), cmdbKey(id), "admin", "same system", T1));
  }

  private static String criticalityOf(UUID gid) {
    return driver.executableQuery("MATCH (n:ITSystem {gid: $gid}) RETURN n.criticality AS c")
        .withParameters(Map.of("gid", gid.toString())).execute().records().get(0).get("c").asString();
  }

  /** Маркер на ребре ASSERTS записи; {@code null}, если свойства нет. */
  private static List<String> marker(SourceKey record) {
    var rows =
        driver.executableQuery(
                "MATCH (r:SourceRecord {source: $s, sourceType: $t, sourceId: $i})-[a:ASSERTS]->() RETURN a.conflicts AS c")
            .withParameters(Map.of("s", record.source().name(), "t", record.sourceType(), "i", record.sourceId()))
            .execute()
            .records();
    assertThat(rows).hasSize(1);
    return rows.get(0).get("c").isNull() ? null : rows.get(0).get("c").asList(v -> v.asString());
  }

  @Test
  void nonAuthoritativeValueOpensConflictAndMarksOnlyDissentEdge() {
    String id = uid();
    UUID gid = link(id);

    assertThat(eam(id, "1", "HIGH")).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(cmdb(id, "1", "LOW")).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(criticalityOf(gid)).isEqualTo("HIGH");
    assertThat(conflicts.openConflicts(gid)).singleElement().satisfies(c -> {
      assertThat(c.property()).isEqualTo("criticality");
      assertThat(c.dissent()).isEqualTo(cmdbKey(id));
      assertThat(c.master()).isEqualTo(eamKey(id));
      assertThat(c.dissentValue()).isEqualTo("LOW");
      assertThat(c.masterValue()).isEqualTo("HIGH");
      assertThat(c.status()).isEqualTo(SourceConflict.Status.OPEN);
      assertThat(c.openedAt()).isNotNull();
    });
    assertThat(marker(cmdbKey(id))).containsExactly("criticality");
    assertThat(marker(eamKey(id))).isNull();
  }

  @Test
  void reverseOrderGivesTheSameOutcome() {
    String id = uid();
    UUID gid = link(id);

    cmdb(id, "1", "LOW");
    assertThat(conflicts.openConflicts(gid)).isEmpty();
    assertThat(marker(cmdbKey(id))).isNull();
    eam(id, "1", "HIGH");

    assertThat(criticalityOf(gid)).isEqualTo("HIGH");
    assertThat(conflicts.openConflicts(gid)).singleElement().satisfies(c -> {
      assertThat(c.dissentValue()).isEqualTo("LOW");
      assertThat(c.masterValue()).isEqualTo("HIGH");
    });
    // Событие пришло от мастера, а маркер появился на ребре чужой записи.
    assertThat(marker(cmdbKey(id))).containsExactly("criticality");
    assertThat(marker(eamKey(id))).isNull();
  }

  @Test
  void agreeingValueResolvesConflictAndClearsMarker() {
    String id = uid();
    UUID gid = link(id);
    eam(id, "1", "HIGH");
    cmdb(id, "1", "LOW");

    assertThat(cmdb(id, "2", "HIGH")).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(conflicts.openConflicts(gid)).isEmpty();
    assertThat(marker(cmdbKey(id))).isNull();
    assertThat(criticalityOf(gid)).isEqualTo("HIGH");
  }

  @Test
  void masterChangeClearsMarkerOnDissentEdge() {
    String id = uid();
    UUID gid = link(id);
    eam(id, "1", "HIGH");
    cmdb(id, "1", "LOW");

    eam(id, "2", "LOW");

    assertThat(conflicts.openConflicts(gid)).isEmpty();
    assertThat(marker(cmdbKey(id))).isNull();
  }

  @Test
  void sameValuesNeverOpenConflict() {
    String id = uid();
    UUID gid = link(id);

    eam(id, "1", "HIGH");
    cmdb(id, "1", "HIGH");

    assertThat(conflicts.openConflicts(gid)).isEmpty();
    assertThat(marker(cmdbKey(id))).isNull();
  }

  @Test
  void rebuildKeepsNodeConflictAndMarker() {
    String id = uid();
    UUID gid = link(id);
    eam(id, "1", "HIGH");
    cmdb(id, "1", "LOW");
    List<SourceConflict> before = new ArrayList<>(conflicts.openConflicts(gid));

    // Как RebuildService: граф стирается, журнал переигрывается; PostgreSQL (assertions, conflicts, mapping) остаётся.
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    assertThat(eam(id, "1", "HIGH")).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(cmdb(id, "1", "LOW")).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(criticalityOf(gid)).isEqualTo("HIGH");
    assertThat(conflicts.openConflicts(gid)).isEqualTo(before);
    assertThat(marker(cmdbKey(id))).containsExactly("criticality");
    assertThat(marker(eamKey(id))).isNull();
  }
}
