package io.github.unlocker.archrag.eventschemas;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

  /**
   * Возвращает событие из {@code QUARANTINED} в очередь повторов: одной транзакцией ставит {@code RETRYING} с
   * {@code attempts = 0} (обычный переход счётчик увеличивает) и закрывает открытую запись DLQ. Дальше событие берёт
   * диспетчер, как любой {@code RETRYING}; если оно упадёт снова, откроется новая запись DLQ.
   *
   * @throws UnknownEventException если события нет
   * @throws NotQuarantinedException если событие не в {@code QUARANTINED}
   */
  JournalEntry releaseFromQuarantine(String source, String eventId);

  /**
   * Закрывает открытую запись DLQ события ({@code replayed_at = now()}): оператор её разобрал. Идемпотентно.
   *
   * @return {@code true}, если открытая запись была и закрыта
   */
  boolean closeDlq(String source, String eventId);

  /** Читает checkpoint consumer по источнику. */
  Optional<Checkpoint> loadCheckpoint(String consumer, String source);

  /**
   * Сохраняет checkpoint (upsert); вызывать только после коммита проекции. Монотонность курсора
   * журнал не проверяет (курсор непрозрачный): за порядком записи следит вызывающий.
   */
  Checkpoint saveCheckpoint(String consumer, String source, String cursor);

  /**
   * Объекты прогона полного snapshot {@code syncRunId}: события с id {@code snap:<syncRunId>:...}.
   * Основа missing set (reconciliation); читает журнал, а не граф, потому что проекция объекта могла
   * не состояться ({@code DUPLICATE}, {@code QUARANTINED}).
   */
  SnapshotContents snapshotContents(String source, String syncRunId);

  /** Объекты источника, по которым в журнал попало любое событие не раньше {@code since}. */
  Set<ObjectRef> objectsReceivedSince(String source, Instant since);

  /**
   * Сколько событий прогона {@code syncRunId} в каком статусе ({@code inbox_event.sync_run_id}), без события
   * {@code excludeEventId} (маркер). Статусы без событий в карту не попадают. Основа счётчиков {@code SyncRun}.
   */
  Map<ProcessingStatus, Long> statusCounts(String source, String syncRunId, String excludeEventId);
}
