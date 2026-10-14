# transition-event: дизайн

> Общий дизайн: [`../design.md`](../design.md). Сокращения: `PEJ`, `SQL`, `ES`.

## Интерфейс

- `JournalEntry transition(String source, String eventId, ProcessingStatus status, String errorCode, String errorReason)` (`PEJ:113-129`).
- `JournalEntry toDlq(String source, String eventId, String errorCode, String reason)` (`PEJ:131-160`).
- Внутренний `update(Connection, source, eventId, status, errorCode, errorReason)` (`PEJ:320-354`).

## Допустимые переходы (`ES/ProcessingStatus.java:26-42`) 🟢

| Из | Допустимые в (кроме перехода в себя) |
|---|---|
| `RECEIVED` | `VALIDATED`, `RETRYING`, `QUARANTINED`, `DUPLICATE`, `IGNORED_OLD_VERSION` |
| `VALIDATED` | `NORMALIZED`, `RETRYING`, `QUARANTINED`, `IGNORED_OLD_VERSION` |
| `NORMALIZED` | `RESOLVED`, `RETRYING`, `QUARANTINED`, `IGNORED_OLD_VERSION` |
| `RESOLVED` | `PROJECTED`, `RETRYING`, `QUARANTINED`, `IGNORED_OLD_VERSION` |
| `RETRYING` | `VALIDATED`, `NORMALIZED`, `RESOLVED`, `PROJECTED`, `QUARANTINED`, `IGNORED_OLD_VERSION` |
| `QUARANTINED` | `RETRYING` |
| `PROJECTED`, `DUPLICATE`, `IGNORED_OLD_VERSION` | никуда |

## Основной поток `transition`

1. Соединение, `autoCommit=false` (`PEJ:116-117`).
2. `update`: `SELECT <COLUMNS> FROM inbox_event WHERE source=? AND event_id=? FOR UPDATE` (`PEJ:324-327`).
3. Строки нет → `IllegalArgumentException("unknown event " + fingerprint)` (`PEJ:329-331`).
4. `current.status().canTransitionTo(status)` ложно → `IllegalStateException("transition CUR -> NEW is not allowed for event " + fingerprint)` (`PEJ:335-338`).
5. `UPDATE inbox_event SET status=?, error_code=?, error_reason=?, attempts=attempts+?, updated_at=? WHERE source=? AND event_id=? RETURNING <COLUMNS>`; параметры: `status.name()`, `errorCode`, `truncate(errorReason)` или NULL, `1` для `RETRYING` иначе `0`, `Instant.now()` (`PEJ:339-353`).
6. `commit`, вернуть обновлённую запись (`PEJ:119-121`).

## Основной поток `toDlq`

1. Проверка `errorCode` (`PEJ:133-135`).
2. В одной транзакции: `update(→QUARANTINED, errorCode, reason)` → `INSERT INTO dlq_entry (source, event_id, reason, error_code, payload_key, created_at) VALUES (?,?,?,?,?,?) ON CONFLICT (source, event_id) WHERE replayed_at IS NULL DO NOTHING` (`PEJ:139-149`).
3. `commit`, вернуть запись после `update` (`PEJ:151-152`).

Параметры вставки: `reason` — `truncate(reason == null ? errorCode : reason)`; `payload_key` — `entry.payloadRef().key()` или NULL (`PEJ:145-147`).

## Альтернативные потоки

- Любая ошибка внутри блока → `rollback()` и `throw`; `SQLException` оборачивается в `JournalException("transition failed"/"toDlq failed", e)`, `IllegalArgumentException`/`IllegalStateException` выходят без оборачивания (`PEJ:122-128`, `PEJ:153-159`) 🟢. Из-за общей транзакции сбой вставки в DLQ откатывает и перевод в `QUARANTINED` 🟡 (тест отсутствует).
- Self-transition (`QUARANTINED → QUARANTINED`, `RETRYING → RETRYING`) разрешён; во втором случае `attempts` растёт 🟢 (`ES/ProcessingStatus.java:27-29`, `PEJ:345`).

## Зависимости

`ProcessingStatus.canTransitionTo`; `fingerprint` использует `S3RawPayloadStore.sha256` (`PEJ:357-358`) — скрытая связь между классами модуля 🟢.

## Зависит от / связанные спецификации

[`../design.md`](../design.md); `contracts/event-schemas` (`ProcessingStatus`); вызывающие: `EventProcessor.java:605-625` (`transition` в промежуточные статусы, `RETRYING` с кодом, `toDlq`), `Reconciler.java:178`, `:192`, `JournalDispatcher.java:166` (`toDlq`). Консоль и UI дублируют список статусов (`spec-impact-matrix.md:13`, `:57`).

## Решения

| Решение | Evidence | Метка |
|---|---|---|
| Пессимистическая блокировка строки для проверки перехода | `PEJ:322-325` | 🟢 |
| Журнал не хранит историю ошибок, только последнюю | `PEJ:339-344` | 🟢 |
| `attempts` как счётчик переходов в `RETRYING`, без предела | `PEJ:345` | 🟢 |

## Риски

- 🟢 [UNLOCKER-281: реализовано] R-1..R-4: `replayed_at` пишет `closeDlq`; выход из карантина — `releaseFromQuarantine` (`attempts = 0` отдельным SQL, обычный `transition` счётчик увеличивает); `SUPERSEDED` удалён; лимит попыток — в `EventProcessor.retry` (см. [`../questions.md`](../questions.md)).
- 🟡 Повторный `toDlq` расходится: `inbox_event.error_*` — новые значения, `dlq_entry` — первые.
- 🟡 Успешный переход затирает код последней ошибки (`RETRYING → PROJECTED`).
