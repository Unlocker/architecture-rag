package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
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
import io.github.unlocker.archrag.identityresolution.IdentityMapping;
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
  private final List<String> snapshots = new ArrayList<>();
  private EventProcessor processor;

  @BeforeEach
  void setUp() {
    journal = new FakeJournal();
    projection = new FakeProjection();
    processor =
        new EventProcessor(
            journal,
            Normalizer.standard(key -> true),
            new FakeIdentity(),
            projection,
            (source, run, eventId, count) -> snapshots.add(source + "/" + run + "/" + eventId));
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
