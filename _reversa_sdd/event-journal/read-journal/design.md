# read-journal: дизайн

> Общий дизайн: [`../design.md`](../design.md). Сокращения: `PEJ`, `SQL`, `ES`.

## Интерфейс

| Метод | Сигнатура | Evidence |
|---|---|---|
| `read` | `List<StoredEvent> read(JournalQuery q)` | `PEJ:162-207` |
| `pending` | `List<StoredEvent> pending(JournalKey after, Instant retryNotAfter, int limit)` | `PEJ:209-237` |
| `loadCheckpoint` | `Optional<Checkpoint> loadCheckpoint(String consumer, String source)` | `PEJ:239-254` |
| `saveCheckpoint` | `Checkpoint saveCheckpoint(String consumer, String source, String cursor)` | `PEJ:256-272` |
| `snapshotContents` | `SnapshotContents snapshotContents(String source, String syncRunId)` | `PEJ:274-299` |
| `objectsReceivedSince` | `Set<ObjectRef> objectsReceivedSince(String source, Instant since)` | `PEJ:301-318` |

`StoredEvent` = `JournalEntry` + `type` + `subject` (читаются дополнительно к `COLUMNS`) (`PEJ:165`, `PEJ:200`).

## Поток `read`

1. База: `SELECT <COLUMNS>, type, subject FROM inbox_event WHERE true` (`PEJ:165`).
2. Условия добавляются в порядке: `source = ?`, `received_at >= ?`, `received_at < ?`, `event_id NOT LIKE 'replay:%'` (аргумент `REPLAY_PREFIX + "%"`), `(received_at, source, event_id) > (?, ?, ?)` (`PEJ:167-189`).
3. `ORDER BY received_at, source, event_id LIMIT ?` (`PEJ:190`).
4. Параметры привязываются через `setObject` по порядку; `Timestamp.from(Instant)` для времени (`PEJ:192-196`).
5. Результат — страница `StoredEvent` (`PEJ:197-203`).

## Поток `pending`

1. `limit` вне `1..MAX_LIMIT` → `IllegalArgumentException` (`PEJ:211-213`).
2. SQL: `WHERE (status = 'RECEIVED' OR (status IN ('RETRYING','VALIDATED','NORMALIZED','RESOLVED') AND updated_at <= ?))` + при `after != null` ` AND (received_at, source, event_id) > (?, ?, ?)` + `ORDER BY received_at, source, event_id LIMIT ?` (`PEJ:214-217`).
3. Параметры: `Timestamp.from(retryNotAfter)`, затем (если есть) три значения ключа, затем `limit` (`PEJ:219-226`).

Потребитель (`JournalDispatcher`) вычисляет `retryNotAfter = now - retryDelay` и продвигает `after` по последней строке страницы (`JournalDispatcher.java:94-130`) 🟡 (вне модуля).

## Поток checkpoint

- `loadCheckpoint`: `SELECT cursor, updated_at FROM consumer_checkpoint WHERE consumer=? AND source=?` (`PEJ:242-245`).
- `saveCheckpoint`: `INSERT INTO consumer_checkpoint (consumer, source, cursor, updated_at) VALUES (?,?,?,?) ON CONFLICT (consumer, source) DO UPDATE SET cursor = EXCLUDED.cursor, updated_at = EXCLUDED.updated_at`; возвращает `Checkpoint(consumer, source, cursor, now)` (`PEJ:257-268`). Autocommit, без проверки монотонности.

## Поток reconciliation-запросов

- `snapshotContents`: `SELECT source_type, source_id, received_at FROM inbox_event WHERE source = ? AND starts_with(event_id, ?)` с префиксом `"snap:" + syncRunId + ":"`; накапливает `Set<ObjectRef>`, счётчик строк, минимум `received_at` (`PEJ:276-298`).
- `objectsReceivedSince`: `SELECT DISTINCT source_type, source_id FROM inbox_event WHERE source = ? AND received_at >= ?` (`PEJ:303-317`).

## Альтернативные потоки

- Ошибка SQL → `JournalException("<read|pending|loadCheckpoint|saveCheckpoint|snapshotContents|objectsReceivedSince> failed", e)` (`PEJ:205`, `235`, `252`, `270`, `296`, `316`) 🟢.
- `retryNotAfter == null` → `NullPointerException` (контракт не определён) 🟡 (`PEJ:220`).
- Транзакционной изоляции между страницами нет: keyset устойчив к вставкам, но не к изменению `updated_at`/`status` между страницами 🟡.

## Зависимости

`DataSource`; DTO `event-schemas`.

## Зависит от / связанные спецификации

[`../design.md`](../design.md); `ingestion-service` (диспетчер, replay, rebuild), `adapter-core` (`Poller`), `graph-projector` (`EventProcessor`, `Reconciler`). Правило «удалять только после полного snapshot»: журнал лишь поставляет множество объектов, решение о tombstone принимает `Reconciler` (`Reconciler.java:105`, `:113`) 🟡.

## Решения

| Решение | Evidence | Метка |
|---|---|---|
| Keyset по `(received_at, source, event_id)`: `event_id` уникален только в пределах `source` | `ES/JournalKey.java:5-9` (Javadoc); `PEJ:185` | 🟢 |
| Промежуточные статусы выбираются как зависшие после падения | `PEJ:214-215`, `ES/JournalReader.java:17-21` | 🟢 |
| Основа missing set — журнал, не граф (проекция могла не состояться) | Javadoc `ES/EventJournal.java:52-56` | 🟢 |

## Риски

- 🟡 Нет индекса под условие `updated_at` в `pending`; при росте журнала выборка опирается на `ix_inbox_event_status (status, received_at)`.
- 🟡 `snapshotContents` — линейная память от размера snapshot.
- 🟡 `saveCheckpoint` не проверяет порядок: ответственность вызывающего (сдвигать только после коммита проекции).
