package io.github.unlocker.archrag.eventschemas;

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
}
