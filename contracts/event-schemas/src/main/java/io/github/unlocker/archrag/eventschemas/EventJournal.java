package io.github.unlocker.archrag.eventschemas;

import java.util.List;
import java.util.Optional;

/**
 * Журнал событий (граница варианта A: PostgreSQL inbox; позднее может быть заменён Kafka).
 *
 * <p>Инварианты: уникальность {@code (source, eventId)}; повторный {@code append} ничего не меняет;
 * переход состояний и ошибки не теряются молча.
 */
public interface EventJournal {

  /**
   * Атомарно записывает событие в состоянии {@code RECEIVED}, если его ещё нет.
   *
   * @param payloadRef ссылка на уже сохранённый raw payload (может быть {@code null})
   * @param syncRunId прогон синхронизации (может быть {@code null})
   */
  AppendResult append(CanonicalEvent event, RawPayloadRef payloadRef, String syncRunId);

  /** Возвращает запись по ключу дедупликации. */
  Optional<JournalEntry> find(String source, String eventId);

  /** Переводит событие в {@code status}; {@code errorCode}/{@code errorReason} — только для сбойных состояний. */
  JournalEntry transition(String source, String eventId, ProcessingStatus status, String errorCode, String errorReason);

  /** Возвращает самую новую {@code sourceVersion} в состоянии {@code PROJECTED} для объекта источника. */
  Optional<SourceVersion> latestProjectedVersion(String source, String sourceType, String sourceId);

  /** События в состоянии {@code status} по возрастанию времени получения, не больше {@code limit}. */
  List<JournalEntry> findByStatus(ProcessingStatus status, int limit);

  /** Читает checkpoint потока. */
  Optional<Checkpoint> loadCheckpoint(String source, String stream);

  /** Сохраняет checkpoint; вызывать только после коммита проекции. */
  Checkpoint saveCheckpoint(String source, String stream, String cursor);
}
