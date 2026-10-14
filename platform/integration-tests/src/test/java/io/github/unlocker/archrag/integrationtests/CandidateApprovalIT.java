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
import io.github.unlocker.archrag.graphprojector.schema.Neo4jSchema;
import io.github.unlocker.archrag.identityresolution.CandidateApproval;
import io.github.unlocker.archrag.identityresolution.CandidateNotFoundException;
import io.github.unlocker.archrag.identityresolution.CandidateNotOpenException;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.identityresolution.PostgresCandidateApprovals;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityCandidates;
import io.github.unlocker.archrag.identityresolution.PostgresIdentityMapping;
import io.github.unlocker.archrag.identityresolution.PostgresSourceConflicts;
import io.github.unlocker.archrag.ingestionservice.CandidateApprovalResult;
import io.github.unlocker.archrag.ingestionservice.CandidateApprovalService;
import io.github.unlocker.archrag.ingestionservice.StoredEventReader;
import io.github.unlocker.archrag.normalizer.Normalizer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Подтверждение кандидата на совпадение (UNLOCKER-282) на реальных PostgreSQL и Neo4j: запись переносится на
 * {@code gid} выжившей стороны и сразу перепроецируется из raw, прежний узел закрывается с {@code mergedInto},
 * результат совпадает с тем, что даёт replay/rebuild с нуля.
 */
@Testcontainers
class CandidateApprovalIT {

  private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-14T09:00:00Z");

  @Container
  static final Neo4jContainer NEO4J = new Neo4jContainer(TestImages.NEO4J);

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

  /** Raw в памяти: reproject читает его так же, как из S3. */
  private static final class MemoryRawStore implements RawPayloadStore {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public RawPayloadRef put(String source, byte[] content) {
      String key = "raw/" + UUID.randomUUID();
      objects.put(key, content);
      return new RawPayloadRef(key, "sha256:" + UUID.nameUUIDFromBytes(content));
    }

    @Override
    public byte[] get(RawPayloadRef ref) {
      return objects.get(ref.key());
    }
  }

  static Driver driver;
  static PostgresEventJournal journal;
  static PostgresIdentityMapping identity;
  static PostgresIdentityCandidates candidates;
  static PostgresSourceConflicts conflicts;
  static PostgresCandidateApprovals approvals;
  static EventProcessor processor;
  static CandidateApprovalService service;
  static PGSimpleDataSource pg;
  static final MemoryRawStore RAW = new MemoryRawStore();

  @BeforeAll
  static void setUp() {
    driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", NEO4J.getAdminPassword()));
    Neo4jSchema.apply(driver);
    pg = new PGSimpleDataSource();
    pg.setUrl(POSTGRES.getJdbcUrl());
    pg.setUser(POSTGRES.getUsername());
    pg.setPassword(POSTGRES.getPassword());
    JournalMigrations.apply(pg);
    journal = new PostgresEventJournal(pg);
    identity = new PostgresIdentityMapping(pg);
    candidates = new PostgresIdentityCandidates(pg);
    conflicts = new PostgresSourceConflicts(pg, AuthorityMatrix.defaults());
    approvals = new PostgresCandidateApprovals(pg);
    var projector = new GraphProjector(driver, AuthorityMatrix.defaults());
    processor =
        new EventProcessor(
            journal, Normalizer.standard(projector::isActive), identity, projector, (source, run, id, count) -> {}, candidates,
            conflicts);
    service =
        new CandidateApprovalService(
            approvals, projector, journal, RAW, new StoredEventReader(JsonMapper.builder().build()), processor);
  }

  private static String uid() {
    return UUID.randomUUID().toString();
  }

  private static SourceKey ci(String id) {
    return new SourceKey(SourceSystemCode.CMDB, "COMPUTE_INSTANCE", id);
  }

  /** Событие CMDB {@code COMPUTE_INSTANCE} с настоящим raw в хранилище, как оставил бы адаптер. */
  private static CanonicalEvent host(String id, String hostname, String ip, String version) {
    String raw = "{\"operation\":\"UPSERT\",\"completeness\":\"COMPLETE\",\"updatedAt\":\"" + T1 + "\",\"payload\":{"
        + "\"hostname\":\"" + hostname + "\",\"kind\":\"VIRTUAL_MACHINE\"" + (ip == null ? "" : ",\"ip\":\"" + ip + "\"")
        + "}}";
    RawPayloadRef ref = RAW.put("urn:corp:cmdb", raw.getBytes(StandardCharsets.UTF_8));
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("hostname", hostname);
    payload.put("kind", "VIRTUAL_MACHINE");
    if (ip != null) {
      payload.put("ip", ip);
    }
    payload.put("_completeness", "COMPLETE");
    var event =
        new CanonicalEvent(
            "e-" + uid(), "urn:corp:cmdb", CanonicalEvent.TYPE_ASSET_UPSERTED, "COMPUTE_INSTANCE/" + id, T1,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData("COMPUTE_INSTANCE", id, new SourceVersion(version), payload));
    journal.append(event, ref, null);
    assertThat(processor.process(event, ref).status()).isEqualTo(ProcessingStatus.PROJECTED);
    return event;
  }

