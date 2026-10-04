package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.ObjectRef;
import io.github.unlocker.archrag.eventschemas.SnapshotContents;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciliation после полного snapshot: активные записи графа, которых источник в прогоне не отдал
 * (missing set), получают tombstone. Запускается только маркером {@code snapshot-complete}
 * ({@link ReconciliationTrigger}); без маркера удалений нет, поэтому падение на середине snapshot ничего
 * не удаляет.
 *
 * <p>Инварианты:
 * <ul>
 *   <li>множество объектов прогона берётся из журнала, а не из графа: событие объекта могло закончиться
 *       {@code DUPLICATE}, {@code QUARANTINED} или {@code RETRYING}, но объект в источнике есть и удалять его нельзя;</li>
 *   <li>если в журнале меньше событий прогона, чем заявил маркер ({@code objectCount}), прогон считается
 *       неполным: {@link IllegalStateException}, ничего не удаляется (маркер уйдёт в {@code RETRYING});</li>
 *   <li>объект, по которому после начала прогона пришло любое событие (например, webhook о создании), в
 *       missing set не попадает: snapshot мог прочитать источник раньше его появления; его судьбу решит
 *       следующий прогон;</li>
 *   <li>tombstone идёт с уже применённой версией записи: если объект успели обновить, проекция вернёт
 *       {@code IGNORED_OLD_VERSION}, а не удалит новое состояние;</li>
 *   <li>повторный вызов безопасен: уже неактивные записи не возвращаются {@link GraphProjection#activeRecords}.</li>
 * </ul>
 */
public final class Reconciler implements ReconciliationTrigger {

  private final EventJournal journal;
  private final GraphProjection projection;
  private final Clock clock;

  public Reconciler(EventJournal journal, GraphProjection projection, Clock clock) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Итог обработки missing set. */
  public record Report(int tombstoned, int ignoredOldVersion, int keptRecentlyTouched, int keptInSnapshot) {}

  @Override
  public void snapshotComplete(String source, String syncRunId, String eventId, long objectCount) {
    reconcile(source, syncRunId, eventId, objectCount);
  }

  /**
   * Применяет missing set прогона {@code syncRunId}.
   *
   * @param eventId id маркера; нужен, если в прогоне нет событий (пустой источник)
   * @throws IllegalStateException если прогон неполон по журналу
   * @throws IllegalArgumentException если источник неизвестен
   */
  public Report reconcile(String source, String syncRunId, String eventId, long objectCount) {
    SourceSystemCode code = EventProcessor.sourceCode(source);
    if (code == null) {
      throw new IllegalArgumentException("event source is not known");
    }
    SnapshotContents run = journal.snapshotContents(source, syncRunId);
    if (run.eventCount() < objectCount) {
      throw new IllegalStateException("snapshot run is incomplete in the journal");
    }
    Instant since =
        run.firstReceivedAt() != null
            ? run.firstReceivedAt()
            : journal.find(source, eventId).orElseThrow().receivedAt();
    Set<ObjectRef> touched = journal.objectsReceivedSince(source, since);
    Instant now = clock.instant();
    int tombstoned = 0;
    int old = 0;
    int recent = 0;
    int inSnapshot = 0;
    for (GraphProjection.ActiveRecord record : projection.activeRecords(code)) {
      SourceKey key = record.key();
      ObjectRef ref = new ObjectRef(key.sourceType(), key.sourceId());
      if (run.objects().contains(ref)) {
        inSnapshot++;
        continue;
      }
      if (touched.contains(ref)) {
        recent++;
        continue;
      }
      ProjectionResult result =
          projection.project(
              new ProjectionRequest(
                  key, record.version(), now, syncRunId, Map.of(), List.of(new TombstoneSourceRecord(key, now))));
      if (result.outcome() == ProjectionOutcome.APPLIED) {
        tombstoned++;
      } else if (result.outcome() == ProjectionOutcome.IGNORED_OLD_VERSION) {
        old++;
      }
    }
    return new Report(tombstoned, old, recent, inSnapshot);
  }
}
