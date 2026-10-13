# Use case: transition-event (смена статуса, ошибки, DLQ)

> Модульные требования и сокращения (`PEJ`, `SQL`, `ES`): [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md).

## Обзор

Обработчики (`EventProcessor`, `JournalDispatcher`, `Reconciler`) переводят событие по состояниям `ProcessingStatus` через `transition`, а неисправимые ошибки уводят в карантин и DLQ через `toDlq`. Журнал гарантирует допустимость перехода, счётчик попыток и единственность открытой записи DLQ; решение «в какой статус» принимает вызывающий.

## Бизнес-правила

1. Допустимость: `current.canTransitionTo(next)`; переход в себя разрешён; конечные `SUPERSEDED`, `DUPLICATE`, `IGNORED_OLD_VERSION` не покидаются; `PROJECTED → SUPERSEDED` единственный; `QUARANTINED → RETRYING` единственный выход 🟢 (`ES/ProcessingStatus.java:26-42`).
2. Нет события → `IllegalArgumentException("unknown event <отпечаток>")` 🟢 (`PEJ:329-331`).
3. Запрещённый переход → `IllegalStateException("transition X -> Y is not allowed for event <отпечаток>")` 🟢 (`PEJ:335-338`).
4. `attempts += 1` ровно когда целевой статус `RETRYING`, иначе `+0` 🟢 (`PEJ:340`, `PEJ:345`).
5. `error_code`, `error_reason` пишутся из аргументов (в том числе `NULL`), `error_reason` усекается до 500; `updated_at = now` 🟢 (`PEJ:339-346`).
6. `toDlq`: `errorCode` не `null` и не blank, иначе `IllegalArgumentException("errorCode is required")` до любого обращения к БД 🟢 (`PEJ:133-135`).
7. `toDlq` = `update(→QUARANTINED, errorCode, reason)` + `INSERT INTO dlq_entry ... ON CONFLICT (source, event_id) WHERE replayed_at IS NULL DO NOTHING` в одной транзакции 🟢 (`PEJ:139-151`).
8. Поля `dlq_entry`: `reason = truncate(reason ?? errorCode)`, `error_code`, `payload_key` из записи (NULL, если raw нет), `created_at = now` 🟢 (`PEJ:145-148`).
9. `toDlq` из `PROJECTED` и конечных статусов невозможен (исключение перехода, DLQ не создаётся) 🟢 (`PEJ:139`, `PEJ:335`).
10. Повторный `toDlq` (QUARANTINED → QUARANTINED допустим): вторая запись DLQ не создаётся, но `error_code`/`error_reason` в `inbox_event` перезаписываются новыми значениями 🟢 (`PEJ:139-142`, `PEJ:339-344`).
11. Выход из карантина (`QUARANTINED → RETRYING`) в main-коде никем не вызывается; `dlq_entry.replayed_at` не заполняется 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] (R-1, R-2).

## Требования

| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-T1 | `transition` под `FOR UPDATE` с проверкой `canTransitionTo` | Must | AC-T1, AC-T3 |
| RF-T2 | `attempts` растёт только на `RETRYING` | Must | AC-T2 |
| RF-T3 | Исключения: неизвестное событие, запрещённый переход | Must | AC-T3, AC-T4 |
| RF-T4 | `toDlq` атомарно | Must | AC-T5 |
| RF-T5 | Единственная открытая запись DLQ | Must | AC-T6 |
| RF-T6 | Отказ на пустом `errorCode` | Must | AC-T7 |
| RF-T7 | Усечение причины до 500 символов | Should | AC-T8 |

## Нефункциональные

| Тип | Требование | Evidence | Метка |
|---|---|---|---|
| Конкурентность | Строка блокируется `SELECT ... FOR UPDATE` до `UPDATE`; параллельные воркеры сериализуются на уровне строки | `PEJ:324-325` | 🟢 |
| Безопасность | В сообщениях об ошибках только отпечаток (12 hex SHA-256) `source`+`eventId` | `PEJ:356-359` | 🟢 |
| Безопасность | Причина (недоверенный текст) усечена | `PEJ:399-402` | 🟢 |

## Критерии приёмки

```gherkin
# AC-T1 (позитив: попытки и ошибка)
Дано событие в RECEIVED
Когда transition(src, id, RETRYING, "NEO4J_TRANSIENT", "timeout")
Тогда status=RETRYING, attempts=1, errorCode="NEO4J_TRANSIENT"
# trace: EventJournalIT.transitionsAndDlqArePersisted

# AC-T2 (attempts не растёт на остальных)
Дано событие в RETRYING (attempts=1)
Когда transition(..., VALIDATED, null, null)
Тогда attempts остаётся 1, error_code и error_reason становятся NULL
# trace: поведение PEJ:339-345; теста на обнуление нет 🟡

# AC-T3 (негатив: запрещённый переход)
Дано событие в PROJECTED
Когда transition(src, id, RETRYING, "LATE", "late worker")
Тогда IllegalStateException; статус в БД остаётся PROJECTED
# trace: EventJournalIT.finalStateCannotBeRolledBack

# AC-T4 (негатив: неизвестное событие)
Дано события нет
Когда transition(src, "missing", PROJECTED, null, null)
Тогда IllegalArgumentException
# trace: EventJournalIT.transitionsAndDlqArePersisted

# AC-T5 (toDlq)
Дано событие в RETRYING с payloadRef(key="raw/k")
Когда toDlq(src, id, "SCHEMA_UNKNOWN", "unknown schema")
Тогда status=QUARANTINED, errorCode="SCHEMA_UNKNOWN"
И в dlq_entry одна строка ("unknown schema", "SCHEMA_UNKNOWN", "raw/k")
# trace: EventJournalIT.transitionsAndDlqArePersisted

# AC-T6 (идемпотентность DLQ)
Дано toDlq(..., "first") уже выполнен
Когда toDlq(..., "second")
Тогда в dlq_entry остаётся одна строка
# trace: EventJournalIT.repeatedDlqKeepsSingleRecord

# AC-T7 (негатив: пустой код)
Дано существующее событие
Когда toDlq(src, id, " ", "r") или toDlq(src, "missing", "CODE", "r")
Тогда IllegalArgumentException, dlq_entry пуст, статус не менялся
# trace: EventJournalIT.dlqRejectsBlankCodeAndUnknownEvent

# AC-T8 (усечение)
Дано errorReason длиной 600 символов
Когда transition(..., RETRYING, "C", reason600)
Тогда в error_reason ровно первые 500 символов
# trace: PEJ:399-402; теста нет 🟡
```

## MoSCoW

Must: RF-T1..T6. Should: RF-T7. Won't (в коде нет): выход из карантина, закрытие DLQ (`replayed_at`), лимит попыток (R-1, R-2, R-4).

## Зависит от / связанные спецификации

`contracts/event-schemas` (`ProcessingStatus.canTransitionTo`); `graph-projector` (`EventProcessor.java:605-625`, `Reconciler.java:178`, `:192`); `ingestion-service` (`JournalDispatcher.java:166`); [`../design.md`](../design.md). Автомат состояний: `_reversa_sdd/state-machines.md` §1.

## Прослеживаемость

`PEJ:113-160`, `PEJ:320-359`, `PEJ:399-402`; `SQL:2-22`, `SQL:35-48`; `EventJournalIT` (5 методов).
