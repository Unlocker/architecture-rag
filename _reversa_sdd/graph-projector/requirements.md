# ingestion/graph-projector: требования

> Writer R.6a. Алиасы путей: `GP` = `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector`, `GPT` = `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector`, `IT` = `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests`, `CM` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel`, `ES` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas`, `IS` = `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice`. Метки: 🟢 подтверждено кодом, 🟡 выведено, 🔴 пробел. Use case: [`process-event/`](process-event/requirements.md), [`project-request/`](project-request/requirements.md), [`deferred-relations/`](deferred-relations/requirements.md), [`reconcile-missing-set/`](reconcile-missing-set/requirements.md). Контракты: [`contracts.md`](contracts.md); вопросы: [`questions.md`](questions.md); решения: [`decisions.md`](decisions.md).

## Обзор

Модуль превращает событие журнала в состояние графа Neo4j. Он (1) ведёт одно событие от `RECEIVED` до конечного статуса (`EventProcessor`), (2) применяет `ProjectionRequest` одной транзакцией записи (`GraphProjector`), (3) хранит и достраивает отложенные связи (`PendingRelations`), (4) обрабатывает missing set после маркера `snapshot-complete` (`Reconciler`) и (5) применяет схему Neo4j (`Neo4jSchema`) и разовую миграцию темпоральности (`TemporalRelationMigration`) 🟢 (`GP/EventProcessor.java:46-80`, `GP/GraphProjector.java:35-80`, `GP/Reconciler.java:27-57`). Граф — производная read-only витрина (ADR [0001](../../docs/adr/0001-neo4j-derived-read-model.md)); единственный писатель в него — этот модуль под учёткой writer 🟢 (`CLAUDE.md`, раздел «Ключевые инварианты»; `IS/IngestionServiceConfiguration.java:85-86`).

Состав: 21 Java-файл main (~2700 строк) + 7 тестовых классов (в модуле против настоящего Neo4j **не тестируется ничего**: `GraphProjector` покрывают IT платформы) 🟢 (`ls`/`wc -l` модуля; `IT/GraphProjectorIT.java`, `IT/ReconciliationIT.java`).

## Ответственности

- Маршрутизировать событие по `type`: `asset.upserted` → нормализация и проекция; `asset.deleted` → tombstone без нормализатора; `sync.snapshot-complete` → передача в `ReconciliationTrigger` (граф не пишется); иное → `QUARANTINED`/`UNSUPPORTED_EVENT_TYPE` 🟢 (`GP/EventProcessor.java:185-199`).
- Решить идемпотентность по версии объекта (`DUPLICATE`, `IGNORED_OLD_VERSION`) до записи и повторить решение в транзакции Neo4j под блокировкой записи 🟢 (`GP/EventProcessor.java:303-344`, `GP/GraphProjector.java:305-324`).
- Получить `gid` (только от `IdentityMapping`) и передать кандидатов, утверждения свойств и сверки конфликтов в PostgreSQL до транзакции Neo4j 🟢 (`GP/EventProcessor.java:225-251, 569-601`).
- Записать в граф `SourceRecord`, канонический узел, `ASSERTS`, связи (с authority и темпоральностью), tombstone, маркеры конфликтов, `SyncRun` — одной `executeWrite` 🟢 (`GP/GraphProjector.java:106-111, 302-359`).
- Заместить темпоральные связи записи при новой версии и закрыть их при tombstone 🟢 (`GP/GraphProjector.java:507-550, 611-647`).
- Хранить связи с неизвестным концом (`DeferRelation` → `PendingRelation`) и достраивать их при проекции конечной точки, в том числе через alias 🟢 (`GP/GraphProjector.java:361-415`, `GP/PendingRelations.java:24-134`).
- После `snapshot-complete` поставить tombstone активным записям источника, которых нет в прогоне, фиксируя tombstone в журнале и S3 до записи в граф 🟢 (`GP/Reconciler.java:100-144`).
- Поддерживать идемпотентную схему Neo4j Community (UNIQUE `gid` по каждой канонической метке, ключи `SourceRecord`, `SourceSystem`, `SyncRun`, `PendingRelation`, `Environment`; индекс `ITSystem.eamCode`; fulltext `asset_text`) 🟢 (`GP/schema/Neo4jSchema.java:41-60`).
- Отдавать порты чтения графа для нормализатора и сверок: `isActive`, `applied`, `activeRecords`, `DeploymentLookup`, `EamLookup` 🟢 (`GP/GraphProjector.java:113-300`).

