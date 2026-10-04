package io.github.unlocker.archrag.ingestionservice;

import io.github.unlocker.archrag.eventschemas.CanonicalEvent;
import io.github.unlocker.archrag.eventschemas.EventJournal;
import io.github.unlocker.archrag.eventschemas.JournalEntry;
import io.github.unlocker.archrag.eventschemas.JournalKey;
import io.github.unlocker.archrag.eventschemas.JournalQuery;
import io.github.unlocker.archrag.eventschemas.JournalReader;
import io.github.unlocker.archrag.eventschemas.RawPayloadRef;
import io.github.unlocker.archrag.eventschemas.RawPayloadStore;
import io.github.unlocker.archrag.eventschemas.StoredEvent;
import io.github.unlocker.archrag.graphprojector.EventProcessor;
import io.github.unlocker.archrag.graphprojector.ProcessingResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Replay событий журнала из raw storage под новым {@code replayId}.
 *
 * <p>Каждое исходное событие из диапазона получает новую строку журнала
 * ({@code eventId = replay:<replayId>:<исходный eventId>}) с исходными {@code payloadRef}, {@code syncRunId} и
 * {@code correlationId} и проходит через {@link EventProcessor}; повтор отсекает проверка версий. Повторный запуск с
 * тем же {@code replayId} идемпотентен (дедупликация {@code append}). Строки самих replay не переигрываются, маркеры
 * {@code snapshot-complete} пропускаются: reconciliation повторно не запускается. Исходные строки журнала не меняются.
 *
 * <p>Ошибки raw storage и журнала не глушатся: replay прерывается, повтор безопасен.
 */
@Component
public class ReplayService {

  static final String SKIPPED_SNAPSHOT_MARKER = "SKIPPED_SNAPSHOT_MARKER";
  static final String SKIPPED_NO_RAW = "SKIPPED_NO_RAW";
  private static final String TYPE_SNAPSHOT_COMPLETE = "architecture.sync.snapshot-complete.v1";
  private static final int PAGE_SIZE = 200;

  private final JournalReader reader;
  private final EventJournal journal;
  private final RawPayloadStore rawStore;
  private final StoredEventReader events;
  private final EventProcessor processor;

  public ReplayService(
      JournalReader reader,
      EventJournal journal,
      RawPayloadStore rawStore,
      StoredEventReader events,
      EventProcessor processor) {
    this.reader = reader;
    this.journal = journal;
    this.rawStore = rawStore;
    this.events = events;
    this.processor = processor;
  }

  /**
   * Переигрывает события источника {@code source} (все источники, если {@code null}), принятые в
   * {@code [receivedFrom, receivedTo)}.
   */
  public ReplayResult replay(String source, Instant receivedFrom, Instant receivedTo, String replayId) {
    Objects.requireNonNull(replayId, "replayId");
    Map<String, Long> statuses = new TreeMap<>();
    long total = 0;
    JournalQuery query = new JournalQuery(source, receivedFrom, receivedTo, true, null, PAGE_SIZE);
    for (List<StoredEvent> page = reader.read(query); !page.isEmpty(); page = reader.read(query)) {
      for (StoredEvent stored : page) {
        statuses.merge(replayOne(stored, replayId), 1L, Long::sum);
        total++;
      }
      query = query.after(JournalKey.of(page.get(page.size() - 1).entry()));
    }
    return new ReplayResult(replayId, total, statuses);
  }

  private String replayOne(StoredEvent stored, String replayId) {
    JournalEntry entry = stored.entry();
    if (TYPE_SNAPSHOT_COMPLETE.equals(stored.type())) {
      return SKIPPED_SNAPSHOT_MARKER;
    }
    RawPayloadRef ref = entry.payloadRef();
    if (ref == null) {
      return SKIPPED_NO_RAW;
    }
    CanonicalEvent original = events.toEvent(stored, rawStore.get(ref));
    CanonicalEvent replayed = new CanonicalEvent(
        JournalQuery.REPLAY_PREFIX + replayId + ":" + original.id(),
        original.source(),
        original.type(),
        original.subject(),
        original.time(),
        original.dataschema(),
        original.correlationid(),
        original.data());
    journal.append(replayed, ref, entry.syncRunId());
    ProcessingResult result = processor.process(replayed, ref);
    return result.status().name();
  }
}
