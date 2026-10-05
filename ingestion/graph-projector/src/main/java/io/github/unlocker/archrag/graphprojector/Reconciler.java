package io.github.unlocker.archrag.graphprojector;

import io.github.unlocker.archrag.canonicalmodel.command.TombstoneSourceRecord;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceKey;
import io.github.unlocker.archrag.canonicalmodel.provenance.SourceSystemCode;
import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.ObjectRef;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.SnapshotContents;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
 *   <li>повторный вызов безопасен: уже неактивные записи не возвращаются {@link GraphProjection#activeRecords};</li>
 *   <li>каждый tombstone до записи в граф фиксируется в журнале событием {@code architecture.asset.deleted.v1}
 *       ({@code eventId = reconcile:<syncRunId>:<sourceType>/<sourceId>}, raw в {@link RawPayloadStore}), чтобы
 *       replay и rebuild из журнала воспроизводили удаление; повтор даёт ту же строку, а не дубль, и доводит
 *       незавершённую до конечного статуса.</li>
 * </ul>
 */
public final class Reconciler implements ReconciliationTrigger {

  /** Префикс {@code eventId} tombstone-событий reconciliation. */
  public static final String EVENT_ID_PREFIX = "reconcile:";

  private final EventJournal journal;
  private final RawPayloadStore rawStore;
  private final GraphProjection projection;
  private final Clock clock;

  public Reconciler(EventJournal journal, RawPayloadStore rawStore, GraphProjection projection, Clock clock) {
    this.journal = Objects.requireNonNull(journal, "journal");
    this.rawStore = Objects.requireNonNull(rawStore, "rawStore");
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
      String tombstoneId = journalTombstone(source, key, record.version(), now, syncRunId);
      ProjectionResult result =
          projection.project(
              new ProjectionRequest(
                  key, record.version(), now, syncRunId, Map.of(), List.of(new TombstoneSourceRecord(key, now))));
      finishTombstone(source, tombstoneId, result.outcome());
      if (result.outcome() == ProjectionOutcome.APPLIED) {
        tombstoned++;
      } else if (result.outcome() == ProjectionOutcome.IGNORED_OLD_VERSION) {
        old++;
      }
    }
    return new Report(tombstoned, old, recent, inSnapshot);
  }

  /** Фиксирует tombstone в журнале до записи в граф; возвращает {@code eventId}. */
  private String journalTombstone(String source, SourceKey key, SourceVersion version, Instant now, String syncRunId) {
    String eventId = EVENT_ID_PREFIX + syncRunId + ":" + key.sourceType() + "/" + key.sourceId();
    if (journal.find(source, eventId).isEmpty()) {
      CanonicalEvent event =
          new CanonicalEvent(
              eventId,
              source,
              EventProcessor.TYPE_ASSET_DELETED,
              key.sourceType().toLowerCase(Locale.ROOT).replace('_', '-') + "/" + key.sourceId(),
              now,
              EventProcessor.SCHEMA_ASSET_DELETED,
              syncRunId,
              new AssetEventData(key.sourceType(), key.sourceId(), version, Map.of()));
      RawPayloadRef ref = rawStore.put(source, rawDelete(key, version, now).getBytes(StandardCharsets.UTF_8));
      journal.append(event, ref, syncRunId);
    }
    JournalEntry entry = journal.find(source, eventId).orElseThrow();
    // Повтор после сбоя: строка уже могла продвинуться; статусы идут по одному шагу вперёд.
    for (ProcessingStatus step :
        List.of(ProcessingStatus.VALIDATED, ProcessingStatus.NORMALIZED, ProcessingStatus.RESOLVED)) {
      if (entry.status() == ProcessingStatus.RETRYING || entry.status().ordinal() < step.ordinal()) {
        entry = journal.transition(source, eventId, step, null, null);
      }
    }
    return eventId;
  }

  /** Доводит строку tombstone до конечного статуса по исходу проекции. */
  private void finishTombstone(String source, String eventId, ProjectionOutcome outcome) {
    ProcessingStatus status =
        outcome == ProjectionOutcome.IGNORED_OLD_VERSION
            ? ProcessingStatus.IGNORED_OLD_VERSION
            : ProcessingStatus.PROJECTED;
    ProcessingStatus current = journal.find(source, eventId).orElseThrow().status();
    if (current != ProcessingStatus.PROJECTED && current != ProcessingStatus.IGNORED_OLD_VERSION) {
      journal.transition(source, eventId, status, null, null);
    }
  }

  /** Raw tombstone в формате, который читает replay: как {@code InboxWriter.rawJson} для {@code DELETE}. */
  private static String rawDelete(SourceKey key, SourceVersion version, Instant now) {
    return "{\"sourceType\":" + quote(key.sourceType()) + ",\"sourceId\":" + quote(key.sourceId())
        + ",\"sourceVersion\":" + version.value() + ",\"operation\":\"DELETE\",\"completeness\":\"COMPLETE\""
        + ",\"updatedAt\":\"" + now + "\",\"payload\":{}}";
  }

  private static String quote(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
