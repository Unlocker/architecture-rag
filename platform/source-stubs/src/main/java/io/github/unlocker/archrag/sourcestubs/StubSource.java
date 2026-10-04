package io.github.unlocker.archrag.sourcestubs;

import io.github.unlocker.archrag.sourcespi.ChangeOperation;
import io.github.unlocker.archrag.sourcespi.ChangePage;
import io.github.unlocker.archrag.sourcespi.Completeness;
import io.github.unlocker.archrag.sourcespi.SourceChange;
import io.github.unlocker.archrag.sourcespi.SourceConnector;
import io.github.unlocker.archrag.sourcespi.SourceSystem;
import io.github.unlocker.archrag.sourcespi.SourceUnavailableException;
import io.github.unlocker.archrag.sourcespi.WebhookEvent;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Управляемая заглушка мастер-системы в памяти.
 *
 * <p>Тест меняет состояние через {@link #upsert}, {@link #upsertPartial}, {@link #delete}, гасит
 * webhook ({@link #suppressWebhooks}) и включает сбои ({@link #failNext}). Версия объекта
 * монотонна. Курсор — {@code s<seq>:<bound>} внутри snapshot и {@code i<seq>} для инкрементального
 * polling; потребитель считает его непрозрачным. Класс потокобезопасен.
 */
public final class StubSource implements SourceConnector {

  private record Entry(long seq, SourceChange change, boolean notified) {}

  private final SourceSystem system;
  private final Clock clock;
  private final List<Entry> journal = new ArrayList<>();
  private final Map<String, Long> versions = new LinkedHashMap<>();
  private boolean webhooksSuppressed;
  private boolean reorderNextPage;
  private int failuresLeft;
  private int failureStatus;
  private Duration failureRetryAfter;

  public StubSource(SourceSystem system, Clock clock) {
    this.system = system;
    this.clock = clock;
  }

  @Override
  public SourceSystem system() {
    return system;
  }

  /** Создаёт или обновляет объект полной записью; возвращает новое состояние. */
  public synchronized SourceChange upsert(String type, String id, Map<String, Object> payload) {
    return append(type, id, ChangeOperation.UPSERT, Completeness.COMPLETE, payload);
  }

  /**
   * Обновляет объект неполной записью: в payload только переданные поля, часть может быть
   * {@code null}. Потребитель не должен затирать ими известные значения.
   */
  public synchronized SourceChange upsertPartial(
      String type, String id, Map<String, Object> payload) {
    return append(type, id, ChangeOperation.UPSERT, Completeness.PARTIAL, payload);
  }

  /** Удаляет объект: в журнале появляется изменение {@code DELETE} с новой версией. */
  public synchronized SourceChange delete(String type, String id) {
    return append(type, id, ChangeOperation.DELETE, Completeness.COMPLETE, Map.of());
  }

  /** Пока включено, новые изменения не порождают webhook (имитация потерянного события). */
  public synchronized void suppressWebhooks(boolean suppressed) {
    this.webhooksSuppressed = suppressed;
  }

  /**
   * Следующая инкрементальная страница {@link #fetchChanges} придёт в обратном порядке и с
   * дубликатом первого изменения (повторная доставка и перестановка версий при polling). Курсор
   * страницы не меняется. Действует один раз.
   */
  public synchronized void reorderNextPage() {
    this.reorderNextPage = true;
  }

  /**
   * Следующие {@code times} вызовов чтения завершатся {@link SourceUnavailableException}.
   *
   * @param status 429, 5xx или 0 для timeout
   * @param retryAfter значение {@code Retry-After}, либо {@code null}
   */
  public synchronized void failNext(int times, int status, Duration retryAfter) {
    this.failuresLeft = times;
    this.failureStatus = status;
    this.failureRetryAfter = retryAfter;
  }

  /**
   * Webhook-уведомления в порядке возникновения изменений, без подавленных. Повторную доставку и
   * out-of-order тест строит над этим списком (дублирует элемент, меняет порядок); старая версия
   * в уведомлении при актуальном {@code fetchById} и есть out-of-order.
   */
  public synchronized List<WebhookEvent> webhookEvents() {
    return journal.stream().filter(Entry::notified).map(this::toEvent).toList();
  }

  @Override
  public synchronized ChangePage fetchChanges(String cursor, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    failIfRequested();
    if (cursor != null && cursor.startsWith("i")) {
      long after = Long.parseLong(cursor.substring(1));
      List<SourceChange> page = new ArrayList<>();
      long last = after;
      for (Entry e : journal) {
        if (e.seq() > after && page.size() < limit) {
          page.add(e.change());
          last = e.seq();
        }
      }
      if (reorderNextPage && !page.isEmpty()) {
        reorderNextPage = false;
        SourceChange first = page.getFirst();
        Collections.reverse(page);
        page.add(first);
      }
      // seq == индекс в журнале + 1, поэтому есть ещё записи, если журнал длиннее last.
      return new ChangePage(page, "i" + last, journal.size() > last, false);
    }
    long bound;
    long after;
    if (cursor == null) {
      bound = journal.size();
      after = 0;
    } else if (cursor.startsWith("s")) {
      String[] parts = cursor.substring(1).split(":");
      after = Long.parseLong(parts[0]);
      bound = Long.parseLong(parts[1]);
    } else {
      throw new IllegalArgumentException("unknown cursor");
    }
    // Snapshot: актуальное состояние каждого живого объекта на момент bound, в порядке seq.
    TreeMap<Long, SourceChange> latest = new TreeMap<>();
    Map<String, Entry> byKey = new LinkedHashMap<>();
    for (Entry e : journal) {
      if (e.seq() <= bound) {
        byKey.put(key(e.change().sourceType(), e.change().sourceId()), e);
      }
    }
    byKey.values().stream()
        .filter(e -> e.change().operation() == ChangeOperation.UPSERT)
        .forEach(e -> latest.put(e.seq(), e.change()));
    List<SourceChange> page = new ArrayList<>();
    long last = after;
    for (Map.Entry<Long, SourceChange> e : latest.tailMap(after, false).entrySet()) {
      if (page.size() == limit) {
        break;
      }
      page.add(e.getValue());
      last = e.getKey();
    }
    long lastReturned = last;
    boolean more = latest.keySet().stream().anyMatch(seq -> seq > lastReturned);
    String next = more ? "s" + last + ":" + bound : "i" + bound;
    return new ChangePage(page, next, more, !more);
  }

  @Override
  public synchronized Optional<SourceChange> fetchById(String sourceType, String sourceId) {
    failIfRequested();
    Entry found = null;
    for (Entry e : journal) {
      if (e.change().sourceType().equals(sourceType) && e.change().sourceId().equals(sourceId)) {
        found = e;
      }
    }
    return Optional.ofNullable(found).map(Entry::change);
  }

  private SourceChange append(
      String type, String id, ChangeOperation op, Completeness completeness, Map<String,
          Object> p) {
    long version = versions.merge(key(type, id), 1L, Long::sum);
    SourceChange change =
        new SourceChange(type, id, version, op, completeness, clock.instant(), p);
    journal.add(new Entry(journal.size() + 1L, change, !webhooksSuppressed));
    return change;
  }

  private WebhookEvent toEvent(Entry e) {
    SourceChange c = e.change();
    return new WebhookEvent(
        system.code() + "-evt-" + e.seq(),
        system.code(),
        c.sourceType(),
        c.sourceId(),
        c.sourceVersion(),
        c.operation(),
        c.updatedAt());
  }

  private void failIfRequested() {
    if (failuresLeft > 0) {
      failuresLeft--;
      throw new SourceUnavailableException(system, failureStatus, failureRetryAfter);
    }
  }

  private static String key(String type, String id) {
    return type + "/" + id;
  }
}
