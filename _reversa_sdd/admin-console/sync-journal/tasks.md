# sync-journal: задачи

## Pré-requisitos
- [ ] Таблицы `inbox_event`, `consumer_checkpoint`, `dlq_entry` созданы миграциями ingestion; роль `archrag_console_ro` имеет `SELECT` (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:15`)
- [ ] Шаблон `console_latest_sync_runs` в реестре (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReaderConfiguration.java:47`)

## Tarefas

- [ ] T-01: `SyncSources` (коды, статусы, `toStored`/`toCode`/`status`)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:13-63`
  - Критерий готовности: AC-04 (400 без эха ввода)
  - Уверенность: 🟢
- [ ] T-02: Выборки `checkpoints`, `eventCounts`, `lastProjected`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:52-94`
  - Критерий готовности: AC-01, AC-02
  - Уверенность: 🟢
- [ ] T-03: Выборки `events`, `eventsById`, `errorReason`, `dlqOfEvent`, `dlq`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:96-167, 300-331`
  - Критерий готовности: AC-03, AC-05, AC-06
  - Уверенность: 🟢
- [ ] T-04: `SyncController` (`sources`, `events`, `event`, `dlq`, `latestRuns`)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:32-146`
  - Критерий готовности: AC-01..AC-06
  - Уверенность: 🟢
- [ ] T-05: Заполнение `dlq_entry.replayed_at` на стороне ingestion — предложение, закрывает Q-03
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:157-159` (читатель); писателя в коде нет
  - Критерий готовности: решение архитектора
  - Уверенность: 🟡

## Tarefas de Teste

- [ ] TT-01: `SyncApiTest`: `everyRouteRequiresTokenAndAdminScope`, `sizeAboveLimitAndNegativePageAreRejected`, `unknownSourceAndStatusAreRejectedWithoutEchoingInput`, `filtersAreMappedToStoredValuesAndResponseHasNoPayloadField`, `responseTypesDeclareNoPayloadComponent`, `sourcesAlwaysListAllFourSourcesWithZeroCounts`, `unknownEventIsNotFoundAndUnknownSourceIsRejected`, `lagIsComputedFromInjectedClock` (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java:77-176`)
- [ ] TT-02: `SyncConsoleIT`: `eventsFilterBySourceStatusAndPeriod`, `eventsPaginateAndSizeIsCapped`, `eventMetadataHasRefAndHashButNoPayload`, `eventDetailCarriesDlqHistoryAndUnknownIsNotFound`, `dlqListsEntriesWithReason`, `sourcesShowCheckpointCountsLastProjectedLagAndLatestSyncRun` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:264-324`)
- [ ] TT-03: тест на `eventId` с `/` (предложение, Q-04)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 (T-05 по решению Q-03).

## Lacunas Pendentes (🔴)
Q-03 (`replayed_at`), Q-04 (`eventId` с `/`) — решение за архитектором.
