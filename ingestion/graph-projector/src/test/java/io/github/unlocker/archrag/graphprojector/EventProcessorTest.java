package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.command.DeferRelation;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.relation.RelationType;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.Checkpoint;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ObjectRef;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SnapshotContents;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.graphprojector.GraphProjection.AppliedRecord;
import io.github.unlocker.archrag.identityresolution.Crosswalk;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.identityresolution.Feature;
import io.github.unlocker.archrag.identityresolution.FeatureKind;
import io.github.unlocker.archrag.identityresolution.IdentityCandidate;
import io.github.unlocker.archrag.identityresolution.IdentityCandidates;
import io.github.unlocker.archrag.identityresolution.IdentityMapping;
import io.github.unlocker.archrag.identityresolution.IdentityStoreException;

import io.github.unlocker.archrag.normalizer.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.exceptions.ClientException;
import org.neo4j.driver.exceptions.ServiceUnavailableException;

/** Оркестрация события на подменах портов: статусы, маршрутизация по type, checkpoint. Граф здесь не участвует. */
class EventProcessorTest {

  private static final Instant T = Instant.parse("2026-10-01T10:00:00Z");
  private static final RawPayloadRef RAW = new RawPayloadRef("raw/1", "sha256:abc");

  private FakeJournal journal;
  private FakeProjection projection;
  private FakeIdentity identity;
  private FakeCandidates candidates;
  private final List<String> snapshots = new ArrayList<>();
  private EventProcessor processor;

  @BeforeEach
  void setUp() {
    journal = new FakeJournal();
    projection = new FakeProjection();
    identity = new FakeIdentity();
    candidates = new FakeCandidates(projection);
    processor =
        new EventProcessor(
            journal,
            Normalizer.standard(key -> true),
            identity,
            projection,
            (source, run, eventId, count) -> snapshots.add(source + "/" + run + "/" + eventId),
            candidates);
  }

  private static CanonicalEvent upsert(String id, String version, Map<String, Object> payload) {
    return new CanonicalEvent(
        id, "urn:corp:eam", CanonicalEvent.TYPE_ASSET_UPSERTED, "team/t1", T, "urn:corp:schema:asset-upserted:1", null,
        new AssetEventData("TEAM", "t1", new SourceVersion(version), payload));
  }

  private static CanonicalEvent other(String id, String type, String schema, String sourceType, String sourceId) {
    return new CanonicalEvent(
        id, "urn:corp:eam", type, "x/y", T, schema, null,
        new AssetEventData(sourceType, sourceId, new SourceVersion("3"), Map.of()));
  }

  private ProcessingResult run(CanonicalEvent event) {
    journal.append(event, RAW, "run-1");
    return processor.process(event, RAW);
  }

  @Test
  void upsertWalksStatusesStepByStepAndSavesCheckpointAfterWrite() {
    var event = upsert("e1", "5", Map.of("name", "core"));

    var result = run(event);

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(journal.history("e1"))
        .containsExactly(
            ProcessingStatus.VALIDATED, ProcessingStatus.NORMALIZED, ProcessingStatus.RESOLVED, ProcessingStatus.PROJECTED);
    assertThat(journal.checkpoint).isEqualTo("e1");
    assertThat(projection.requests).hasSize(1);
    assertThat(projection.requests.get(0).gids()).hasSize(1);
    assertThat(projection.requests.get(0).syncRunId()).isEqualTo("run-1");
  }

  @Test
  void recordsCandidateFeaturesAfterResolveAndBeforeGraphWrite() {
    var result = run(upsert("e1", "5", Map.of("name", "Core  Team")));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    var key = new SourceKey(SourceSystemCode.EAM, "TEAM", "t1");
    assertThat(identity.map).containsKey(key);
    assertThat(candidates.recorded).containsExactly(key + "/TEAM/" + new Feature(FeatureKind.NAME, "core team"));
    assertThat(candidates.graphUntouchedAtRecord).containsExactly(true);
  }

  @Test
  void matchingDoesNotChangeGidsPassedToProjection() {
    run(upsert("e1", "5", Map.of("name", "core")));

    var key = new SourceKey(SourceSystemCode.EAM, "TEAM", "t1");
    assertThat(projection.requests.get(0).gids()).isEqualTo(Map.of(key, identity.map.get(key)));
    assertThat(identity.map).hasSize(1);
  }

  @Test
  void candidateStoreFailureRetriesBeforeGraphIsTouched() {
    candidates.fail = true;

    var result = run(upsert("e1", "5", Map.of("name", "core")));

    assertThat(result.status()).isEqualTo(ProcessingStatus.RETRYING);
    assertThat(result.errorCode()).isEqualTo("IDENTITY_CANDIDATE_FAILED");
    assertThat(projection.requests).isEmpty();
    assertThat(journal.checkpoint).isNull();
  }