Вне модуля 🟢: нормализация ([`../normalizer/`](../normalizer/requirements.md)), `gid`/crosswalk/кандидаты/конфликты в PG ([`../identity-resolution/`](../identity-resolution/requirements.md)), журнал и DLQ ([`../event-journal/`](../event-journal/requirements.md)), статусы и `SourceVersion` ([`../event-schemas/`](../event-schemas/requirements.md)), модель и authority ([`../canonical-model/`](../canonical-model/requirements.md)), цикл диспетчера, replay, rebuild, admin REST (`ingestion-service`), чтение графа (`access/graph-query-core`).

## Регламент (бизнес-правила)

- BR-01. Маршрут по `type` идёт до `Normalizer`; в нормализатор попадает только `architecture.asset.upserted.v1` 🟢 (`GP/EventProcessor.java:47-53, 193-198`).
- BR-02. Статусы проходят по одному шагу `RECEIVED → VALIDATED → NORMALIZED → RESOLVED → PROJECTED`; из `RETRYING` допустим прыжок в любой этап; повторный `advance` в уже достигнутый статус — no-op 🟢 (`GP/EventProcessor.java:603-610`; тест `EventProcessorTest#upsertWalksStatusesStepByStepAndSavesCheckpointAfterWrite`).
- BR-03. События в конечных статусах (`PROJECTED`, `DUPLICATE`, `IGNORED_OLD_VERSION`, `QUARANTINED`) повторно не обрабатываются: возвращается текущий статус 🟢 (`GP/EventProcessor.java:99-105, 190-192`; тест `finishedEventIsNotProcessedTwice`).
- BR-04. Событие, которого нет в журнале, — `IllegalArgumentException("event is not in the journal")`, без изменения состояния 🟢 (`GP/EventProcessor.java:186-189`; тест `eventMissingFromJournalIsRejected`).
- BR-05. Идемпотентность решает версия объекта, а не `eventId`: равная версия под другим `eventId` — `DUPLICATE`, ниже — `IGNORED_OLD_VERSION`, выше — применить; версии сравниваются как числа (`99 < 184`) 🟢 (`GP/VersionDecision.java:8-13, 20-26`; `ES/SourceVersion.java:19-21`; тесты `VersionDecisionTest#versionsAreComparedAsNumbersNotStrings`, `EventProcessorTest#sameVersionUnderAnotherEventIdIsDuplicateAndDoesNotTouchGraph`).
- BR-06. Tombstone с равной версией применяется к ещё активной записи (удаление не обязано повышать версию), к уже неактивной — `SAME` 🟢 (`GP/VersionDecision.java:28-38`; тест `VersionDecisionTest#tombstoneWithEqualVersionAppliesOnlyToActiveRecord`).
- BR-07. `RETRYING`-событие перед записью в PG отсекается, если применена более новая версия: иначе устаревшие утверждения затёрли бы свежие 🟢 (`GP/EventProcessor.java:324-344`; тест `retriedOlderVersionDoesNotOverwriteAssertionsOfNewerOne`). Оговорка: признаки кандидатов пишутся **до** этой проверки 🟡 (см. [`questions.md`](questions.md) Q-10).
- BR-08. `gid` выдаёт только `IdentityMapping.resolve` — и только для ключа самого события; чужой конец связи берётся `find`, и без `gid` событие уходит в `RETRYING`/`UNRESOLVED_ENDPOINT`, а не создаёт `gid` 🟢 (`GP/EventProcessor.java:587-599`).
- BR-09. Matching кандидатов, утверждения свойств и сверки конфликтов выполняются вне транзакции Neo4j; `gid` и граф они не меняют; сбой хранилища — `RETRYING` с кодом `IDENTITY_CANDIDATE_FAILED`/`SOURCE_CONFLICT_FAILED`, граф не тронут 🟢 (`GP/EventProcessor.java:55-71, 231-249`; тесты `candidateStoreFailureRetriesBeforeGraphIsTouched`, `conflictStoreFailureRetriesWithoutTouchingGraphOrCheckpoint`).
- BR-10. Checkpoint `projector` (ключ — `source`, курсор — `eventId`) сохраняется только после перехода события в конечный статус (после коммита Neo4j); при `RETRYING`/`QUARANTINED` не двигается 🟢 (`GP/EventProcessor.java:84, 612-627`; тест `graphOutageMovesEventToRetryingWithoutCheckpoint`).
- BR-11. Ошибки не глушатся: нормализация/граф → `QUARANTINED`/`RETRYING` с кодом и причиной без значений из источника; неожиданные исключения и ошибки журнала пробрасываются, статус остаётся прежним, повтор безопасен 🟢 (`GP/EventProcessor.java:72-74, 359-373`).
- BR-12. Транзакция проекции — единственный `executeWrite`; внутри только Neo4j (ни PG, ни S3, ни журнала); `gid` приходят готовыми 🟢 (`GP/GraphProjector.java:40-41, 106-111`).
- BR-13. `MERGE` только по ключам под constraint: `SourceRecord(source, sourceType, sourceId)`, `<RootLabel>.gid`, `SourceSystem.code`, `SyncRun.runId`, `PendingRelation.key`; связи `MERGE`-ятся только между уже найденными по ключу узлами 🟢 (`GP/GraphProjector.java:42-44, 305-308, 451-461, 577-589`).
- BR-14. Версия сравнивается внутри транзакции после блокировки записи (`MERGE … SET r._lock = true REMOVE r._lock`), поэтому параллельные проекции одного объекта не обгоняют друг друга; равная версия ничего не пишет 🟢 (`GP/GraphProjector.java:45-46, 304-324`; IT `concurrentUpsertsOfOneObjectKeepOneRecordAtTheHighestVersion`).
- BR-15. Свойство узла пишется, только если источник авторитетен для него по `AuthorityMatrix`; связь — если авторитетен для типа; пропущенное возвращается в `ProjectionResult.skippedProperties/skippedRelations` (`Label.property`, имя типа) 🟢 (`GP/GraphProjector.java:422-432, 559-563`; IT `nonAuthoritativeSourceDoesNotReplaceCanonicalValue`, `nonAuthoritativeRelationIsSkippedAndReported`).
- BR-16. `null` никогда не записывается: `NodeProperties.of` отбрасывает `null`; неполная запись не затирает известные поля 🟢 (`GP/NodeProperties.java:16-17, 34-36`; IT `partialRecordDoesNotEraseKnownFields`).
- BR-17. Темпоральная связь открывается заново, если новая или закрыта (`validFrom` источника, иначе время события); открытая сохраняет `validFrom`; повторное утверждение даёт `validTo = $validTo` (обычно `null`) 🟢 (`GP/GraphProjector.java:566-575`; IT `temporalRelationGetsValidFromAndIsNotReopenedByRepeat`, `hostReturningInLaterVersionReopensRunsOn`).
- BR-18. Новая версия записи замещает её темпоральные связи: для каждого типа, который версия утверждает (`UpsertRelation`) или откладывает (`DeferRelation`), открытые связи **этой же записи** вне набора троек `(from.gid, to.gid, identity props)` закрываются на `eventTime`; тип без утверждений в версии не затрагивается; tombstone и повтор версии ничего не замещают 🟢 (`GP/GraphProjector.java:52-54, 604-647`; IT `replacedHostClosesOldRunsOnAtEventTimeAndKeepsNewOpen`, `partialRecordWithoutHostsClosesNothing`, `repeatedVersionClosesNothing`).
- BR-19. Физического удаления нет: tombstone ставит `SourceRecord.active=false`, `deletedAt`, закрывает открытые темпоральные связи записи и архивирует канонический узел (`isCurrent=false`, `deletedAt`) только если нет других активных **MASTER**-утверждений 🟢 (`GP/GraphProjector.java:507-550`; IT `tombstoneKeepsNodeWhileAnotherActiveMasterAssertionRemains`).
- BR-20. Подтип узла может смениться (`ComputeInstance` → `VirtualMachine`) без дубля: `MERGE` идёт по корневой метке, прочие подтипы корня снимаются 🟢 (`GP/GraphProjector.java:442-457, 729-745`; IT `computeInstanceSubtypeChangeKeepsOneNode`).
- BR-21. Маркеры конфликтов — полный набор для `gid`: `ASSERTS.conflicts` ставится на рёбра записей из набора и снимается с остальных; значения свойств в граф не попадают; `ConflictMarkers.none()` — «не трогать» 🟢 (`GP/ConflictMarkers.java:12-21`, `GP/GraphProjector.java:467-491`).
- BR-22. Отложенная связь хранится узлом `PendingRelation` и заменяется целиком при каждой применённой проекции записи; tombstone её удаляет; достраивается только пока запись-источник активна и имеет ту версию, с которой связь отложена 🟢 (`GP/GraphProjector.java:327-328`, `GP/PendingRelations.java:81-106`; IT `newReferrerVersionReplacesDeferredRelation`, `tombstoneOfReferrerRemovesDeferredRelation`).
- BR-23. Удалять по missing set можно только после маркера `snapshot-complete` и только если журнал содержит не меньше событий прогона, чем заявлено `objectCount` (маркер без `objectCount` — карантин; прогон `PARTIAL` не удаляет; missing set больше доли `archrag.reconcile.max-missing-share` — карантин, UNLOCKER-279); объект, получивший любое событие после начала прогона, не удаляется 🟢 (`GP/Reconciler.java:105-113, 122-129`; ловушка №9 `CLAUDE.md`; тесты `ReconcilerTest#incompleteRunInJournalDeletesNothing`, `objectTouchedAfterRunStartIsNotDeleted`).
- BR-24. Tombstone `Reconciler` идёт с применённой версией записи и до записи в граф фиксируется событием `architecture.asset.deleted.v1` (`eventId = reconcile:<syncRunId>:<sourceType>/<sourceId>`) и raw в S3 — replay и rebuild воспроизводят удаление 🟢 (`GP/Reconciler.java:42-48, 130-136, 156-182`).
- BR-25. Время хранится как `Instant` → UTC `OffsetDateTime` (в Neo4j `datetime`) 🟢 (`GP/NodeProperties.java:57-59`).
- BR-26. Cypher только с параметрами; в текст подставляются лишь метки из `NodeLabel`, типы связей и имена identity-свойств из `RelationType`; границ переменной длины пути в модуле нет 🟢 (`GP/GraphProjector.java:78-79, 442-447, 578-589, 637`).
- BR-27. Сообщения исключений и причины журнала не содержат значений из источника 🟢 (`GP/ProjectionException.java:3-6`, `GP/EventProcessor.java:72-74, 235, 248`).
- BR-28. Схема Neo4j применяется только функциями Community: UNIQUE, индекс, fulltext; без NODE KEY, existence constraints, vector index; каждый statement — отдельная транзакция записи, ошибки не глушатся 🟢 (`GP/schema/Neo4jSchema.java:13-20, 62-75`; тест `Neo4jSchemaTest#noEnterpriseOrOutOfScopeFeatures`).

