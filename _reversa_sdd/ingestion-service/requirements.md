# ingestion/ingestion-service: требования

> Единственное запускаемое приложение группы `ingestion/*`. Пути в трассировке развёрнуты до корня репозитория. Дизайн: [`design.md`](design.md); внешние контракты: [`contracts.md`](contracts.md); открытые вопросы: [`questions.md`](questions.md); решения: [`decisions.md`](decisions.md).
> Use case-спецификации: [`dispatch-journal/`](dispatch-journal/requirements.md), [`replay-rebuild/`](replay-rebuild/requirements.md), [`reconcile-source/`](reconcile-source/requirements.md), [`load-crosswalk/`](load-crosswalk/requirements.md).

## Визуальная схема

```mermaid
flowchart LR
  subgraph PG[(PostgreSQL)]
    IE[inbox_event / dlq_entry]
    AA[admin_audit]
  end
  S3[(S3 raw)]
  N4[(Neo4j writer)]
  D[JournalDispatcher<br/>1 поток, 1 экземпляр] -->|pending| IE
  D -->|get raw| S3
  D -->|EventProcessor.process| N4
  OP[Оператор: JWT architecture.admin] --> AC[AdminController /admin/**]
  AC --> AO[AdminOperations: замок + аудит]
  AO --> AA
  AO --> RP[ReplayService / RebuildService / CrosswalkService / AdapterControlClient]
  RP --> IE
  RP --> N4
  RP -->|POST control-url| AD[adapter-* /control/snapshot]
  L{{AdminLock: pg advisory}} --- D
  L --- AO
```

## Visão Geral