  @Test
  void tombstoneForgetsFeaturesOfKey() {
    var result = run(other("d1", EventProcessor.TYPE_ASSET_DELETED, EventProcessor.SCHEMA_ASSET_DELETED, "TEAM", "t1"));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(candidates.forgotten).containsExactly(new SourceKey(SourceSystemCode.EAM, "TEAM", "t1"));
  }

  @Test
  void tombstoneCandidateStoreFailureRetriesBeforeGraphIsTouched() {
    candidates.fail = true;

    var result = run(other("d1", EventProcessor.TYPE_ASSET_DELETED, EventProcessor.SCHEMA_ASSET_DELETED, "TEAM", "t1"));

    assertThat(result.status()).isEqualTo(ProcessingStatus.RETRYING);
    assertThat(result.errorCode()).isEqualTo("IDENTITY_CANDIDATE_FAILED");
    assertThat(projection.requests).isEmpty();
  }

  private static final class FakeCandidates implements IdentityCandidates {
    final List<String> recorded = new ArrayList<>();
    final List<SourceKey> forgotten = new ArrayList<>();
    final List<Boolean> graphUntouchedAtRecord = new ArrayList<>();
    private final FakeProjection projection;
    boolean fail;

    FakeCandidates(FakeProjection projection) {
      this.projection = projection;
    }

    @Override
    public List<IdentityCandidate> record(SourceKey key, NodeLabel label, Set<Feature> features) {
      if (fail) {
        throw new IdentityStoreException("down", null);
      }
      graphUntouchedAtRecord.add(projection.requests.isEmpty());
      features.forEach(f -> recorded.add(key + "/" + label.name() + "/" + f));
      return List.of();
    }

    @Override
    public void forget(SourceKey key) {
      if (fail) {
        throw new IdentityStoreException("down", null);
      }
      forgotten.add(key);
    }

    @Override
    public List<IdentityCandidate> candidatesOf(SourceKey key) {
      return List.of();
    }
  }

  @Test
  void relationOnlyEventIsProjectedNotQuarantined() {
    var from = new SourceKey(SourceSystemCode.SCM, "SERVICE", "svc-a");
    var to = new SourceKey(SourceSystemCode.SCM, "SERVICE", "svc-b");
    identity.map.put(from, UUID.randomUUID());
    identity.map.put(to, UUID.randomUUID());
    var event =
        new CanonicalEvent(
            "e-dep", "urn:corp:eam", CanonicalEvent.TYPE_ASSET_UPSERTED, "dep/d1", T,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData(
                "SERVICE_DEPENDENCY", "d1", new SourceVersion("1"), Map.of("from", "svc-a", "to", "svc-b", "kind", "SYNC")));

    var result = run(event);

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(projection.requests).singleElement().satisfies(r -> assertThat(r.isRelationOnly()).isTrue());
  }

  @Test
  void unresolvedReferenceReachesProjectorAsDeferredRelationOnce() {
    var unknown = new EventProcessor(
        journal, Normalizer.standard(key -> false), identity, projection, (source, run, eventId, count) -> {}, candidates);
    var event =
        new CanonicalEvent(
            "e-dep", "urn:corp:eam", CanonicalEvent.TYPE_ASSET_UPSERTED, "dep/d1", T,
            "urn:corp:schema:asset-upserted:1", null,
            new AssetEventData(
                "SERVICE_DEPENDENCY", "d1", new SourceVersion("1"), Map.of("from", "svc-a", "to", "svc-b", "kind", "SYNC")));
    journal.append(event, RAW, "run-1");

    var result = unknown.process(event, RAW);

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(projection.requests).singleElement().satisfies(r -> {
      assertThat(r.commands()).hasSize(1).first().isInstanceOf(DeferRelation.class);
      var spec = ((DeferRelation) r.commands().get(0)).relation();
      assertThat(spec.type()).isEqualTo(RelationType.DEPENDS_ON);
      assertThat(spec.from().sourceId()).isEqualTo("svc-a");
      assertThat(spec.to().sourceId()).isEqualTo("svc-b");
    });
  }

  @Test
  void sameVersionUnderAnotherEventIdIsDuplicateAndDoesNotTouchGraph() {
    projection.applied = Optional.of(new AppliedRecord(new SourceVersion("5"), true));

    var result = run(upsert("poll:team/t1/v5", "5", Map.of("name", "core")));

    assertThat(result.status()).isEqualTo(ProcessingStatus.DUPLICATE);
    assertThat(projection.requests).isEmpty();
  }

