# dispatch-journal: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md).

## Visão Geral

Фоновый сценарий: диспетчер периодически читает из журнала `inbox_event` события `RECEIVED` (и застрявшие в промежуточных статусах/`RETRYING` дольше `retryDelay`), достаёт raw из S3, собирает `CanonicalEvent` и передаёт в `EventProcessor`. Строки, которые до процессора дойти не могут, отправляет в DLQ.

## Regras de Negócio

- Выборка — `JournalReader.pending(after, now − retryDelay, batchSize)`; порядок `(receivedAt, source, eventId)`; страницы читаются до пустой 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:97-98, 107, 124-127`). `RECEIVED` берётся всегда; `RETRYING/VALIDATED/NORMALIZED/RESOLVED` — если `updated_at <= retryNotAfter` 🟢 (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:209-237`).
- Каждая страница обрабатывается под `AdminLock`; замок занят → проход заканчивается 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:100-123`).
- Только `null` (исключение) и `RETRYING` блокируют объект до конца прохода; `QUARANTINED`, `IGNORED_OLD_VERSION`, `DUPLICATE`, `PROJECTED` не блокируют 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:119-121`).
- Нет `payloadRef` → `NO_RAW_PAYLOAD`, а для `snapshot-complete` → `MARKER_NO_RAW` 🟢 (`:142-146`).
- Невалидный raw (`IllegalArgumentException` из `toEvent`) → `INVALID_RAW_PAYLOAD` 🟢 (`:150-154`).
- Ошибка S3 (`rawStore.get`) и любое прочее `RuntimeException` → событие остаётся, `null`-статус, лог `dispatch failed: source= eventId= cause=<класс>` 🟢 (`:147-148, 156-162`).
- Дубликат и устаревшую версию решает `EventProcessor`, не диспетчер 🟢 (Javadoc `:34`).
- Каждое обработанное событие считается метрикой `archrag.ingestion.events` (`null` → `ERROR`) 🟢 (`:133-137`).
- `stop()` прерывает проход между событиями; дорабатывается текущее 🟢 (`:88-91, 99, 109-111`).
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] Предела числа повторов нет (Q-01); `RECEIVED`, на котором процессор бросает исключение, берётся в каждый проход (нет гейта по `updated_at` для `RECEIVED`) 🟡.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Обработка в порядке журнала через страницы по `batchSize` | Must | AC-01 |
| RF-02 | Пропуск последующих событий объекта после `RETRYING`/исключения | Must | AC-02 |
| RF-03 | DLQ для строк без raw и с битым raw | Must | AC-03 |
| RF-04 | Уступка замка админской операции (проход завершается) | Must | AC-04 |
| RF-05 | Плавная остановка | Should | AC-05 |
| RF-06 | `StoredEventReader` воспроизводит событие, совпадающее с `EventMapper` | Must | AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Disponibilidade | Исключение прохода не отменяет расписание | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:42-52` | 🟢 |
| Disponibilidade | Остановка ждёт `shutdown-timeout` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:55-71` | 🟢 |
| Segurança | В лог не пишется текст исключения | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:157-160` | 🟢 |
| Segurança | Ошибки разбора raw не цитируют его содержимое | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:19-29, 50-53`; `StoredEventReaderContractTest.brokenRawIsRejectedWithoutQuotingContent` | 🟢 |
| Escalabilidade | Один поток-демон `journal-dispatcher` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:36-40` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (JournalDispatcherTest.processesInJournalOrderAcrossPages)
Дано в журнале пять событий e1..e5 разных объектов с raw, batchSize = 2
Когда вызывается runOnce
Тогда процессор получает e1..e5 в порядке журнала, runOnce возвращает 5,
      первое чтение pending(null, now − retryDelay, 2)

# AC-02 (laterEventsOfObjectAreSkippedAfterRetrying; exceptionOfOneEventDoesNotStopPassButSkipsItsObject)
Дано a1 и a2 принадлежат объекту A, b1 — объекту B, процессор вернул RETRYING для a1
Когда вызывается runOnce
Тогда a2 не передаётся процессору, b1 обработан, runOnce возвращает 2

# AC-03 (invalidRawGoesToDlqAndNextEventIsProcessed; missingRawGoesToDlqWithCodeByType)
Дано строка без payloadRef
Когда диспетчер её берёт
Тогда journal.toDlq(source, eventId, "NO_RAW_PAYLOAD", ...), для snapshot-complete код "MARKER_NO_RAW", процессор не вызван
Дано raw, из которого toEvent бросает IllegalArgumentException
Когда диспетчер её берёт
Тогда toDlq с кодом INVALID_RAW_PAYLOAD, следующее событие обрабатывается

# AC-04 (busyAdminLockSkipsPassWithoutReadingJournal; lockIsReleasedAfterEachPage)
Дано AdminLock занят админской операцией
Когда вызывается runOnce
Тогда reader.pending не вызывается, возвращается 0 (негатив: событие не потеряно, будет взято в следующем проходе)

# AC-05 (stopInterruptsPassBetweenEvents)
Дано идёт проход
Когда вызван stop()
Тогда после текущего события следующие не берутся

# AC-06 (StoredEventReaderContractTest.*)
Дано raw, записанный EventMapper адаптера (upsert/partial/delete/marker)
Когда StoredEventReader.toEvent собирает событие
Тогда оно равно EventMapper.toEvent; marker без валидного objectCount читается без этого поля, и `EventProcessor` уводит его в карантин `SNAPSHOT_OBJECT_COUNT_MISSING` (UNLOCKER-279; `snapshotMarkerWithoutObjectCountIsReadWithoutTheFieldSoThatEventProcessorQuarantinesIt`, IT `markerWithoutObjectCountInRawGoesToDlqWithSpecificCodeAndClosesNothing`)
```

Интеграционно: `JournalDispatcherIT.eventsAppendedToJournalReachGraphWithoutManualCall`, `brokenEventIsQuarantinedWithDlqAndFollowingEventsAreProjected`, `markerWithoutRawGoesToDlq` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/JournalDispatcherIT.java:122, 137, 219`) 🟢.

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-04, RF-06 | Must | Основной путь данных в граф |
| RF-05 | Should | Корректное завершение контекста |
| Лимит повторов | Won't | Нет в коде (Q-01) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java` | `JournalDispatcher` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java` | `DispatcherRunner` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherProperties.java` | `DispatcherProperties` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java` | `StoredEventReader` | 🟢 |
