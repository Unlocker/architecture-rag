package io.github.unlocker.archrag.graphprojector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.Checkpoint;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ObjectRef;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.SnapshotContents;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.canonicalmodel.node.NodeLabel;
import io.github.unlocker.archrag.identityresolution.Feature;
import io.github.unlocker.archrag.identityresolution.IdentityCandidate;
import io.github.unlocker.archrag.identityresolution.IdentityCandidates;
import io.github.unlocker.archrag.identityresolution.IdentityStoreException;
import io.github.unlocker.archrag.identityresolution.SourceConflict;
import io.github.unlocker.archrag.identityresolution.SourceConflicts;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

  private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
  private static final String SOURCE = "urn:corp:eam";

  private final StubJournal journal = new StubJournal();
  private final StubRawStore rawStore = new StubRawStore();
  private final RecordingProjection projection = new RecordingProjection();
  private final List<String> calls = new ArrayList<>();
  private final EventProcessorTest.FakeIdentity identity = new EventProcessorTest.FakeIdentity();
  private final FakeConflicts conflicts = new FakeConflicts(calls);
  private final FakeCandidates candidates = new FakeCandidates(calls);
  private final List<String> skips = new ArrayList<>();
  /** Без ограничения доли: тесты механики tombstone закрывают всё, что не вошло в прогон. */
  private final Reconciler reconciler = reconciler(1.0);

  private Reconciler reconciler(double maxMissingShare) {
    return new Reconciler(
        journal, rawStore, projection, Clock.fixed(NOW, ZoneOffset.UTC), identity, conflicts, candidates, maxMissingShare,
        (source, reason) -> skips.add(source + "/" + reason));
  }

  private static SourceKey team(String id) {
    return new SourceKey(SourceSystemCode.EAM, "TEAM", id);
  }

  @Test
  void tombstonesOnlyActiveRecordsMissingFromTheRun() {
    journal.runObjects.add(new ObjectRef("TEAM", "kept"));
    projection.active.add(new GraphProjection.ActiveRecord(team("kept"), new SourceVersion("3")));
    projection.active.add(new GraphProjection.ActiveRecord(team("gone"), new SourceVersion("7")));

    var report = reconciler.reconcile(SOURCE, "run-1", "snapshot-complete:run-1", 1);

    assertThat(report).isEqualTo(new Reconciler.Report(1, 0, 0, 1));
    assertThat(projection.requests).hasSize(1);
    var request = projection.requests.getFirst();
    assertThat(request.key()).isEqualTo(team("gone"));
    // Tombstone идёт с применённой версией, а не с выдуманной: обновлённый объект не будет затёрт.
    assertThat(request.version()).isEqualTo(new SourceVersion("7"));
    assertThat(request.commands()).singleElement().isInstanceOf(TombstoneSourceRecord.class);
    assertThat(request.eventTime()).isEqualTo(NOW);
  }

  @Test
  void incompleteRunInJournalDeletesNothing() {
    journal.runObjects.add(new ObjectRef("TEAM", "a"));
    projection.active.add(new GraphProjection.ActiveRecord(team("gone"), new SourceVersion("1")));

    assertThatThrownBy(() -> reconciler.reconcile(SOURCE, "run-1", "m", 3))
        .isInstanceOf(IllegalStateException.class);
    assertThat(projection.requests).isEmpty();
  }

  @Test
  void objectTouchedAfterRunStartIsNotDeleted() {
    journal.runObjects.add(new ObjectRef("TEAM", "a"));
    journal.touched.add(new ObjectRef("TEAM", "created-by-webhook"));
    projection.active.add(new GraphProjection.ActiveRecord(team("created-by-webhook"), new SourceVersion("1")));

    var report = reconciler.reconcile(SOURCE, "run-1", "m", 1);

    assertThat(report.keptRecentlyTouched()).isEqualTo(1);
    assertThat(report.tombstoned()).isZero();
    assertThat(projection.requests).isEmpty();
    assertThat(journal.since).isEqualTo(journal.firstReceivedAt);
  }

  @Test
  void emptyRunWithMarkerTombstonesEverythingActiveBeforeTheMarker() {
    journal.firstReceivedAt = null;
    projection.active.add(new GraphProjection.ActiveRecord(team("x"), new SourceVersion("1")));

    var report = reconciler.reconcile(SOURCE, "run-1", "snapshot-complete:run-1", 0);

    assertThat(report.tombstoned()).isEqualTo(1);
    assertThat(journal.since).isEqualTo(journal.markerReceivedAt);
  }

  @Test
  void unknownSourceIsRejected() {
    assertThatThrownBy(() -> reconciler.reconcile("urn:corp:nope", "run-1", "m", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void projectionOldVersionIsCountedAndNotTreatedAsDeleted() {
    projection.outcome = ProjectionOutcome.IGNORED_OLD_VERSION;
    projection.active.add(new GraphProjection.ActiveRecord(team("x"), new SourceVersion("1")));

    var report = reconciler.reconcile(SOURCE, "run-1", "m", 0);

    assertThat(report).isEqualTo(new Reconciler.Report(0, 1, 0, 0));
  }

  @Test
  void retractsAssertionsBeforeProjectionAndPassesMarkers() {
    projection.calls = calls;
    var gone = team("gone");
    var dissent = new SourceKey(SourceSystemCode.CMDB, "TEAM", "d1");
    UUID gid = UUID.randomUUID();
    identity.map.put(gone, gid);
    conflicts.open =
        List.of(new SourceConflict(gid, "name", dissent, "x", gone, "y", SourceConflict.Status.OPEN, NOW, NOW, null));
    projection.active.add(new GraphProjection.ActiveRecord(gone, new SourceVersion("7")));

    reconciler.reconcile(SOURCE, "run-1", "m", 0);

    assertThat(calls).containsExactly("retract", "forget", "project");
    assertThat(projection.requests).singleElement().satisfies(r -> {
      assertThat(r.conflicts().gid()).isEqualTo(gid);
      assertThat(r.conflicts().byRecord()).containsExactly(Map.entry(dissent, List.of("name")));
    });
  }

  @Test
  void recordWithoutGidDoesNotCallConflictStore() {
    projection.active.add(new GraphProjection.ActiveRecord(team("gone"), new SourceVersion("7")));

    reconciler.reconcile(SOURCE, "run-1", "m", 0);

    assertThat(calls).doesNotContain("retract");
    assertThat(projection.requests).singleElement().satisfies(r -> assertThat(r.conflicts().isNone()).isTrue());
  }

  @Test
  void conflictStoreFailurePropagatesBeforeGraphIsTouched() {
    identity.map.put(team("gone"), UUID.randomUUID());
    conflicts.fail = true;
    projection.active.add(new GraphProjection.ActiveRecord(team("gone"), new SourceVersion("7")));

    assertThatThrownBy(() -> reconciler.reconcile(SOURCE, "run-1", "m", 0)).isInstanceOf(IdentityStoreException.class);
    assertThat(projection.requests).isEmpty();
  }

  @Test
  void newerAppliedVersionSkipsRetract() {
    var gone = team("gone");
    identity.map.put(gone, UUID.randomUUID());
    projection.applied.put(gone, new GraphProjection.AppliedRecord(new SourceVersion("8"), true));
    projection.active.add(new GraphProjection.ActiveRecord(gone, new SourceVersion("7")));

    reconciler.reconcile(SOURCE, "run-1", "m", 0);

    assertThat(calls).isEmpty();
    assertThat(projection.requests).singleElement().satisfies(r -> assertThat(r.conflicts().isNone()).isTrue());
  }

  private void activeTeams(int count) {
    for (int i = 0; i < count; i++) {
      projection.active.add(new GraphProjection.ActiveRecord(team("t" + i), new SourceVersion("1")));
    }
  }

  @Test
  void partialRunDeletesNothingAndIsReported() {
    activeTeams(2);

    var report = reconciler.reconcile(SOURCE, "run-1", "m", 0, true);

    assertThat(report).isEqualTo(new Reconciler.Report(0, 0, 0, 0));
    assertThat(projection.requests).isEmpty();
    assertThat(journal.appended).isEmpty();
    assertThat(candidates.forgotten).isEmpty();
    assertThat(skips).containsExactly("EAM/PARTIAL");
  }

  @Test
  void partialFlagIsPassedThroughTheTriggerPort() {
    activeTeams(1);

    reconciler.snapshotComplete(SOURCE, "run-1", "m", 0, true);

    assertThat(projection.requests).isEmpty();
  }

  @Test
  void missingSetOverTheShareIsRejectedBeforeAnyWrite() {
    activeTeams(4);
    journal.runObjects.add(new ObjectRef("TEAM", "t0"));

    // 3 из 4 активных не вошли в прогон: 3 > 0.5 * 4
    assertThatThrownBy(() -> reconciler(0.5).reconcile(SOURCE, "run-1", "m", 1))
        .isInstanceOfSatisfying(
            SnapshotRejectedException.class,
            e -> assertThat(e.code()).isEqualTo(SnapshotRejectedException.MASS_CLOSE_LIMIT));

    assertThat(projection.requests).isEmpty();
    assertThat(journal.appended).isEmpty();
    assertThat(rawStore.stored).isEmpty();
    assertThat(candidates.forgotten).isEmpty();
    assertThat(calls).isEmpty();
    assertThat(skips).containsExactly("EAM/" + SnapshotRejectedException.MASS_CLOSE_LIMIT);
  }

  @Test
  void missingSetEqualToTheShareIsAllowed() {
    activeTeams(4);
    journal.runObjects.add(new ObjectRef("TEAM", "t0"));
    journal.runObjects.add(new ObjectRef("TEAM", "t1"));

    var report = reconciler(0.5).reconcile(SOURCE, "run-1", "m", 2);

    assertThat(report.tombstoned()).isEqualTo(2);
    assertThat(skips).isEmpty();
  }

  @Test
  void objectsKeptByTheRunOrRecentTouchDoNotCountTowardTheShare() {
    activeTeams(4);
    journal.runObjects.add(new ObjectRef("TEAM", "t0"));
    journal.touched.add(new ObjectRef("TEAM", "t1"));
    journal.touched.add(new ObjectRef("TEAM", "t2"));

    var report = reconciler(0.5).reconcile(SOURCE, "run-1", "m", 1);

    assertThat(report).isEqualTo(new Reconciler.Report(1, 0, 2, 1));
  }

  @Test
  void sourceWithoutActiveRecordsPassesTheShareCheck() {
    assertThat(reconciler(0.5).reconcile(SOURCE, "run-1", "m", 0).tombstoned()).isZero();
  }

  @Test
  void negativeObjectCountIsRejected() {
    activeTeams(1);

    assertThatThrownBy(() -> reconciler.reconcile(SOURCE, "run-1", "m", -1))
        .isInstanceOfSatisfying(
            SnapshotRejectedException.class,
            e -> assertThat(e.code()).isEqualTo(SnapshotRejectedException.MISSING_OBJECT_COUNT));
    assertThat(projection.requests).isEmpty();
  }

  @Test
  void shareOutsideZeroToOneIsRejected() {
    for (double bad : new double[] {0, -0.1, 1.01, Double.NaN}) {
      assertThatThrownBy(() -> reconciler(bad)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void tombstoneForgetsIdentityFeaturesBeforeTheGraphWrite() {
    projection.calls = calls;
    activeTeams(1);

    reconciler.reconcile(SOURCE, "run-1", "m", 0);

    assertThat(candidates.forgotten).containsExactly(team("t0"));
    assertThat(calls).containsExactly("forget", "project");
  }

  @Test
  void newerAppliedVersionDoesNotForgetFeatures() {
    var gone = team("gone");
    projection.applied.put(gone, new GraphProjection.AppliedRecord(new SourceVersion("8"), true));
    projection.active.add(new GraphProjection.ActiveRecord(gone, new SourceVersion("7")));

    reconciler.reconcile(SOURCE, "run-1", "m", 0);

    assertThat(candidates.forgotten).isEmpty();
  }

  @Test
  void candidateStoreFailureDoesNotTouchTheGraph() {
    candidates.fail = true;
    activeTeams(1);

    assertThatThrownBy(() -> reconciler.reconcile(SOURCE, "run-1", "m", 0)).isInstanceOf(IdentityStoreException.class);
    assertThat(projection.requests).isEmpty();
  }

  static final class FakeCandidates implements IdentityCandidates {
    final List<String> calls;
    final List<SourceKey> forgotten = new ArrayList<>();
    boolean fail;

    FakeCandidates(List<String> calls) {
      this.calls = calls;
    }

    @Override
    public List<IdentityCandidate> record(SourceKey key, NodeLabel label, Set<Feature> features) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void forget(SourceKey key) {
      if (fail) {
        throw new IdentityStoreException("down", null);
      }
      calls.add("forget");
      forgotten.add(key);
    }

    @Override
    public List<IdentityCandidate> candidatesOf(SourceKey key) {
      throw new UnsupportedOperationException();
    }
  }

  static final class FakeConflicts implements SourceConflicts {
    final List<String> calls;
    List<SourceConflict> open = List.of();
    boolean fail;

    FakeConflicts(List<String> calls) {
      this.calls = calls;
    }

    @Override
    public List<SourceConflict> assertProperties(SourceKey key, UUID gid, NodeLabel label, Map<String, String> values) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SourceConflict> assertRelation(
        SourceKey dissent, UUID gid, String property, String dissentValue, Optional<SourceKey> master, String masterValue) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SourceConflict> retract(SourceKey key, UUID gid) {
      if (fail) {
        throw new IdentityStoreException("down", null);
      }
      calls.add("retract");
      return open;
    }

    @Override
    public List<SourceConflict> openConflicts(UUID gid) {
      return open;
    }
  }

  static final class RecordingProjection implements GraphProjection {
    final List<GraphProjection.ActiveRecord> active = new ArrayList<>();
    final List<ProjectionRequest> requests = new ArrayList<>();
    final Map<SourceKey, AppliedRecord> applied = new HashMap<>();
    List<String> calls;
    ProjectionOutcome outcome = ProjectionOutcome.APPLIED;

    @Override
    public ProjectionResult project(ProjectionRequest request) {
      if (calls != null) {
        calls.add("project");
      }
      requests.add(request);
      return ProjectionResult.of(outcome);
    }

    @Override
    public List<ActiveRecord> activeRecords(SourceSystemCode source) {
      return active;
    }

    @Override
    public Optional<AppliedRecord> applied(SourceKey key) {
      return Optional.ofNullable(applied.get(key));
    }

    @Override
    public boolean isActive(SourceKey key) {
      throw new UnsupportedOperationException();
    }
  }

  static final class StubJournal implements EventJournal {
    final Set<ObjectRef> runObjects = new HashSet<>();
    final Set<ObjectRef> touched = new HashSet<>();
    Instant firstReceivedAt = Instant.parse("2026-10-04T11:00:00Z");
    final Instant markerReceivedAt = Instant.parse("2026-10-04T11:30:00Z");
    Instant since;

    @Override
    public SnapshotContents snapshotContents(String source, String syncRunId) {
      return new SnapshotContents(runObjects, runObjects.size(), firstReceivedAt);
    }

    @Override
    public Set<ObjectRef> objectsReceivedSince(String source, Instant since) {
      this.since = since;
      return touched;
    }

    final Map<String, JournalEntry> rows = new HashMap<>();
    final List<CanonicalEvent> appended = new ArrayList<>();
    final List<String> transitions = new ArrayList<>();

    @Override
    public Optional<JournalEntry> find(String source, String eventId) {
      if (eventId.startsWith(Reconciler.EVENT_ID_PREFIX)) {
        return Optional.ofNullable(rows.get(eventId));
      }
      return Optional.of(
          new JournalEntry(source, eventId, null, "run-1", "SYNC_RUN", "run-1", new SourceVersion("1"), "1",
              ProcessingStatus.RECEIVED, null, null, 0, null, markerReceivedAt, markerReceivedAt));
    }

    @Override
    public JournalEntry append(CanonicalEvent event, RawPayloadRef payloadRef, String syncRunId) {
      appended.add(event);
      var e = new JournalEntry(event.source(), event.id(), event.correlationid(), syncRunId,
          event.data().sourceType(), event.data().sourceId(), event.data().sourceVersion(), event.dataschema(),
          ProcessingStatus.RECEIVED, null, null, 0, payloadRef, NOW, NOW);
      rows.put(event.id(), e);
      return e;
    }

    @Override
    public JournalEntry transition(String s, String id, ProcessingStatus st, String c, String r) {
      transitions.add(id + "->" + st);
      var o = rows.get(id);
      var e = new JournalEntry(o.source(), o.eventId(), o.correlationId(), o.syncRunId(), o.sourceType(), o.sourceId(),
          o.sourceVersion(), o.schemaVersion(), st, c, r, o.attempts(), o.payloadRef(), o.receivedAt(), NOW);
      rows.put(id, e);
      return e;
    }

    @Override
    public JournalEntry toDlq(String s, String id, String c, String r) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Checkpoint> loadCheckpoint(String consumer, String source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Checkpoint saveCheckpoint(String consumer, String source, String cursor) {
      throw new UnsupportedOperationException();
    }
  }

  static final class StubRawStore implements RawPayloadStore {
    final List<String> stored = new ArrayList<>();

    @Override
    public RawPayloadRef put(String source, byte[] content) {
      stored.add(new String(content, java.nio.charset.StandardCharsets.UTF_8));
      return new RawPayloadRef("raw/" + stored.size(), "h" + stored.size());
    }

    @Override
    public byte[] get(RawPayloadRef ref) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  void tombstoneIsJournaledBeforeProjectionAndReachesFinalStatus() {
    projection.active.add(new GraphProjection.ActiveRecord(team("gone"), new SourceVersion("7")));

    reconciler.reconcile(SOURCE, "run-1", "m", 0);

    String id = "reconcile:run-1:TEAM/gone";
    assertThat(journal.appended).singleElement().satisfies(e -> {
      assertThat(e.id()).isEqualTo(id);
      assertThat(e.type()).isEqualTo("architecture.asset.deleted.v1");
      assertThat(e.dataschema()).isEqualTo("urn:corp:schema:asset-deleted:1");
      assertThat(e.time()).isEqualTo(NOW);
      assertThat(e.data().sourceVersion()).isEqualTo(new SourceVersion("7"));
    });
    assertThat(rawStore.stored).singleElement().asString()
        .contains("\"operation\":\"DELETE\"", "\"sourceVersion\":7", "\"updatedAt\":\"" + NOW + "\"");
    assertThat(journal.transitions).containsExactly(
        id + "->VALIDATED", id + "->NORMALIZED", id + "->RESOLVED", id + "->PROJECTED");
  }

  @Test
  void repeatedReconcileDoesNotDuplicateTheJournalRowAndOldVersionIsRecorded() {
    projection.outcome = ProjectionOutcome.IGNORED_OLD_VERSION;
    projection.active.add(new GraphProjection.ActiveRecord(team("x"), new SourceVersion("1")));

    reconciler.reconcile(SOURCE, "run-1", "m", 0);
    reconciler.reconcile(SOURCE, "run-1", "m", 0);

    assertThat(journal.appended).hasSize(1);
    assertThat(rawStore.stored).hasSize(1);
    assertThat(journal.rows.get("reconcile:run-1:TEAM/x").status()).isEqualTo(ProcessingStatus.IGNORED_OLD_VERSION);
  }
}