  @Test
  void olderVersionIsIgnoredBeforeWrite() {
    projection.applied = Optional.of(new AppliedRecord(new SourceVersion("184"), true));

    var result = run(upsert("e1", "99", Map.of("name", "core")));

    assertThat(result.status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(projection.requests).isEmpty();
    assertThat(journal.checkpoint).isEqualTo("e1");
  }

  @Test
  void racedOldVersionFromGraphTransactionIsIgnored() {
    projection.result = ProjectionResult.of(ProjectionOutcome.IGNORED_OLD_VERSION);

    assertThat(run(upsert("e1", "5", Map.of("name", "core"))).status())
        .isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
  }

  @Test
  void racedSameVersionFromGraphTransactionIsProjected() {
    projection.result = ProjectionResult.of(ProjectionOutcome.NOOP_SAME_VERSION);

    assertThat(run(upsert("e1", "5", Map.of("name", "core"))).status()).isEqualTo(ProcessingStatus.PROJECTED);
  }

  @Test
  void graphOutageMovesEventToRetryingWithoutCheckpoint() {
    projection.failure = new ServiceUnavailableException("down");

    var result = run(upsert("e1", "5", Map.of("name", "core")));

    assertThat(result.status()).isEqualTo(ProcessingStatus.RETRYING);
    assertThat(result.errorCode()).isEqualTo("GRAPH_UNAVAILABLE");
    assertThat(journal.checkpoint).isNull();
    assertThat(journal.entry("e1").errorReason()).doesNotContain("down");
  }

  @Test
  void securityAndConstraintErrorsRetryButStatementErrorsQuarantine() {
    projection.failure = new ClientException("Neo.ClientError.Security.Unauthorized", "x");
    assertThat(run(upsert("e1", "5", Map.of("name", "core"))).status()).isEqualTo(ProcessingStatus.RETRYING);
    projection.failure = new ClientException("Neo.ClientError.Schema.ConstraintValidationFailed", "x");
    assertThat(run(upsert("e2", "5", Map.of("name", "core"))).status()).isEqualTo(ProcessingStatus.RETRYING);
    projection.failure = new ClientException("Neo.ClientError.Statement.SyntaxError", "x");
    var result = run(upsert("e3", "5", Map.of("name", "core")));
    assertThat(result.status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(result.errorCode()).isEqualTo("GRAPH_REJECTED");
  }

  @Test
  void retryingEventIsReprocessedToProjected() {
    projection.failure = new ServiceUnavailableException("down");
    var event = upsert("e1", "5", Map.of("name", "core"));
    run(event);
    projection.failure = null;

    var result = processor.process(event, RAW);

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(journal.checkpoint).isEqualTo("e1");
  }

  @Test
  void normalizationErrorQuarantinesWithCodeAndNoWrite() {
    var result = run(upsert("e1", "5", Map.of()));

    assertThat(result.status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(result.errorCode()).isEqualTo("MISSING_REQUIRED_FIELD");
    assertThat(projection.requests).isEmpty();
    assertThat(journal.checkpoint).isNull();
  }

  @Test
  void unknownTypeIsQuarantinedBeforeNormalizer() {
    var result = run(other("e1", "architecture.something.v1", "urn:x", "TEAM", "t1"));

    assertThat(result.status()).isEqualTo(ProcessingStatus.QUARANTINED);
    assertThat(result.errorCode()).isEqualTo("UNSUPPORTED_EVENT_TYPE");
  }

  @Test
  void deleteBecomesTombstoneRequestWithoutNormalizer() {
    var result = run(other("d1", EventProcessor.TYPE_ASSET_DELETED, EventProcessor.SCHEMA_ASSET_DELETED, "TEAM", "t1"));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(projection.requests).hasSize(1);
    assertThat(projection.requests.get(0).isTombstone()).isTrue();
    assertThat(projection.requests.get(0).key()).isEqualTo(new SourceKey(SourceSystemCode.EAM, "TEAM", "t1"));
  }

  @Test
  void deleteWithOldVersionIsIgnored() {
    projection.applied = Optional.of(new AppliedRecord(new SourceVersion("9"), true));

    var result = run(other("d1", EventProcessor.TYPE_ASSET_DELETED, EventProcessor.SCHEMA_ASSET_DELETED, "TEAM", "t1"));

    assertThat(result.status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
    assertThat(projection.requests).isEmpty();
  }

  @Test
  void deleteWithUnknownSchemaIsQuarantined() {
    var result = run(other("d1", EventProcessor.TYPE_ASSET_DELETED, "urn:corp:schema:asset-deleted:9", "TEAM", "t1"));

    assertThat(result.errorCode()).isEqualTo("UNKNOWN_SCHEMA_VERSION");
  }

  @Test
  void snapshotCompleteIsHandedToReconciliationAndNotProjected() {
    var result =
        run(other("snapshot-complete:run-7", EventProcessor.TYPE_SNAPSHOT_COMPLETE, "urn:corp:schema:snapshot-complete:1", "SYNC_RUN", "run-7"));

    assertThat(result.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(snapshots).containsExactly("urn:corp:eam/run-7/snapshot-complete:run-7");
    assertThat(projection.requests).isEmpty();
  }

  @Test
  void finishedEventIsNotProcessedTwice() {
    var event = upsert("e1", "5", Map.of("name", "core"));
    run(event);

    var again = processor.process(event, RAW);

    assertThat(again.status()).isEqualTo(ProcessingStatus.PROJECTED);
    assertThat(projection.requests).hasSize(1);
  }

  @Test
  void eventMissingFromJournalIsRejected() {
    assertThatThrownBy(() -> processor.process(upsert("nope", "5", Map.of("name", "x")), RAW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** Журнал в памяти, который, как настоящий, запрещает недопустимые переходы статусов. */
  static final class FakeJournal implements EventJournal {
    final Map<String, JournalEntry> entries = new HashMap<>();
    final Map<String, List<ProcessingStatus>> history = new HashMap<>();
    String checkpoint;

    JournalEntry entry(String id) {
      return entries.get(id);
    }

    List<ProcessingStatus> history(String id) {
      return history.get(id);
    }

    @Override
    public JournalEntry append(CanonicalEvent e, RawPayloadRef ref, String syncRunId) {
      var entry =
          new JournalEntry(
              e.source(), e.id(), null, syncRunId, e.data().sourceType(), e.data().sourceId(),
              e.data().sourceVersion(), "1", ProcessingStatus.RECEIVED, null, null, 0, ref, T, T);
      entries.put(e.id(), entry);
      history.put(e.id(), new ArrayList<>());
      return entry;
    }

    @Override
    public Optional<JournalEntry> find(String source, String eventId) {
      return Optional.ofNullable(entries.get(eventId));
    }

    @Override
    public JournalEntry transition(String source, String eventId, ProcessingStatus status, String code, String reason) {
      var old = entries.get(eventId);
      if (!old.status().canTransitionTo(status)) {
        throw new IllegalStateException(old.status() + " -> " + status);
      }
      var updated =
          new JournalEntry(
              old.source(), old.eventId(), old.correlationId(), old.syncRunId(), old.sourceType(), old.sourceId(),
              old.sourceVersion(), old.schemaVersion(), status, code, reason, old.attempts(), old.payloadRef(),
              old.receivedAt(), T);
      entries.put(eventId, updated);
      history.get(eventId).add(status);
      return updated;
    }

    @Override
    public JournalEntry toDlq(String source, String eventId, String errorCode, String reason) {
      return transition(source, eventId, ProcessingStatus.QUARANTINED, errorCode, reason);
    }

    @Override
    public SnapshotContents snapshotContents(String source, String syncRunId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<ObjectRef> objectsReceivedSince(String source, Instant since) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Checkpoint> loadCheckpoint(String consumer, String source) {
      return Optional.empty();
    }

    @Override
    public Checkpoint saveCheckpoint(String consumer, String source, String cursor) {
      checkpoint = cursor;
      return new Checkpoint(consumer, source, cursor, T);
    }
  }

  static final class FakeIdentity implements IdentityMapping {
    final Map<SourceKey, UUID> map = new HashMap<>();

    @Override
    public Optional<UUID> find(SourceKey key) {
      return Optional.ofNullable(map.get(key));
    }

    @Override
    public UUID resolve(SourceKey key) {
      return map.computeIfAbsent(key, k -> UUID.randomUUID());
    }

    @Override
    public List<SourceKey> keysOf(UUID gid) {
      return map.entrySet().stream().filter(e -> e.getValue().equals(gid)).map(Map.Entry::getKey).toList();
    }

    @Override
    public UUID approve(Crosswalk crosswalk) {
      throw new UnsupportedOperationException();
    }
  }

  static final class FakeProjection implements GraphProjection {
    final List<ProjectionRequest> requests = new ArrayList<>();
    Optional<AppliedRecord> applied = Optional.empty();
    ProjectionResult result = ProjectionResult.of(ProjectionOutcome.APPLIED);
    RuntimeException failure;

    @Override
    public ProjectionResult project(ProjectionRequest request) {
      if (failure != null) {
        throw failure;
      }
      requests.add(request);
      return result;
    }

    @Override
    public Optional<AppliedRecord> applied(SourceKey key) {
      return applied;
    }

    @Override
    public boolean isActive(SourceKey key) {
      return true;
    }

    @Override
    public List<ActiveRecord> activeRecords(SourceSystemCode source) {
      return List.of();
    }
  }
}