## Требования функциональные

| ID | Требование | Приоритет | Критерий |
|----|-----------|-----------|----------|
| RF-01 | `EventProcessor.process` ведёт `upserted`-событие до `PROJECTED` и сохраняет checkpoint только после записи | Must | [`process-event/`](process-event/requirements.md) AC-01; `upsertWalksStatusesStepByStepAndSavesCheckpointAfterWrite` 🟢 |
| RF-02 | `delete` превращает `asset.deleted` в tombstone без `Normalizer`, проверяя `dataschema` | Must | `deleteBecomesTombstoneRequestWithoutNormalizer`, `deleteWithUnknownSchemaIsQuarantined` 🟢 |
| RF-03 | Отсечь повтор/устаревшее до записи (`DUPLICATE`, `IGNORED_OLD_VERSION`) | Must | `sameVersionUnderAnotherEventIdIsDuplicateAndDoesNotTouchGraph`, `olderVersionIsIgnoredBeforeWrite` 🟢 |
| RF-04 | Перевести ошибки графа и PG в `RETRYING`/`QUARANTINED` по таблице кодов | Must | `securityAndConstraintErrorsRetryButStatementErrorsQuarantine`, `graphOutageMovesEventToRetryingWithoutCheckpoint` 🟢 |
| RF-05 | `GraphProjector.project`: одна транзакция, блокировка записи, решение по версии, команды в порядке списка | Must | [`project-request/`](project-request/requirements.md); IT `upsertCreatesNodeWithProvenanceAndMovesEventToProjected`, `replayDoesNotChangeGraph` 🟢 |
| RF-06 | Авторитетность свойств и связей, `skipped*` в результате | Must | IT `nonAuthoritativeSourceDoesNotReplaceCanonicalValue` 🟢 |
| RF-07 | Замещение и закрытие темпоральных связей, tombstone | Must | IT `changedOwnerTeamClosesOldOwnedBy`, `tombstoneClosesStructuralEdges` 🟢 |
| RF-08 | Отложенные связи и достраивание (в т.ч. alias) | Must | [`deferred-relations/`](deferred-relations/requirements.md); IT `dependencyBeforeBothServicesAppearsAfterSecondService` 🟢 |
| RF-09 | Reconciliation по `snapshot-complete` | Must | [`reconcile-missing-set/`](reconcile-missing-set/requirements.md); `ReconciliationIT` 🟢 |
| RF-10 | Маркеры конфликтов на `ASSERTS` | Should | `assertsPropertiesBeforeGraphWriteAndPassesMarkersToProjector` 🟢 |
| RF-11 | Сверки `Deployment.eamSystemId` и `ITSystem.platformId` (ADR [0030](../../docs/adr/0030-deployment-system-conflict.md), [0031](../../docs/adr/0031-it-system-platform-invariant.md)) и их перепроверка после записи сервиса/решения | Should | `deploymentWithChartSystemIsComparedWithSystemOfItsService`, `systemEventComparesPlatformWithSolutionAndPassesBothSides`, `solutionEventRechecksAllItsSystemsAfterGraphWrite` 🟢 |
| RF-12 | Схема Neo4j идемпотентна и порождается из `NodeLabel.canonical()` | Must | `Neo4jSchemaTest#everyCanonicalLabelHasGidConstraint`, `everyStatementIsIdempotent` 🟢 |
| RF-13 | `TemporalRelationMigration.migrate` проставляет `validFrom` связям без него (ADR [0028](../../docs/adr/0028-structural-relations-temporal.md)) | Could | IT `temporalRelationMigrationFillsMissingValidFromAndIsIdempotent` 🟢 |
| RF-14 | Передавать предупреждения нормализации в порт `NormalizationWarnings` (один раз, только непустые) | Could | `unmappedCriticalityIsReportedToWarningsPortOnce`, `eventWithoutWarningsDoesNotCallWarningsPort` 🟢 |
| RF-15 | Обработка `CloseAssertion` в проекторе | Won't (мёртвый путь: никто не создаёт) | 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 5)] [`questions.md`](questions.md) Q-03 |

