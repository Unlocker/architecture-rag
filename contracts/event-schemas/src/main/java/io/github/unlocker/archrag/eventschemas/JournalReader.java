package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;
import java.util.List;

/**
 * Чтение журнала страницами для replay и rebuild. Только чтение: состояние строк не меняет.
 *
 * <p>Инварианты: порядок результата — {@code (receivedAt, source, eventId)}; пагинация keyset-ом по
 * {@link JournalQuery#after()} устойчива к вставкам во время обхода; ошибки хранилища не глушатся.
 */
public interface JournalReader {

  /** Возвращает не более {@link JournalQuery#limit()} строк, подходящих под запрос. */
  List<StoredEvent> read(JournalQuery query);

  /**
   * Строки, ожидающие обработки: {@code RECEIVED}, а также {@code RETRYING} и промежуточные статусы
   * ({@code VALIDATED}, {@code NORMALIZED}, {@code RESOLVED}), не менявшиеся с {@code retryNotAfter} или раньше.
   * Промежуточные статусы оставляет прерванная обработка, поэтому они ждут так же, как {@code RETRYING}.
   *
   * <p>Порядок и keyset как у {@link #read}: {@code (receivedAt, source, eventId)}, строго после {@code after}
   * ({@code null} — с начала). Строки replay не исключаются. Состояние строк не меняется.
   *
   * @param retryNotAfter граница {@code updatedAt} для повторов (включительно)
   * @param limit размер страницы, {@code 1..}{@value JournalQuery#MAX_LIMIT}
   */
  List<StoredEvent> pending(JournalKey after, Instant retryNotAfter, int limit);
}
