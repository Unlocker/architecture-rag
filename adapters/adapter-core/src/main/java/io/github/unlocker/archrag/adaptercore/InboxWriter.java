package io.github.unlocker.archrag.adaptercore;

import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Фиксация события в inbox: сначала raw payload в объектное хранилище, затем {@link
 * EventJournal#append}. Порядок важен: строка в inbox не должна ссылаться на несуществующий
 * объект. Повтор безопасен: raw адресуется содержимым, {@code append} дедуплицирует.
 *
 * <p>Ошибки хранилищ не глушатся: вызывающий не должен считать событие принятым.
 */
final class InboxWriter {

  private final SourceSystem system;
  private final EventJournal journal;
  private final RawPayloadStore rawStore;

  InboxWriter(SourceSystem system, EventJournal journal, RawPayloadStore rawStore) {
    this.system = system;
    this.journal = journal;
    this.rawStore = rawStore;
  }

  /** Записывает событие по изменению вместе с raw payload; возвращает запись журнала (статус {@code DUPLICATE} для повтора). */
  JournalEntry record(
      String eventId, SourceChange change, String correlationId, String syncRunId) {
    CanonicalEvent event = EventMapper.toEvent(system, eventId, change, correlationId);
    RawPayloadRef ref = rawStore.put(system.code(), rawJson(change).getBytes(StandardCharsets.UTF_8));
    return journal.append(event, ref, syncRunId);
  }

  /**
   * Записывает маркер snapshot вместе с raw {@code {syncRunId, objectCount, updatedAt}}: {@code objectCount} нужен
   * reconciliation, чтобы не удалять объекты при неполном журнале.
   */
  JournalEntry record(CanonicalEvent marker, String syncRunId) {
    RawPayloadRef ref = rawStore.put(system.code(), markerRawJson(marker).getBytes(StandardCharsets.UTF_8));
    return journal.append(marker, ref, syncRunId);
  }

  /** Raw маркера snapshot; формат читает {@code StoredEventReader}. */
  static String markerRawJson(CanonicalEvent marker) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("syncRunId", marker.data().payload().get("syncRunId"));
    m.put("objectCount", marker.data().payload().get("objectCount"));
    m.put("updatedAt", marker.time());
    return Json.write(m);
  }

  /** Состояние объекта в формате источника; недоверенное, хранится только в S3. */
  static String rawJson(SourceChange c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("sourceType", c.sourceType());
    m.put("sourceId", c.sourceId());
    m.put("sourceVersion", c.sourceVersion());
    m.put("operation", c.operation());
    m.put("completeness", c.completeness());
    m.put("updatedAt", c.updatedAt());
    m.put("payload", c.payload());
    return Json.write(m);
  }
}