`ingestion-service` собирает в одно Spring Boot-приложение компоненты ingestion (журнал, raw storage, identity, проектор), крутит фоновый диспетчер журнала (`RECEIVED`/`RETRYING` → `EventProcessor`), предоставляет админский REST (`replay`, `rebuild`, `reconcile`, `crosswalks`), пишет аудит админских операций в `admin_audit`, экспортирует метрики и несёт миграции `V3`/`V6` общего каталога Flyway. Во внешний ingress не публикуется 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceApplication.java:7-14`).

## Responsabilidades

- Сборка бинов вручную (без Boot-Flyway): миграции PostgreSQL, схема Neo4j, `TemporalRelationMigration`, бакет S3 при старте 🟢.
- Диспетчер журнала в одном потоке: передаёт ожидающие события в `EventProcessor` в порядке журнала, под `AdminLock`, ведёт карантин для строк без пригодного raw 🟢.
- Сборка `CanonicalEvent` из строки `inbox_event` и raw payload (`StoredEventReader`) 🟢.
- Админский REST под scope `architecture.admin` и allow-list `azp` 🟢.
- Единый путь админской операции: замок, аудит `STARTED` → `SUCCEEDED`/`FAILED` 🟢.
- Метрики `archrag.ingestion.*`, `archrag.normalizer.unmapped.values` 🟢.

## Regras de Negócio

- Одновременно выполняется одна админская операция; диспетчер и админские операции делят один PostgreSQL advisory lock `0x41524348_41444D31L` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminLock.java:19`, `:32-56`).
- Диспетчер отдаёт замок после каждой страницы; занятый замок завершает проход, а не ждёт 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:99-128`).
- Если событие объекта `(source, sourceType, sourceId)` ушло в `RETRYING` или бросило исключение, более поздние события этого объекта в том же проходе пропускаются 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:113-121`).
- Строка без `payloadRef` и строка с невалидным raw уходят в DLQ из диспетчера с кодами `NO_RAW_PAYLOAD` / `MARKER_NO_RAW` / `INVALID_RAW_PAYLOAD` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:47-49`, `:142-154`).
- Ошибки raw storage (нет объекта, расхождение hash) и журнала в DLQ не ведут: событие остаётся в прежнем статусе, метрика `ERROR`, повтор в следующем проходе 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:147-148`, `:156-162`).
- Админская операция без записи `STARTED` в аудит не выполняется; сбой записи итога после успеха клиенту не отдаётся 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:45-61`).
- В аудит и лог идёт только простое имя класса исключения, не текст 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:52-53`).
- `rebuild` требует `confirm=true`; `replayId` — только UUID 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:85-88`, `:140-149`).
- Crosswalk-элементы независимы; `approvedBy` — `sub` токена, `approvedAt` — серверное время 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:48`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkItem.java:3-7`).
- Токен принимается, только если `azp` входит в `archrag.admin.allowed-clients`; пустой список запрещён 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AuthorizedPartyValidator.java:26-31`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminProperties.java:15-19`).
- 🟡 Корректность держится на допущении «один экземпляр сервиса, один поток диспетчера» (Javadoc, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:24`); кодом оно не защищено (замок защищает страницу, но не мешает второму экземпляру взять следующую).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Диспетчер обрабатывает `pending`-страницы keyset-ом `(receivedAt, source, eventId)` до пустой страницы, размер страницы `batchSize` | Must | [`dispatch-journal`](dispatch-journal/requirements.md) AC-01; `JournalDispatcherTest.processesInJournalOrderAcrossPages` |
| RF-02 | Объект, чьё событие `RETRYING` или бросило исключение, пропускается до конца прохода | Must | AC-02; `JournalDispatcherTest.laterEventsOfObjectAreSkippedAfterRetrying` |
| RF-03 | Битый raw / нет raw / нет raw у маркера → `QUARANTINED` + `dlq_entry` | Must | AC-03; `JournalDispatcherTest.invalidRawGoesToDlqAndNextEventIsProcessed`, `missingRawGoesToDlqWithCodeByType` |
| RF-04 | Занятый `AdminLock` завершает проход диспетчера без чтения журнала | Must | AC-04; `JournalDispatcherTest.busyAdminLockSkipsPassWithoutReadingJournal` |
| RF-05 | `POST /admin/replay` переигрывает диапазон журнала под новым `replayId` | Must | [`replay-rebuild`](replay-rebuild/requirements.md) AC-01; `AdminEndpointIT.replayIsIdempotentForSameReplayIdAndRestoresLostProjection` |
| RF-06 | `POST /admin/rebuild?confirm=true` стирает граф и переигрывает весь журнал | Must | AC-03; `AdminEndpointIT.rebuildOnWipedGraphGivesEquivalentGraphAndAuditsIt` |
| RF-07 | `POST /admin/reconcile/{source}` запускает snapshot в adapter-сервисе и пробрасывает ответ | Should | [`reconcile-source`](reconcile-source/requirements.md) AC-01; `AdminControllerTest.reconcileProxiesToAdapterAndPassesStatusAsIs` |
| RF-08 | `POST /admin/crosswalks` применяет 1..1000 пар поэлементно с исходами `APPLIED`/`CONFLICT`/`INVALID` | Must | [`load-crosswalk`](load-crosswalk/requirements.md) AC-01; `AdminEndpointIT.crosswalkAppliesValidItemAndReportsConflictAndInvalidPerItem` |
| RF-09 | Любая админская операция: замок, аудит `STARTED`→итог, `409` при занятом замке | Must | Раздел «Критерии приёмки» ниже; `AdminOperationsTest`, `AdminEndpointIT.concurrentAdminOperationIsRejectedWith409` |
| RF-10 | `/admin/**` требует scope `architecture.admin`, audience и `azp`; всё остальное `denyAll` | Must | AC-05..AC-07 ниже; `AdminControllerTest`, `AuthorizedPartyValidatorTest`, `AdminEndpointIT` |
| RF-11 | Метрики событий, лага, открытой DLQ, неотображённых словарных значений | Should | `IngestionMetricsTest`, `IngestionMetricsIT` |
| RF-12 | `StoredEventReader` восстанавливает событие так же, как `EventMapper` адаптеров | Must | `StoredEventReaderContractTest` |
| RF-13 | Старт приложения применяет миграции, схему Neo4j и миграцию темпоральности, создаёт бакет | Must | косвенно `JournalDispatcherIT`, `AdminEndpointIT` (поднимают контекст) 🟡 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Только scope `architecture.admin`; без токена 401, без scope 403; остальные пути `denyAll`; сессии `STATELESS`; CSRF отключён | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:37-45` | 🟢 |
| Безопасность | audience обязателен, нет значения по умолчанию | `ingestion/ingestion-service/src/main/resources/application.yml:16-17` | 🟢 |
| Безопасность | Секреты Neo4j/S3 не попадают в `toString` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/Neo4jProperties.java:9-12`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/S3Properties.java:9-12` | 🟢 |
| Безопасность | Тексты исключений и значения источников не попадают в лог/аудит/ответы | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java:15-17, 52-53`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:157-160`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/BadRequestException.java:3` | 🟢 |
| Безопасность | Теги метрик ограничены перечислениями | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:27-29, 150-154` | 🟢 |
| Производительность | Snapshot-вызов адаптера: connect 5 с, запрос 10 мин | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java:20, 23, 60` | 🟢 |
| Производительность | Replay: страницы по 200; rebuild: удаление пачками по 10000 строк | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:38`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:18` | 🟢 |
| Производительность | Пауза диспетчера 1 с, страница 100 (1..1000), задержка повтора 30 с | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherProperties.java:18-22, 31-33` | 🟢 |
| Disponibilidade | Остановка: текущее событие дорабатывает, ожидание до `shutdown-timeout` (30 с), затем `shutdownNow` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:55-71` | 🟢 |
| Disponibilidade | Исключение прохода не отменяет следующих запусков | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:42-52` | 🟢 |
| Disponibilidade | Сбой обновления gauges оставляет прежние значения | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:128-147` | 🟢 |
| Escalabilidade | Один поток и один экземпляр; масштабирование не поддержано | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:24`; `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java:36-44` | 🟢 |
| Observabilidade | Метрики экспортируются OTLP, по умолчанию выключено; `service.name=ingestion` | `ingestion/ingestion-service/src/main/resources/application.yml:50-61` | 🟢 |
| Эксплуатация | Админский порт 8081 по умолчанию, наружу не публикуется | `ingestion/ingestion-service/src/main/resources/application.yml:2-4` | 🟢 |

> Inferido a partir do código. Validar с эксплуатацией: SLO в модуле не заданы 🟢 [R.8: ответ владельца, вопрос 12] (см. [`questions.md`](questions.md) Q-08).

## Критерии приёмки (сквозные)

```gherkin
# AC-05: безопасность, позитив
Дано запущено приложение с allowed-clients = [archrag-demo] и audience = urn:archrag:admin
Когда клиент archrag-demo присылает JWT со scope architecture.admin и вызывает POST /admin/rebuild?confirm=true
Тогда запрос допускается к операции, актор аудита = sub токена