## Требования нефункциональные

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Консистентность | Версия сравнивается под явной блокировкой записи в транзакции Neo4j | `GP/GraphProjector.java:304-310` | 🟢 |
| Надёжность | Функция транзакции `executeWrite` повторяется драйвером при транзиентных ошибках, поэтому внутри нет побочных эффектов вне Neo4j | `GP/GraphProjector.java:40-41, 108-110`; счётчики `SyncRun` не инкрементируются именно из-за повтора `GP/GraphProjector.java:694` | 🟢 |
| Надёжность | Сессии открываются на операцию в `try-with-resources`; драйвер один (внедряется) | `GP/GraphProjector.java:101-110, 114-116`; `GP/TemporalRelationMigration.java:47` | 🟢 |
| Конкурентность | Допущение «один писатель»: однопоточный диспетчер, один экземпляр сервиса, страницы под `AdminLock`; повторной проверки после записи отложенной связи нет | `GP/GraphProjector.java:65-67` (исполнимость — `IS/AdminLock`, вне модуля) | 🟡 |
| Масштабируемость | `activeRecords` читает все активные записи источника в память — допущено для PoC (сотни–тысячи объектов) | `GP/GraphProjector.java:146-147` | 🟢 |
| Производительность | Закрытие связей по типу без индекса (`MATCH (a)-[rel:TYPE]->(b)`), как и при tombstone — допущено для PoC | `GP/GraphProjector.java:528-541, 632-646` | 🟢 |
| Безопасность | Доменные метки и типы связей — из enum (allowlist), значения — параметрами | `GP/GraphProjector.java:78-79, 578-589` | 🟢 |
| Безопасность | Секреты не используются в модуле; учётные данные Neo4j приходят через внедряемый `Driver` | `GP/GraphProjector.java:101-104` | 🟢 |

