package io.github.unlocker.archrag.ingestionservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.JournalKey;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.ProcessingResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/** Диспетчер на фейках журнала, raw storage и процессора: порядок, пропуск объекта, DLQ, замок, остановка. */
class JournalDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
  private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
  private static final String UPSERT = CanonicalEvent.TYPE_ASSET_UPSERTED;
  private static final String MARKER = StoredEventReader.TYPE_SNAPSHOT_COMPLETE;

  private final JournalReader reader = mock(JournalReader.class);
  private final EventJournal journal = mock(EventJournal.class);
  private final RawPayloadStore rawStore = mock(RawPayloadStore.class);
  private final StoredEventReader events = mock(StoredEventReader.class);
  private final EventProcessor processor = mock(EventProcessor.class);
  private final AdminLock lock = mock(AdminLock.class);
  private final AdminLock.Lease lease = mock(AdminLock.Lease.class);
  private final List<StoredEvent> journalRows = new ArrayList<>();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IngestionMetrics metrics = new IngestionMetrics(registry, mock(JdbcTemplate.class));
  private JournalDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher = new JournalDispatcher(reader, journal, rawStore, events, processor, lock,
        Clock.fixed(NOW, ZoneOffset.UTC), 2, RETRY_DELAY, metrics);
    when(lock.tryAcquire()).thenReturn(Optional.of(lease));
    // keyset по списку журнальных строк, страницы по 2
    when(reader.pending(any(), any(), anyInt())).thenAnswer(inv -> {
      JournalKey after = inv.getArgument(0);
      int limit = inv.getArgument(2);
      List<StoredEvent> page = new ArrayList<>();
      boolean passed = after == null;
      for (StoredEvent s : journalRows) {
        if (!passed) {
          passed = JournalKey.of(s.entry()).equals(after);
          continue;
        }
        if (page.size() < limit) {
          page.add(s);
        }
      }
      return page;
    });
    when(rawStore.get(any())).thenReturn(new byte[] {1});
    when(events.toEvent(any(), any())).thenAnswer(inv -> event(inv.getArgument(0)));
    when(processor.process(any(), any())).thenReturn(new ProcessingResult(ProcessingStatus.PROJECTED, null, null));
  }

  @Test
  void processesInJournalOrderAcrossPages() {
    add("e1", "A", UPSERT, true);
    add("e2", "B", UPSERT, true);
    add("e3", "C", UPSERT, true);
    add("e4", "D", UPSERT, true);
    add("e5", "E", UPSERT, true);

    assertThat(dispatcher.runOnce()).isEqualTo(5);

    InOrder order = inOrder(processor);
    for (String id : List.of("e1", "e2", "e3", "e4", "e5")) {
      order.verify(processor).process(org.mockito.ArgumentMatchers.argThat(e -> e.id().equals(id)), any());
    }
    verify(reader).pending(eq(null), eq(NOW.minus(RETRY_DELAY)), eq(2));
  }

  @Test
  void laterEventsOfObjectAreSkippedAfterRetrying() {
    add("a1", "A", UPSERT, true);
    add("a2", "A", UPSERT, true);
    add("b1", "B", UPSERT, true);
    when(processor.process(argEvent("a1"), any())).thenReturn(new ProcessingResult(ProcessingStatus.RETRYING, "X", null));

    assertThat(dispatcher.runOnce()).isEqualTo(2);

    verify(processor, never()).process(argEvent("a2"), any());
    verify(processor).process(argEvent("b1"), any());
  }

  @Test
  void metricsCountFinalStatusAndErrorForUnexpectedException() {
    add("a1", "A", UPSERT, true);
    add("b1", "B", UPSERT, true);
    when(rawStore.get(rawRef("a1"))).thenThrow(new IllegalStateException("S3 down"));

    dispatcher.runOnce();

    assertThat(registry.get(IngestionMetrics.EVENTS).tag("source", "eam").tag("status", "ERROR").counter().count())
        .isEqualTo(1);
    assertThat(registry.get(IngestionMetrics.EVENTS).tag("source", "eam").tag("status", "PROJECTED").counter().count())
        .isEqualTo(1);
  }

  @Test
  void exceptionOfOneEventDoesNotStopPassButSkipsItsObject() {
    add("a1", "A", UPSERT, true);
    add("a2", "A", UPSERT, true);
    add("b1", "B", UPSERT, true);
    when(rawStore.get(rawRef("a1"))).thenThrow(new IllegalStateException("S3 down SECRET"));

    assertThat(dispatcher.runOnce()).isEqualTo(2);

    verify(processor, never()).process(argEvent("a1"), any());
    verify(processor, never()).process(argEvent("a2"), any());
    verify(processor).process(argEvent("b1"), any());
    verify(journal, never()).toDlq(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void invalidRawGoesToDlqAndNextEventIsProcessed() {
    add("bad", "A", UPSERT, true);
    add("ok", "B", UPSERT, true);
    org.mockito.Mockito.doThrow(new IllegalArgumentException("SECRET")).when(events).toEvent(storedId("bad"), any());
    when(journal.toDlq(anyString(), eq("bad"), anyString(), anyString())).thenReturn(entryWith("bad", ProcessingStatus.QUARANTINED));

    dispatcher.runOnce();

    verify(journal).toDlq("urn:corp:eam", "bad", JournalDispatcher.INVALID_RAW_PAYLOAD, "raw payload is not a valid event");
    verify(processor).process(argEvent("ok"), any());
    verify(processor, never()).process(argEvent("bad"), any());
  }

  @Test
  void missingRawGoesToDlqWithCodeByType() {
    add("noraw", "A", UPSERT, false);
    add("marker", "run-1", MARKER, false);
    when(journal.toDlq(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(inv -> entryWith(inv.getArgument(1), ProcessingStatus.QUARANTINED));

    dispatcher.runOnce();

    verify(journal).toDlq(eq("urn:corp:eam"), eq("noraw"), eq(JournalDispatcher.NO_RAW_PAYLOAD), anyString());
    verify(journal).toDlq(eq("urn:corp:eam"), eq("marker"), eq(JournalDispatcher.MARKER_NO_RAW), anyString());
    verify(processor, never()).process(any(), any());
  }

  @Test
  void busyAdminLockSkipsPassWithoutReadingJournal() {
    add("e1", "A", UPSERT, true);
    when(lock.tryAcquire()).thenReturn(Optional.empty());

    assertThat(dispatcher.runOnce()).isZero();

    verify(reader, never()).pending(any(), any(), anyInt());
    verify(processor, never()).process(any(), any());
  }

  @Test
  void lockIsReleasedAfterEachPage() {
    add("e1", "A", UPSERT, true);
    add("e2", "B", UPSERT, true);
    add("e3", "C", UPSERT, true);

    dispatcher.runOnce();

    // страницы: [e1,e2], [e3], пустая
    verify(lease, org.mockito.Mockito.times(3)).close();
  }

  @Test
  void stopInterruptsPassBetweenEvents() {
    add("e1", "A", UPSERT, true);
    add("e2", "B", UPSERT, true);
    when(processor.process(argEvent("e1"), any())).thenAnswer(inv -> {
      dispatcher.stop();
      return new ProcessingResult(ProcessingStatus.PROJECTED, null, null);
    });

    assertThat(dispatcher.runOnce()).isEqualTo(1);

    verify(processor, never()).process(argEvent("e2"), any());
  }

  // ---- helpers -----------------------------------------------------------------------------

  private int seq;

  private void add(String eventId, String objectId, String type, boolean withRaw) {
    Instant t = NOW.minusSeconds(1000 - seq++);
    RawPayloadRef ref = withRaw ? new RawPayloadRef("raw/" + eventId, "h") : null;
    JournalEntry entry = new JournalEntry("urn:corp:eam", eventId, "corr", "run", "IT_SYSTEM", objectId,
        new SourceVersion("1"), "urn:corp:schema:asset-upserted:1", ProcessingStatus.RECEIVED, null, null, 0, ref, t, t);
    journalRows.add(new StoredEvent(entry, type, "s/" + objectId));
  }

  private static JournalEntry entryWith(String eventId, ProcessingStatus status) {
    return new JournalEntry("urn:corp:eam", eventId, "corr", "run", "IT_SYSTEM", "x", new SourceVersion("1"),
        "urn:corp:schema:asset-upserted:1", status, "C", "r", 0, null, NOW, NOW);
  }

  private static CanonicalEvent event(StoredEvent s) {
    JournalEntry e = s.entry();
    return new CanonicalEvent(e.eventId(), e.source(), s.type(), s.subject(), NOW, e.schemaVersion(), "corr",
        new AssetEventData(e.sourceType(), e.sourceId(), e.sourceVersion(), Map.of()));
  }

  private static CanonicalEvent argEvent(String id) {
    return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.id().equals(id));
  }

  private static StoredEvent storedId(String id) {
    return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.entry().eventId().equals(id));
  }

  private static RawPayloadRef rawRef(String eventId) {
    return org.mockito.ArgumentMatchers.argThat(r -> r != null && r.key().equals("raw/" + eventId));
  }
}
