package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.Reconciler;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.graphquerycore.GraphQueryExecutor;
import io.github.unlocker.archrag.graphquerycore.QueryLimits;
import io.github.unlocker.archrag.graphquerycore.QueryTemplateRegistry;
import io.github.unlocker.archrag.graphquerycore.templates.AssetTemplates;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.identityresolution.PostgresSourceConflicts;
import io.github.unlocker.archrag.mcpserver.tools.ExplainProvenanceResult;
import io.github.unlocker.archrag.mcpserver.tools.ExplainProvenanceTool;
import io.github.unlocker.archrag.normalizer.CmdbMapper;
import io.github.unlocker.archrag.normalizer.DeployMapMapper;
import io.github.unlocker.archrag.normalizer.EamMapper;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.SessionConfig;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * F4.1: {@code explain_provenance} поверх графа, наполненного настоящим проектором. Актив {@code ITSystem}
 * приходит из EAM (мастер) и SCM (через crosswalk), SCM расходится по {@code criticality}. Читает граф tool
 * через {@link GraphQueryExecutor} под пользователем {@code mcp-reader}.
 *
 * <p>В PoC SCM-маппер не принимает {@code IT_SYSTEM}, поэтому сценарий воспроизводится тестовым маппером SCM.
 */
@Testcontainers
class ExplainProvenanceIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
  private static final QueryLimits LIMITS =
      new QueryLimits(6, 500, 50, Duration.ofSeconds(Long.getLong("archrag.it.queryTimeoutSeconds", 5)), 512 * 1024);

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static Driver reader;
  static PostgresEventJournal journal;
  static PostgresIdentityMapping identity;
  static EventProcessor processor;
  static Reconciler reconciler;
  static AnnotationConfigApplicationContext mcp;
  static ExplainProvenanceTool tool;

  private static final class NoopRawStore implements RawPayloadStore {
    @Override
    public RawPayloadRef put(String source, byte[] content) {
      return new RawPayloadRef("raw/" + UUID.nameUUIDFromBytes(content), "sha256:" + UUID.nameUUIDFromBytes(content));
    }

    @Override
    public byte[] get(RawPayloadRef ref) {
      throw new UnsupportedOperationException();
    }
  }

  @BeforeAll
  static void setUp() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    try (var system = driver.session(SessionConfig.forDatabase("system"))) {
      system.run("CREATE USER `mcp-reader` SET PASSWORD 'reader-pass' CHANGE NOT REQUIRED").consume();
    }
    Neo4jSchema.apply(driver);
    var ds = new PGSimpleDataSource();
    ds.setUrl(POSTGRES.getJdbcUrl());
    ds.setUser(POSTGRES.getUsername());
    ds.setPassword(POSTGRES.getPassword());
    JournalMigrations.apply(ds);
    journal = new PostgresEventJournal(ds);
    identity = new PostgresIdentityMapping(ds);
    var conflicts = new PostgresSourceConflicts(ds, AuthorityMatrix.defaults());
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    var normalizer =
        new Normalizer(
            List.of(new EamMapper(), new ScmItSystemMapper(), new CmdbMapper(), new DeployMapMapper()), projector::isActive);
    reconciler = new Reconciler(journal, new NoopRawStore(), projector, Clock.systemUTC(), identity, conflicts);
    processor =
        new EventProcessor(
            journal, normalizer, identity, projector, (source, run, id, count) -> {}, new PostgresIdentityCandidates(ds), conflicts);

    reader = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("mcp-reader", "reader-pass"));
    var executor =
        new GraphQueryExecutor(reader, QueryTemplateRegistry.of(List.of(AssetTemplates.EXPLAIN_PROVENANCE), LIMITS));
    // Tool и его GraphQueries — компоненты со внутренними конструкторами: собираем их контейнером, как в приложении.
    mcp = new AnnotationConfigApplicationContext();
    mcp.registerBean(GraphQueryExecutor.class, () -> executor);
    mcp.registerBean(QueryLimits.class, () -> LIMITS);
    mcp.register(io.github.unlocker.archrag.mcpserver.graph.GraphQueries.class, ExplainProvenanceTool.class);
    mcp.refresh();
    tool = mcp.getBean(ExplainProvenanceTool.class);
  }

  @AfterAll
  static void tearDown() {
    mcp.close();
    reader.close();
    driver.close();
  }

  private static String uid() {
    return UUID.randomUUID().toString();
  }

  private static ProcessingStatus send(String source, String id, String version, String criticality) {
    var event =
        new CanonicalEvent(
            "e-" + uid(), "urn:corp:" + source, CanonicalEvent.TYPE_ASSET_UPSERTED, "IT_SYSTEM/" + id, T1,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData("IT_SYSTEM", id, new SourceVersion(version), Map.of("name", "Billing", "criticality", criticality)));
    journal.append(event, RAW, null);
    return processor.process(event, RAW).status();
  }

  /** Crosswalk до первого события любой из записей: иначе approve даст CrosswalkConflictException. */
  private static UUID link(String id) {
    return identity.approve(
        new Crosswalk(
            new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", id),
            new SourceKey(SourceSystemCode.SCM, "IT_SYSTEM", id),
            "admin",
            "same system",
            T1));
  }

  /** Актив из двух источников: EAM=HIGH, SCM=LOW. */
  private static UUID conflicting(String id) {
    UUID gid = link(id);
    assertThat(send("eam", id, "1", "HIGH")).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(send("scm", id, "1", "LOW")).isEqualTo(ProcessingStatus.PROJECTED);
    return gid;
  }

  private static ExplainProvenanceResult.RecordRef of(ExplainProvenanceResult r, String source) {
    return r.records().stream().filter(x -> x.source().equals(source)).findFirst().orElseThrow();
  }

  @Test
  void twoSourcesShowMasterAndOpenCriticalityConflict() {
    String id = uid();
    UUID gid = conflicting(id);

    ExplainProvenanceResult r = tool.explainProvenance(gid.toString(), null);

    assertThat(r.gid()).isEqualTo(gid.toString());
    assertThat(r.type()).isEqualTo("ITSystem");
    assertThat(r.isCurrent()).isTrue();
    assertThat(r.authorities()).containsExactly("EAM");
    assertThat(r.conflictState()).isEqualTo("OPEN");
    assertThat(r.records()).hasSize(2);
    var eam = of(r, "EAM");
    assertThat(eam.authoritative()).isTrue();
    assertThat(eam.authority()).isEqualTo("MASTER");
    assertThat(eam.active()).isTrue();
    assertThat(eam.sourceId()).isEqualTo(id);
    assertThat(eam.sourceType()).isEqualTo("IT_SYSTEM");
    assertThat(eam.sourceVersion()).isEqualTo("1");
    assertThat(eam.fetchedAt()).isNotBlank();
    assertThat(eam.contentHash()).isNotBlank();
    assertThat(eam.confidence()).isEqualTo(1.0);
    assertThat(eam.conflictProperties()).isEmpty();
    var scm = of(r, "SCM");
    assertThat(scm.authoritative()).isFalse();
    assertThat(scm.authority()).isEqualTo("SUPPLEMENTARY");
    assertThat(scm.conflictProperties()).containsExactly("criticality");
    assertThat(r.records().getFirst().source()).isEqualTo("EAM");
  }

  @Test
  void propertyFilterKeepsOnlyThatPropertyConflict() {
    UUID gid = conflicting(uid());

    ExplainProvenanceResult name = tool.explainProvenance(gid.toString(), "name");
    assertThat(name.property()).isEqualTo("name");
    assertThat(name.conflictState()).isEqualTo("NONE");
    assertThat(of(name, "SCM").conflictProperties()).isEmpty();

    ExplainProvenanceResult criticality = tool.explainProvenance(gid.toString(), "criticality");
    assertThat(criticality.authorities()).containsExactly("EAM");
    assertThat(criticality.conflictState()).isEqualTo("OPEN");
    assertThat(of(criticality, "SCM").conflictProperties()).containsExactly("criticality");
  }

  @Test
  void agreeingSourcesHaveNoConflict() {
    String id = uid();
    UUID gid = link(id);
    send("eam", id, "1", "HIGH");
    send("scm", id, "1", "HIGH");

    ExplainProvenanceResult r = tool.explainProvenance(gid.toString(), null);

    assertThat(r.records()).hasSize(2);
    assertThat(r.conflictState()).isEqualTo("NONE");
  }

  @Test
  void tombstonedSourceIsInactiveAndConflictIsCleared() throws Exception {
    String id = uid();
    UUID gid = conflicting(id);

    // Snapshot SCM без этой записи: в прогоне только посторонний объект; маркер snapshot-complete уже обработан.
    String run = "run-" + uid();
    var filler =
        new CanonicalEvent(
            "snap:" + run + ":other", "urn:corp:scm", CanonicalEvent.TYPE_ASSET_UPSERTED, "IT_SYSTEM/other", T1,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData("IT_SYSTEM", "other-" + id, new SourceVersion("1"), Map.of("name", "x", "criticality", "LOW")));
    journal.append(filler, RAW, run);
    var marker =
        new CanonicalEvent(
            "marker-" + run, "urn:corp:scm", CanonicalEvent.TYPE_ASSET_UPSERTED, "m", T1,
            "urn:corp:schema:asset-upserted:1", null, new AssetEventData("IT_SYSTEM", run, new SourceVersion("1"), Map.of()));
    journal.append(marker, RAW, run);
    reconciler.reconcile("urn:corp:scm", run, marker.id(), 1);

    ExplainProvenanceResult r = tool.explainProvenance(gid.toString(), null);

    assertThat(r.records()).hasSize(2);
    assertThat(r.isCurrent()).isTrue();
    var scm = of(r, "SCM");
    assertThat(scm.active()).isFalse();
    assertThat(scm.deletedAt()).isNotBlank();
    assertThat(scm.conflictProperties()).isEmpty();
    assertThat(r.conflictState()).isEqualTo("NONE");
    assertThat(r.records().getLast().source()).isEqualTo("SCM");
  }

  @Test
  void unknownGidIsAssetNotFound() {
    assertThatThrownBy(() -> tool.explainProvenance(UUID.randomUUID().toString(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("asset not found");
  }
}