# AC-06: безопасность, негатив
Дано токен без scope architecture.admin (например, только architecture.read)
Когда вызывается любая операция /admin/**
Тогда ответ 403 и ни один сервис (replay/rebuild/crosswalk/adapters) не вызван      # AdminControllerTest.withoutAdminScopeIs403

Дано токен без Authorization или с чужим audience
Когда вызывается /admin/**
Тогда ответ 401                                   # AdminControllerTest.withoutTokenIs401; AdminEndpointIT.tokenForForeignAudienceIsUnauthorized

# AC-07: azp
Дано токен консоли (azp = клиент консоли) с audience админки и scope architecture.admin
Когда вызывается /admin/replay
Тогда ответ 401 (azp не в allow-list)             # AdminEndpointIT.tokenOfForeignClientIsUnauthorizedEvenWithAdminAudienceAndScope

# AC-08: аудит и замок
Дано идёт админская операция (замок взят)
Когда приходит вторая админская операция
Тогда 409 {"error":"another admin operation is in progress"} и запись аудита не создаётся   # AdminEndpointIT.concurrentAdminOperationIsRejectedWith409

Дано сбой записи STARTED в admin_audit
Когда вызывается операция
Тогда тело операции не выполняется                # AdminOperationsTest.auditStartFailureMeansOperationIsNotExecuted

Дано операция бросила IllegalStateException("secret value")
Когда AdminOperations.run перехватывает её
Тогда admin_audit получает FAILED с error = "IllegalStateException", исключение пробрасывается   # AdminOperationsTest.failureAndErrorAreRecordedAsFailedWithClassNameOnly
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| Диспетчер журнала (RF-01..04) | Must | Единственный путь `RECEIVED` → граф |
| Замок + аудит + security админки (RF-09, RF-10) | Must | Защита записывающего REST (ADR 0013/0026) |
| Replay / rebuild / crosswalk (RF-05, 06, 08) | Must | Восстановление витрины и подтверждение идентичности |
| `StoredEventReader` (RF-12) | Must | Без него нет ни диспетчера, ни replay |
| Reconcile-прокси (RF-07) | Should | Ручной триггер snapshot; удаления делает `Reconciler` |
| Метрики (RF-11) | Should | Дашборд PoC, не влияет на корректность |
| Эскалация `RETRYING` → `QUARANTINED` по числу попыток | Won't | Не реализовано (🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] Q-01) |
| Выход из `QUARANTINED` через REST | Won't | Нет в коде (🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] Q-02) |

## Зависит от / Связанные спецификации

См. раздел «Зависит от / Связанные спецификации» в [`design.md`](design.md).

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java` | `JournalDispatcher` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/DispatcherRunner.java`, `DispatcherProperties.java` | запуск и настройки диспетчера | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java` | `StoredEventReader` | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java` | REST | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminOperations.java`, `AdminLock.java`, `AdminAudit.java` | замок, аудит | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java`, `RebuildService.java` | replay/rebuild | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java`, `CrosswalkItem.java`, `CrosswalkResult.java` | crosswalk | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdapterControlClient.java`, `AdaptersProperties.java`, `AdapterUnavailableException.java` | reconcile-клиент | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java`, `AuthorizedPartyValidator.java`, `AdminProperties.java` | безопасность | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java` | метрики | 🟢 |
| `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java`, `Neo4jProperties.java`, `S3Properties.java`, `IngestionServiceApplication.java` | сборка | 🟢 |
| `ingestion/ingestion-service/src/main/resources/application.yml`, `db/migration/V3__admin_audit.sql`, `db/migration/V6__console_readonly_role.sql` | конфигурация, миграции | 🟢 |
