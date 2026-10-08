package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.JournalKey;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.ProcessingStatus;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Передаёт ожидающие события журнала в {@link EventProcessor} в порядке журнала. Один поток, один экземпляр сервиса.
 *
 * <p>Выборка — {@link JournalReader#pending}: {@code RECEIVED}, а также {@code RETRYING} и промежуточные статусы,
 * не менявшиеся дольше {@code retryDelay}. Порядок — {@code (receivedAt, source, eventId)}, страницы читаются
 * keyset-ом до пустой страницы.
 *
 * <p>Инварианты:
 * <ul>
 *   <li>каждая страница обрабатывается под {@link AdminLock}; если замок занят, проход заканчивается, так что replay
 *       и rebuild не конкурируют с живой проекцией и не теряют свои строки {@code replay:*};</li>
 *   <li>дубль и устаревшую версию решает {@link EventProcessor}, а не диспетчер;</li>
 *   <li>если событие объекта {@code (source, sourceType, sourceId)} ушло в {@code RETRYING} или бросило исключение,
 *       более поздние события этого объекта в том же проходе пропускаются;</li>
 *   <li>ошибка одного события не останавливает проход; в логе только класс исключения, не текст.</li>
 * </ul>
 *
 * <p>Отступление от «DLQ ведёт {@code EventProcessor}»: до {@code process} не доходят и иначе висели бы в
 * {@code RECEIVED} вечно строки с битым raw ({@value #INVALID_RAW_PAYLOAD}), без raw ({@value #NO_RAW_PAYLOAD}) и
 * маркер {@code snapshot-complete} без raw ({@value #MARKER_NO_RAW}: восстановление через
 * {@code POST /admin/reconcile/{source}}). Они уходят в DLQ отсюда. Ошибки raw storage и журнала в DLQ не ведут.
 */
public class JournalDispatcher {

  static final String INVALID_RAW_PAYLOAD = "INVALID_RAW_PAYLOAD";
  static final String NO_RAW_PAYLOAD = "NO_RAW_PAYLOAD";
  static final String MARKER_NO_RAW = "MARKER_NO_RAW";

  private static final Logger LOG = LoggerFactory.getLogger(JournalDispatcher.class);

  private final JournalReader reader;
  private final EventJournal journal;
  private final RawPayloadStore rawStore;
  private final StoredEventReader events;
  private final EventProcessor processor;
  private final AdminLock lock;
  private final Clock clock;
  private final int batchSize;
  private final Duration retryDelay;
  private final IngestionMetrics metrics;
  private volatile boolean stopped;

  public JournalDispatcher(
      JournalReader reader,
      EventJournal journal,
      RawPayloadStore rawStore,
      StoredEventReader events,
      EventProcessor processor,
      AdminLock lock,
      Clock clock,
      int batchSize,
      Duration retryDelay,
      IngestionMetrics metrics) {
    this.reader = reader;
    this.journal = journal;
    this.rawStore = rawStore;
    this.events = events;
    this.processor = processor;
    this.lock = lock;
    this.clock = clock;
    this.batchSize = batchSize;
    this.retryDelay = retryDelay;
    this.metrics = metrics;
  }

  /** Просит текущий проход закончиться после обрабатываемого события. */
  void stop() {
    stopped = true;
  }

  /** Один проход по ожидающим событиям; возвращает число событий, взятых в обработку. */
  public int runOnce() {
    int handled = 0;
    Set<String> blocked = new HashSet<>();
    Instant retryNotAfter = clock.instant().minus(retryDelay);
    JournalKey after = null;
    while (!stopped) {
      Optional<AdminLock.Lease> lease = lock.tryAcquire();
      if (lease.isEmpty()) {
        LOG.debug("admin operation in progress, dispatcher pass skipped");
        return handled;
      }
      List<StoredEvent> page;
      try (AdminLock.Lease ignored = lease.get()) {
        page = reader.pending(after, retryNotAfter, batchSize);
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
      }
      if (page.isEmpty()) {
        return handled;
      }
      after = JournalKey.of(page.get(page.size() - 1).entry());
    }
    return handled;
  }

  /** Итоговый статус события; {@code null}, если обработка не удалась неожиданным исключением. */
  private ProcessingStatus dispatch(StoredEvent stored) {
    ProcessingStatus status = process(stored);
    metrics.recordEvent(stored.entry().source(), status);
    return status;
  }

  private ProcessingStatus process(StoredEvent stored) {
    JournalEntry entry = stored.entry();
    try {
      RawPayloadRef ref = entry.payloadRef();
      if (ref == null) {
        boolean marker = StoredEventReader.TYPE_SNAPSHOT_COMPLETE.equals(stored.type());
        return quarantine(entry, marker ? MARKER_NO_RAW : NO_RAW_PAYLOAD, "event has no raw payload");
      }
      // Ошибки хранилища (нет объекта, расхождение hash) — не вина события: наружу, повтор в следующем проходе.
      byte[] raw = rawStore.get(ref);
      CanonicalEvent event;
      try {
        event = events.toEvent(stored, raw);
      } catch (IllegalArgumentException e) {
        return quarantine(entry, INVALID_RAW_PAYLOAD, "raw payload is not a valid event");
      }
      return processor.process(event, ref).status();
    } catch (RuntimeException e) {
      // Событие остаётся в прежнем статусе и будет взято позже. Текст исключения не логируется: он может нести
      // данные источника.
      LOG.error("dispatch failed: source={} eventId={} cause={}", entry.source(), entry.eventId(),
          e.getClass().getName());
      return null;
    }
  }

  private ProcessingStatus quarantine(JournalEntry entry, String code, String reason) {
    return journal.toDlq(entry.source(), entry.eventId(), code, reason).status();
  }
}
