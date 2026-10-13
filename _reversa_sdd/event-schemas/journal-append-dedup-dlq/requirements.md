# Use case: journal-append-dedup-dlq, Требования

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Модульные документы: [requirements](../requirements.md), [design](../design.md), [tasks](../tasks.md).

## Обзор
Приём события в журнал с дедупликацией по `(source, eventId)`, переходы статусов, карантин с записью DLQ, checkpoint и выборки для reconcile. Контракт — `EventJournal` (`ES/EventJournal.java:13-61`); реализация вне модуля (`PostgresEventJournal`) 🟢.

## Правила
- Уникальность `(source, eventId)`; повторный `append` ничего не меняет и возвращает прежнюю запись со статусом `DUPLICATE` (в строке журнала статус не меняется) 🟢 (`:10-11,15-17`).
- `append` атомарен; `payloadRef` и `syncRunId` могут быть `null` 🟢 (`:16-21`).
- `transition`: `IllegalArgumentException`, если события нет; `IllegalStateException`, если переход запрещён `canTransitionTo` 🟢 (`:30-31`).
- `toDlq`: статус `QUARANTINED` и запись DLQ; при уже существующей необработанной записи DLQ вторая не создаётся; `IllegalArgumentException`, если `errorCode` пуст или события нет 🟢 (`:35-40`).
- `saveCheckpoint` — upsert, только после коммита проекции; монотонность курсора не проверяется 🟢 (`:46-49`).
- `snapshotContents(source, syncRunId)` читает журнал (не граф): события `snap:<syncRunId>:...` включая `DUPLICATE`, `QUARANTINED`, `RETRYING`; маркер не считается 🟢 (`:52-56`; `ES/SnapshotContents.java:7-12`).
- `objectsReceivedSince(source, since)` — объекты (по `sourceType`/`sourceId` журнала) с любым событием не раньше `since` 🟢 (`:59-60`).
- `CanonicalEventPublisher.publish` — обёртка границы: `true` — впервые, `false` — дубликат 🟢 (`ES/CanonicalEventPublisher.java:9-14`); реализаций нет 🟡.
- Ошибки хранилища не глушатся 🟢 (`:10-11`).

## Функциональные требования
| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-01 | Первый `append` создаёт запись `RECEIVED` | Must | AC-01 |
| RF-02 | Повторный `append` того же `(source,id)` → `DUPLICATE`, строка не меняется | Must | AC-02 |
| RF-03 | `transition` отвергает запрещённый переход и неизвестное событие | Must | AC-03, AC-04 |
| RF-04 | `toDlq` идемпотентна по необработанной записи DLQ; пустой код отвергается | Must | AC-05, AC-06 |
| RF-05 | Checkpoint сохраняется upsert-ом после коммита; `loadCheckpoint` возвращает последний | Must | AC-07 |
| RF-06 | `snapshotContents`/`objectsReceivedSince` питают reconcile | Must | AC-08 |

## Критерии приёмки
Тестов самого контракта в модуле нет; сценарии проверяются в `ingestion/event-journal` (`EventJournalIT` — упомянут в `docs/03-interfaces.md:512`) и в тестовых реализациях (`adapters/adapter-core/src/test/.../InMemoryJournal.java`) 🟡.
```gherkin
# AC-01
Дано журнал без события (source="urn:corp:eam", id="1")
Когда append(event, ref, "run-1")
Тогда запись со статусом RECEIVED, attempts=0 (значение attempts по умолчанию определяет реализация 🟡)

# AC-02
Дано событие (urn:corp:eam, 1) уже в журнале
Когда append того же события второй раз
Тогда возвращается запись со статусом DUPLICATE, сохранённая строка не изменилась

# AC-03
Дано статус PROJECTED
Когда transition(..., RETRYING, ...)
Тогда IllegalStateException

# AC-04
Дано события нет в журнале
Когда transition(source, "unknown", VALIDATED, null, null)
Тогда IllegalArgumentException

# AC-05
Дано событие RESOLVED, DLQ-записи нет
Когда toDlq(source, id, "INVALID_COMMANDS", reason) вызывается дважды
Тогда статус QUARANTINED, запись DLQ ровно одна

# AC-06
Дано errorCode пуст
Когда toDlq(...)
Тогда IllegalArgumentException

# AC-07
Дано saveCheckpoint("projector", "urn:corp:eam", "ev-10")
Когда loadCheckpoint("projector", "urn:corp:eam")
Тогда cursor="ev-10", updatedAt — UTC

# AC-08
Дано прогон run-1: два события в RECEIVED/DUPLICATE/QUARANTINED
Когда snapshotContents(source, "run-1")
Тогда objects содержит оба объекта, eventCount=2, firstReceivedAt — момент первой записи
```

## Трассировка
| Файл | Символ | Покрытие |
|---|---|---|
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/EventJournal.java` | `EventJournal` | 🟢 контракт; тестов в модуле нет |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/SnapshotContents.java` | `SnapshotContents` | 🟢 |
| `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/CanonicalEventPublisher.java` | `CanonicalEventPublisher` | 🟢 |
