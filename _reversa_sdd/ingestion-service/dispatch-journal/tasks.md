# dispatch-journal: задачи

## Pré-requisitos
- [ ] Порты `JournalReader.pending`, `EventJournal.toDlq`, `RawPayloadStore.get`, `EventProcessor.process` доступны (event-schemas, event-journal, graph-projector)
- [ ] `AdminLock` и `IngestionMetrics` реализованы (см. [`../tasks.md`](../tasks.md))

## Tarefas

- [ ] T-01: `DispatcherProperties` с умолчаниями и валидацией
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherProperties.java:16-34`
  - Критерий готовности: `poll-interval <= 0`, `retry-delay < 0`, `batch-size` вне 1..1000 → `IllegalArgumentException`; дефолты `true/1s/100/30s/30s`
  - Уверенность: 🟢
- [ ] T-02: `StoredEventReader.toEvent` (upsert, delete, marker)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:44-120`
  - Критерий готовности: `StoredEventReaderContractTest` зелёный против `EventMapper`; сообщения ошибок без содержимого raw
  - Уверенность: 🟢
- [ ] T-03: `JournalDispatcher.runOnce` (замок на страницу, keyset, blocked-set, DLQ-коды, метрика)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:94-167`
  - Критерий готовности: AC-01..AC-04 из `requirements.md`
  - Уверенность: 🟢
- [ ] T-04: `DispatcherRunner` как `SmartLifecycle` (один поток-демон, fixed delay, плавная остановка)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:16-76`
  - Критерий готовности: исключение прохода не отменяет расписание; `stop()` ждёт `shutdownTimeout`, затем `shutdownNow`
  - Уверенность: 🟢 (юнит-теста у `DispatcherRunner` нет)

## Tarefas de Teste

- [ ] TT-01: порядок и страницы (`JournalDispatcherTest.processesInJournalOrderAcrossPages`)
- [ ] TT-02: блокировка объекта (`laterEventsOfObjectAreSkippedAfterRetrying`, `exceptionOfOneEventDoesNotStopPassButSkipsItsObject`)
- [ ] TT-03: метрики и `ERROR` (`metricsCountFinalStatusAndErrorForUnexpectedException`)
- [ ] TT-04: DLQ (`invalidRawGoesToDlqAndNextEventIsProcessed`, `missingRawGoesToDlqWithCodeByType`)
- [ ] TT-05: замок (`busyAdminLockSkipsPassWithoutReadingJournal`, `lockIsReleasedAfterEachPage`), остановка (`stopInterruptsPassBetweenEvents`)
- [ ] TT-06: контрактный тест формата raw (`StoredEventReaderContractTest`, 7 тестов)
- [ ] TT-07: интеграция (`JournalDispatcherIT`: 6 сценариев, включая маркер)

## Ordem Sugerida
1. T-01, T-02 → 2. T-03 → 3. T-04. Сборка бина — в [`../tasks.md`](../tasks.md) T-10.

## Lacunas Pendentes (🔴)
- Q-01 (лимит повторов), Q-05 (литералы типов), Q-09 (необратимый `stop`) — [`../questions.md`](../questions.md).
