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
import io.github.unlocker.archrag.identityresolution.IdentityCandidates;
import io.github.unlocker.archrag.identityresolution.IdentityMapping;
import io.github.unlocker.archrag.identityresolution.SourceConflicts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
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
 *       неполным: {@link IllegalStateException}, ничего не удаляется (маркер уйдёт в {@code RETRYING});
 *       отрицательный {@code objectCount} невалиден ({@link SnapshotRejectedException});</li>
 *   <li>прогон с полнотой {@code PARTIAL} удалений не делает: missing set не считается, пропуск пишется в лог и
 *       в {@link SkipListener} (метрика);</li>
 *   <li>если missing set (после исключения объектов прогона и недавно затронутых) больше {@code maxMissingShare}
 *       активных записей источника, reconcile не выполняется: {@link SnapshotRejectedException}, маркер уходит в
 *       {@code QUARANTINED}. Проверка идёт до первой записи, поэтому отказ ничего не закрывает частично;</li>
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
 *   <li>утверждения свойств tombstone'нутой записи снимаются в {@link SourceConflicts} до записи в граф, как в
 *       {@code EventProcessor} (replay того же tombstone из журнала даёт то же состояние PostgreSQL), а маркеры
 *       конфликтов {@code gid} идут в том же {@link ProjectionRequest}; сбой хранилища не глушится: граф не тронут,
 *       запись остаётся в {@code activeRecords}, следующий {@code snapshot-complete} повторит tombstone
 *       ({@code retract} идемпотентен). Если после выбора записи применилась версия новее, {@code retract} не
 *       вызывается. Остаточная гонка (событие применилось между проверкой и {@code retract}) принята: следующее
 *       upsert-событие записи целиком восстановит утверждения.</li>
 *   <li>как и {@code EventProcessor.delete}, tombstone убирает признаки кандидатов ({@link IdentityCandidates#forget})
 *       до записи в граф (идемпотентно; для записи, у которой применена версия новее, не вызывается), иначе новая
 *       запись с тем же hostname получила бы кандидата на удалённую;</li>
 *   <li>воскрешение объекта той же версией невозможно: upsert с версией tombstone'нутой записи даёт
 *       {@code DUPLICATE} ({@link VersionDecision#forUpsert}), для возврата нужна более высокая версия.</li>
 * </ul>
 */
public final class Reconciler implements ReconciliationTrigger {

  /** Префикс {@code eventId} tombstone-событий reconciliation. */
  public static final String EVENT_ID_PREFIX = "reconcile:";

  private final EventJournal journal;
  private final RawPayloadStore rawStore;
  private final GraphProjection projection;
  private final Clock clock;
  private final IdentityMapping identity;
  private final SourceConflicts conflicts;
  private final IdentityCandidates candidates;
  private final double maxMissingShare;
  private final SkipListener skips;

  private static final System.Logger LOG = System.getLogger(Reconciler.class.getName());

  /** Доля активных записей источника, которую reconcile закрывает без отказа, по умолчанию. */
  public static final double DEFAULT_MAX_MISSING_SHARE = 0.5;

  /** Причина пропуска reconcile из-за полноты {@code PARTIAL}. */
  public static final String SKIP_PARTIAL = "PARTIAL";

  /** Получатель фактов пропуска reconcile (метрика); {@code reason} — {@link #SKIP_PARTIAL} или код отказа. */
  @FunctionalInterface
  public interface SkipListener {
    SkipListener NONE = (source, reason) -> {};

    void skipped(SourceSystemCode source, String reason);
  }

  public Reconciler(
      EventJournal journal,
      RawPayloadStore rawStore,
      GraphProjection projection,
      Clock clock,
      IdentityMapping identity,
      SourceConflicts conflicts,
      IdentityCandidates candidates) {
    this(journal, rawStore, projection, clock, identity, conflicts, candidates, DEFAULT_MAX_MISSING_SHARE, SkipListener.NONE);
  }

  /**
   * @param maxMissingShare допустимая доля missing set от активных записей источника, {@code (0, 1]}; больше —
   *     {@link SnapshotRejectedException}
   * @param skips получатель фактов пропуска и отказа reconcile
   */
  public Reconciler(
      EventJournal journal,
      RawPayloadStore rawStore,
      GraphProjection projection,
      Clock clock,
      IdentityMapping identity,
      SourceConflicts conflicts,
      IdentityCandidates candidates,
      double maxMissingShare,
      SkipListener skips) {
    if (!(maxMissingShare > 0 && maxMissingShare <= 1)) {
      throw new IllegalArgumentException("maxMissingShare must be in (0, 1]");
    }
    this.candidates = Objects.requireNonNull(candidates, "candidates");
    this.maxMissingShare = maxMissingShare;
    this.skips = Objects.requireNonNull(skips, "skips");
    this.journal = Objects.requireNonNull(journal, "journal");
    this.rawStore = Objects.requireNonNull(rawStore, "rawStore");
    this.projection = Objects.requireNonNull(projection, "projection");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.identity = Objects.requireNonNull(identity, "identity");
    this.conflicts = Objects.requireNonNull(conflicts, "conflicts");
  }

  /** Итог обработки missing set. */
  public record Report(int tombstoned, int ignoredOldVersion, int keptRecentlyTouched, int keptInSnapshot) {

    /** Прогон {@code PARTIAL}: ничего не посчитано и не удалено. */
    static final Report SKIPPED = new Report(0, 0, 0, 0);
  }

  @Override
  public void snapshotComplete(String source, String syncRunId, String eventId, long objectCount) {
    reconcile(source, syncRunId, eventId, objectCount, false);
  }

  @Override
  public void snapshotComplete(String source, String syncRunId, String eventId, long objectCount, boolean partial) {
    reconcile(source, syncRunId, eventId, objectCount, partial);
  }

  /**
   * Применяет missing set прогона {@code syncRunId}.
   *
   * @param eventId id маркера; нужен, если в прогоне нет событий (пустой источник)
   * @throws IllegalStateException если прогон неполон по журналу
   * @throws IllegalArgumentException если источник неизвестен
   */
  public Report reconcile(String source, String syncRunId, String eventId, long objectCount) {
    return reconcile(source, syncRunId, eventId, objectCount, false);
  }

  /**
   * Применяет missing set прогона; {@code partial} — прогон имеет полноту {@code PARTIAL}: возвращается пустой
   * {@link Report}, граф не читается и не меняется.
   *
   * @throws SnapshotRejectedException если {@code objectCount} отрицателен или missing set больше допустимой доли
   */
  public Report reconcile(String source, String syncRunId, String eventId, long objectCount, boolean partial) {
    SourceSystemCode code = EventProcessor.sourceCode(source);
    if (code == null) {
      throw new IllegalArgumentException("event source is not known");
    }
    if (objectCount < 0) {
      throw new SnapshotRejectedException(SnapshotRejectedException.MISSING_OBJECT_COUNT, "marker objectCount is not valid");
    }
    if (partial) {
      LOG.log(System.Logger.Level.WARNING, "reconcile skipped: source {0}, run completeness is PARTIAL", code);
      skips.skipped(code, SKIP_PARTIAL);
      return Report.SKIPPED;
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
    List<GraphProjection.ActiveRecord> active = projection.activeRecords(code);
    List<GraphProjection.ActiveRecord> missing = new ArrayList<>();
    for (GraphProjection.ActiveRecord record : active) {
      ObjectRef ref = new ObjectRef(record.key().sourceType(), record.key().sourceId());
      if (run.objects().contains(ref)) {
        inSnapshot++;
      } else if (touched.contains(ref)) {
        recent++;
      } else {
        missing.add(record);
      }
    }
    // Все проверки до первой записи: отказ не должен закрыть часть записей.
    if (missing.size() > maxMissingShare * active.size()) {
      LOG.log(System.Logger.Level.WARNING,
          "reconcile rejected: source {0}, missing {1} of {2} active records exceeds share {3}",
          code, missing.size(), active.size(), maxMissingShare);
      skips.skipped(code, SnapshotRejectedException.MASS_CLOSE_LIMIT);
      throw new SnapshotRejectedException(
          SnapshotRejectedException.MASS_CLOSE_LIMIT, "missing set exceeds the allowed share of active records");
    }
    for (GraphProjection.ActiveRecord record : missing) {
      SourceKey key = record.key();
      String tombstoneId = journalTombstone(source, key, record.version(), now, syncRunId);
      boolean stale = isStale(key, record.version());
      ConflictMarkers markers = stale ? ConflictMarkers.none() : retract(key);
      if (!stale) {
        // Как в EventProcessor.delete: до записи в граф; сбой хранилища не глушится (повтор маркера идемпотентен).
        candidates.forget(key);
      }
      ProjectionResult result =
          projection.project(
              new ProjectionRequest(
                  key, record.version(), now, syncRunId, Map.of(), List.of(new TombstoneSourceRecord(key, now)), markers));
      finishTombstone(source, tombstoneId, result.outcome());
      if (result.outcome() == ProjectionOutcome.APPLIED) {
        tombstoned++;
      } else if (result.outcome() == ProjectionOutcome.IGNORED_OLD_VERSION) {
        old++;
      }
    }
    return new Report(tombstoned, old, recent, inSnapshot);
  }

  /** {@code true}, если после выбора записи применилась версия новее: tombstone будет {@code IGNORED_OLD_VERSION}. */
  private boolean isStale(SourceKey key, SourceVersion version) {
    var applied = projection.applied(key);
    return applied.isPresent()
        && VersionDecision.forTombstone(version, applied.get().version(), applied.get().active()) == VersionDecision.OLD;
  }

  /** Снимает утверждения записи; для записи без {@code gid} или с уже применённой более новой версией маркеры не меняются. */
  private ConflictMarkers retract(SourceKey key) {
    return identity.find(key)
        .map(gid -> ConflictMarkers.of(gid, conflicts.retract(key, gid)))
        .orElseGet(ConflictMarkers::none);
  }

  /** Фиксирует tombstone в журнале до записи в граф; возвращает {@code eventId}. */
  private String journalTombstone(String source, SourceKey key, SourceVersion version, Instant now, String syncRunId) {
    String eventId = EVENT_ID_PREFIX + syncRunId + ":" + key.sourceType() + "/" + key.sourceId();
    if (journal.find(source, eventId).isEmpty()) {
      CanonicalEvent event =
          new CanonicalEvent(
              eventId,
              source,
              CanonicalEvent.TYPE_ASSET_DELETED,
              key.sourceType().toLowerCase(Locale.ROOT).replace('_', '-') + "/" + key.sourceId(),
              now,
              CanonicalEvent.SCHEMA_DELETED,
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
