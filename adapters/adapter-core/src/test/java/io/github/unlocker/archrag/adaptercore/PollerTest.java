package io.github.unlocker.archrag.adaptercore;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcestubs.StubSource;
import io.github.unlocker.archrag.sourcestubs.StubSources;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PollerTest {

  private static final String SOURCE = "urn:corp:eam";
  private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
  private final InMemoryJournal journal = new InMemoryJournal();
  private final InMemoryRawStore raw = new InMemoryRawStore();
  private final List<Duration> sleeps = new ArrayList<>();
  private final AtomicInteger runCounter = new AtomicInteger();
  private StubSource eam;
  private Poller poller;

  @BeforeEach
  void setUp() {
    eam = StubSources.eam(clock);
    AdapterConfig config = new AdapterConfig(SourceSystem.EAM, URI.create("http://unused"), "s",
        Duration.ofMinutes(5), 2, Duration.ofSeconds(1),
        new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4)));
    poller = new Poller(config, eam, journal, raw, clock, sleeps::add, new Random(5),
        () -> "run-" + runCounter.incrementAndGet());
  }

  private List<CanonicalEvent> eventsOfType(String type) {
    return journal.eventList().stream().filter(e -> e.type().equals(type)).toList();
  }

  @Test
  void firstCycleIsFullSnapshotEndingWithMarkerAfterAllObjects() {
    PollResult r = poller.pollOnce();

    assertThat(r.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    assertThat(r.appended()).isEqualTo(4);
    assertThat(r.syncRunId()).isEqualTo("run-1");
    List<CanonicalEvent> all = journal.eventList();
    assertThat(all).hasSize(5);
    assertThat(all.getLast().type()).isEqualTo(EventMapper.TYPE_SNAPSHOT_COMPLETE);
    assertThat(all.getLast().data().sourceId()).isEqualTo("run-1");
    assertThat(all.getLast().data().payload()).containsEntry("objectCount", 4L);
    assertThat(journal.rows.values()).allMatch(row -> "run-1".equals(row.syncRunId()));
    assertThat(journal.loadCheckpoint("eam-poller", SOURCE)).isPresent();
  }

  @Test
  void incompleteSnapshotDoesNotWriteMarkerOrMoveCursor() {
    // Страница 1 прочитана, дальше источник лежит дольше, чем терпит политика повторов.
    var failingAfterFirstPage = new FailingConnector(eam, 1);

    PollResult r = pollerOver(failingAfterFirstPage).pollOnce();

    assertThat(r.outcome()).isEqualTo(PollResult.Outcome.GAVE_UP);
    assertThat(eventsOfType(EventMapper.TYPE_SNAPSHOT_COMPLETE)).isEmpty();
    assertThat(journal.loadCheckpoint("eam-poller", SOURCE)).isEmpty();
  }

  @Test
  void restartedSnapshotReusesRunIdAndRecordsDuplicatesInTheSameRun() {
    pollerOver(new FailingConnector(eam, 1)).pollOnce();
    int rowsAfterFailure = journal.rows.size();
    assertThat(rowsAfterFailure).isEqualTo(2);

    PollResult r = poller.pollOnce();

    assertThat(r.outcome()).isEqualTo(PollResult.Outcome.SNAPSHOT_COMPLETED);
    assertThat(r.syncRunId()).isEqualTo("run-1");
    assertThat(runCounter.get()).isEqualTo(1);
    // 4 объекта + маркер; 2 уже записанных не задвоились.
    assertThat(journal.rows).hasSize(5);
    assertThat(journal.loadCheckpoint("eam-snapshot", SOURCE).orElseThrow().cursor())
        .isEqualTo("done:run-1");
  }

  @Test
  void secondSnapshotGetsNewRunIdAfterCompletion() {
    poller.pollOnce();
    journal.checkpoints.remove("eam-poller|" + SOURCE);

    PollResult r = poller.pollOnce();

    assertThat(r.syncRunId()).isEqualTo("run-2");
    assertThat(eventsOfType(EventMapper.TYPE_SNAPSHOT_COMPLETE)).hasSize(2);
  }

  @Test
  void incrementalPollingAdvancesCursorAfterEachPage() {
    poller.pollOnce();
    String cursorAfterSnapshot = journal.loadCheckpoint("eam-poller", SOURCE).orElseThrow().cursor();
    eam.upsert(StubSources.IT_SYSTEM, "EAM-3000", StubSources.fields("name", "New"));
    eam.upsert(StubSources.IT_SYSTEM, "EAM-3001", StubSources.fields("name", "New2"));
    eam.upsert(StubSources.IT_SYSTEM, "EAM-3002", StubSources.fields("name", "New3"));

    PollResult r = poller.pollOnce();

    assertThat(r.outcome()).isEqualTo(PollResult.Outcome.ADVANCED);
    assertThat(r.appended()).isEqualTo(3);
    assertThat(journal.loadCheckpoint("eam-poller", SOURCE).orElseThrow().cursor())
        .isNotEqualTo(cursorAfterSnapshot);
    assertThat(poller.pollOnce().outcome()).isEqualTo(PollResult.Outcome.UP_TO_DATE);
    assertThat(journal.rows).hasSize(5 + 3);
  }

  @Test
  void rateLimitKeepsCursorAndResumesFromTheSamePlace() {
    poller.pollOnce();
    String before = journal.loadCheckpoint("eam-poller", SOURCE).orElseThrow().cursor();
    eam.upsert(StubSources.IT_SYSTEM, "EAM-4000", StubSources.fields("name", "N"));
    eam.failNext(10, 429, Duration.ofSeconds(3));
    int rowsBefore = journal.rows.size();

    PollResult limited = poller.pollOnce();

    assertThat(limited.outcome()).isEqualTo(PollResult.Outcome.GAVE_UP);
    assertThat(journal.loadCheckpoint("eam-poller", SOURCE).orElseThrow().cursor()).isEqualTo(before);
    assertThat(journal.rows).hasSize(rowsBefore);
    // 3 попытки → 2 паузы, и обе не короче Retry-After.
    assertThat(sleeps).hasSize(2).allMatch(d -> d.compareTo(Duration.ofSeconds(3)) >= 0);

    eam.failNext(0, 429, null);
    PollResult recovered = poller.pollOnce();

    assertThat(recovered.outcome()).isEqualTo(PollResult.Outcome.ADVANCED);
    assertThat(recovered.appended()).isEqualTo(1);
    assertThat(journal.rows).hasSize(rowsBefore + 1);
  }

  @Test
  void transientFailureIsRetriedWithinOneCycle() {
    poller.pollOnce();
    eam.upsert(StubSources.IT_SYSTEM, "EAM-5000", StubSources.fields("name", "N"));
    eam.failNext(2, 503, null);

    PollResult r = poller.pollOnce();

    assertThat(r.outcome()).isEqualTo(PollResult.Outcome.ADVANCED);
    assertThat(sleeps).hasSize(2);
  }

  @Test
  void redeliveredAndReorderedPageProducesNoDuplicateRows() {
    poller.pollOnce();
    eam.upsert(StubSources.IT_SYSTEM, "EAM-6000", StubSources.fields("name", "A"));
    eam.upsert(StubSources.IT_SYSTEM, "EAM-6001", StubSources.fields("name", "B"));
    eam.reorderNextPage();
    int before = journal.rows.size();

    poller.pollOnce();

    assertThat(journal.rows).hasSize(before + 2);
  }

  @Test
  void partialRecordWithNullsDoesNotCrashAndKeepsNullsOutOfPayload() {
    poller.pollOnce();
    eam.upsertPartial(StubSources.IT_SYSTEM, "EAM-1042",
        StubSources.fields("name", null, "ownerTeam", "TEAM-PAY"));

    poller.pollOnce();

    CanonicalEvent e = journal.eventList().getLast();
    assertThat(e.data().payload())
        .containsEntry("ownerTeam", "TEAM-PAY")
        .containsEntry(EventMapper.COMPLETENESS_KEY, "PARTIAL")
        .doesNotContainKey("name");
    // Raw в S3 сохраняет источник как есть, включая null.
    var ref = journal.find(SOURCE, e.id()).orElseThrow().payloadRef();
    assertThat(raw.text(ref)).contains("\"name\":null");
  }

  @Test
  void deleteBecomesTombstoneEventWithEmptyPayload() {
    poller.pollOnce();
    eam.delete(StubSources.IT_SYSTEM, "EAM-2001");

    poller.pollOnce();

    CanonicalEvent e = journal.eventList().getLast();
    assertThat(e.type()).isEqualTo(EventMapper.TYPE_ASSET_DELETED);
    assertThat(e.data().payload()).isEmpty();
    assertThat(e.id()).isEqualTo("poll:IT_SYSTEM/EAM-2001/v2");
  }

  @Test
  void journalFailureDoesNotMoveCheckpoint() {
    poller.pollOnce();
    String before = journal.loadCheckpoint("eam-poller", SOURCE).orElseThrow().cursor();
    eam.upsert(StubSources.IT_SYSTEM, "EAM-7000", StubSources.fields("name", "N"));
    journal.failAppend = new IllegalStateException("db down");

    org.assertj.core.api.Assertions.assertThatThrownBy(poller::pollOnce)
        .isInstanceOf(IllegalStateException.class);

    assertThat(journal.loadCheckpoint("eam-poller", SOURCE).orElseThrow().cursor()).isEqualTo(before);
  }

  @Test
  void hasMoreWithoutNewCursorFailsInsteadOfLooping() {
    var stuckSnapshot = new StuckConnector(null);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> pollerOver(stuckSnapshot).pollOnce())
        .isInstanceOf(IllegalStateException.class);
    assertThat(eventsOfType(EventMapper.TYPE_SNAPSHOT_COMPLETE)).isEmpty();

    journal.saveCheckpoint("eam-poller", SOURCE, "i1");
    var stuckIncremental = new StuckConnector("i1");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> pollerOver(stuckIncremental).pollOnce())
        .isInstanceOf(IllegalStateException.class);
  }

  /** Всегда отвечает {@code hasMore=true} с тем же курсором. */
  private record StuckConnector(String sameCursor)
      implements io.github.unlocker.archrag.sourcespi.SourceConnector {
    @Override
    public SourceSystem system() {
      return SourceSystem.EAM;
    }

    @Override
    public io.github.unlocker.archrag.sourcespi.ChangePage fetchChanges(String cursor, int limit) {
      return new io.github.unlocker.archrag.sourcespi.ChangePage(List.of(), sameCursor, true, false);
    }

    @Override
    public java.util.Optional<io.github.unlocker.archrag.sourcespi.SourceChange> fetchById(String t, String i) {
      return java.util.Optional.empty();
    }
  }

  private Poller pollerOver(io.github.unlocker.archrag.sourcespi.SourceConnector connector) {
    AdapterConfig config = new AdapterConfig(SourceSystem.EAM, URI.create("http://unused"), "s",
        Duration.ofMinutes(5), 2, Duration.ofSeconds(1),
        new RetryPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(4)));
    return new Poller(config, connector, journal, raw, clock, sleeps::add, new Random(5),
        () -> "run-" + runCounter.incrementAndGet());
  }

  /** Отдаёт {@code okCalls} страниц, дальше всегда 503. */
  private static final class FailingConnector implements io.github.unlocker.archrag.sourcespi.SourceConnector {
    private final StubSource delegate;
    private int okCalls;

    FailingConnector(StubSource delegate, int okCalls) {
      this.delegate = delegate;
      this.okCalls = okCalls;
    }

    @Override
    public SourceSystem system() {
      return delegate.system();
    }

    @Override
    public io.github.unlocker.archrag.sourcespi.ChangePage fetchChanges(String cursor, int limit) {
      if (okCalls-- <= 0) {
        throw new io.github.unlocker.archrag.sourcespi.SourceUnavailableException(system(), 503, null);
      }
      return delegate.fetchChanges(cursor, limit);
    }

    @Override
    public java.util.Optional<io.github.unlocker.archrag.sourcespi.SourceChange> fetchById(String t, String i) {
      return delegate.fetchById(t, i);
    }
  }
}
