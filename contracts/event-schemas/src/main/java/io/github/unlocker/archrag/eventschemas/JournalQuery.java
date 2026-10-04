package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;

/**
 * Запрос страницы журнала. Порядок результата — {@code (receivedAt, source, eventId)}.
 *
 * <p>Инварианты: {@code limit} в диапазоне {@code 1..}{@value #MAX_LIMIT}; диапазон времени полуоткрытый
 * {@code [receivedFrom, receivedTo)}; пустые границы не ограничивают.
 *
 * @param source только этот источник ({@code null} — все)
 * @param receivedFrom включительно ({@code null} — без нижней границы)
 * @param receivedTo исключительно ({@code null} — без верхней границы)
 * @param excludeReplays пропускать строки, созданные replay ({@code eventId} с префиксом {@value #REPLAY_PREFIX})
 * @param after вернуть только строки строго после этой позиции ({@code null} — с начала)
 * @param limit размер страницы
 */
public record JournalQuery(
    String source, Instant receivedFrom, Instant receivedTo, boolean excludeReplays, JournalKey after, int limit) {

  /** Префикс {@code eventId} строк, созданных replay. */
  public static final String REPLAY_PREFIX = "replay:";

  /** Верхняя граница размера страницы. */
  public static final int MAX_LIMIT = 1000;

  public JournalQuery {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be in 1.." + MAX_LIMIT);
    }
    if (receivedFrom != null && receivedTo != null && !receivedFrom.isBefore(receivedTo)) {
      throw new IllegalArgumentException("receivedFrom must be before receivedTo");
    }
  }

  /** Тот же запрос, продолженный после позиции {@code next}. */
  public JournalQuery after(JournalKey next) {
    return new JournalQuery(source, receivedFrom, receivedTo, excludeReplays, next, limit);
  }
}
