package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.canonicalmodel.provenance.SyncRunStatus;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import java.time.Clock;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Закрывает {@code SyncRun}: счётчики считает по журналу ({@link EventJournal#statusCounts}), итог пишет
 * {@link SyncRunWriter}. Вызывается вне транзакций Neo4j и PostgreSQL.
 */
public final class SyncRunCloser implements SyncRunClosing {

  private final EventJournal journal;
  private final SyncRunWriter writer;
  private final Clock clock;

  public SyncRunCloser(EventJournal journal, SyncRunWriter writer, Clock clock) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.writer = Objects.requireNonNull(writer, "writer");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void close(String source, String runId, String markerEventId, SyncRunStatus status, OptionalLong fetched) {
    SourceSystemCode adapter = EventProcessor.sourceCode(source);
    if (adapter == null) {
      // Неизвестный источник узлов SyncRun не порождает (проектор его отвергает), закрывать нечего.
      return;
    }
    SyncRunCounts counts = SyncRunCounts.of(fetched.orElse(0), journal.statusCounts(source, runId, markerEventId));
    writer.close(adapter, runId, status, clock.instant(), counts);
  }
}
