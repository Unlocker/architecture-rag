# ingestion/graph-projector: контракты

> Сокращения путей как в [`requirements.md`](requirements.md). Здесь — внешние для модуля форматы: форма графа, которую читают `access/graph-query-core` и консоль, схема Neo4j, статусы и коды ошибок журнала, checkpoint, события `reconcile:`/`deleted`, порты. Метки 🟢/🟡/🔴 как в требованиях.

## 1. Форма графа (то, что пишет проектор)

Сводка по `GP/GraphProjector.java` (номера строк — места записи); словарь данных всей системы — [`../database/data-dictionary.md`](../database/data-dictionary.md) (цитируется, не дублируется).

### Узлы

| Метка | Ключ (constraint) | Свойства, которые пишет модуль | Где |
|---|---|---|---|
| `SourceRecord` | `(source, sourceType, sourceId)` UNIQUE | `source` (= `SourceSystemCode.name()`, верхний регистр), `sourceType`, `sourceId`, `sourceVersion` (строка-число), `contentHash`, `fetchedAt` (datetime UTC), `active` (bool), `deletedAt` (datetime, снимается при upsert) | `GP/GraphProjector.java:448-452, 513-516, 713-716` 🟢 |
| `SourceSystem` | `code` UNIQUE | `code` = `$source` (`EAM`/`SCM`/`CMDB`/`DEPLOYMAP`) | `GP/GraphProjector.java:451` 🟢 |
| `SyncRun` | `runId` UNIQUE | `runId`, `adapter` (= `SourceSystemCode.name()` записи, при создании), `status = 'RUNNING'` (при создании), `startedAt` (минимум по `eventTime`) | `GP/GraphProjector.java:695-700` 🟢; `endedAt`, счётчики не пишутся 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 1)] Q-04 |
| Канонический узел (`ITSystem`, `Solution`, `Platform`, `Service`, `Repository`, `Team`, `Environment`, `Deployment`, `ComputeInstance`+подтипы `VirtualMachine`/`PhysicalServer`, `Namespace`, `KubernetesCluster`) | `gid` UNIQUE по каждой канонической метке | `gid` (строка UUID); `isCurrent`, `firstSeenAt` (минимум по `eventTime`), `lastSeenAt` (максимум), `deletedAt` (при архивации); авторитетные свойства `NodeData` (компоненты record'а, не `null`, `Instant` → datetime, enum → имя) | `GP/GraphProjector.java:422-462`, `GP/NodeProperties.java:25-55` 🟢 |
| `PendingRelation` | `key` UNIQUE | см. §6 | `GP/PendingRelations.java:53-80` 🟢 |

### Рёбра

| Ребро | Откуда → куда | Свойства | Где |
|---|---|---|---|
| `OWNS_RECORD` | `SourceSystem` → `SourceRecord` | — | `GP/GraphProjector.java:452, 502, 516` 🟢 |
| `ASSERTS` | `SourceRecord` → канонический узел | `confidence = 1.0`, `authority ∈ {MASTER, SUPPLEMENTARY}`, `conflicts` (список имён свойств; снимается `REMOVE`) | `GP/GraphProjector.java:441, 461-462, 483-489` 🟢 |
| `PROCESSED` | `SyncRun` → `SourceRecord` | — | `GP/GraphProjector.java:700` 🟢 |
| `DEFERS` | `SourceRecord` → `PendingRelation` | — | `GP/PendingRelations.java:77` 🟢 |
| доменные (`RelationType`) | канонический → канонический | свойства связи (`NodeProperties.of`), `assertedBySource`, `assertedByType`, `assertedById`; для темпоральных — `validFrom`, `validTo` (`null` = открыта) | `GP/GraphProjector.java:564-598` 🟢 |

Темпоральные типы (`RelationType.temporal() == true`): `USES_SOLUTION`, `USES_PLATFORM`, `DECOMPOSED_INTO`, `IMPLEMENTED_IN`, `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `RUNS_ON`, `DEPLOYED_FROM` (identity-свойство `commit`), `DEPENDS_ON`, `OWNED_BY`; нетемпоральные: `PART_OF`, `HOSTED_ON`, `ASSERTS`, `OWNS_RECORD`, `PROCESSED` 🟢 (`CM/relation/RelationType.java`, перечисление `RelationType`).

**Что читает `graph-query-core`/консоль** 🟡 (матрица §1, §2 № 4): `active`, `isCurrent`, `deletedAt`, `validTo IS NULL`, `ASSERTS.authority`, `ASSERTS.conflicts`, `sourceVersion`, `fetchedAt`, индекс `asset_text`. Любое переименование этих свойств — несовместимое изменение; страхует только `GraphQueryCoreIT`.

## 2. Решения проектора, видимые в графе

| Ситуация | Что в графе | Где |
|---|---|---|
| Новая версия записи | `SourceRecord.sourceVersion/contentHash/fetchedAt` обновлены, `active = true`, `deletedAt` снят | `GP/GraphProjector.java:448-452` 🟢 |
| Запись только со связями/пустая | `sourceVersion`, `active = true`, `OWNS_RECORD`; `contentHash`/`fetchedAt` **не** пишутся; `ASSERTS` нет | `GP/GraphProjector.java:493-505` 🟢 |
| Tombstone | `active = false`, `sourceVersion` = версия tombstone, `deletedAt = deletedAt` события; открытые темпоральные связи записи → `validTo = max(validFrom, at)`; узел `isCurrent = false`, `deletedAt = coalesce(deletedAt, at)` при отсутствии других активных MASTER | `GP/GraphProjector.java:507-550` 🟢 |
| Повторное утверждение закрытой связи | `validFrom` = из источника, иначе `eventTime`; `validTo = $validTo` (обычно `null`) | `GP/GraphProjector.java:571-575` 🟢 |
| Мастер архивированного узла вернулся | `isCurrent = true`, `deletedAt` снимается (только если запись — MASTER) | `GP/GraphProjector.java:460` 🟢 |

## 3. Схема Neo4j (`Neo4jSchema.statements`)

Точные statements, порядок стабилен, все `IF NOT EXISTS` 🟢 (`GP/schema/Neo4jSchema.java:41-60`). Исполняются по одному в отдельных `executeWrite` (`GP/schema/Neo4jSchema.java:66-75`).

1. По одному на каждую `NodeLabel.canonical()` в порядке `ordinal`: `CREATE CONSTRAINT <snake_case(label)>_gid IF NOT EXISTS FOR (n:<Label>) REQUIRE n.gid IS UNIQUE` — имена: `it_system_gid`, `solution_gid`, `platform_gid`, `service_gid`, `repository_gid`, `team_gid`, `environment_gid`, `deployment_gid`, `compute_instance_gid`, `virtual_machine_gid`, `physical_server_gid`, `namespace_gid`, `kubernetes_cluster_gid` (`GPT/schema/Neo4jSchemaTest.java#gidConstraintNamesMatchFixedList`).
2. `CREATE CONSTRAINT source_record_key IF NOT EXISTS FOR (n:SourceRecord) REQUIRE (n.source, n.sourceType, n.sourceId) IS UNIQUE`.
3. `CREATE CONSTRAINT source_system_code IF NOT EXISTS FOR (n:SourceSystem) REQUIRE n.code IS UNIQUE`.
4. `CREATE CONSTRAINT sync_run_id IF NOT EXISTS FOR (n:SyncRun) REQUIRE n.runId IS UNIQUE`.
5. `CREATE CONSTRAINT pending_relation_key IF NOT EXISTS FOR (n:PendingRelation) REQUIRE n.key IS UNIQUE`.
6. `CREATE CONSTRAINT environment_code IF NOT EXISTS FOR (n:Environment) REQUIRE n.code IS UNIQUE`.
7. `CREATE INDEX it_system_eam_code IF NOT EXISTS FOR (n:ITSystem) ON (n.eamCode)`.
8. `CREATE FULLTEXT INDEX asset_text IF NOT EXISTS FOR (n:ITSystem|Service) ON EACH [n.name, n.description]` — читается `search_assets`.

Ограничения 🟢: `IF NOT EXISTS` молча пропускает constraint с тем же именем, даже если определение изменилось — при смене определения имя должно меняться (`GP/schema/Neo4jSchema.java:25-26`); Community не проверяет существование свойств, `SourceKey` проверяет приложение (`GP/schema/Neo4jSchema.java:22-23`); NODE KEY, existence constraints, vector index не используются.

## 4. Журнал: статусы и коды ошибок, которые выставляет модуль

Таблица статусов и допустимых переходов — [`../event-schemas/contracts.md`](../event-schemas/contracts.md) и [`../event-journal/contracts.md`](../event-journal/contracts.md); здесь — только то, что ставит `EventProcessor`/`Reconciler`.

| Код (`errorCode`) | Статус | Когда | Evidence |
|---|---|---|---|
| `UNSUPPORTED_EVENT_TYPE` | `QUARANTINED` | `type` не из трёх известных | `GP/EventProcessor.java:197` 🟢 |
| `UNKNOWN_SOURCE` | `QUARANTINED` | `source` не `urn:corp:{eam,scm,cmdb,deploymap}` (для `upserted` и `deleted`; **не** для маркера) | `GP/EventProcessor.java:203-205, 256-258` 🟢 |
| `UNKNOWN_SCHEMA_VERSION` | `QUARANTINED` | `deleted` с `dataschema ≠ urn:corp:schema:asset-deleted:1` | `GP/EventProcessor.java:259-261` 🟢 |
| код `NormalizationResult.Quarantined` | `QUARANTINED` | отказ нормализатора | `GP/EventProcessor.java:214-216` 🟢 |
| `INVALID_COMMANDS` | `QUARANTINED` | `IllegalArgumentException` из `project` (инварианты `ProjectionRequest`) | `GP/EventProcessor.java:361-362` 🟢 |
| `GRAPH_REJECTED` (причина — код Neo4j) | `QUARANTINED` | `ClientException` с кодом `Neo.ClientError.Statement.*` или `Neo.ClientError.Request.*` | `GP/EventProcessor.java:365-369` 🟢 |
| `UNRESOLVED_ENDPOINT` | `RETRYING` | конец связи без `gid` в `resolveGids` либо отсутствует в графе в `upsertRelation` | `GP/EventProcessor.java:228-230, 596-597`, `GP/GraphProjector.java:599-601` 🟢 |
| `IDENTITY_CANDIDATE_FAILED` | `RETRYING` | `IdentityStoreException` при `record`/`forget` | `GP/EventProcessor.java:86, 233-236, 272-274` 🟢 |
| `SOURCE_CONFLICT_FAILED` | `RETRYING` | `IdentityStoreException` при утверждениях, `retract`, `recheck*` | `GP/EventProcessor.java:87, 246-249, 282-284, 378-381` 🟢 |
| `GRAPH_UNAVAILABLE` (причина — имя класса) | `RETRYING` | `TransientException`, `ServiceUnavailableException`, `SessionExpiredException` | `GP/EventProcessor.java:363-364` 🟢 |
| `GRAPH_CLIENT_ERROR` (причина — код Neo4j) | `RETRYING` | прочие `ClientException` (нарушение constraint, безопасность) | `GP/EventProcessor.java:370` 🟢 |
| `GRAPH_ERROR` (причина — имя класса) | `RETRYING` | прочие `Neo4jException` | `GP/EventProcessor.java:371-372` 🟢 |
| `RECONCILIATION_TRIGGER_FAILED` (причина — имя класса) | `RETRYING` | любое `RuntimeException` из `ReconciliationTrigger.snapshotComplete` | `GP/EventProcessor.java:293-298` 🟢 |

Конечные статусы, которые модуль выставляет: `PROJECTED`, `DUPLICATE`, `IGNORED_OLD_VERSION`, `QUARANTINED`. Для события, исчерпавшего `max-attempts`, `retry` выставляет `QUARANTINED` с кодом `RETRY_LIMIT_EXCEEDED` 🟢 [UNLOCKER-281]. `SUPERSEDED` удалён (Q-01). Коды `ProjectionException` приходят в `errorCode` как есть (`GP/EventProcessor.java:359-360`).

> `docs/02-data-model.md:273` перечисляет коды неполно (нет `IDENTITY_CANDIDATE_FAILED`, `SOURCE_CONFLICT_FAILED`, `RECONCILIATION_TRIGGER_FAILED`, `UNKNOWN_SCHEMA_VERSION`) 🟢 [R.8: ответ владельца, вопрос 7] Q-08.

## 5. Checkpoint

`consumer_checkpoint(consumer = "projector", source = <URN источника>, cursor = <eventId>)`; пишется `journal.saveCheckpoint` в `finish` после конечного статуса, включая `DUPLICATE` и `IGNORED_OLD_VERSION`; при `RETRYING`/`QUARANTINED` — нет 🟢 (`GP/EventProcessor.java:84, 612-617`). Курсор — `eventId`, не монотонная величина 🟡; читает его только консоль для отображения, поведение обработки от него не зависит 🟡 (Q-19).

## 6. События и форматы

### 6.1. Tombstone-событие reconciliation 🟢 (`GP/Reconciler.java:156-182`)

Строка журнала (`CanonicalEvent`):

| Поле | Значение |
|---|---|
| `id` | `reconcile:<syncRunId>:<sourceType>/<sourceId>` (`EVENT_ID_PREFIX = "reconcile:"`, `GP/Reconciler.java:61`) |
| `source` | URN источника из маркера |
| `type` | `architecture.asset.deleted.v1` (`GP/EventProcessor.java:92`) |
| `subject` | `<sourceType в нижнем регистре, '_'→'-'>/<sourceId>` |
| `time` | `clock.instant()` на момент reconcile |
| `dataschema` | `urn:corp:schema:asset-deleted:1` (`GP/EventProcessor.java:94`) |
| `correlationid` | `syncRunId` (тот же `syncRunId` передаётся в `journal.append(event, ref, syncRunId)`) |
| `data` | `AssetEventData(sourceType, sourceId, sourceVersion = применённая версия записи, payload = {})` |

Raw в S3 (`rawStore.put(source, bytes)`): строка JSON `{"sourceType":"…","sourceId":"…","sourceVersion":<число без кавычек>,"operation":"DELETE","completeness":"COMPLETE","updatedAt":"<now ISO>","payload":{}}`; строки экранируются вручную (`"`, `\`, управляющие символы `< 0x20` → `\u00XX`) 🟢 (`GP/Reconciler.java:196-220`). Совпадение с `InboxWriter.rawJson` для `DELETE` держится на комментарии, теста нет 🟡 (Q-07). Повтор находит существующую строку и не добавляет дубль (`journal.find` перед `append`, `GP/Reconciler.java:159-172`).

### 6.2. Входные события

| `type` | Что берётся из события | Evidence |
|---|---|---|
| `architecture.asset.upserted.v1` (`CanonicalEvent.TYPE_ASSET_UPSERTED`) | `source`, `data.sourceType/sourceId/sourceVersion`, `time` (→ `eventTime`), `id`, журнал: `syncRunId` | `GP/EventProcessor.java:193-194, 358` 🟢 |
| `architecture.asset.deleted.v1` | то же; `dataschema` обязан быть `urn:corp:schema:asset-deleted:1`; `time` → `deletedAt` tombstone | `GP/EventProcessor.java:254-287` 🟢 |
| `architecture.sync.snapshot-complete.v1` | `source`, `data.sourceId` (= `syncRunId`), `id` (маркера), `data.payload["objectCount"]` (обязателен: целое ≥ 0, иначе карантин `SNAPSHOT_OBJECT_COUNT_MISSING`), `data.payload["completeness"]` (`COMPLETE`/`PARTIAL`, нет поля = `COMPLETE`) | `GP/EventProcessor.java:289-300, 629-631` 🟢 (`dataschema` не проверяется 🟡 Q-05) |

### 6.3. Отложенная связь (`PendingRelation`)

Свойства узла 🟢 (`GP/PendingRelations.java:53-80`): `key` (SHA-256 hex от `length:part|` для `assertedBy.{source,sourceType,sourceId}`, `type`, `from.{…}`, `to.{…}`; `GP/PendingRelations.java:174-197`), `type`, `fromLabel`, `toLabel`, `fromSource/fromType/fromId`, `toSource/toType/toId`, `props` (JSON-строка, см. ниже), `validFrom`/`validTo` (datetime, если заданы), `referrerVersion`, `eventTime`. Ребро `(SourceRecord)-[:DEFERS]->(PendingRelation)`.

`PropertyJson` 🟢 (`GP/PropertyJson.java:9-20, 57-81`): ключи сортируются; значения — `String`, `Boolean`, `Long`, `Integer`, `Double`, `Instant` (как `{"$instant":"<ISO>"}`), список из них; другой тип → `IllegalArgumentException("unsupported property type …")`; целые читаются как `Long`, дробные как `Double`.

## 7. Порты (контракт с `ingestion-service` и `normalizer`)

| Порт | Реализация | Семантика |
|---|---|---|
| `GraphProjection` | `GraphProjector` | `project` — повтор безопасен; `applied`/`activeRecords` — только чтение; `activeRecords` не возвращает заблокированные `MERGE`-ом записи без версии (`GP/GraphProjection.java:9-41`) |
| `ReconciliationTrigger` | `Reconciler` (`NOOP` по умолчанию) | вызывается только маркером; `objectCount` — сколько событий записал адаптер (`GP/ReconciliationTrigger.java:8-20`) |
| `NormalizationWarnings` | `metrics::recordWarnings` | один вызов на успешную нормализацию, только при непустом списке, только коды (`GP/NormalizationWarnings.java:6-21`) |
| `DeploymentLookup` (из `normalizer`) | `GraphProjector` | активные записи и открытые связи; `eamSystemOfService` пуст при >1 системе (WARNING) (`GP/GraphProjector.java:215-235`) |
| `EamLookup` | `GraphProjector` | `solutionPlatform`, `systemsOfSolution` (`GP/EamLookup.java:14-41`) |
