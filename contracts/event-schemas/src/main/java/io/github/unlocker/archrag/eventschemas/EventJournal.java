package io.github.unlocker.archrag.eventschemas;

import java.util.Optional;

/**
 * Журнал событий (граница варианта A: PostgreSQL inbox; позднее может быть заменён Kafka).
 *
 * <p>Инварианты: уникальность {@code (source, eventId)}; повторный {@code append} ничего не меняет;
 * ошибки хранилища не глушатся.
 */
public interface EventJournal {

  /**
   * Атомарно записывает событие в состоянии {@code RECEIVED}, если его ещё нет. Для повтора
   * возвращает прежнюю запись со статусом {@code DUPLICATE}, сохранённая строка не меняется.
   *
   * @param payloadRef ссылка на уже сохранённый raw payload (может быть {@code null})
   * @param syncRunId прогон синхронизации (может быть {@code null})
   */
  JournalEntry append(CanonicalEvent event, RawPayloadRef payloadRef, String syncRunId);

  /** Возвращает запись по ключу дедупликации. */
  Optional<JournalEntry> find(String source, String eventId);

  /**
   * Переводит событие в {@code status}; {@code errorCode}/{@code errorReason} — для сбойных состояний.
   *
   * @throws IllegalArgumentException если события нет
   * @throws IllegalStateException если переход запрещён {@link ProcessingStatus#canTransitionTo}
   */
  JournalEntry transition(String source, String eventId, ProcessingStatus status, String errorCode, String errorReason);

  /**
   * Переводит событие в {@code QUARANTINED} и создаёт запись DLQ. Повторный вызов для события с
   * необработанной записью DLQ вторую запись не создаёт.
   *
   * @throws IllegalArgumentException если {@code errorCode} пуст или события нет
   */
  JournalEntry toDlq(String source, String eventId, String errorCode, String reason);

  /** Читает checkpoint consumer по источнику. */
  Optional<Checkpoint> loadCheckpoint(String consumer, String source);

  /**
   * Сохраняет checkpoint (upsert); вызывать только после коммита проекции. Монотонность курсора
   * журнал не проверяет (курсор непрозрачный): за порядком записи следит вызывающий.
   */
  Checkpoint saveCheckpoint(String consumer, String source, String cursor);
}
