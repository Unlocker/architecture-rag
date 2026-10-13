# sync-journal: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Производитель данных: `ingestion/ingestion-service` (диспетчер, DLQ).

## Visão Geral

Оператор видит состояние синхронизации: сводка по четырём источникам (checkpoints, последний `SyncRun` из графа, число событий по статусам, время последнего `PROJECTED`, отставание), журнал событий `inbox_event` с фильтрами и страницами, карточка события с причиной ошибки и записями DLQ, список DLQ. Данные читаются из PostgreSQL ролью `archrag_console_ro`; payload не отдаётся.

## Regras de Negócio

- **sources**: всегда 4 элемента в порядке `eam, scm, cmdb, deploymap`, даже без данных; статусов в `eventsByStatus` всегда 10 с нулями 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:63-73`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:19-33`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java:147`).
- **sources**: `checkpoints` — все consumer-ы по источнику; `eventsByStatus` — `count(*) GROUP BY source, status`; `lastProjectedAt` — `max(updated_at)` среди `PROJECTED` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:55, 70, 85`).
- **sources**: `lagSeconds = max(0, now − lastProjectedAt)` по `Clock` (UTC), `null`, если `PROJECTED` не было 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:62, 69-70`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java:176`).
- **sources**: `lastSyncRun` берётся из графа шаблоном `console_latest_sync_runs` по значениям `SyncRun.adapter` в верхнем регистре; нет прогона — `null`; `fetched/applied/failed` — `Long` или `null` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:36-37, 123-146`).
- **events**: фильтры `source` (код → URN `urn:corp:<код>`), `status` (из 10), `from`/`to` (`received_at >= from AND < to`); `from < to`, иначе 400; сортировка `received_at DESC, source, event_id` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:78-91`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:99-112`).
- **events**: `source` в ответе — короткий код (URN-префикс срезается) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:49-51`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:302`).
- **event**: ключ `(source, eventId)`; найдено ноль → `404 event not found`; ответ — событие, `errorReason` (обрезано до 500 символов), записи DLQ события (старые первыми) и `historyNote` («Status history is not stored...») 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:97-110`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:128-149`).
- **event**: пустой/неизвестный `source` в пути → 400; `eventId` с символом `/` в путь не помещается — карточка таких событий недоступна 🔴 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:93-101`; Q-04).
- **dlq**: фильтры `source`, `replayed` (`true` → `replayed_at IS NOT NULL`, `false` → `IS NULL`), сортировка `created_at DESC, id DESC`; `reason` режется до 500 символов 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:113-121`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:154-167, 338-340`).
- `replayed_at` нигде не заполняется (Р-3): `replayed=false` возвращает все записи, `replayed=true` — ничего 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] (Q-03; тест `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:312` фиксирует текущее поведение).
- Размер страницы по умолчанию 50, максимум 200; `page ≥ 0`; ответ `Page{items,page,size,total}` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/PageRequest.java:10-24`).
- Ответы не содержат payload: только `payloadRef` и `contentHash` (тест на уровне типов: ни одна запись `Rows` не имеет компонента `payload`/`payloadKey`/`data`) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:14-32`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java:136`).
- Неизвестный `source`/`status` → 400 с перечислением допустимых значений, без эха ввода 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:38-61`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java:98`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Сводка по источникам с лагом | Must | AC-01, AC-02 |
| RF-02 | Журнал событий с фильтрами и страницами | Must | AC-03, AC-04 |
| RF-03 | Карточка события с DLQ | Must | AC-05 |
| RF-04 | Список DLQ | Must | AC-06 |
| RF-05 | Отказ на невалидные параметры без эха | Must | AC-04 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Только `SELECT` под ролью `archrag_console_ro`, параметры именованные | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:24-29`; `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:15` | 🟢 |
| Безопасность | Тексты ошибок источников ограничены 500 символами | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:34, 338-340` | 🟢 |
| Производительность | Каждый список — два запроса (`count(*)` и страница) с одним `WHERE` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:271-290` | 🟢 |
| Надёжность | Отставание считается на инжектируемых UTC-часах | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsolePgConfiguration.java:38-43` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (SyncApiTest.sourcesAlwaysListAllFourSourcesWithZeroCounts)
Дано пустые checkpoints, счётчики и граф без прогонов
Когда GET /api/sync/sources
Тогда 4 элемента eam, scm, cmdb, deploymap; eventsByStatus.PROJECTED = 0; lagSeconds и lastSyncRun = null

# AC-02 (SyncApiTest.lagIsComputedFromInjectedClock; SyncConsoleIT.sourcesShowCheckpointCountsLastProjectedLagAndLatestSyncRun)
Дано часы 2026-03-01T12:00:00Z и последний PROJECTED eam на 90 с раньше
Когда GET /api/sync/sources
Тогда lagSeconds для eam = 90, для scm = null

# AC-03 (SyncApiTest.filtersAreMappedToStoredValuesAndResponseHasNoPayloadField; SyncConsoleIT.eventsFilterBySourceStatusAndPeriod, eventsPaginateAndSizeIsCapped, eventMetadataHasRefAndHashButNoPayload)
Дано события eam-1..eam-5
Когда GET /api/sync/events?source=eam&from=2026-01-02T00:00:00Z&to=2026-01-04T00:00:00Z
Тогда eam-3, eam-2 (новые сверху), в элементе payloadRef и contentHash, нет полей payload/data
Когда page=0..2, size=2
Тогда eam-5,eam-4 | eam-3,eam-2 | eam-1; total = 5

# AC-04 (SyncApiTest.sizeAboveLimitAndNegativePageAreRejected, unknownSourceAndStatusAreRejectedWithoutEchoingInput)
Дано size=201, size=0, page=-1, source=evil'..., status=NOPE, from позже to
Когда GET /api/sync/events (и dlq, identity, audit для size/page)
Тогда 400, в теле нет введённого значения, обращений к репозиторию нет

# AC-05 (SyncApiTest.unknownEventIsNotFoundAndUnknownSourceIsRejected; SyncConsoleIT.eventDetailCarriesDlqHistoryAndUnknownIsNotFound)
Дано событие eam-4 в QUARANTINED с одной записью DLQ
Когда GET /api/sync/events/eam/eam-4
Тогда event.status = QUARANTINED, dlqEntries[0].reason = "cannot map", historyNote задан
Когда GET /api/sync/events/eam/nope
Тогда 404; GET /api/sync/events/bogus/e-1 → 400

# AC-06 (SyncConsoleIT.dlqListsEntriesWithReason)
Дано одна запись DLQ eam с кодом MAPPING_FAILED и replayed_at = null
Когда GET /api/sync/dlq?source=eam
Тогда total = 1, errorCode = MAPPING_FAILED, replayedAt = null
Когда GET /api/sync/dlq?replayed=true
Тогда total = 0
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Мониторинг синхронизации оператором (ADR 0026) |
| Закрытие/replay записей DLQ из консоли | Won't | Нет в коде; admin REST ingestion не закрывает DLQ (Q-03) |
| История статусов события | Won't | Не хранится (`historyNote`) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java` | `SyncController` | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java` | выборки журнала | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java`, `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/PageRequest.java`, `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Page.java`, `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java` | allowlist, страницы, формы | 🟢 |
