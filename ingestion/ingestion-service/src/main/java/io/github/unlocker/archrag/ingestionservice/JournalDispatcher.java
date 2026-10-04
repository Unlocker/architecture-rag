package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.eventschemas.AssetEventData;
import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.JournalKey;
import io.github.unlocker.archrag.eventschemas.JournalQuery;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.ProcessingResult;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Передаёт ожидающие события журнала в {@link EventProcessor} в порядке журнала. Один поток, один экземпляр сервиса.
 *
 * <p>Выбираются строки в статусах {@code RECEIVED} и {@code RETRYING}, а также промежуточных
 * ({@code VALIDATED}, {@code NORMALIZED}, {@code RESOLVED}): их оставляет прерванная обработка, и без повторного
 * захода они зависли бы навсегда. Строки replay ({@code eventId} с префиксом {@code replay:}) пропускаются, их
 * ведёт {@link ReplayService}. Порядок — {@code (receivedAt, source, eventId)}, страницы читаются keyset-ом.
 *
 * <p>Инварианты: дубль и устаревшую версию решает {@link EventProcessor}, а не диспетчер; пока ранняя версия объекта
 * остаётся в {@code RETRYING}, более поздние события этого объекта в том же проходе не берутся, чтобы порядок внутри
 * {@code (source, sourceType, sourceId)} не нарушался; ошибка одного события не останавливает проход; битый или
 * отсутствующий raw переводит событие в {@code QUARANTINED} с кодом и причиной без содержимого источника.
 */
public class JournalDispatcher {

  static final String RAW_MISSING = "RAW_MISSING";
  static final String RAW_INVALID = "RAW_INVALID";
  private static final String TYPE_SNAPSHOT_COMPLETE = "architecture.sync.snapshot-complete.v1";
  private static final Set<ProcessingStatus> PENDING = Set.of(
      ProcessingStatus.RECEIVED,
      ProcessingStatus.RETRYING,
      ProcessingStatus.VALIDATED,
      ProcessingStatus.NORMALIZED,
      ProcessingStatus.RESOLVED);

  private static final Logger LOG = LoggerFactory.getLogger(JournalDispatcher.class);

  private final JournalReader reader;
  private final EventJournal journal;
  private final RawPayloadStore rawStore;
  private final StoredEventReader events;
  private final EventProcessor processor;
  private final int batchSize;
  private volatile boolean stopped;

  public JournalDispatcher(
      JournalReader reader,
      EventJournal journal,
      RawPayloadStore rawStore,
      StoredEventReader events,
      EventProcessor processor,
      int batchSize) {
    this.reader = reader;
    this.journal = journal;
    this.rawStore = rawStore;
    this.events = events;
    this.processor = processor;
    this.batchSize = batchSize;
  }

  /** Просит текущий проход закончиться после обрабатываемого события. */
  void stop() {
    stopped = true;
  }

  /** Один проход по ожидающим событиям; возвращает число переданных в {@link EventProcessor}. */
  public int runOnce() {
    int handled = 0;
    Set<String> blocked = new HashSet<>();
    JournalQuery query = new JournalQuery(null, null, null, true, null, batchSize).withStatuses(PENDING);
    for (List<StoredEvent> page = reader.read(query); !page.isEmpty(); page = reader.read(query)) {
      for (StoredEvent stored : page) {
        if (stopped) {
          return handled;
        }
        JournalEntry entry = stored.entry();
        String object = entry.source() + "\0" + entry.sourceType() + "\0" + entry.sourceId();
        if (blocked.contains(object)) {
          continue;
        }
        ProcessingStatus status = dispatch(stored);
        handled++;
        if (status == null || status == ProcessingStatus.RETRYING) {
          blocked.add(object);
        }
      }
      query = query.after(JournalKey.of(page.get(page.size() - 1).entry()));
    }
    return handled;
  }

  /** Итоговый статус события; {@code null}, если обработка не удалась неожиданным исключением. */
  private ProcessingStatus dispatch(StoredEvent stored) {
    JournalEntry entry = stored.entry();
    try {
      RawPayloadRef ref = entry.payloadRef();
      CanonicalEvent event;
      if (ref == null) {
        if (!TYPE_SNAPSHOT_COMPLETE.equals(stored.type())) {
          return quarantine(entry, RAW_MISSING, "event has no raw payload");
        }
        // У маркера snapshot-complete raw нет: всё нужное лежит в строке журнала.
        event = marker(stored);
      } else {
        try {
          event = events.toEvent(stored, rawStore.get(ref));
        } catch (IllegalArgumentException e) {
          return quarantine(entry, RAW_INVALID, "raw payload is not a valid event");
        }
      }
      ProcessingResult result = processor.process(event, ref);
      return result.status();
    } catch (RuntimeException e) {
      // Событие остаётся в прежнем статусе и будет взято в следующем проходе. Текст исключения не логируется:
      // он может нести данные источника.
      LOG.error("dispatch failed: source={} eventId={} cause={}", entry.source(), entry.eventId(),
          e.getClass().getName());
      return null;
    }
  }

  private ProcessingStatus quarantine(JournalEntry entry, String code, String reason) {
    return journal.toDlq(entry.source(), entry.eventId(), code, reason).status();
  }

  private static CanonicalEvent marker(StoredEvent stored) {
    JournalEntry e = stored.entry();
    return new CanonicalEvent(
        e.eventId(),
        e.source(),
        stored.type(),
        stored.subject(),
        e.receivedAt(),
        e.schemaVersion(),
        e.correlationId(),
        new AssetEventData(e.sourceType(), e.sourceId(), e.sourceVersion(), Map.of()));
  }
}
