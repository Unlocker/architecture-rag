package io.github.unlocker.archrag.integrationtests;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.canonicalmodel.authority.AuthorityMatrix;
import io.github.unlocker.archrag.canonicalmodel.command.CloseAssertion;
import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertNode;
import io.github.unlocker.archrag.canonicalmodel.command.UpsertRelation;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.node.Service;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventjournal.JournalMigrations;
import io.github.unlocker.archrag.eventjournal.PostgresEventJournal;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.GraphProjection;
import io.github.unlocker.archrag.graphprojector.GraphProjector;
import io.github.unlocker.archrag.graphprojector.ProcessingResult;
import io.github.unlocker.archrag.graphprojector.ProjectionOutcome;
import io.github.unlocker.archrag.graphprojector.ProjectionRequest;
import io.github.unlocker.archrag.graphprojector.ProjectionResult;
import io.github.unlocker.archrag.graphprojector.ReconciliationTrigger;
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Projector на реальных Neo4j Community и PostgreSQL: идемпотентность, версии, out-of-order, неполные записи,
 * authority matrix, tombstone, временные связи, статусы журнала и checkpoint (критерий 3).
 */
@Testcontainers
class GraphProjectorIT {

  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");
  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-02T10:00:00Z");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer("neo4j:5-community");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  static Driver driver;
  static PostgresEventJournal journal;
  static PostgresIdentityMapping identity;
  static PostgresIdentityCandidates candidates;
  static GraphProjector projector;
  static EventProcessor processor;
  static final List<String> snapshots = new ArrayList<>();

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
    projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor = processor(projector);
  }

  private static EventProcessor processor(GraphProjection projection) {
    ReconciliationTrigger trigger = (source, run, id, count) -> snapshots.add(run);
    return new EventProcessor(journal, Normalizer.standard(projector::isActive), identity, projection, trigger, candidates);
  }

  @BeforeEach
  void clearGraph() {
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    snapshots.clear();
  }

  // ---- helpers -----------------------------------------------------------------------------

  private static String uid() {
    return UUID.randomUUID().toString();
  }

  private static CanonicalEvent upsertEvent(
      String eventId, String source, String type, String id, String version, Instant time, Map<String, Object> payload) {
    return new CanonicalEvent(
        eventId, "urn:corp:" + source, CanonicalEvent.TYPE_ASSET_UPSERTED, type + "/" + id, time,
        "urn:corp:schema:asset-upserted:1", null,
        new AssetEventData(type, id, new SourceVersion(version), payload));
  }

  private static CanonicalEvent deleteEvent(String eventId, String source, String type, String id, String version, Instant time) {
    return new CanonicalEvent(
        eventId, "urn:corp:" + source, "architecture.asset.deleted.v1", type + "/" + id, time,
        "urn:corp:schema:asset-deleted:1", null, new AssetEventData(type, id, new SourceVersion(version), Map.of()));
  }

  private static ProcessingResult handle(CanonicalEvent event, String syncRunId) {
    journal.append(event, RAW, syncRunId);
    return processor.process(event, RAW);
  }

  private static ProcessingResult handle(CanonicalEvent event) {
    return handle(event, null);
  }

  private ProcessingResult upsert(String source, String type, String id, String version, Map<String, Object> payload) {
    return handle(upsertEvent("e-" + uid(), source, type, id, version, T1, payload));
  }

  private static List<Record> query(String cypher, Object... params) {
    Map<String, Object> map = new TreeMap<>();
    for (int i = 0; i < params.length; i += 2) {
      map.put((String) params[i], params[i + 1]);
    }
    return driver.executableQuery(cypher).withParameters(map).execute().records();
  }

  private static Record single(String cypher, Object... params) {
    var rows = query(cypher, params);
    assertThat(rows).hasSize(1);
    return rows.get(0);
  }

  /** Полный дамп графа: все узлы и связи со свойствами; сравнивается до и после повтора. */
  private static List<String> dump() {
    List<String> lines = new ArrayList<>();
    query("MATCH (n) RETURN elementId(n) AS id, labels(n) AS l, properties(n) AS p")
        .forEach(r -> lines.add("N " + r.get("id").asString() + " " + r.get("l").asList() + " "
            + new TreeMap<>(r.get("p").asMap())));
    query("MATCH (a)-[r]->(b) RETURN elementId(a) AS a, type(r) AS t, properties(r) AS p, elementId(b) AS b")
        .forEach(r -> lines.add("R " + r.get("a").asString() + " " + r.get("t").asString() + " "
            + new TreeMap<>(r.get("p").asMap()) + " " + r.get("b").asString()));
    return lines.stream().sorted().toList();
  }

  private static Record node(String label, String idProperty, String value) {
    return single("MATCH (n:" + label + " {" + idProperty + ": $v}) RETURN n", "v", value);
  }

  private static Record sourceRecord(String source, String type, String id) {
    return single("MATCH (r:SourceRecord {source: $s, sourceType: $t, sourceId: $i}) RETURN r",
        "s", source, "t", type, "i", id);
  }

  private static SourceKey key(SourceSystemCode source, String type, String id) {
    return new SourceKey(source, type, id);
  }

  // ---- upsert and provenance -------------------------------------------------------------------

  @Test
  void upsertCreatesNodeWithProvenanceAndMovesEventToProjected() {
    String id = "t-" + uid();
    var event = upsertEvent("e-" + uid(), "eam", "TEAM", id, "5", T1, Map.of("name", "Core", "type", "PLATFORM"));

    var result = handle(event, "run-1");

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    var team = node("Team", "name", "Core").get("n").asNode();
    assertThat(team.get("gid").asString()).isEqualTo(identity.find(key(SourceSystemCode.EAM, "TEAM", id)).orElseThrow().toString());
    assertThat(team.get("type").asString()).isEqualTo("PLATFORM");
    assertThat(team.get("isCurrent").asBoolean()).isTrue();
    var record = sourceRecord("EAM", "TEAM", id).get("r").asNode();
    assertThat(record.get("sourceVersion").asString()).isEqualTo("5");
    assertThat(record.get("active").asBoolean()).isTrue();
    assertThat(record.get("contentHash").asString()).isEqualTo("sha256:abc");
    var provenance = single(
        "MATCH (:SourceSystem {code: 'EAM'})-[:OWNS_RECORD]->(r:SourceRecord {sourceId: $i})-[a:ASSERTS]->(n:Team) "
            + "MATCH (:SyncRun {runId: 'run-1'})-[:PROCESSED]->(r) RETURN a.authority AS authority", "i", id);
    assertThat(provenance.get("authority").asString()).isEqualTo("MASTER");
    assertThat(journal.loadCheckpoint(EventProcessor.CONSUMER, "urn:corp:eam").orElseThrow().cursor()).isEqualTo(event.id());
  }

  @Test
  void replayDoesNotChangeGraph() {
    String id = "t-" + uid();
    var event = upsertEvent("e-" + uid(), "eam", "TEAM", id, "5", T1, Map.of("name", "Core"));
    handle(event);
    var before = dump();

    // Тот же eventId повторно.
    assertThat(processor.process(event, RAW).status()).isEqualTo(ProcessingStatus.PROJECTED);
    // Тот же запрос напрямую в транзакции Neo4j: версия равна применённой, граф не меняется.
    var keyEam = key(SourceSystemCode.EAM, "TEAM", id);
    var request = new ProjectionRequest(keyEam, new SourceVersion("5"), T1, null,
        Map.of(keyEam, identity.resolve(keyEam)),
        List.of(new UpsertNode(new SourceRecord(keyEam, "5", "sha256:abc", T1, true),
            new io.github.unlocker.archrag.canonicalmodel.node.Team("Core", null))));
    assertThat(projector.project(request).outcome()).isEqualTo(ProjectionOutcome.NOOP_SAME_VERSION);

    assertThat(dump()).isEqualTo(before);
  }

  @Test
  void webhookAndPollingWithSameVersionAreDuplicateNotOldVersion() {
    String id = "t-" + uid();
    handle(upsertEvent("wh-" + uid(), "eam", "TEAM", id, "5", T1, Map.of("name", "Core")));
    var before = dump();

    var result = handle(upsertEvent("poll:TEAM/" + id + "/v5", "eam", "TEAM", id, "5", T2, Map.of("name", "Core")));

    assertThat(result.status()).isEqualTo(ProcessingStatus.DUPLICATE);
    assertThat(dump()).isEqualTo(before);
  }

  // ---- versions -------------------------------------------------------------------------------

  @Test
  void olderVersionIsIgnoredAndComparedNumerically() {
    String id = "t-" + uid();
    handle(upsertEvent("e-" + uid(), "eam", "TEAM", id, "184", T1, Map.of("name", "New")));
    var before = dump();

    var result = handle(upsertEvent("e-" + uid(), "eam", "TEAM", id, "99", T2, Map.of("name", "Old")));

    assertThat(result.status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(dump()).isEqualTo(before);
    assertThat(sourceRecord("EAM", "TEAM", id).get("r").get("sourceVersion").asString()).isEqualTo("184");
  }

  @Test
  void outOfOrderDeliveryConvergesToTheNewestVersion() {
    String late = "t-" + uid();
    String inOrder = "t-" + uid();
    upsert("eam", "TEAM", late, "5", Map.of("name", "Five"));
    var stale = upsert("eam", "TEAM", late, "3", Map.of("name", "Three"));
    upsert("eam", "TEAM", inOrder, "3", Map.of("name", "Three"));
    upsert("eam", "TEAM", inOrder, "5", Map.of("name", "Five"));

    assertThat(stale.status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(query("MATCH (n:Team) RETURN n.name AS name")).extracting(r -> r.get("name").asString())
        .containsExactly("Five", "Five");
    assertThat(query("MATCH (r:SourceRecord) RETURN r.sourceVersion AS v")).extracting(r -> r.get("v").asString())
        .containsExactly("5", "5");
  }

  @Test
  void concurrentUpsertsOfOneObjectKeepOneRecordAtTheHighestVersion() throws Exception {
    String id = "t-" + uid();
    try (var pool = Executors.newFixedThreadPool(8)) {
      var calls = IntStream.rangeClosed(1, 16).<Callable<ProcessingResult>>mapToObj(
          v -> () -> upsert("eam", "TEAM", id, Integer.toString(v), Map.of("name", "v" + v))).toList();
      for (var future : pool.invokeAll(calls)) {
        assertThat(future.get().status()).isIn(
            ProcessingStatus.PROJECTED, ProcessingStatus.IGNORED_OLD_VERSION, ProcessingStatus.DUPLICATE);
      }
    }

    assertThat(query("MATCH (r:SourceRecord {sourceId: $i}) RETURN r.sourceVersion AS v", "i", id))
        .extracting(r -> r.get("v").asString()).containsExactly("16");
    assertThat(query("MATCH (n:Team) RETURN n.name AS name")).extracting(r -> r.get("name").asString()).containsExactly("v16");
  }

  // ---- partial records ------------------------------------------------------------------------

  @Test
  void partialRecordDoesNotEraseKnownFields() {
    String id = "its-" + uid();
    upsert("eam", "IT_SYSTEM", id, "1", Map.of("name", "Payments", "description", "billing", "criticality", "HIGH"));

    var result = upsert("eam", "IT_SYSTEM", id, "2", Map.of("name", "Payments v2"));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    var node = single("MATCH (n:ITSystem) RETURN n").get("n").asNode();
    assertThat(node.get("name").asString()).isEqualTo("Payments v2");
    assertThat(node.get("description").asString()).isEqualTo("billing");
    assertThat(node.get("criticality").asString()).isEqualTo("HIGH");
  }

  // ---- authority ------------------------------------------------------------------------------

  @Test
  void nonAuthoritativeSourceDoesNotReplaceCanonicalValue() {
    String id = "svc-" + uid();
    upsert("scm", "SERVICE", id, "1", Map.of("name", "pay", "language", "java"));
    var scm = key(SourceSystemCode.SCM, "SERVICE", id);
    var cmdb = key(SourceSystemCode.CMDB, "SERVICE", id);
    identity.approve(new Crosswalk(scm, cmdb, "admin", "same service", T1));
    var request = new ProjectionRequest(cmdb, new SourceVersion("1"), T2, null, Map.of(cmdb, identity.resolve(cmdb)),
        List.of(new UpsertNode(new SourceRecord(cmdb, "1", "sha256:x", T2, true), new Service("hijack", null, "go", null))));

    ProjectionResult result = projector.project(request);

    assertThat(result.outcome()).isEqualTo(ProjectionOutcome.APPLIED);
    assertThat(result.skippedProperties()).containsExactlyInAnyOrder("Service.name", "Service.language");
    var node = single("MATCH (n:Service) RETURN n").get("n").asNode();
    assertThat(node.get("name").asString()).isEqualTo("pay");
    assertThat(node.get("language").asString()).isEqualTo("java");
    assertThat(query("MATCH (:SourceRecord)-[a:ASSERTS]->(:Service) RETURN a.authority AS a ORDER BY a"))
        .extracting(r -> r.get("a").asString()).containsExactly("MASTER", "SUPPLEMENTARY");
  }

  // ---- relations and validity --------------------------------------------------------------------

  @Test
  void temporalRelationGetsValidFromAndIsNotReopenedByRepeat() {
    String team = "t-" + uid();
    String system = "its-" + uid();
    upsert("eam", "TEAM", team, "1", Map.of("name", "Core"));
    upsert("eam", "IT_SYSTEM", system, "1",
        Map.of("name", "Payments", "ownerTeam", team, "ownerSince", "2026-01-01T00:00:00Z"));
    var first = single("MATCH (:ITSystem)-[r:OWNED_BY]->(:Team) RETURN r").get("r").asRelationship();
    assertThat(first.get("validFrom").asZonedDateTime().toInstant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(first.containsKey("validTo")).isFalse();

    upsert("eam", "IT_SYSTEM", system, "2", Map.of("name", "Payments", "ownerTeam", team));

    var second = single("MATCH (:ITSystem)-[r:OWNED_BY]->(:Team) RETURN r").get("r").asRelationship();
    assertThat(second.get("validFrom").asZonedDateTime().toInstant()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(second.containsKey("validTo")).isFalse();
    assertThat(query("MATCH ()-[r:OWNED_BY]->() RETURN r")).hasSize(1);
  }

  @Test
  void relationValidFromDefaultsToEventTimeAndRunsOnLinksDeploymentToHost() {
    String host = "ci-" + uid();
    String deployment = "dep-" + uid();
    upsert("cmdb", "COMPUTE_INSTANCE", host, "1", Map.of("hostname", "h1", "kind", "VIRTUAL_MACHINE"));
    upsert("deploymap", "DEPLOYMENT", deployment, "1", Map.of("name", "pay-prod", "hosts", List.of(host)));

    var rel = single("MATCH (:Deployment)-[r:RUNS_ON]->(:ComputeInstance) RETURN r").get("r").asRelationship();
    assertThat(rel.get("validFrom").asZonedDateTime().toInstant()).isEqualTo(T1);
  }

  @Test
  void closeAssertionClosesOnlyTheOpenRelationAssertedByTheRecordAndIsIdempotent() {
    String team = "t-" + uid();
    String system = "its-" + uid();
    upsert("eam", "TEAM", team, "1", Map.of("name", "Core"));
    upsert("eam", "IT_SYSTEM", system, "1", Map.of("name", "Payments", "ownerTeam", team));
    var sys = key(SourceSystemCode.EAM, "IT_SYSTEM", system);
    var tm = key(SourceSystemCode.EAM, "TEAM", team);
    var request = new ProjectionRequest(sys, new SourceVersion("2"), T2, null,
        Map.of(sys, identity.resolve(sys), tm, identity.resolve(tm)),
        List.of(new UpsertNode(new SourceRecord(sys, "2", "sha256:y", T2, true),
                new io.github.unlocker.archrag.canonicalmodel.node.ITSystem("Payments", null, null, null)),
            new CloseAssertion(RelationType.OWNED_BY, sys, tm, T2, sys)));

    assertThat(projector.project(request).outcome()).isEqualTo(ProjectionOutcome.APPLIED);

    var rel = single("MATCH (:ITSystem)-[r:OWNED_BY]->(:Team) RETURN r").get("r").asRelationship();
    assertThat(rel.get("validTo").asZonedDateTime().toInstant()).isEqualTo(T2);
  }

  @Test
  void relationToUnknownTargetIsNotBuiltAndCreatesNoPhantomNode() {
    String system = "its-" + uid();

    var result = upsert("eam", "IT_SYSTEM", system, "1", Map.of("name", "Payments", "ownerTeam", "missing-" + uid()));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(query("MATCH ()-[r:OWNED_BY]->() RETURN r")).isEmpty();
    assertThat(query("MATCH (n:Team) RETURN n")).isEmpty();
  }

  @Test
  void scmServiceRecordBuildsDecomposedIntoFromEamSystem() {
    String system = "its-" + uid();
    String service = "svc-" + uid();
    upsert("eam", "IT_SYSTEM", system, "1", Map.of("name", "Payments"));

    var result = upsert("scm", "SERVICE", service, "1", Map.of("name", "pay", "systemCode", system));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(result.projection().skippedRelations()).isEmpty();
    assertThat(query("MATCH (:ITSystem)-[r:DECOMPOSED_INTO]->(:Service) RETURN r")).hasSize(1);
  }

  @Test
  void nonAuthoritativeRelationIsSkippedAndReported() {
    String a = "svc-" + uid();
    String b = "svc-" + uid();
    upsert("scm", "SERVICE", a, "1", Map.of("name", "a"));
    upsert("scm", "SERVICE", b, "1", Map.of("name", "b"));
    var from = key(SourceSystemCode.SCM, "SERVICE", a);
    var to = key(SourceSystemCode.SCM, "SERVICE", b);
    // Мастер DEPENDS_ON по матрице — EAM; SCM утверждает связь от своего имени.
    var request = new ProjectionRequest(from, new SourceVersion("2"), T2, null,
        Map.of(from, identity.resolve(from), to, identity.resolve(to)),
        List.of(new UpsertNode(new SourceRecord(from, "2", "sha256:z", T2, true), new Service("a", null, null, null)),
            new UpsertRelation(RelationType.DEPENDS_ON, from, NodeLabel.SERVICE, to, NodeLabel.SERVICE,
                Map.of(), null, from)));

    ProjectionResult result = projector.project(request);

    assertThat(result.outcome()).isEqualTo(ProjectionOutcome.APPLIED);
    assertThat(result.skippedRelations()).containsExactly("DEPENDS_ON");
    assertThat(query("MATCH ()-[r:DEPENDS_ON]->() RETURN r")).isEmpty();
  }

  // ---- compute instance subtype ----------------------------------------------------------------

  @Test
  void computeInstanceSubtypeChangeKeepsOneNode() {
    String host = "ci-" + uid();
    upsert("cmdb", "COMPUTE_INSTANCE", host, "1", Map.of("hostname", "h1", "kind", "UNSPECIFIED"));
    upsert("cmdb", "COMPUTE_INSTANCE", host, "2", Map.of("hostname", "h1", "kind", "VIRTUAL_MACHINE"));

    var node = single("MATCH (n:ComputeInstance) RETURN n, labels(n) AS l");
    assertThat(node.get("l").asList()).containsExactlyInAnyOrder("ComputeInstance", "VirtualMachine");
    upsert("cmdb", "COMPUTE_INSTANCE", host, "3", Map.of("hostname", "h1", "kind", "PHYSICAL_SERVER"));
    assertThat(single("MATCH (n:ComputeInstance) RETURN labels(n) AS l").get("l").asList())
        .containsExactlyInAnyOrder("ComputeInstance", "PhysicalServer");
  }

  // ---- relation-only records (SERVICE_DEPENDENCY -> DEPENDS_ON) -----------------------------------

  private String[] twoServices() {
    String a = "svc-" + uid();
    String b = "svc-" + uid();
    upsert("scm", "SERVICE", a, "1", Map.of("name", "payments"));
    upsert("scm", "SERVICE", b, "1", Map.of("name", "ledger"));
    return new String[] {a, b};
  }

  @Test
  void serviceDependencyBuildsDependsOnEdgeAndIsIdempotentAndVersioned() {
    String[] s = twoServices();
    String dep = "dep-" + uid();
    var payload = Map.<String, Object>of("from", s[0], "to", s[1], "kind", "SYNC", "protocol", "HTTP", "criticality", "HIGH");

    assertThat(upsert("eam", "SERVICE_DEPENDENCY", dep, "5", payload).status()).isEqualTo(ProcessingStatus.PROJECTED);

    var rel = single("MATCH (:Service {name: 'payments'})-[r:DEPENDS_ON]->(:Service {name: 'ledger'}) RETURN r")
        .get("r").asRelationship();
    assertThat(rel.get("kind").asString()).isEqualTo("SYNC");
    assertThat(rel.get("protocol").asString()).isEqualTo("HTTP");
    assertThat(rel.get("criticality").asString()).isEqualTo("HIGH");
    var record = sourceRecord("EAM", "SERVICE_DEPENDENCY", dep).get("r").asNode();
    assertThat(record.get("sourceVersion").asString()).isEqualTo("5");
    assertThat(record.get("active").asBoolean()).isTrue();
    assertThat(query("MATCH (:SourceSystem {code: 'EAM'})-[:OWNS_RECORD]->(:SourceRecord {sourceType: 'SERVICE_DEPENDENCY'}) RETURN 1"))
        .hasSize(1);

    var before = dump();
    var same = upsert("eam", "SERVICE_DEPENDENCY", dep, "5", payload);
    assertThat(same.status()).isEqualTo(ProcessingStatus.DUPLICATE);
    var old = upsert("eam", "SERVICE_DEPENDENCY", dep, "4", Map.of("from", s[0], "to", s[1], "kind", "ASYNC"));
    assertThat(old.status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(dump()).isEqualTo(before);
  }

  @Test
  void serviceDependencyTombstoneClosesEdgeAndKeepsServicesCurrent() {
    String[] s = twoServices();
    String dep = "dep-" + uid();
    upsert("eam", "SERVICE_DEPENDENCY", dep, "1", Map.of("from", s[0], "to", s[1], "kind", "SYNC"));

    var result = handle(deleteEvent("d-" + uid(), "eam", "SERVICE_DEPENDENCY", dep, "2", T2));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    var rel = single("MATCH (:Service)-[r:DEPENDS_ON]->(:Service) RETURN r").get("r").asRelationship();
    assertThat(rel.get("validTo").asZonedDateTime().toInstant()).isEqualTo(T2);
    assertThat(sourceRecord("EAM", "SERVICE_DEPENDENCY", dep).get("r").get("active").asBoolean()).isFalse();
    assertThat(query("MATCH (n:Service) WHERE n.isCurrent = true RETURN n")).hasSize(2);
  }

  @Test
  void missingServiceDependencyIsFoundByReconcilerAndItsEdgeIsClosed() {
    String[] s = twoServices();
    String dep = "dep-" + uid();
    upsert("eam", "SERVICE_DEPENDENCY", dep, "3", Map.of("from", s[0], "to", s[1]));
    var depKey = key(SourceSystemCode.EAM, "SERVICE_DEPENDENCY", dep);

    var active = projector.activeRecords(SourceSystemCode.EAM);
    assertThat(active).anySatisfy(r -> {
      assertThat(r.key()).isEqualTo(depKey);
      assertThat(r.version()).isEqualTo(new SourceVersion("3"));
    });

    // Так же, как Reconciler: tombstone с применённой версией для записи, отсутствующей в snapshot.
    var result = projector.project(new ProjectionRequest(
        depKey, new SourceVersion("3"), T2, null, Map.of(), List.of(new TombstoneSourceRecord(depKey, T2))));

    assertThat(result.outcome()).isEqualTo(ProjectionOutcome.APPLIED);
    assertThat(single("MATCH (:Service)-[r:DEPENDS_ON]->(:Service) RETURN r").get("r").asRelationship().get("validTo")
        .asZonedDateTime().toInstant()).isEqualTo(T2);
    assertThat(projector.activeRecords(SourceSystemCode.EAM)).noneMatch(r -> r.key().equals(depKey));
  }

  // ---- tombstone ------------------------------------------------------------------------------

  @Test
  void tombstoneDeactivatesRecordArchivesNodeAndClosesRelations() {
    String team = "t-" + uid();
    String system = "its-" + uid();
    upsert("eam", "TEAM", team, "1", Map.of("name", "Core"));
    upsert("eam", "IT_SYSTEM", system, "1", Map.of("name", "Payments", "ownerTeam", team, "ownerSince", "2026-01-01T00:00:00Z"));

    var result = handle(deleteEvent("d-" + uid(), "eam", "IT_SYSTEM", system, "2", T2));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    var record = sourceRecord("EAM", "IT_SYSTEM", system).get("r").asNode();
    assertThat(record.get("active").asBoolean()).isFalse();
    assertThat(record.get("sourceVersion").asString()).isEqualTo("2");
    var node = single("MATCH (n:ITSystem) RETURN n").get("n").asNode();
    assertThat(node.get("isCurrent").asBoolean()).isFalse();
    assertThat(node.get("deletedAt").asZonedDateTime().toInstant()).isEqualTo(T2);
    var rel = single("MATCH (:ITSystem)-[r:OWNED_BY]->(:Team) RETURN r").get("r").asRelationship();
    assertThat(rel.get("validTo").asZonedDateTime().toInstant()).isEqualTo(T2);
    assertThat(node("Team", "name", "Core").get("n").get("isCurrent").asBoolean()).isTrue();
  }

  @Test
  void upsertWithNewerVersionAfterTombstoneReactivates() {
    String id = "t-" + uid();
    upsert("eam", "TEAM", id, "1", Map.of("name", "Core"));
    handle(deleteEvent("d-" + uid(), "eam", "TEAM", id, "2", T2));

    var stale = upsert("eam", "TEAM", id, "2", Map.of("name", "Core"));
    assertThat(stale.status()).isEqualTo(ProcessingStatus.DUPLICATE);
    upsert("eam", "TEAM", id, "3", Map.of("name", "Core"));

    var node = single("MATCH (n:Team) RETURN n").get("n").asNode();
    assertThat(node.get("isCurrent").asBoolean()).isTrue();
    assertThat(node.containsKey("deletedAt")).isFalse();
    assertThat(sourceRecord("EAM", "TEAM", id).get("r").get("active").asBoolean()).isTrue();
  }

  @Test
  void tombstoneKeepsNodeWhileAnotherActiveMasterAssertionRemains() {
    String a = "t-" + uid();
    String b = "t-" + uid();
    upsert("eam", "TEAM", a, "1", Map.of("name", "Core"));
    identity.approve(new Crosswalk(key(SourceSystemCode.EAM, "TEAM", a), key(SourceSystemCode.EAM, "TEAM", b), "admin", "dup", T1));
    upsert("eam", "TEAM", b, "1", Map.of("name", "Core"));

    handle(deleteEvent("d-" + uid(), "eam", "TEAM", a, "2", T2));
    assertThat(single("MATCH (n:Team) RETURN n").get("n").get("isCurrent").asBoolean()).isTrue();

    handle(deleteEvent("d-" + uid(), "eam", "TEAM", b, "2", T2));
    var node = single("MATCH (n:Team) RETURN n").get("n").asNode();
    assertThat(node.get("isCurrent").asBoolean()).isFalse();
    assertThat(node.containsKey("deletedAt")).isTrue();
  }

  @Test
  void deleteArrivingBeforeUpsertBlocksOlderUpsertAndCreatesNoNode() {
    String id = "t-" + uid();
    var delete = handle(deleteEvent("d-" + uid(), "eam", "TEAM", id, "5", T2));
    var older = upsert("eam", "TEAM", id, "3", Map.of("name", "Core"));

    assertThat(delete.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(older.status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(query("MATCH (n:Team) RETURN n")).isEmpty();
    assertThat(sourceRecord("EAM", "TEAM", id).get("r").get("active").asBoolean()).isFalse();
  }

  @Test
  void deleteWithOlderVersionIsIgnored() {
    String id = "t-" + uid();
    upsert("eam", "TEAM", id, "9", Map.of("name", "Core"));
    var before = dump();

    var result = handle(deleteEvent("d-" + uid(), "eam", "TEAM", id, "4", T2));

    assertThat(result.status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(dump()).isEqualTo(before);
  }

  // ---- sync run, snapshot marker, failures --------------------------------------------------------

  @Test
  void syncRunIsLinkedOnceAndNotDoubledOnRepeat() {
    String id = "t-" + uid();
    handle(upsertEvent("e-" + uid(), "eam", "TEAM", id, "1", T1, Map.of("name", "Core")), "run-x");
    handle(upsertEvent("e-" + uid(), "eam", "TEAM", id, "1", T1, Map.of("name", "Core")), "run-x");

    assertThat(query("MATCH (s:SyncRun {runId: 'run-x'})-[:PROCESSED]->(:SourceRecord) RETURN s")).hasSize(1);
  }

  @Test
  void snapshotCompleteIsHandedToReconciliationAndNotProjected() {
    var event = new CanonicalEvent("snapshot-complete:run-9", "urn:corp:eam", "architecture.sync.snapshot-complete.v1",
        "sync-run/run-9", T1, "urn:corp:schema:snapshot-complete:1", null,
        new AssetEventData("SYNC_RUN", "run-9", new SourceVersion("1"), Map.of()));

    var result = handle(event, "run-9");

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(snapshots).containsExactly("run-9");
    assertThat(query("MATCH (n) RETURN n")).isEmpty();
  }

  @Test
  void graphFailureLeavesEventRetryingAndDoesNotMoveCheckpoint() {
    String id = "t-" + uid();
    GraphProjection down = new GraphProjection() {
      @Override
      public ProjectionResult project(ProjectionRequest request) {
        throw new ServiceUnavailableException("neo4j is down");
      }

      @Override
      public Optional<AppliedRecord> applied(SourceKey key) {
        return projector.applied(key);
      }

      @Override
      public boolean isActive(SourceKey key) {
        return projector.isActive(key);
      }

      @Override
      public List<ActiveRecord> activeRecords(SourceSystemCode source) {
        return projector.activeRecords(source);
      }
    };
    var event = upsertEvent("e-" + uid(), "eam", "TEAM", id, "1", T1, Map.of("name", "Core"));
    journal.append(event, RAW, null);
    var before = journal.loadCheckpoint(EventProcessor.CONSUMER, "urn:corp:eam");

    var failed = processor(down).process(event, RAW);

    assertThat(failed.status()).isEqualTo(ProcessingStatus.RETRYING);
    assertThat(failed.errorCode()).isEqualTo("GRAPH_UNAVAILABLE");
    assertThat(journal.loadCheckpoint(EventProcessor.CONSUMER, "urn:corp:eam")).isEqualTo(before);
    assertThat(query("MATCH (n:Team) RETURN n")).isEmpty();

    var retried = processor.process(event, RAW);

    assertThat(retried.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(journal.loadCheckpoint(EventProcessor.CONSUMER, "urn:corp:eam").orElseThrow().cursor()).isEqualTo(event.id());
  }

  @Test
  void invalidPayloadIsQuarantinedWithCodeAndWritesNothing() {
    var result = upsert("eam", "TEAM", "t-" + uid(), "1", Map.of());

    assertThat(result.status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(result.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD");
    assertThat(query("MATCH (n) RETURN n")).isEmpty();
  }

  // ---- deferred relations (E1.14) -----------------------------------------------------------------

  private static long pendingCount() {
    return single("MATCH (p:PendingRelation) RETURN count(p) AS c").get("c").asLong();
  }

  private static long edgeCount(String type) {
    return single("MATCH ()-[r:" + type + "]->() RETURN count(r) AS c").get("c").asLong();
  }

  private void dependency(String id, String version, String from, String to) {
    assertThat(upsert("eam", "SERVICE_DEPENDENCY", id, version, Map.of("from", from, "to", to, "kind", "SYNC")).status())
        .isEqualTo(ProcessingStatus.PROJECTED);
  }

  private void service(String id, String version, String name) {
    assertThat(upsert("scm", "SERVICE", id, version, Map.of("name", name)).status()).isEqualTo(ProcessingStatus.PROJECTED);
  }

  @Test
  void dependencyBeforeBothServicesAppearsAfterSecondService() {
    String dep = "d-" + uid();
    String a = "a-" + uid();
    String b = "b-" + uid();
    dependency(dep, "1", a, b);
    assertThat(pendingCount()).isEqualTo(1);
    assertThat(edgeCount("DEPENDS_ON")).isZero();

    service(a, "1", "svc-a");

    assertThat(edgeCount("DEPENDS_ON")).isZero();
    assertThat(pendingCount()).isEqualTo(1);

    service(b, "1", "svc-b");

    var rel = single("MATCH (:Service {name: 'svc-a'})-[r:DEPENDS_ON]->(:Service {name: 'svc-b'}) RETURN r").get("r").asRelationship();
    assertThat(rel.get("kind").asString()).isEqualTo("SYNC");
    assertThat(rel.get("assertedBySource").asString()).isEqualTo("EAM");
    assertThat(rel.get("assertedById").asString()).isEqualTo(dep);
    assertThat(pendingCount()).isZero();
    assertThat(query("MATCH (:SourceRecord)-[d:DEFERS]->() RETURN d")).isEmpty();
  }

  @Test
  void scmServiceBeforeEamSystemGetsDecomposedInto() {
    String sys = "sys-" + uid();
    String svc = "s-" + uid();
    assertThat(upsert("scm", "SERVICE", svc, "1", Map.of("name", "pay-api", "systemCode", sys)).status())
        .isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(edgeCount("DECOMPOSED_INTO")).isZero();
    assertThat(pendingCount()).isEqualTo(1);

    assertThat(upsert("eam", "IT_SYSTEM", sys, "1", Map.of("name", "Payments")).status()).isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(edgeCount("DECOMPOSED_INTO")).isEqualTo(1);
    single("MATCH (:ITSystem {name: 'Payments'})-[:DECOMPOSED_INTO]->(:Service {name: 'pay-api'}) RETURN 1 AS ok");
    assertThat(pendingCount()).isZero();
    // Узел сервиса достройка не трогала: его версия осталась прежней.
    assertThat(sourceRecord("SCM", "SERVICE", svc).get("r").asNode().get("sourceVersion").asString()).isEqualTo("1");
  }

  @Test
  void systemBeforeTeamGetsOwnedBy() {
    String team = "t-" + uid();
    String sys = "sys-" + uid();
    assertThat(upsert("eam", "IT_SYSTEM", sys, "1", Map.of("name", "Payments", "ownerTeam", team)).status())
        .isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(edgeCount("OWNED_BY")).isZero();

    assertThat(upsert("eam", "TEAM", team, "1", Map.of("name", "Core")).status()).isEqualTo(ProcessingStatus.PROJECTED);

    single("MATCH (:ITSystem {name: 'Payments'})-[:OWNED_BY]->(:Team {name: 'Core'}) RETURN 1 AS ok");
    assertThat(pendingCount()).isZero();
  }

  @Test
  void newReferrerVersionReplacesDeferredRelation() {
    String dep = "d-" + uid();
    String a = "a-" + uid();
    String b = "b-" + uid();
    String c = "c-" + uid();
    dependency(dep, "1", a, b);
    dependency(dep, "2", a, c);
    assertThat(pendingCount()).isEqualTo(1);

    service(a, "1", "svc-a");
    service(b, "1", "svc-b");
    assertThat(edgeCount("DEPENDS_ON")).isZero();
    service(c, "1", "svc-c");

    assertThat(edgeCount("DEPENDS_ON")).isEqualTo(1);
    single("MATCH (:Service {name: 'svc-a'})-[:DEPENDS_ON]->(:Service {name: 'svc-c'}) RETURN 1 AS ok");
  }

  @Test
  void tombstoneOfReferrerRemovesDeferredRelation() {
    String dep = "d-" + uid();
    String a = "a-" + uid();
    String b = "b-" + uid();
    dependency(dep, "1", a, b);
    assertThat(pendingCount()).isEqualTo(1);

    assertThat(handle(deleteEvent("e-" + uid(), "eam", "SERVICE_DEPENDENCY", dep, "2", T2)).status())
        .isEqualTo(ProcessingStatus.PROJECTED);

    assertThat(pendingCount()).isZero();
    service(a, "1", "svc-a");
    service(b, "1", "svc-b");
    assertThat(edgeCount("DEPENDS_ON")).isZero();
  }

  @Test
  void repeatedEndpointProjectionDoesNotDuplicateEdge() {
    String dep = "d-" + uid();
    String a = "a-" + uid();
    String b = "b-" + uid();
    dependency(dep, "1", a, b);
    service(a, "1", "svc-a");
    service(b, "1", "svc-b");
    assertThat(edgeCount("DEPENDS_ON")).isEqualTo(1);

    service(b, "2", "svc-b");
    service(a, "2", "svc-a");

    assertThat(edgeCount("DEPENDS_ON")).isEqualTo(1);
    assertThat(pendingCount()).isZero();
  }

  @Test
  void sameVersionReplayOfReferrerKeepsDeferredRelation() {
    String dep = "d-" + uid();
    String a = "a-" + uid();
    String b = "b-" + uid();
    dependency(dep, "1", a, b);

    // Повтор той же версии (re-snapshot) — DUPLICATE, отложенная связь остаётся.
    assertThat(upsert("eam", "SERVICE_DEPENDENCY", dep, "1", Map.of("from", a, "to", b, "kind", "SYNC")).status())
        .isEqualTo(ProcessingStatus.DUPLICATE);
    assertThat(pendingCount()).isEqualTo(1);
  }
}