  /** Пара записей с одним hostname: два узла и один OPEN-кандидат. */
  private record Pair(String a, String b, String hostname) {
    SourceKey ka() {
      return ci(a);
    }

    SourceKey kb() {
      return ci(b);
    }
  }

  private static Pair candidatePair() {
    Pair pair = new Pair(uid(), uid(), "app-" + uid() + ".corp");
    host(pair.a, pair.hostname, "10.0.0.1", "1");
    host(pair.b, pair.hostname.toUpperCase(), "10.0.0.2", "1");
    assertThat(identity.find(pair.ka())).isNotEqualTo(identity.find(pair.kb()));
    assertThat(candidates.candidatesOf(pair.ka())).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("OPEN"));
    return pair;
  }

  private static CandidateApproval approval(SourceKey left, SourceKey right, CandidateApproval.Survivor survivor) {
    return new CandidateApproval(left, right, survivor, "operator-1", "same host", T2);
  }

  private static Set<String> assertersOf(UUID gid) {
    return new TreeSet<>(
        driver
            .executableQuery("MATCH (r:SourceRecord)-[:ASSERTS]->(n {gid: $gid}) RETURN r.sourceId AS id")
            .withParameters(Map.of("gid", gid.toString()))
            .execute()
            .records()
            .stream()
            .map(r -> r.get("id").asString())
            .toList());
  }

  private static Map<String, Object> nodeOf(UUID gid) {
    var records =
        driver
            .executableQuery("MATCH (n:ComputeInstance {gid: $gid}) RETURN properties(n) AS p")
            .withParameters(Map.of("gid", gid.toString()))
            .execute()
            .records();
    assertThat(records).hasSize(1);
    return new TreeMap<>(records.get(0).get("p").asMap());
  }

  private static long assertsEdgesOf(SourceKey key) {
    return driver
        .executableQuery(
            "MATCH (:SourceRecord {source: $s, sourceType: $t, sourceId: $i})-[a:ASSERTS]->() RETURN count(a) AS c")
        .withParameters(Map.of("s", key.source().name(), "t", key.sourceType(), "i", key.sourceId()))
        .execute()
        .records()
        .get(0)
        .get("c")
        .asLong();
  }

  @Test
  void recordIsVisibleOnSurvivorImmediatelyAndOldNodeIsClosedWithMergedInto() {
    Pair p = candidatePair();
    UUID survivor = identity.find(p.ka()).orElseThrow();
    UUID old = identity.find(p.kb()).orElseThrow();

    CandidateApprovalResult result = service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.LEFT));

    assertThat(result.status()).isEqualTo(CandidateApprovalResult.Status.REPROJECTED);
    assertThat(result.gid()).isEqualTo(survivor);
    assertThat(result.previousGid()).isEqualTo(old);
    assertThat(result.previousRetired()).isTrue();
    assertThat(identity.find(p.kb())).contains(survivor);
    // Запись видна на узле выжившего сразу: обе записи утверждают его, а ASSERTS на прежний узел снят.
    assertThat(assertersOf(survivor)).containsExactlyInAnyOrder(p.a, p.b);
    assertThat(assertsEdgesOf(p.kb())).isEqualTo(1);
    assertThat(assertersOf(old)).isEmpty();
    // Прежний узел закрыт тем же путём, что при tombstone, и указывает на выжившего.
    Map<String, Object> closed = nodeOf(old);
    assertThat(closed).containsEntry("isCurrent", false).containsEntry("mergedInto", survivor.toString()).containsKey("deletedAt");
    Map<String, Object> alive = nodeOf(survivor);
    assertThat(alive).containsEntry("isCurrent", true).doesNotContainKey("mergedInto").doesNotContainKey("deletedAt");
    // Версия переносимой записи не изменилась, она по-прежнему активна.
    var applied = new GraphProjector(driver, AuthorityMatrix.defaults()).applied(p.kb()).orElseThrow();
    assertThat(applied.version()).isEqualTo(new SourceVersion("1"));
    assertThat(applied.active()).isTrue();
  }

  @Test
  void approvalResolvesCandidateAndRecordsCrosswalkAndMovesAssertions() throws SQLException {
    Pair p = candidatePair();
    UUID survivor = identity.find(p.ka()).orElseThrow();

    service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.LEFT));

    assertThat(candidates.candidatesOf(p.ka())).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("RESOLVED"));
    try (var c = pg.getConnection();
        var ps = c.prepareStatement(
            "SELECT resolved_by, resolved_at FROM identity_candidate WHERE left_id = ? OR right_id = ?")) {
      ps.setString(1, p.a);
      ps.setString(2, p.a);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("operator-1");
        assertThat(rs.getTimestamp(2).toInstant()).isEqualTo(T2);
      }
    }
    try (var c = pg.getConnection();
        var ps = c.prepareStatement("SELECT count(*) FROM approved_crosswalk WHERE left_id = ? OR right_id = ?")) {
      ps.setString(1, p.a);
      ps.setString(2, p.a);
      try (var rs = ps.executeQuery()) {
        rs.next();
        assertThat(rs.getInt(1)).isEqualTo(1);
      }
    }
    try (var c = pg.getConnection();
        var ps = c.prepareStatement("SELECT DISTINCT gid FROM property_assertion WHERE source_id IN (?, ?)")) {
      ps.setString(1, p.a);
      ps.setString(2, p.b);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getObject(1, UUID.class)).isEqualTo(survivor);
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  void survivorRightAndSwappedKeyOrderMovesTheOtherSide() {
    Pair p = candidatePair();
    UUID gidA = identity.find(p.ka()).orElseThrow();
    UUID gidB = identity.find(p.kb()).orElseThrow();

    // Ключи заданы в порядке (b, a), выживает правая сторона, то есть a: переносится b независимо от порядка хранения пары.
    CandidateApprovalResult result = service.approve(approval(p.kb(), p.ka(), CandidateApproval.Survivor.RIGHT));

    assertThat(result.gid()).isEqualTo(gidA);
    assertThat(result.previousGid()).isEqualTo(gidB);
    assertThat(identity.find(p.ka())).contains(gidA);
    assertThat(identity.find(p.kb())).contains(gidA);
    assertThat(assertersOf(gidA)).containsExactlyInAnyOrder(p.a, p.b);
  }

  @Test
  void survivorLeftIsTheFirstKeyAsGivenNotAsStored() {
    Pair p = candidatePair();
    UUID gidA = identity.find(p.ka()).orElseThrow();
    UUID gidB = identity.find(p.kb()).orElseThrow();

    // Выживает b (левый в запросе): a переносится на gid b.
    CandidateApprovalResult result = service.approve(approval(p.kb(), p.ka(), CandidateApproval.Survivor.LEFT));

    assertThat(result.gid()).isEqualTo(gidB);
    assertThat(result.previousGid()).isEqualTo(gidA);
    assertThat(identity.find(p.ka())).contains(gidB);
  }

  @Test
  void oldNodeStaysAliveWhenOtherKeysRemainOnItsGid() {
    Pair p = candidatePair();
    String third = uid();
    // Третья запись ещё не приходила: crosswalk даёт ей gid записи b до первого события.
    UUID old = identity.approve(new Crosswalk(p.kb(), ci(third), "admin", "same host", T1));
    host(third, p.hostname + ".third", "10.0.0.3", "1");
    assertThat(identity.keysOf(old)).hasSize(2);
    UUID survivor = identity.find(p.ka()).orElseThrow();

    CandidateApprovalResult result = service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.LEFT));

    assertThat(result.status()).isEqualTo(CandidateApprovalResult.Status.REPROJECTED);
    assertThat(result.previousRetired()).isFalse();
    assertThat(assertersOf(survivor)).containsExactlyInAnyOrder(p.a, p.b);
    // Прежний узел живёт дальше без перенесённой записи, но с оставшейся.
    assertThat(assertersOf(old)).containsExactly(third);
    assertThat(nodeOf(old)).containsEntry("isCurrent", true).doesNotContainKey("mergedInto").doesNotContainKey("deletedAt");
  }

  private static void insertConflict(UUID gid, SourceKey dissent, SourceKey master) throws SQLException {
    try (var c = pg.getConnection();
        var ps = c.prepareStatement(
            "INSERT INTO source_conflict (gid, property, dissent_source, dissent_type, dissent_id, dissent_value,"
                + " master_value, master_source, master_type, master_id, status, opened_at, updated_at)"
                + " VALUES (?,?,?,?,?,'x','y',?,?,?,'OPEN',now(),now())")) {
      ps.setObject(1, gid);
      ps.setString(2, "ip");
      ps.setString(3, dissent.source().name());
      ps.setString(4, dissent.sourceType());
      ps.setString(5, dissent.sourceId());
      ps.setString(6, master.source().name());
      ps.setString(7, master.sourceType());
      ps.setString(8, master.sourceId());
      ps.executeUpdate();
    }
  }

  private static String conflictStatus(UUID gid, SourceKey dissent) throws SQLException {
    try (var c = pg.getConnection();
        var ps = c.prepareStatement("SELECT status FROM source_conflict WHERE gid = ? AND dissent_id = ?")) {
      ps.setObject(1, gid);
      ps.setString(2, dissent.sourceId());
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  @Test
  void conflictsOfOldGidAreClosedOnlyForTheMovedRecord() throws SQLException {
    Pair p = candidatePair();
    String third = uid();
    UUID old = identity.approve(new Crosswalk(p.kb(), ci(third), "admin", "same host", T1));
    host(third, p.hostname + ".third", "10.0.0.3", "1");
    // Движимая запись — расходящаяся в одном конфликте и мастер в другом; конфликт третьей записи с чужим мастером остаётся.
    SourceKey master = new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", uid());
    insertConflict(old, p.kb(), master);
    insertConflict(old, ci(third), p.kb());
    SourceKey other = new SourceKey(SourceSystemCode.EAM, "IT_SYSTEM", uid());
    String thirdUntouched = uid();
    insertConflict(old, ci(thirdUntouched), other);

    service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.LEFT));

    assertThat(conflictStatus(old, p.kb())).isEqualTo("RESOLVED");
    assertThat(conflictStatus(old, ci(third))).isEqualTo("RESOLVED");
    assertThat(conflictStatus(old, ci(thirdUntouched))).isEqualTo("OPEN");
  }

  @Test
  void otherOpenCandidatesWithTheMovedKeyAreNotTouched() {
    Pair p = candidatePair();
    String other = uid();
    host(other, p.hostname + "", "10.0.0.9", "1");
    assertThat(candidates.candidatesOf(p.kb())).hasSize(2);

    service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.LEFT));

    var after = candidates.candidatesOf(p.kb());
    assertThat(after).hasSize(2);
    assertThat(after.stream().map(c -> c.status()).sorted().toList()).containsExactly("OPEN", "RESOLVED");
  }

  @Test
  void secondApprovalOfResolvedCandidateIsRejectedAndUnknownPairIsNotFound() {
    Pair p = candidatePair();
    service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.LEFT));
    UUID gid = identity.find(p.ka()).orElseThrow();

    assertThatThrownBy(() -> service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.RIGHT)))
        .isInstanceOf(CandidateNotOpenException.class);
    assertThatThrownBy(() -> service.approve(approval(ci(uid()), ci(uid()), CandidateApproval.Survivor.LEFT)))
        .isInstanceOf(CandidateNotFoundException.class);
    // Отказы ничего не изменили.
    assertThat(identity.find(p.kb())).contains(gid);
    assertThat(assertersOf(gid)).containsExactlyInAnyOrder(p.a, p.b);
  }

  @Test
  void replayFromAnEmptyGraphGivesTheSameSurvivorAsTheImmediateReprojection() {
    Pair p = candidatePair();
    UUID survivor = identity.find(p.ka()).orElseThrow();
    service.approve(approval(p.ka(), p.kb(), CandidateApproval.Survivor.LEFT));
    Map<String, Object> expected = nodeOf(survivor);
    Set<String> expectedAsserters = assertersOf(survivor);

    // Rebuild: граф пуст, события переигрываются в исходном порядке. gid берётся из identity_mapping, доп. логики нет.
    driver.executableQuery("MATCH (n) DETACH DELETE n").execute();
    for (SourceKey key : List.of(p.ka(), p.kb())) {
      var original = journal.read(new io.github.unlocker.archrag.eventschemas.JournalQuery(
          "urn:corp:cmdb", null, null, true, null, 1000)).stream()
          .filter(s -> s.entry().sourceId().equals(key.sourceId()))
          .findFirst().orElseThrow();
      var event = new StoredEventReader(JsonMapper.builder().build()).toEvent(original, RAW.get(original.entry().payloadRef()));
      var replayed = new CanonicalEvent("replay:r1:" + event.id(), event.source(), event.type(), event.subject(), event.time(),
          event.dataschema(), event.correlationid(), event.data());
      journal.append(replayed, original.entry().payloadRef(), null);
      assertThat(processor.process(replayed, original.entry().payloadRef()).status()).isEqualTo(ProcessingStatus.PROJECTED);
    }

    assertThat(assertersOf(survivor)).isEqualTo(expectedAsserters);
    assertThat(nodeOf(survivor)).isEqualTo(expected);
    // Прежнего узла после rebuild нет: никто на него не ссылается.
    assertThat(driver.executableQuery("MATCH (n) WHERE n.mergedInto IS NOT NULL RETURN count(n) AS c").execute().records()
        .get(0).get("c").asLong()).isZero();
  }
}
