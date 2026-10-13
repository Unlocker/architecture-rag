# Use case: append-event (идемпотентный приём события в inbox)

> Модульные требования и сокращения (`PEJ`, `SQL`, `ES`): [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md).

## Обзор

Адаптер, `Reconciler` и `ReplayService` записывают каноническое событие в `inbox_event` через `EventJournal.append` (или `CanonicalEventPublisher.publish`). Повтор того же `(source, eventId)` безопасен и не меняет сохранённую строку. `find` читает запись по тому же ключу.

## Бизнес-правила

1. Ключ дедупликации — `(source, event_id)` (`SQL:21`) 🟢.
2. Первая запись: `status=RECEIVED`, `attempts=0`, `received_at=updated_at=now` (UTC), `error_*=NULL` 🟢 (`PEJ:55-78`).
3. Дубль: строка в БД не меняется; результат — существующая запись с статусом `DUPLICATE` **только в возвращаемом объекте** 🟢 (`PEJ:86-89`, `PEJ:393-397`).
4. `publish` = «не дубль» 🟢 (`PEJ:99-102`).
5. `payloadRef` и `syncRunId` необязательны: `null` → NULL в `payload_key`, `payload_hash`, `sync_run_id` 🟢 (`PEJ:72`, `PEJ:75-76`).
6. Тело дубля (версия, payload) не сравнивается с сохранённым 🟡 (`PEJ:56-89`).

## Требования

| ID | Требование | MoSCoW | Критерий |
|---|---|---|---|
| RF-A1 | Вставка с `ON CONFLICT (source, event_id) DO NOTHING RETURNING` | Must | AC-A1, AC-A2 |
| RF-A2 | Возврат `DUPLICATE` для повтора без изменения БД | Must | AC-A2 |
| RF-A3 | `publish` возвращает `false` для дубля, `true` для нового | Must | AC-A3 |
| RF-A4 | Разные `source` с одним `eventId` независимы | Must | AC-A5 |
| RF-A5 | Атомарность: commit только после успешного чтения/вставки; ошибка → rollback + `JournalException("append failed")` | Must | AC-A6 |
| RF-A6 | `find` возвращает `Optional.empty()` для отсутствующей записи | Should | AC-A4 |

## Нефункциональные

| Тип | Требование | Evidence | Метка |
|---|---|---|---|
| Целостность | Конкурентные `append` одного ключа разрешает уникальный ключ БД, не код | `SQL:21`, `PEJ:59` | 🟢 |
| Время | `received_at` фиксируется приложением (`Instant.now()`), не БД | `PEJ:55`, `PEJ:77-78` | 🟢 |

## Критерии приёмки

```gherkin
# AC-A1 (позитив)
Дано пустой inbox
Когда append(event(source="urn:corp:eam", id=X, sourceVersion="5"), null, "run-1")
Тогда возвращается JournalEntry со status=RECEIVED, attempts=0, sourceVersion="5"
И в inbox_event ровно одна строка (source, X)
# trace: EventJournalIT.duplicateEventIsNotInsertedTwice (first.status == RECEIVED)

# AC-A2 (негатив: дубль)
Дано в inbox_event есть (urn:corp:eam, X) с sourceVersion="5"
Когда append(event(urn:corp:eam, X, sourceVersion="6"), null, "run-2")
Тогда возвращается запись со status=DUPLICATE и sourceVersion="5" (сохранённой)
И число строк (urn:corp:eam, X) остаётся 1
И find(urn:corp:eam, X).status == RECEIVED (в БД статус не менялся)
# trace: EventJournalIT.duplicateEventIsNotInsertedTwice

# AC-A3 (publish)
Дано событие (urn:corp:eam, X) уже в журнале
Когда publish(event(urn:corp:eam, X, "5"), null, null)
Тогда возвращается false
# trace: EventJournalIT.duplicateEventIsNotInsertedTwice; для нового события true: прямого теста нет 🟡

# AC-A4 (find)
Дано события (urn:corp:eam, "missing") нет
Когда find("urn:corp:eam", "missing")
Тогда Optional.empty()
# trace: прямого теста нет 🟡 (find используется внутри тестов на существующих записях)

# AC-A5 (изоляция источников)
Дано (urn:corp:eam, X) уже есть
Когда append(event(urn:corp:scm, X, "1"), null, null)
Тогда возвращается status=RECEIVED
# trace: EventJournalIT.sameEventIdFromAnotherSourceIsAccepted

# AC-A6 (ошибка хранилища)
Дано DataSource недоступен
Когда append(...)
Тогда JournalException("append failed") с причиной SQLException, строка не создана
# trace: PEJ:94-96; теста нет 🔴
```

## MoSCoW

Must: RF-A1..A5. Should: RF-A6 (`find` используется `Reconciler`/`EventProcessor`). Won't: сравнение содержимого дубля (R-7 в [`../questions.md`](../questions.md)).

## Зависит от / связанные спецификации

[`../design.md`](../design.md) (общая схема), `adapter-core` (`InboxWriter.java:38`, `:47`), `graph-projector` (`Reconciler.java:171`), `ingestion-service` (`ReplayService.java:97`), [`../transition-event/`](../transition-event/requirements.md) (далее статусы), [`../raw-payload-store/`](../raw-payload-store/requirements.md) (источник `RawPayloadRef`).

## Прослеживаемость

`PEJ:53-111`, `PEJ:361-370`, `PEJ:372-397`; `SQL:2-25`; тесты `EventJournalIT` (4 метода выше).