> Таймауты, пулы и лимиты запросов в модуле не заданы — строк о них нет (по правилу «нет evidence — не писать»).

## Критерии приёмки (модульный уровень)

Сценарные критерии — в use case. Здесь — сквозные.

```gherkin
Дано событие asset.upserted.v1 версии 5 объекта EAM/IT_SYSTEM/10, уже применённой в графе
Когда то же изменение приходит под другим eventId (webhook после polling)
Тогда событие получает DUPLICATE, граф не меняется, checkpoint projector сдвигается на это eventId

Дано граф Neo4j недоступен (ServiceUnavailableException)
Когда EventProcessor.process доходит до записи
Тогда событие переходит в RETRYING с кодом GRAPH_UNAVAILABLE, checkpoint не сдвигается, исключение наружу не выходит

Дано событие с type, которого модуль не знает
Когда вызывается process
Тогда QUARANTINED/UNSUPPORTED_EVENT_TYPE, Normalizer не вызывается

Дано два экземпляра ProjectionRequest одного ключа с разными версиями, пришедшие в обратном порядке
Когда проектор применяет оба
Тогда в графе остаётся запись с наибольшей версией (outOfOrderDeliveryConvergesToTheNewestVersion)
```

## Приоритеты (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| Транзакция проекции, версия, MERGE по ключам, tombstone | Must | Единственный путь записи в граф |
| Статусная машина `EventProcessor`, коды ошибок, checkpoint | Must | Без неё нет идемпотентности и повтора |
| Reconciler | Must | Единственный источник удалений (ловушка №9) |
| Отложенные связи + alias | Must | Порядок прихода источников не гарантирован |
| Темпоральное замещение | Must | ADR 0028; без него в графе остаются устаревшие рёбра |
| Конфликты и сверки (маркеры, ADR 0030/0031) | Should | Не меняют граф, диагностика расхождений |
| `TemporalRelationMigration` | Could | Разовая миграция живого графа; rebuild её не требует |
| `CloseAssertion` | Won't | Не создаётся нигде в main-коде (Q-03) |

## Трассировка кода

| Файл | Класс / назначение | Покрытие |
|------|--------------------|----------|
| `GP/EventProcessor.java` | оркестратор события | 🟢 |
| `GP/GraphProjector.java` | транзакция проекции + порты чтения | 🟢 |
| `GP/PendingRelations.java`, `GP/PropertyJson.java` | отложенные связи, JSON свойств | 🟢 |
| `GP/Reconciler.java`, `GP/ReconciliationTrigger.java` | missing set | 🟢 |
| `GP/VersionDecision.java` | решение по версии | 🟢 |
| `GP/ProjectionRequest.java`, `GP/ProjectionResult.java`, `GP/ProjectionOutcome.java`, `GP/ProjectionException.java`, `GP/ProcessingResult.java`, `GP/ConflictMarkers.java`, `GP/NodeProperties.java` | типы обмена | 🟢 |
| `GP/GraphProjection.java`, `GP/EamLookup.java`, `GP/NormalizationWarnings.java` | порты | 🟢 |
| `GP/schema/Neo4jSchema.java`, `GP/TemporalRelationMigration.java` | схема и миграция | 🟢 |
| `GP/GraphProjectorModule.java`, `GP/package-info.java` | маркер модуля | 🟢 (без логики) |
