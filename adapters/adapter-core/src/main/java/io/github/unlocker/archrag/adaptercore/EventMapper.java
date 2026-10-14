package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.SourceVersion;
import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Преобразует {@link SourceChange} в {@link CanonicalEvent}. Маппинга в {@code GraphCommand} здесь
 * нет: source DTO не покидает адаптер, дальше идёт только канонический конверт.
 *
 * <p>Контракт, на который опираются normalizer и reconciliation (описан в {@code package-info}):
 * <ul>
 *   <li>{@code DELETE} → тип {@link CanonicalEvent#TYPE_ASSET_DELETED}, пустой payload; {@code UPSERT} → {@link
 *       CanonicalEvent#TYPE_ASSET_UPSERTED};
 *   <li>значения {@code null} из payload отбрасываются («поле не передано», а не «стёрто»);
 *   <li>полнота записи лежит в зарезервированном ключе {@link #COMPLETENESS_KEY}.
 * </ul>
 */
public final class EventMapper {

  /** Зарезервированный ключ payload: {@code COMPLETE} или {@code PARTIAL}. */
  public static final String COMPLETENESS_KEY = "_completeness";

  /** Полнота прогона в payload маркера и его raw; нет поля (формат до UNLOCKER-279) — {@code COMPLETE}. */
  public static final String COMPLETENESS_FIELD = "completeness";

  private EventMapper() {}

  /** {@code urn:corp:<code>} — значение {@code source} события и ключа checkpoint. */
  public static String sourceUrn(SourceSystem system) {
    return system.systemCode().urn();
  }

  /** Событие по состоянию объекта. */
  public static CanonicalEvent toEvent(
      SourceSystem system, String eventId, SourceChange change, String correlationId) {
    boolean delete = change.operation() == ChangeOperation.DELETE;
    Map<String, Object> payload = new LinkedHashMap<>();
    if (!delete) {
      change.payload().forEach((k, v) -> {
        if (v != null) {
          payload.put(k, v);
        }
      });
      payload.put(COMPLETENESS_KEY, change.completeness().name());
    }
    return new CanonicalEvent(
        eventId,
        sourceUrn(system),
        delete ? CanonicalEvent.TYPE_ASSET_DELETED : CanonicalEvent.TYPE_ASSET_UPSERTED,
        subject(change.sourceType(), change.sourceId()),
        change.updatedAt(),
        delete ? CanonicalEvent.SCHEMA_DELETED : CanonicalEvent.SCHEMA_UPSERTED,
        correlationId,
        new AssetEventData(
            change.sourceType(),
            change.sourceId(),
            new SourceVersion(Long.toString(change.sourceVersion())),
            payload));
  }

  /** Маркер {@code snapshot-complete} прогона с полнотой {@code COMPLETE}. */
  public static CanonicalEvent snapshotComplete(
      SourceSystem system, String syncRunId, Instant time, long objectCount) {
    return snapshotComplete(system, syncRunId, time, objectCount, false);
  }

  /**
   * Маркер {@code snapshot-complete}: последнее событие прогона {@code syncRunId}. {@code partial} — среди записей
   * прогона была {@code PARTIAL}; такой прогон reconciliation не использует для удалений.
   */
  public static CanonicalEvent snapshotComplete(
      SourceSystem system, String syncRunId, Instant time, long objectCount, boolean partial) {
    return new CanonicalEvent(
        "snapshot-complete:" + syncRunId,
        sourceUrn(system),
        CanonicalEvent.TYPE_SNAPSHOT_COMPLETE,
        "sync-run/" + syncRunId,
        time,
        CanonicalEvent.SCHEMA_SNAPSHOT_COMPLETE,
        syncRunId,
        new AssetEventData(
            CanonicalEvent.SYNC_RUN_SOURCE_TYPE,
            syncRunId,
            new SourceVersion("1"),
            Map.of(
                "syncRunId", syncRunId,
                "objectCount", objectCount,
                COMPLETENESS_FIELD, (partial ? Completeness.PARTIAL : Completeness.COMPLETE).name())));
  }

  /** Идентификатор события для изменения, прочитанного polling'ом: один и тот же при перечитывании. */
  static String pollEventId(SourceChange c) {
    return "poll:" + c.sourceType() + "/" + c.sourceId() + "/v" + c.sourceVersion();
  }

  /** Идентификатор события snapshot: привязан к прогону, чтобы повторный snapshot не слипался со старым. */
  static String snapshotEventId(String syncRunId, SourceChange c) {
    return "snap:" + syncRunId + ":" + c.sourceType() + "/" + c.sourceId() + "/v" + c.sourceVersion();
  }

  private static String subject(String sourceType, String sourceId) {
    return sourceType.toLowerCase(Locale.ROOT).replace('_', '-') + "/" + sourceId;
  }
}
