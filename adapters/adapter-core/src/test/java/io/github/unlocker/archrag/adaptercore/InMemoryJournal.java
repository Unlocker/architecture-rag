package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.eventschemas.Checkpoint;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ObjectRef;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.SnapshotContents;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Журнал в памяти с тем же контрактом дедупликации, что и PostgreSQL; для unit-тестов. */
final class InMemoryJournal implements EventJournal {

  final Map<String, JournalEntry> rows = new LinkedHashMap<>();
  final Map<String, CanonicalEvent> events = new LinkedHashMap<>();
  final Map<String, Checkpoint> checkpoints = new LinkedHashMap<>();
  /** Если задано, {@code append} бросает это исключение. */
  RuntimeException failAppend;

  @Override
  public JournalEntry append(CanonicalEvent e, RawPayloadRef ref, String syncRunId) {
    if (failAppend != null) {
      throw failAppend;
    }
    String key = e.source() + "|" + e.id();
    JournalEntry existing = rows.get(key);
    if (existing != null) {
      return new JournalEntry(existing.source(), existing.eventId(), existing.correlationId(),
          existing.syncRunId(), existing.sourceType(), existing.sourceId(), existing.sourceVersion(),
          existing.schemaVersion(), ProcessingStatus.DUPLICATE, null, null, 0, existing.payloadRef(),
          existing.receivedAt(), existing.updatedAt());
    }
    Instant now = Instant.parse("2026-10-04T00:00:00Z");
    JournalEntry row = new JournalEntry(e.source(), e.id(), e.correlationid(), syncRunId,
        e.data().sourceType(), e.data().sourceId(), e.data().sourceVersion(), e.dataschema(),
        ProcessingStatus.RECEIVED, null, null, 0, ref, now, now);
    rows.put(key, row);
    events.put(key, e);
    return row;
  }

  List<CanonicalEvent> eventList() {
    return new ArrayList<>(events.values());
  }

  @Override
  public Optional<JournalEntry> find(String source, String eventId) {
    return Optional.ofNullable(rows.get(source + "|" + eventId));
  }

  @Override
  public JournalEntry transition(String s, String id, ProcessingStatus st, String c, String r) {
    throw new UnsupportedOperationException();
  }

  @Override
  public JournalEntry toDlq(String s, String id, String c, String r) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SnapshotContents snapshotContents(String source, String syncRunId) {
    var runRows = rows.values().stream()
        .filter(r -> r.source().equals(source) && r.eventId().startsWith("snap:" + syncRunId + ":")).toList();
    return new SnapshotContents(
        runRows.stream().map(r -> new ObjectRef(r.sourceType(), r.sourceId())).collect(Collectors.toSet()),
        runRows.size(),
        runRows.stream().map(JournalEntry::receivedAt).min(Comparator.naturalOrder()).orElse(null));
  }

  @Override
  public Set<ObjectRef> objectsReceivedSince(String source, Instant since) {
    return rows.values().stream()
        .filter(r -> r.source().equals(source) && !r.receivedAt().isBefore(since))
        .map(r -> new ObjectRef(r.sourceType(), r.sourceId()))
        .collect(Collectors.toSet());
  }

  @Override
  public Optional<Checkpoint> loadCheckpoint(String consumer, String source) {
    return Optional.ofNullable(checkpoints.get(consumer + "|" + source));
  }

  @Override
  public Checkpoint saveCheckpoint(String consumer, String source, String cursor) {
    Checkpoint cp = new Checkpoint(consumer, source, cursor, Instant.parse("2026-10-04T00:00:00Z"));
    checkpoints.put(consumer + "|" + source, cp);
    return cp;
  }
}
