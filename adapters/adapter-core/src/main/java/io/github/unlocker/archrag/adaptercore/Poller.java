package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.eventschemas.Checkpoint;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Цикл чтения источника: инкрементальный polling по курсору и полный snapshot с маркером
 * {@code snapshot-complete}. На один источник должен работать один poller.
 *
 * <p>Курсор хранится в checkpoint consumer {@code <code>-poller} и сдвигается только после
 * успешного {@code append} всей страницы. Пустой checkpoint означает полный snapshot: страницы
 * записываются под одним {@code syncRunId}, а маркер — только после последней страницы. {@code
 * syncRunId} незавершённого snapshot хранится в checkpoint {@code <code>-snapshot} ({@code
 * run:<id>}), поэтому перезапуск с начала переиспользует его, а повторные события дают {@code
 * DUPLICATE} и остаются в том же прогоне. Недоступность источника повторяется по {@link
 * RetryPolicy}; если попытки исчерпаны, цикл завершается {@link PollResult.Outcome#GAVE_UP} без
 * изменения checkpoint. Ошибки журнала и S3 пробрасываются.
 */
public final class Poller {

  private static final String RUN_PREFIX = "run:";
  private static final String DONE_PREFIX = "done:";

  private final AdapterConfig config;
  private final SourceConnector connector;
  private final EventJournal journal;
  private final InboxWriter inbox;
  private final Clock clock;
  private final Sleeper sleeper;
  private final Random random;
  private final Supplier<String> runIds;

  public Poller(
      AdapterConfig config,
      SourceConnector connector,
      EventJournal journal,
      RawPayloadStore rawStore,
      Clock clock,
      Sleeper sleeper,
      Random random,
      Supplier<String> runIds) {
    this.config = config;
    this.connector = connector;
    this.journal = journal;
    this.inbox = new InboxWriter(config.system(), journal, rawStore);
    this.clock = clock;
    this.sleeper = sleeper;
    this.random = random;
    this.runIds = runIds;
  }

  /** Poller с системными часами, реальными паузами и случайными идентификаторами прогонов. */
  public static Poller create(
      AdapterConfig config, SourceConnector connector, EventJournal journal, RawPayloadStore rawStore) {
    return new Poller(config, connector, journal, rawStore, Clock.systemUTC(), Sleeper.THREAD,
        new Random(), () -> UUID.randomUUID().toString());
  }

  /** Имя consumer, под которым хранится курсор polling. */
  public String pollerConsumer() {
    return config.system().code() + "-poller";
  }

  /** Имя consumer, под которым хранится состояние snapshot. */
  public String snapshotConsumer() {
    return config.system().code() + "-snapshot";
  }

  /** Один цикл: snapshot, если курсора ещё нет, иначе дочитывание изменений после курсора. */
  public PollResult pollOnce() {
    String source = config.sourceUrn();
    Optional<Checkpoint> cp = journal.loadCheckpoint(pollerConsumer(), source);
    try {
      return cp.isEmpty() ? snapshot(source) : incremental(source, cp.get().cursor());
    } catch (GaveUp e) {
      return new PollResult(PollResult.Outcome.GAVE_UP, e.appended, e.syncRunId);
    }
  }

  private PollResult incremental(String source, String start) {
    String cursor = start;
    long appended = 0;
    boolean advanced = false;
    while (true) {
      ChangePage page = fetch(cursor, appended, null);
      for (SourceChange c : page.changes()) {
        inbox.record(EventMapper.pollEventId(c), c, null, null);
        appended++;
      }
      if (page.hasMore() && (page.nextCursor() == null || page.nextCursor().equals(cursor))) {
        // Источник недоверенный: страница «есть ещё» без нового курсора зациклила бы чтение.
        throw noProgress();
      }
      if (page.nextCursor() != null && !page.nextCursor().equals(cursor)) {
        // Курсор сдвигается только после append всей страницы.
        journal.saveCheckpoint(pollerConsumer(), source, page.nextCursor());
        cursor = page.nextCursor();
        advanced = true;
      }
      if (!page.hasMore()) {
        return new PollResult(
            advanced ? PollResult.Outcome.ADVANCED : PollResult.Outcome.UP_TO_DATE, appended, null);
      }
    }
  }

  private PollResult snapshot(String source) {
    String runId = snapshotRunId(source);
    String cursor = null;
    long appended = 0;
    while (true) {
      ChangePage page = fetch(cursor, appended, runId);
      for (SourceChange c : page.changes()) {
        inbox.record(EventMapper.snapshotEventId(runId, c), c, runId, runId);
        appended++;
      }
      if (page.hasMore()) {
        if (page.nextCursor() == null || page.nextCursor().equals(cursor)) {
          throw noProgress();
        }
        cursor = page.nextCursor();
        continue;
      }
      if (!page.snapshotComplete() || page.nextCursor() == null) {
        // Без подтверждения полноты маркер ставить нельзя: reconciliation удалил бы живые объекты.
        throw new IllegalStateException(config.system().code() + " ended snapshot without snapshot-complete");
      }
      // Порядок: маркер, затем отметка прогона завершённым, затем переход на инкрементальный курсор.
      inbox.record(EventMapper.snapshotComplete(config.system(), runId, clock.instant(), appended), runId);
      journal.saveCheckpoint(snapshotConsumer(), source, DONE_PREFIX + runId);
      journal.saveCheckpoint(pollerConsumer(), source, page.nextCursor());
      return new PollResult(PollResult.Outcome.SNAPSHOT_COMPLETED, appended, runId);
    }
  }

  private IllegalStateException noProgress() {
    return new IllegalStateException(config.system().code() + " reported hasMore without advancing the cursor");
  }

  private String snapshotRunId(String source) {
    Optional<Checkpoint> state = journal.loadCheckpoint(snapshotConsumer(), source);
    if (state.isPresent() && state.get().cursor().startsWith(RUN_PREFIX)) {
      return state.get().cursor().substring(RUN_PREFIX.length());
    }
    String runId = runIds.get();
    journal.saveCheckpoint(snapshotConsumer(), source, RUN_PREFIX + runId);
    return runId;
  }

  /** Читает страницу с повторами при недоступности источника. */
  private ChangePage fetch(String cursor, long appended, String syncRunId) {
    SourceUnavailableException last = null;
    for (int attempt = 1; attempt <= config.retry().maxAttempts(); attempt++) {
      try {
        return connector.fetchChanges(cursor, config.pageSize());
      } catch (SourceUnavailableException e) {
        last = e;
        if (attempt < config.retry().maxAttempts()) {
          pause(config.retry().delay(attempt, e, random));
        }
      }
    }
    throw new GaveUp(last, appended, syncRunId);
  }

  private void pause(Duration d) {
    try {
      sleeper.sleep(d);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("poller interrupted");
    }
  }

  private static final class GaveUp extends RuntimeException {
    private final long appended;
    private final String syncRunId;

    GaveUp(SourceUnavailableException cause, long appended, String syncRunId) {
      super("source unavailable after retries, status=" + cause.statusCode(), cause);
      this.appended = appended;
      this.syncRunId = syncRunId;
    }
  }
}
