# Data Dictionary → Словарь данных

> Archaeologist, этапы R.2a (UNLOCKER-263): разделы A (`canonical-model`), E (`event-schemas`), S (`source-spi`); R.2b (UNLOCKER-264): раздел P (`adapters/*`); R.2c (UNLOCKER-265): раздел Q (`ingestion/*`, в конце файла). Разделы остальных модулей добавят R.2d–R.2e.
> Столбец «Обяз.»: **да** — `null`/пустое отвергается конструктором; **нет** — допускается `null`, пустая строка отвергается (`Invariants.optionalText`, `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/Invariants.java:30-35`); **—** — проверки нет.
> Пути сокращены: `CM/` = `contracts/canonical-model/.../canonicalmodel/`, `ES/` = `contracts/event-schemas/.../eventschemas/`, `SPI/` = `contracts/source-spi/.../sourcespi/`. Метки: 🟢 код · 🟡 выведено · 🔴 пробел.
> Семантику узлов и связей смотри в `docs/02-data-model.md` §2; здесь только то, что утверждает код.

---

## A. canonical-model

### A.1 Узлы (`NodeData`, sealed, 11 вариантов — `CM/node/NodeData.java:9-11`)

**ITSystem** (`CM/node/ITSystem.java:13`) 🟢

| Поле | Тип | Обяз. | Правило |
|---|---|---|---|
| `name` | String | да | не пусто (`:17`) |
| `status` | String | нет | словарь источника (`:18`) |
| `criticality` | `Criticality` | нет | LOW/MEDIUM/HIGH/CRITICAL |
| `description` | String | нет | `:19` |
| `eamCode` | String | нет | `eam_id`, напр. `EAM-1042` (Javadoc `:7-11`) |
| `criticalityRaw` | String | нет | значение, не распознанное словарём; задаётся **вместо** `criticality` (Javadoc; взаимоисключение не проверяется) 🟡 |
| `platformId` | Long | нет | `> 0` (`:22-24`); равен платформе решения, ADR 0031 (проверка — не здесь) |

Конструкторы-перегрузки на 6 и 4 аргумента (`:28-36`).

**Solution** (`CM/node/Solution.java:12`) 🟢: `name`* , `lifecycle`, `description`, `platformId` (`> 0`, `:18-20`); перегрузка без `platformId` (`:24`).
**Platform** (`CM/node/Platform.java:10`) 🟢: `name`*, `type`, `version`, `lifecycle`, `description`.
**Service** (`CM/node/Service.java:7`) 🟢: `name`*, `serviceType`, `language`, `status` (свободный текст).
**Repository** (`CM/node/Repository.java:7`) 🟢: `url`*, `defaultBranch`, `archived` (`Boolean`; `null` = неизвестно, не перезаписывает известное; формат `url` конструктором **не** проверяется 🟡 — в отличие от `Deployment.repositoryUrl`).
**Team** (`CM/node/Team.java:7`) 🟢: `name`*, `type`.
**Environment** (`CM/node/Environment.java:8`) 🟢: `code`* (нормализованный ключ), `name`*, `environmentClass`* (`EnvironmentClass` DEV/TEST/PREPROD/PROD).

**Deployment** (`CM/node/Deployment.java:17`) 🟢

| Поле | Тип | Обяз. | Правило |
|---|---|---|---|
| `deploymentKey` | String | да | ключ идентичности (`:23`) |
| `name` | String | да | `:24` |
| `version`, `status` | String | нет | `:25-26` |
| `sourceCommit` | String | нет | `[0-9a-f]{40}` (`:20, 27-29`) |
| `repositoryUrl` | String | нет | должен равняться `GitUrls.normalize(x)` (`:30-32`) |
| `eamSystemId` | Long | нет | `> 0` (`:33-35`), `id` системы EAM, не `eam_id` (ADR 0024) |
| `observedAt` | Instant | нет | — |

**ComputeInstance** (`CM/node/ComputeInstance.java:15`) 🟢

| Поле | Тип | Обяз. | Правило |
|---|---|---|---|
| `hostname` | String | да | `:19` |
| `kind` | `ComputeKind` | да | VIRTUAL_MACHINE / PHYSICAL_SERVER / UNSPECIFIED; определяет метку (`:35-41`) |
| `ip`, `os`, `state` | String | нет | `:21-23` |
| `hypervisorRef` | String | нет | только при `kind=VIRTUAL_MACHINE` (`:26-28`) |
| `serialNumber` | String | нет | только при `kind=PHYSICAL_SERVER` (`:29-31`) |

**Namespace** (`CM/node/Namespace.java:6`) 🟢: `name`*. **KubernetesCluster** (`CM/node/KubernetesCluster.java:7`) 🟢: `name`*, `version`. Оба «смоделированы, не наполняются» (ADR 0011).

**CanonicalNode&lt;T&gt;** (`CM/node/CanonicalNode.java:7`) 🟢: `gid` UUID*, `data` T*, `lifecycle` `Lifecycle`*.
**Lifecycle** (`CM/node/Lifecycle.java:12`) 🟢: `firstSeenAt`*, `lastSeenAt`*, `deletedAt` (нет), `isCurrent` boolean; инварианты `firstSeenAt ≤ lastSeenAt`, `isCurrent ⇔ deletedAt == null` (`:17-20`).

### A.2 Метки узлов (`NodeLabel`, `CM/node/NodeLabel.java:14-29`) 🟢

| Константа | Строка в Neo4j | Надтип | Каноническая |
|---|---|---|---|
| `IT_SYSTEM` | ITSystem | — | да |
| `SOLUTION` | Solution | — | да |
| `PLATFORM` | Platform | — | да |
| `SERVICE` | Service | — | да |
| `REPOSITORY` | Repository | — | да |
| `TEAM` | Team | — | да |
| `ENVIRONMENT` | Environment | — | да |
| `DEPLOYMENT` | Deployment | — | да |
| `COMPUTE_INSTANCE` | ComputeInstance | — | да |
| `VIRTUAL_MACHINE` | VirtualMachine | COMPUTE_INSTANCE | да |
| `PHYSICAL_SERVER` | PhysicalServer | COMPUTE_INSTANCE | да |
| `NAMESPACE` | Namespace | — | да |
| `KUBERNETES_CLUSTER` | KubernetesCluster | — | да |
| `SOURCE_SYSTEM` | SourceSystem | — | нет (провенанс) |
| `SOURCE_RECORD` | SourceRecord | — | нет |
| `SYNC_RUN` | SyncRun | — | нет |

### A.3 Связи (`RelationType`, `CM/relation/RelationType.java:13-27`) 🟢

| Тип | Из | В | Temporal | Identity-свойства | Авторитет (`AuthorityMatrix.java:104-115`) |
|---|---|---|---|---|---|
| `USES_SOLUTION` | IT_SYSTEM | SOLUTION | да | — | EAM |
| `USES_PLATFORM` | SOLUTION | PLATFORM | да | — | EAM |
| `DECOMPOSED_INTO` | IT_SYSTEM | SERVICE | да | — | SCM |
| `IMPLEMENTED_IN` | SERVICE | REPOSITORY | да | — | SCM |
| `HAS_DEPLOYMENT` | SERVICE | DEPLOYMENT | да | — | DEPLOYMAP |
| `IN_ENVIRONMENT` | DEPLOYMENT | ENVIRONMENT | да | — | DEPLOYMAP |
| `RUNS_ON` | DEPLOYMENT | COMPUTE_INSTANCE, NAMESPACE | да | — | DEPLOYMAP |
| `DEPLOYED_FROM` | DEPLOYMENT | REPOSITORY | да | `commit` | DEPLOYMAP |
| `PART_OF` | NAMESPACE | KUBERNETES_CLUSTER | нет | — | DEPLOYMAP |
| `HOSTED_ON` | VIRTUAL_MACHINE | PHYSICAL_SERVER | нет | — | CMDB |
| `DEPENDS_ON` | SERVICE | SERVICE | да | — | EAM |
| `OWNED_BY` | IT_SYSTEM, SERVICE | TEAM | да | — | EAM |
| `ASSERTS` | SOURCE_RECORD | все канонические | нет | — | вне матрицы |
| `OWNS_RECORD` | SOURCE_SYSTEM | SOURCE_RECORD | нет | — | вне матрицы |
| `PROCESSED` | SYNC_RUN | SOURCE_RECORD | нет | — | вне матрицы |

`Validity` (`CM/relation/Validity.java:8`): `validFrom`*, `validTo` (нет, `null` = открыта); `validFrom ≤ validTo` (`:12-14`).

### A.4 Команды проекции (`GraphCommand`, sealed, 5 вариантов — `CM/command/GraphCommand.java:7-8`) 🟢

| Команда | Поля (обяз.) | Проверки конструктора |
|---|---|---|
| `UpsertNode` (`CM/command/UpsertNode.java:8`) | `record: SourceRecord`*, `data: NodeData`* | только `null` |
| `UpsertRelation` (`CM/command/UpsertRelation.java:21`) | `type`*, `from`*, `fromLabel`*, `to`*, `toLabel`*, `properties`*, `validity` (нет), `assertedBy`* | `type.allows(fromLabel,toLabel)` (`:32-34`); `validity` только для temporal (`:35-37`); `properties` — `copyProperties`; включают все `identityProperties` (`:38-41`) |
| `CloseAssertion` (`CM/command/CloseAssertion.java:9`) | `type`*, `from`*, `to`*, `validTo`*, `assertedBy`* | `type.temporal()` (`:18-20`) |
| `TombstoneSourceRecord` (`CM/command/TombstoneSourceRecord.java:8`) | `key`*, `deletedAt`* | только `null`; «после полного snapshot» — только в Javadoc 🟡 |
| `DeferRelation` (`CM/command/DeferRelation.java:12`) | `relation: UpsertRelation`* | только `null` |

### A.5 Provenance

| Тип | Поля | Правила |
|---|---|---|
| `SourceKey` (`CM/provenance/SourceKey.java:8`) 🟢 | `source: SourceSystemCode`*, `sourceType`*, `sourceId`* | `sourceId` — непрозрачная непустая строка |
| `SourceRecord` (`CM/provenance/SourceRecord.java:9`) 🟢 | `key`*, `sourceVersion: String`*, `contentHash`*, `fetchedAt`*, `active: boolean` | `sourceVersion` — **любая** непустая строка (числовой формат не проверяется, в отличие от `SourceVersion` в `event-schemas`) 🟡 |
| `SourceSystem` (`CM/provenance/SourceSystem.java:8`) 🟢 | `code: SourceSystemCode`*, `name`* | — |
| `SyncRun` (`CM/provenance/SyncRun.java:14`) 🟢 | `runId: UUID`*, `adapter`*, `startedAt`*, `endedAt` (нет), `status`*, `fetched`, `applied`, `failed` (long ≥ 0) | `startedAt ≤ endedAt` (`:22-24`); счётчики ≥ 0 (`:25-27`) |
| `LookupKeys` (`CM/provenance/LookupKeys.java:8`) 🟢 | константы `REPOSITORY_URL`, `IT_SYSTEM_CODE`; фабрики `repositoryUrl(url)` → `SourceKey(SCM, ...)`, `itSystemCode(code)` → `SourceKey(EAM, ...)` | аргументы не проверяются явно (проверит `SourceKey`) |

### A.6 Authority matrix (`CM/authority/AuthorityMatrix.java:78-117`) 🟢

Узлы: EAM → ITSystem, Solution, Platform, Team; SCM → Service, Repository; CMDB → ComputeInstance, VirtualMachine, PhysicalServer; DEPLOYMAP → Deployment, Environment, Namespace, KubernetesCluster. Явные правила свойств: ITSystem `criticality`/`eamCode`/`platformId` → EAM; Solution `platformId` → EAM; Service `language` → SCM; Repository `url` → SCM; ComputeInstance/VirtualMachine/PhysicalServer `state` → CMDB. Связи — таблица A.3. Не охвачены явными правилами: `Repository.defaultBranch`/`archived`, `Service.serviceType`/`status`, `Deployment.*` (наследуют узловое).

### A.7 Допустимые типы значений свойств связи (`CM/Invariants.java:60-77`) 🟢

`String`, `Boolean`, `Long`, `Integer`, `Double` (не NaN/∞), `Instant`, `List<…>` из перечисленных (без вложенных списков); `null` и ключ-пустая строка отвергаются; результат — `Map.copyOf` (неизменяем).

---

## E. event-schemas

### E.1 `CanonicalEvent` (`ES/CanonicalEvent.java:17-25`) 🟢

| Поле | Тип | Обяз. | Правило |
|---|---|---|---|
| `id` | String | да | непусто (`:34`), уникален в `source` |
| `source` | String | да | непусто (`:35`), напр. `urn:corp:eam` |
| `type` | String | да | непусто (`:36`); константа `TYPE_ASSET_UPSERTED` |
| `subject` | String | — | не проверяется |
| `time` | Instant | да | `:38` |
| `dataschema` | String | да | непусто (`:37`) |
| `correlationid` | String | нет | `null` допустим |
| `data` | `AssetEventData` | да | `:38` |
| `specversion()` | String | — | метод, всегда `"1.0"` (`:44-46`) |

**AssetEventData** (`ES/AssetEventData.java:14`) 🟢: `sourceType`*, `sourceId`*, `sourceVersion: SourceVersion`*, `payload: Map<String,Object>` (`null` → пустая; значения `null` сохраняются, `:22-23`).
**SourceVersion** (`ES/SourceVersion.java:10`) 🟢: `value` — `[0-9]{1,40}`; `compareTo` через `BigInteger`.

### E.2 Журнал

**JournalEntry** (`ES/JournalEntry.java:14-29`) 🟢 — проверок нет.

| Поле | Тип | Заметка |
|---|---|---|
| `source` | String | часть ключа дедупликации |
| `eventId` | String | часть ключа дедупликации |
| `correlationId` | String | — |
| `syncRunId` | String | `null` допустим |
| `sourceType`, `sourceId` | String | отдельные поля (id непрозрачен, `ObjectRef` Javadoc) |
| `sourceVersion` | `SourceVersion` | — |
| `schemaVersion` | String | — |
| `status` | `ProcessingStatus` | — |
| `errorCode`, `errorReason` | String | для QUARANTINED/RETRYING; причина без содержимого источника (Javadoc `:11`) |
| `attempts` | int | — |
| `payloadRef` | `RawPayloadRef` | `null` допустим (`SKIPPED_NO_RAW`, `docs/03-interfaces.md:398`) |
| `receivedAt`, `updatedAt` | Instant | UTC |

**StoredEvent** (`ES/StoredEvent.java:12`) 🟢: `entry`*, `type`*, `subject` (нет).
**RawPayloadRef** (`ES/RawPayloadRef.java:7`) 🟢: `key`*, `contentHash`* (SHA-256 hex; формат не проверяется 🟡).
**ObjectRef** (`ES/ObjectRef.java:7`) 🟢: `sourceType`, `sourceId` — без проверок.
**Checkpoint** (`ES/Checkpoint.java:13`) 🟢: `consumer`, `source`, `cursor` (непрозрачный), `updatedAt` — без проверок.
**SnapshotContents** (`ES/SnapshotContents.java:14`) 🟢: `objects: Set<ObjectRef>` (копируется), `eventCount: long`, `firstReceivedAt` (нет).
**JournalKey** (`ES/JournalKey.java:11`) 🟢: `receivedAt`*, `source`*, `eventId`*.
**JournalQuery** (`ES/JournalQuery.java:18-19`) 🟢: `source` (нет), `receivedFrom`/`receivedTo` (нет; `from < to` строго, `:31`), `excludeReplays`, `after: JournalKey` (нет), `limit` ∈ 1..1000 (`:28`).

### E.3 `ProcessingStatus` (`ES/ProcessingStatus.java:9-18`) 🟢

| Значение | Тип | Исходящие переходы (кроме «в себя») |
|---|---|---|
| `RECEIVED` | начальное | VALIDATED, RETRYING, QUARANTINED, DUPLICATE, IGNORED_OLD_VERSION |
| `VALIDATED` | промежуточное | NORMALIZED, RETRYING, QUARANTINED, IGNORED_OLD_VERSION |
| `NORMALIZED` | промежуточное | RESOLVED, RETRYING, QUARANTINED, IGNORED_OLD_VERSION |
| `RESOLVED` | промежуточное | PROJECTED, RETRYING, QUARANTINED, IGNORED_OLD_VERSION |
| `PROJECTED` | конечное* | SUPERSEDED |
| `QUARANTINED` | ждёт оператора | RETRYING |
| `RETRYING` | повтор | VALIDATED, NORMALIZED, RESOLVED, PROJECTED, QUARANTINED, IGNORED_OLD_VERSION |
| `SUPERSEDED` | конечное | — |
| `DUPLICATE` | конечное | — |
| `IGNORED_OLD_VERSION` | конечное | — |

Источник переходов: `ES/ProcessingStatus.java:30-41`.

### E.4 Константы

`CanonicalEvent.SPEC_VERSION="1.0"`, `TYPE_ASSET_UPSERTED="architecture.asset.upserted.v1"` (`:28, 31`); `JournalQuery.REPLAY_PREFIX="replay:"`, `MAX_LIMIT=1000` (`:22, 25`) 🟢.

---

## S. source-spi

**SourceChange** (`SPI/SourceChange.java:24-32`) 🟢

| Поле | Тип | Обяз. | Правило |
|---|---|---|---|
| `sourceType` | String | да | `requireNonNull`; **пустая строка допустима** 🟡 (`:34`) |
| `sourceId` | String | да | то же (`:35`) |
| `sourceVersion` | long | — | знак не проверяется 🟡 |
| `operation` | `ChangeOperation` | да | UPSERT / DELETE |
| `completeness` | `Completeness` | да | COMPLETE / PARTIAL |
| `updatedAt` | Instant | да | UTC |
| `payload` | Map<String,Object> | нет → пустая | неизменяемая обёртка, `null`-значения сохраняются (`:39-41`) |

**ChangePage** (`SPI/ChangePage.java:17-18`) 🟢: `changes` (копируется, `null` → NPE), `nextCursor` (непрозрачный), `hasMore`, `snapshotComplete`; `snapshotComplete && hasMore` → IAE (`:22-24`).
**WebhookEvent** (`SPI/WebhookEvent.java:18-25`) 🟢: `eventId`, `source` (String), `sourceType`, `sourceId`, `sourceVersion` (long), `operation`, `occurredAt` — без проверок.
**SourceSystem** (enum, `SPI/SourceSystem.java:5-9`) 🟢: EAM(`eam`), SCM(`scm`), CMDB(`cmdb`), DEPLOY_MAP(`deploymap`).
**SourceUnavailableException** (`SPI/SourceUnavailableException.java:18-22`) 🟢: `statusCode` (int; 0 = timeout), `retryAfter` (Duration, нет); `isRateLimited()` ⇔ 429; сообщение `"<code> unavailable, status=<n>"`.
**WebhookSignature.Result** (`SPI/WebhookSignature.java:36-42`) 🟢: VALID, MALFORMED, BAD_SIGNATURE, OUT_OF_WINDOW.

---

## P. adapters

> Пути: `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`, `EA/` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter/`. Столбец «Обяз.» — как выше. Метки 🟢/🟡/🔴 — по шкале Reversa.

### P.1 `AdapterConfig` (`AC/AdapterConfig.java:21-29`) 🟢

| Поле | Тип | Обяз. | Правило / значение по умолчанию |
|---|---|---|---|
| `system` | `SourceSystem` | да | `requireNonNull` (`:35`) |
| `sourceBaseUri` | `URI` | да | `requireNonNull` (`:36`) |
| `webhookSecret` | String | да | не `null` и не пробельная (`:39-41`); в `toString` — `***` (`:73`) |
| `replayWindow` | `Duration` | да | `> 0` (`:42`); по умолчанию 5 мин (`:32`); `null` → NPE без имени поля 🟡 |
| `pageSize` | int | да | `≥ 1` (`:45`); по умолчанию 100 (`:61`) |
| `pollInterval` | `Duration` | да | `> 0` (`:48`); по умолчанию 30 с; `null` → NPE 🟡 |
| `reconcileInterval` | `Duration` | да | `requireNonNull` (`:38`), `> 0` (`:51`); по умолчанию 6 ч (`:57`) |
| `retry` | `RetryPolicy` | да | `requireNonNull` (`:37`); по умолчанию `RetryPolicy.defaults()` |

Производное: `sourceUrn()` = `urn:corp:<system.code()>` (`:66-68`).

### P.2 `RetryPolicy` (`AC/RetryPolicy.java:19-28`) 🟢

| Поле | Тип | Правило | По умолчанию |
|---|---|---|---|
| `maxAttempts` | int | `≥ 1` | 5 |
| `baseDelay` | `Duration` | `> 0` | 500 мс |
| `maxDelay` | `Duration` | `≥ baseDelay` | 30 с |

### P.3 `PollResult` (`AC/PollResult.java:10`) 🟢

| Поле | Тип | Обяз. | Значение |
|---|---|---|---|
| `outcome` | `Outcome` | — | `UP_TO_DATE`, `ADVANCED`, `SNAPSHOT_COMPLETED`, `GAVE_UP`, `ALREADY_RUNNING` (`:13-24`) |
| `appended` | long | — | число записанных изменений, включая дубликаты |
| `syncRunId` | String | нет | только для snapshot, иначе `null` |

### P.4 Каноническое событие, формируемое адаптером (`AC/EventMapper.java:52-95`) 🟢

Конверт — `CanonicalEvent` (поля — раздел E.1). Что подставляет адаптер:

| Поле события | `upserted` / `deleted` | `snapshot-complete` |
|---|---|---|
| `id` | `poll:<type>/<id>/v<ver>` (`:98-100`) · `snap:<runId>:<type>/<id>/v<ver>` (`:103-105`) · `eventId` webhook'а | `snapshot-complete:<syncRunId>` (`:83`) |
| `source` | `urn:corp:<code>` | то же |
| `type` | `architecture.asset.upserted.v1` / `architecture.asset.deleted.v1` (`:29`) | `architecture.sync.snapshot-complete.v1` (`:32`) |
| `subject` | `<sourceType в нижнем регистре, '_' → '-'>/<sourceId>` (`:107-109`) | `sync-run/<syncRunId>` |
| `time` | `SourceChange.updatedAt` | время завершения (`clock.instant()`, `Poller.java:169`) |
| `dataschema` | `urn:corp:schema:asset-upserted:1` / `asset-deleted:1` | `urn:corp:schema:snapshot-complete:1` |
| `correlationid` | `eventId` (webhook) · `syncRunId` (snapshot) · `null` (polling) | `syncRunId` |
| `data.sourceType` / `sourceId` | как у изменения | `SYNC_RUN` / `<syncRunId>` |
| `data.sourceVersion` | `Long.toString(sourceVersion)` (`:75`) | `"1"` |
| `data.payload` | поля источника без `null`-значений верхнего уровня + `_completeness`; для `DELETE` пусто (`:55-63`) | `{syncRunId, objectCount}` (`:94`) |

Зарезервированный ключ payload `_completeness` ∈ {`COMPLETE`, `PARTIAL`} (`:35`); `SYNC_RUN_SOURCE_TYPE = "SYNC_RUN"` (`:38`).

### P.5 Raw-объекты в S3 (`AC/InboxWriter.java:51-70`) 🟢

| Объект | Поля JSON |
|---|---|
| изменение | `sourceType`, `sourceId`, `sourceVersion`, `operation`, `completeness`, `updatedAt`, `payload` (с `null`-значениями — **без** фильтрации) |
| маркер snapshot | `syncRunId`, `objectCount`, `updatedAt` |

Ключ в хранилище: `rawStore.put(system.code(), bytes)` (`:37, 46`); адресация по содержимому — на стороне `RawPayloadStore` (Javadoc `InboxWriter.java:17`) 🟡.

### P.6 Checkpoint-ы адаптера 🟢

| Consumer (`Poller.java:79-86`) | Source | Значение курсора |
|---|---|---|
| `<code>-poller` | `urn:corp:<code>` | непрозрачный курсор коннектора (`Poller.java:136, 171`) |
| `<code>-snapshot` | `urn:corp:<code>` | `run:<syncRunId>` пока snapshot идёт, `done:<syncRunId>` после маркера (`:39-40, 170, 186`) |

### P.7 Webhook: вход и ответ

Тело — `WebhookEvent` (раздел S); обязательные для обработчика ключи: `eventId`, `sourceType`, `sourceId` (строки, не пустые), `source` = код источника адаптера (**код**, не URN), `sourceVersion` — число ≥ 0 для `DELETE` без объекта в источнике, заголовки `X-Archrag-Signature`, `X-Archrag-Timestamp`, `X-Archrag-Event-Id` (`AC/WebhookHandler.java:56-100`) 🟢.
Коды ответа `/webhook`: `202`, `400`, `401`, `404`, `405`, `413`, `500`, `503` (`WebhookHandler.java:20-25`, `WebhookServer.java:99-122`); `/control/snapshot`: `202`, `405`, `409`, `500`, `503`, тело `{"outcome","appended","syncRunId"}` (`WebhookServer.java:73-88`) 🟢.

### P.8 EAM-коннектор

**`EamAdapter.EamApi`** (`EA/EamAdapter.java:47`) 🟢: `baseUri` (URI), `token` (String; в `toString` — `***`).

**`EamApiCursor`** (`EA/EamApiCursor.java:18`) 🟢

| Поле | Тип | Смысл |
|---|---|---|
| `mode` | `SNAPSHOT` / `INCREMENTAL` / `WATERMARK` | кодируется как `S` / `I` / `W` |
| `type` | int | индекс в `TYPES` (0 `PLATFORM`, 1 `SOLUTION`, 2 `IT_SYSTEM`), `≥ 0` |
| `page` | int | `≥ 1` |
| `size` | int | `≥ 1` |
| `from` | `Instant` | нижняя граница инкремента (не включительно) |
| `watermark` | `Instant` | максимум `datetime`, увиденный за проход |

Форматы: `S,<type>,<page>,<size>,<wm>`, `I,<type>,<page>,<size>,<from>,<wm>`, `W,<wm>` (`:39-44`).

**Составной курсор** (`EA/CompositeEamConnector.java:118-122`) 🟢: `S|I ; A|L ; eamapi:<base64url|-> | legacy:<base64url|->` — `snapshot` (boolean), `legacyPhase` (boolean), `api` (String|null), `legacy` (String|null).

**Соответствие типов** (`EA/EamApiConnector.java:51-54`) 🟢

| `sourceType` в журнале | Тип EAM API | Источник |
|---|---|---|
| `PLATFORM` | `platforms` | `eam-api` |
| `SOLUTION` | `itproducts` (`SOLUTION_TYPE`, допущение 1) | `eam-api` |
| `IT_SYSTEM` | `itsystems` | `eam-api` |
| `TEAM`, `SERVICE_DEPENDENCY` | — | legacy-заглушка (`CompositeEamConnector.java:30`) |

**`ArchObject`** (вход `EamApiConnector.change`, `EA/EamApiConnector.java:169-188`) 🟢: `id` (Long, обяз.), `ver` (Long, обяз.), `datetime` (ISO-8601, `null` → `EPOCH` 🟡), `attrs` (объект, обяз.; схема не описана в OpenAPI — `docs/06-eam-integration.md:27`). Поля `attrs` по допущению 2: `name`, `status`, `description`, `lifecycle`, `criticality`, `eam_id`, `product_id`, `platform_id`, `ownerTeam` (нормализатор, R.2c).

### P.9 Привязки `scm`, `asset`, `deploymap`

Собственных данных нет; каждая даёт `SYSTEM`, `config(URI, String)` и `create(config, journal, rawStore)` (`SC/ScmAdapter.java:18-38`, `AS/AssetAdapter.java:18-38`, `DM/DeploymapAdapter.java:18-38`). Соответствие: `ScmAdapter` → `SCM` (`scm`), `AssetAdapter` → `CMDB` (`cmdb`), `DeploymapAdapter` → `DEPLOY_MAP` (`deploymap`) 🟢.

### P.10 Временные форматы заглушек (поля — только справочно; нормативно — исходник формата)

**Deploy map / Helm chart** (`platform/source-stubs/.../DeployMapFormat.java:24-45`) 🟢 — формат `deploymap-poc-0`, `deploymap-poc-1`

| Узел | Поле payload | Тип | Обяз. | Примечание |
|---|---|---|---|---|
| `ENVIRONMENT` | `name` | String | да | |
| `DEPLOYMENT` | `service` | String | да | id сервиса SCM |
| | `environment` | String | да | id `ENVIRONMENT` |
| | `chart`, `chartVersion` | String | да | дублируют `chartMeta.name/version` в `poc-1` |
| | `hosts` | String[] | да | hostname'ы CMDB |
| | `format` | String | да | `deploymap-poc-0` / `deploymap-poc-1` |
| | `chartMeta` | объект | только `poc-1` | `name`, `version`, `appVersion`, `annotations` |
| `chartMeta.annotations` | `archrag.io/eam-system-id` | String | `poc-1` | `id` EAM, не `eam_id` |
| | `archrag.io/git-repo-url` | String | `poc-1` | нормализуется `GitUrls.normalize` |
| | `archrag.io/git-commit` | String | `poc-1` | 40 hex, нижний регистр |

Fixtures: `adapters/deploymap-adapter/src/test/resources/deploymap/` (элемент массива — `{sourceId, sourceVersion, payload}`; `deploymap/README.md:3-4`).

**EAM API** (`docs/eam-api.yaml`, `docs/06-eam-integration.md`; формат разбирает `platform/source-stubs/.../EamApiFormat.java`) 🟡 — реальный контракт не проверен (ADR 0010); допущения 1–9 в `docs/06-eam-integration.md:87-131`.

---

# Q. ingestion (R.2c, UNLOCKER-265)

> Archaeologist, этап R.2c. Пути сокращены: `EJ/` = `ingestion/event-journal/src/main/`, `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`. Метки: 🟢 код · 🟡 выведено · 🔴 пробел.
> Схема PostgreSQL собирается из миграций **трёх модулей** в одном каталоге `classpath:db/migration` (`EJ/java/.../JournalMigrations.java:11-13`): `V1` — event-journal, `V2/V4/V5/V7` — identity-resolution, `V3/V6` — ingestion-service. Полный словарь схемы — R.3 (Data Master); здесь — таблицы, через которые проходит логика модулей.

## Q.1 event-journal: таблицы PostgreSQL (`EJ/resources/db/migration/V1__event_journal.sql`) 🟢

### Q.1.1 `inbox_event` (`V1__event_journal.sql:2-22`)

| Колонка | Тип | NULL | Назначение / правило |
|---|---|---|---|
| `source` | text | нет | URN источника (`urn:corp:eam` …), часть ключа идемпотентности |
| `event_id` | text | нет | `id` CloudEvent; префиксы `poll:`, `snap:<syncRunId>:`, `replay:` (см. data-dictionary §P.4) |
| `type` | text | нет | `architecture.asset.upserted.v1` и т.п. |
| `subject` | text | да | `CanonicalEvent.subject` |
| `source_type`, `source_id` | text | нет | ключ объекта в источнике |
| `source_version` | text | нет | версия объекта **как строка** (сравнение — `VersionDecision`, Q4) |
| `correlation_id`, `sync_run_id` | text | да | трассировка и прогон snapshot |
| `schema_version` | text | нет | значение `CanonicalEvent.dataschema` (колонка названа иначе, `PostgresEventJournal.java:73`) 🟢 |
| `status` | text | нет | `ProcessingStatus.name()`; CHECK-ограничения нет — допустимость значений держит код 🟡 |
| `attempts` | integer | нет, 0 | +1 на каждый переход в `RETRYING` |
| `error_code`, `error_reason` | text | да | перезаписываются каждым переходом; `error_reason` ≤ 500 символов |
| `payload_key`, `payload_hash` | text | да | ссылка на raw в S3: `raw/<source>/<sha256>` и hash |
| `received_at`, `updated_at` | timestamptz | нет | UTC; `received_at` — основа курсора диспетчера |

Ограничения и индексы: `uq_inbox_event UNIQUE (source,event_id)`; `ix_inbox_event_status (status, received_at)`; `ix_inbox_event_object (source, source_type, source_id)` (`V1__event_journal.sql:21, 24-25`). Индекса под `pending` с условием `updated_at` нет 🟡.

### Q.1.2 `consumer_checkpoint` (`V1__event_journal.sql:27-33`)

`consumer` + `source` (PK), `cursor` text NOT NULL (формат свободный: курсор poll-а, `run:<id>`, `done:<id>`, `eventId` последнего спроецированного события), `updated_at`. Потребители: `poller:*`/`snapshot:*` адаптеров (`adapters/adapter-core/.../Poller.java:93,136,170-186`), проектор (`ingestion/graph-projector/.../EventProcessor.java:615`) 🟢.

### Q.1.3 `dlq_entry` (`V1__event_journal.sql:35-45`)

| Колонка | Тип | NULL | Примечание |
|---|---|---|---|
| `id` | bigserial | нет | PK |
| `source`, `event_id` | text | нет | FK → `inbox_event(source,event_id)` |
| `reason` | text | нет | ≤ 500 символов; если причины нет — подставляется `error_code` |
| `error_code` | text | нет | обязателен (`toDlq` бросает `IllegalArgumentException` при пустом) |
| `payload_key` | text | да | копия ссылки на raw |
| `created_at` | timestamptz | нет | |
| `replayed_at` | timestamptz | да | 🔴 **в коде не заполняется** (code-analysis §Q1.5) |

Частичный уникальный индекс `uq_dlq_entry_open (source,event_id) WHERE replayed_at IS NULL` — «одна открытая запись на событие» (`V1__event_journal.sql:47-48`).

### Q.1.4 Raw payload в S3 🟢

Ключ `raw/<source>/<sha256 hex>` (`EJ/java/.../S3RawPayloadStore.java:74`); `source` по `[A-Za-z0-9:._-]{1,200}` и не только из точек (`:33, :70`). Метаданные объекта не задаются; целостность — сравнением SHA-256 при `get` (`:85`).

## Q.2 normalizer: вход, выход и форматы payload

### Q.2.1 Выходные типы 🟢

| Тип | Поля | Правила |
|---|---|---|
| `NormalizationResult.Normalized` (`NZ/NormalizationResult.java:19-27`) | `commands: List<GraphCommand>`, `unresolved: List<UnresolvedReference>`, `warnings: List<String>` | списки копируются `List.copyOf`; `commands` начинается с `UpsertNode` записи события (кроме случаев, когда маппер добавляет только связь — не встречается в коде) |
| `NormalizationResult.Quarantined` (`:34`) | `errorCode`, `reason` | коды: `UNSUPPORTED_EVENT_TYPE`, `UNKNOWN_SOURCE`, `UNKNOWN_SCHEMA_VERSION`, `UNKNOWN_SOURCE_TYPE`, `MISSING_REQUIRED_FIELD`, `INVALID_PAYLOAD` |
| `UnresolvedReference` (`NZ/UnresolvedReference.java:19-28`) | `from: SourceKey`, `field: String`, `relationType`, `target: SourceKey`, `relation: UpsertRelation` | все поля обязательны; `field` — имя поля источника без значения; `target` — отсутствующий конец |
| `CanonicalMapper.Input` (`NZ/CanonicalMapper.java:36`) | `record: SourceRecord`, `payload: Map<String,Object>`, `time: Instant` | `payload` — недоверенные данные |

`SourceRecord` для события: `key=(source,sourceType,sourceId)`, `sourceVersion=data.sourceVersion`, `contentHash=raw.contentHash()`, `fetchedAt=event.time()`, `active=true` (`NZ/Normalizer.java:68-75`). Флаг `active=false` normalizer не выставляет никогда; удаление — отдельный тип события (Q4/Q5) 🟢.

### Q.2.2 Форматы payload по источникам 🟢

| Источник / `sourceType` | Поля payload (обяз. — **жирным**) | Команды |
|---|---|---|
| EAM `PLATFORM` (ArchObject) | `attrs.`**`name`**, `type`, `version`, `lifecycle`, `description` | `UpsertNode(Platform)` (`EamMapper.java:139-148`) |
| EAM `SOLUTION` | `attrs.`**`name`**, `lifecycle`, `description`, `platform_id` (целое) | `UpsertNode(Solution)` + `USES_PLATFORM` → `EAM/PLATFORM/<platform_id>` (`:149-159`) |
| EAM `IT_SYSTEM` (ArchObject, есть `attrs`) | `attrs.`**`name`**, `status`, `criticality` (HIGH/MEDIUM/LOW), `description`, `eam_id`, `platform_id`, `product_id`, `ownerTeam`, `ownerSince` | `UpsertNode(ITSystem)`, `USES_SOLUTION` → `EAM/SOLUTION/<product_id>`, `OWNED_BY` → `EAM/TEAM/<ownerTeam>` (`:160-195`) |
| EAM `IT_SYSTEM` (плоский) | **`name`**, `status`, `criticality` (enum `Criticality`), `description`, `ownerTeam`, `ownerSince`, `dependsOn` | `UpsertNode(ITSystem)` без `eamCode`; `OWNED_BY`; `dependsOn` → warning (`:77-98`) |
| EAM `TEAM` | **`name`**, `type` | `UpsertNode(Team)` (`:71`) |
| EAM `SERVICE_DEPENDENCY` | **`from`**, **`to`** (коды сервисов SCM), `kind`, `protocol`, `criticality`, `validFrom` | `DEPENDS_ON` Service→Service через `link` (`:100-115`) |
| SCM `REPOSITORY` | **`url`** (нормализуется `GitUrls`), `defaultBranch`, `archived` (boolean) | `UpsertNode(Repository)` (`ScmMapper.java:51-58`) |
| SCM `SERVICE` | **`name`**, `serviceType`, `language`, `status`, `systemCode`, `repositoryId`, `validFrom` | `UpsertNode(Service)`, `DECOMPOSED_INTO` (система → сервис), `IMPLEMENTED_IN` (сервис → репозиторий) (`:59-94`) |
| CMDB `COMPUTE_INSTANCE` | **`hostname`**, **`kind`** (`ComputeKind`, регистр не важен), `ip`, `os`, `state`, `hypervisorRef`, `serialNumber` | `UpsertNode(ComputeInstance)`; `environment` игнорируется (`CmdbMapper.java:31-48`) |
| DEPLOYMAP `ENVIRONMENT` | **`name`**, `class` (иначе из `sourceId`) | `UpsertNode(Environment(code=sourceId, name, class))` (`DeployMapMapper.java:84-91`) |
| DEPLOYMAP `DEPLOYMENT` | **`name`**, `chartVersion`, `status`, `service`, `environment`, `hosts[]`, `validFrom`, `format`, `chartMeta.annotations.*` (poc-1) | `UpsertNode(Deployment)`, `HAS_DEPLOYMENT`, `DEPLOYED_FROM{commit}`, `IN_ENVIRONMENT`, `RUNS_ON` на каждый хост (`:92-133`) |

Нормативный формат источников — `docs/06-eam-integration.md` и заглушки `platform/source-stubs` (R.2e); таблица описывает то, что **читает** маппер.

### Q.2.3 Warnings и их коды 🟢

| Код | Условие | Где |
|---|---|---|
| `DEPENDS_ON_NOT_SUPPORTED` | плоский `IT_SYSTEM` с непустым `dependsOn` | `EamMapper.java:96-98` |
| `UNMAPPED_CRITICALITY` | ArchObject `IT_SYSTEM` с `criticality` вне HIGH/MEDIUM/LOW | `:164-168` |
| `DEPLOYMENT_SERVICE_UNRESOLVED` | poc-1: сервис не определился | `DeployMapMapper.java:222-224` |

### Q.2.4 Порты

`ReferenceResolver.exists(SourceKey)` (`NZ/ReferenceResolver.java:10-13`); `DeploymentLookup` — `repositoryByUrl`, `itSystemByEamCode`, `servicesImplementedIn`, `eamSystemOfService`, `chartDeploymentsOfService` (последние два нужны `EventProcessor`, не мапперам) (`NZ/DeploymentLookup.java:16-70`). Реализует порты `GraphProjector` (Q4) 🟢.

## Q.3 identity-resolution: таблицы PostgreSQL и записи

Миграции: `V2__identity_mapping.sql`, `V4__identity_candidate.sql`, `V5__source_conflict.sql`, `V7__source_conflict_kind.sql` в `ingestion/identity-resolution/src/main/resources/db/migration/` (`V3` и `V6` — в `ingestion-service`, §Q.5). 🟢

### Q.3.1 `identity_mapping` (V2:3-10)

PK `(source, source_type, source_id)`; `gid uuid NOT NULL`; `created_at timestamptz NOT NULL`; индекс `ix_identity_mapping_gid (gid)`. `source` хранит `SourceSystemCode.name()` (`EAM|SCM|CMDB|DEPLOYMAP`), не URN (`PostgresIdentityMapping.java:157`) 🟢.

### Q.3.2 `approved_crosswalk` (V2:15-26)

PK по шести колонкам `(left_source, left_type, left_id, right_source, right_type, right_id)`; `approved_by`, `reason` (оба NOT NULL), `approved_at timestamptz`. Порядок пары задаёт приложение (`SourceKeys.compare`), CHECK нет. Запись `Crosswalk(left, right, approvedBy, reason, approvedAt)`: ключи различны, `approvedBy`/`reason` непустые (`IR/Crosswalk.java:14-25`) 🟢.

### Q.3.3 `identity_feature` (V4:5-14, индекс :16)

PK `(source, source_type, source_id, feature)` — **одно значение на вид признака на ключ**; `value` (нормализованное), `label_family` (имя `NodeLabel`), `updated_at`; индекс `ix_identity_feature_lookup (feature, label_family, value)`. При upsert записи признаки заменяются целиком (delete + insert) 🟢. `Feature(kind, value)`: `kind ∈ {HOSTNAME, REPOSITORY_URL, NAME, OWNER}`, значение непустое (`IR/Feature.java:6-13`).

### Q.3.4 `identity_candidate` (V4:21-35, индекс :37)

| Колонка | Тип | Примечание |
|---|---|---|
| `left_*`, `right_*` | text ×6 | PK; пара в нормализованном порядке |
| `label_family` | text | |
| `matched` | jsonb NOT NULL | массив `{feature, value}` в порядке признаков |
| `score` | numeric(3,2) NOT NULL | 0.00–1.00 |
| `status` | text NOT NULL DEFAULT `'OPEN'` | 🔴 других значений код не выставляет |
| `first_seen_at`, `updated_at` | timestamptz | `first_seen_at` не меняется при повторе |

Индекс `ix_identity_candidate_right (right_*)`. Запись `IdentityCandidate(left, right, labelFamily, matched, score, status, firstSeenAt, updatedAt)` без инвариантов в конструкторе (`IR/IdentityCandidate.java:14-22`) 🟡.

### Q.3.5 `property_assertion` (V5:6-17, индекс :19)

PK `(source, source_type, source_id, property)`; `gid`, `label`, `value` (текст: enum → `name`, `Instant` → UTC ISO, по комментарию миграции), `authoritative boolean`, `asserted_at`; индекс `(gid, property)`. Заменяется целиком при upsert записи, удаляется tombstone'ом 🟢.

### Q.3.6 `source_conflict` (V5:23-39, V7:4)

PK `(gid, property, dissent_source, dissent_type, dissent_id)`; `dissent_value`, `master_value`, `master_source/type/id`, `status` (`OPEN`/`RESOLVED`, по умолчанию `OPEN`), `opened_at`, `updated_at`, `resolved_at` (nullable), `kind text NOT NULL DEFAULT 'PROPERTY'` (`PROPERTY|RELATION`). Строка не удаляется: `RESOLVED` — история; повторное расхождение снова открывает её (`opened_at` сохраняется) 🟢. Запись `SourceConflict` (`IR/SourceConflict.java:24-35`): все поля, кроме `resolvedAt`, обязательны; `toString()` без значений.

### Q.3.7 Веса признаков (`IR/FeatureKind.java:15-18`) 🟢

| Вид | Вес | Сильный | Нормализация (`FeatureNormalizer`) |
|---|---|---|---|
| `HOSTNAME` | 0.6 | да | trim, lower-case, без завершающих `.` |
| `REPOSITORY_URL` | 0.8 | да | `host/path` lower-case без схемы/`user@`/`.git`/`/` |
| `NAME` | 0.4 | да | NFKC, lower-case, серии не-букв/цифр → пробел |
| `OWNER` | 0.2 | нет | `gid` команды-владельца (строка UUID) |

`score = min(1.00, Σ весов различных совпавших видов)`.

## Q.4 graph-projector: что пишется в Neo4j

> Каноническая модель узлов и связей — `docs/02-data-model.md`, §A.1–A.3 этого словаря; полная схема Neo4j — R.3. Здесь — свойства служебных узлов и рёбер, которые создаёт проектор (`GP/` = `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/`). 🟢

### Q.4.1 Узел `SourceRecord` (`GP/GraphProjector.java:447-464, 493-505, 507-518`)

| Свойство | Тип в Neo4j | Когда пишется | Примечание |
|---|---|---|---|
| `source`, `sourceType`, `sourceId` | string | при `MERGE` (ключ, UNIQUE) | `source` = `SourceSystemCode.name()` |
| `sourceVersion` | string | upsert, relation-only, tombstone | цифры; сравнивается как число |
| `contentHash` | string | только `UpsertNode` | SHA-256 raw payload |
| `fetchedAt` | datetime | только `UpsertNode` | `event.time()` |
| `active` | boolean | `true` при upsert/relation-only, `false` при tombstone | |
| `deletedAt` | datetime | tombstone ставит; upsert снимает | |
| `_lock` | — | ставится и тут же снимается (`:307`) | приём блокировки; в данных не остаётся |

Запись без версии (`sourceVersion IS NULL`) — «заготовка» от `MERGE`; `applied()` и `activeRecords()` её игнорируют (`:120, 154`).

### Q.4.2 Служебные узлы и рёбра

| Элемент | Ключ | Свойства | Создаётся |
|---|---|---|---|
| `SourceSystem` | `code` (UNIQUE) | — | upsert, relation-only, tombstone |
| `(:SourceSystem)-[:OWNS_RECORD]->(:SourceRecord)` | — | — | то же |
| `(:SourceRecord)-[:ASSERTS]->(:<Canonical>)` | — | `confidence=1.0`, `authority ∈ {MASTER, SUPPLEMENTARY}`, `conflicts: list<string>` (имена свойств; снимается при отсутствии конфликта) | `UpsertNode`; `conflicts` — `writeConflictMarkers` |
| `SyncRun` | `runId` (UNIQUE) | `adapter`, `status='RUNNING'` (при создании), `startedAt` (минимум) | если у события есть `syncRunId` (`:687-703`) |
| `(:SyncRun)-[:PROCESSED]->(:SourceRecord)` | — | — | то же |
| `PendingRelation` | `key` (UNIQUE, SHA-256) | `type`, `fromLabel`, `toLabel`, `from{Source,Type,Id}`, `to{Source,Type,Id}`, `props` (JSON-строка), `validFrom?`, `validTo?`, `referrerVersion`, `eventTime` | `DeferRelation` (`GP/PendingRelations.java:53-80`) |
| `(:SourceRecord)-[:DEFERS]->(:PendingRelation)` | — | — | то же |

Счётчики `SyncRun` проектор **не** ведёт (инкремент внутри повторяемой транзакции удваивался бы, `:694`); `SyncRun.status` после создания не меняется 🟡 — кто его завершает, из кода ingestion не видно (проверить в R.3).

### Q.4.3 Канонический узел

`MERGE (n:<root> {gid})` (`gid` — строка UUID); `n += $props` только авторитетные непустые свойства компонентов record'а (`NodeProperties.of`: `Instant` → `datetime` UTC, enum → имя, список → список преобразованных значений); служебные: `isCurrent` (true при создании; для мастера — всегда true при upsert), `deletedAt` (tombstone без другого активного мастера), `firstSeenAt` (минимум по `eventTime`), `lastSeenAt` (максимум) (`GraphProjector.java:442-465, 543-549`) 🟢.

### Q.4.4 Свойства темпоральной связи

`rel += $props` (только переданные свойства связи); `assertedBySource`, `assertedByType`, `assertedById` — запись, утверждающая связь; `validFrom`, `validTo` — для `RelationType.temporal()` (`:564-596`). Закрытие: `validTo = max(validFrom, <момент>)` — интервал не бывает отрицательным (`:525, 538, 643, 682`) 🟢.

### Q.4.5 Результаты и порты (Java)

| Тип | Поля | Инварианты |
|---|---|---|
| `ProjectionRequest` (`GP/ProjectionRequest.java:37-44`) | `key`, `version`, `eventTime`, `syncRunId?`, `gids: Map<SourceKey,UUID>`, `commands`, `conflicts` | ≤ 1 узел-команды; ключ и версия узла совпадают с запросом; все связи утверждаются этой записью; `gid` есть для узла и обоих концов `UpsertRelation`/`CloseAssertion` (кроме `DeferRelation`); tombstone — единственная команда; маркеры конфликтов — только для `gid` записи |
| `ProjectionResult` (`GP/ProjectionResult.java:13-14`) | `outcome ∈ {APPLIED, NOOP_SAME_VERSION, IGNORED_OLD_VERSION}`, `skippedProperties`, `skippedRelations` | списки неизменяемы |
| `ProcessingResult` (`GP/ProcessingResult.java:13`) | `status`, `errorCode?`, `projection?` | `status` обязателен |
| `ConflictMarkers` (`GP/ConflictMarkers.java:22`) | `gid?`, `byRecord: Map<SourceKey, List<String>>` | непустые списки; маркеры без `gid` недопустимы; `none()` = «не менять» |
| `Reconciler.Report` (`GP/Reconciler.java:86`) | `tombstoned`, `ignoredOldVersion`, `keptRecentlyTouched`, `keptInSnapshot` | — |

Порты, которые реализует `GraphProjector`: `GraphProjection` (`project`, `applied`, `isActive`, `activeRecords`), `DeploymentLookup` (Q.2.4), `EamLookup` (`solutionPlatform`, `systemsOfSolution`); внешние порты `ReconciliationTrigger` и `NormalizationWarnings` реализуют `Reconciler` и слой метрик (Q5).

### Q.4.6 Схема Neo4j, которую применяет `Neo4jSchema` (`GP/schema/Neo4jSchema.java:41-60`)

| Объект | Определение |
|---|---|
| `<label>_gid` для каждой канонической метки | `UNIQUE n.gid` (имя из `CamelCase` → `snake_case` + `_gid`) |
| `source_record_key` | `UNIQUE (SourceRecord.source, .sourceType, .sourceId)` |
| `source_system_code` | `UNIQUE SourceSystem.code` |
| `sync_run_id` | `UNIQUE SyncRun.runId` |
| `pending_relation_key` | `UNIQUE PendingRelation.key` |
| `environment_code` | `UNIQUE Environment.code` |
| `it_system_eam_code` | индекс `ITSystem.eamCode` |
| `asset_text` | FULLTEXT `ITSystem\|Service` по `name`, `description` |

## Q.5 ingestion-service: таблицы, DTO админского REST, конфигурация

> `IS/` = `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/`. 🟢

### Q.5.1 `admin_audit` (`ingestion/ingestion-service/src/main/resources/db/migration/V3__admin_audit.sql`)

| Колонка | Тип | NULL | Назначение |
|---|---|---|---|
| `id` | bigserial | нет | PK |
| `operation` | text | нет | `REPLAY`, `REBUILD`, `RECONCILE`, `CROSSWALK` |
| `actor` | text | нет | `sub` токена |
| `request` | jsonb | нет | параметры запроса без payload; у crosswalk — ключи и усечённая `reason` (≤ 200) |
| `replay_id` | text | да | UUID replay/rebuild |
| `status` | text | нет | `STARTED` → `SUCCEEDED` / `FAILED` |
| `result` | jsonb | да | счётчики (`total`, `statuses`) / `adapterStatus` / `status`+`gid` |
| `error` | text | да | класс исключения, ≤ 200 символов |
| `started_at`, `finished_at` | timestamptz | нет / да | |

Индексы: `ix_admin_audit_started (started_at)`, `ix_admin_audit_replay (replay_id)`. Запись `STARTED` делается **до** операции; зависшая `STARTED` означает падение процесса или сбой записи итога (`IS/AdminOperations.java:56`).

### Q.5.2 Роль БД `archrag_console_ro` (`V6__console_readonly_role.sql`)

`CREATE ROLE … LOGIN` (идемпотентно, пароль не задаётся), `GRANT USAGE ON SCHEMA public`, `GRANT SELECT` на `inbox_event, consumer_checkpoint, dlq_entry, identity_candidate, source_conflict, admin_audit`. Raw payload в PostgreSQL не хранится. Любое новое чтение консоли требует нового GRANT 🟡.

### Q.5.3 Админский REST (`IS/AdminController.java`)

| Запрос | Тело / параметры | Ответ | Ошибки |
|---|---|---|---|
| `POST /admin/replay` | `ReplayRequest{source*, receivedFrom?, receivedTo?, replayId?}`; `[from, to)` по `received_at` | `ReplayResult{replayId, total, statuses: Map<String,Long>}` | 400 (`source`, интервал, `replayId`), 409 (замок) |
| `POST /admin/rebuild` | `confirm=true` | `ReplayResult` | 400 без `confirm`, 409 |
| `POST /admin/reconcile/{source}` | путь: `urn:corp:<код>` или `<код>` | тело и статус адаптера (202 / 409) | 400, 404 (источник не настроен), 409 (замок), 502 |
| `POST /admin/crosswalks` | `[CrosswalkItem{left, right, reason}]`, 1..1000; `KeyDto{source, sourceType, sourceId}` | `[CrosswalkResult{index, status, gid?}]`; `status ∈ APPLIED, CONFLICT, INVALID` | 400 (размер списка), 409 |

Статусы в `ReplayResult.statuses`: имена `ProcessingStatus` и два служебных — `SKIPPED_SNAPSHOT_MARKER`, `SKIPPED_NO_RAW`. Аутентификация: JWT со scope `architecture.admin`, `aud` = `ARCHRAG_ADMIN_RESOURCE_URI`, `azp ∈ archrag.admin.allowed-clients`. Остальные пути — `denyAll`.

### Q.5.4 Свойства (`application.yml`; env-переменные без значения по умолчанию обязательны)

| Свойство | Env | Умолчание | Класс |
|---|---|---|---|
| `server.port` | `ARCHRAG_ADMIN_PORT` | 8081 | — |
| `spring.datasource.{url,username,password}` | `ARCHRAG_PG_URL/USER/PASSWORD` | — (обяз.) | Boot |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `ARCHRAG_OIDC_ISSUER_URI` | — (обяз.) | Boot |
| `…jwt.audiences` | `ARCHRAG_ADMIN_RESOURCE_URI` | — (обяз.) | Boot |
| `archrag.admin.allowed-clients` | `ARCHRAG_ADMIN_ALLOWED_CLIENTS` | `archrag-demo` | `AdminProperties` (непустой список) |
| `archrag.neo4j.{uri,username,password}` | `ARCHRAG_NEO4J_URI/USER/PASSWORD` | — (обяз.) | `Neo4jProperties` (`toString` без пароля) |
| `archrag.s3.{endpoint,access-key,secret-key,bucket}` | `ARCHRAG_S3_*` | — (обяз.) | `S3Properties` (`toString` без ключей) |
| `archrag.adapters.<eam\|scm\|cmdb\|deploymap>.control-url` | `ARCHRAG_<КОД>_CONTROL_URL` | пусто → источник не настроен (404) | `AdaptersProperties` |
| `archrag.dispatcher.enabled` | `ARCHRAG_DISPATCHER_ENABLED` | true | `DispatcherProperties` |
| `archrag.dispatcher.poll-interval` | `…_POLL_INTERVAL` | 1s (> 0) | то же |
| `archrag.dispatcher.batch-size` | `…_BATCH_SIZE` | 100 (1..1000) | то же |
| `archrag.dispatcher.retry-delay` | `…_RETRY_DELAY` | 30s (≥ 0) | то же |
| `archrag.dispatcher.shutdown-timeout` | `…_SHUTDOWN_TIMEOUT` | 30s | то же |
| `archrag.metrics.refresh-interval` | — | 15s | `@Scheduled` в `IngestionMetrics` |
| `management.otlp.metrics.export.enabled` | — | false | Boot |
| `management.endpoints.web.exposure.include` | — | `health` | Boot (путь закрыт `denyAll`) |

### Q.5.5 Коды ошибок журнала, которые выставляет ingestion (сводка) 🟢

| Код | Откуда | Статус | Тип |
|---|---|---|---|
| `NO_RAW_PAYLOAD`, `MARKER_NO_RAW`, `INVALID_RAW_PAYLOAD` | `JournalDispatcher` | `QUARANTINED` (DLQ) | входные данные |
| `UNSUPPORTED_EVENT_TYPE`, `UNKNOWN_SOURCE`, `UNKNOWN_SCHEMA_VERSION`, `UNKNOWN_SOURCE_TYPE`, `MISSING_REQUIRED_FIELD`, `INVALID_PAYLOAD` | `Normalizer`/`EventProcessor` | `QUARANTINED` | входные данные |
| `INVALID_COMMANDS`, `GRAPH_REJECTED` | `EventProcessor.write` | `QUARANTINED` | отказ графа по запросу |
| `UNRESOLVED_ENDPOINT`, `IDENTITY_CANDIDATE_FAILED`, `SOURCE_CONFLICT_FAILED`, `GRAPH_UNAVAILABLE`, `GRAPH_CLIENT_ERROR`, `GRAPH_ERROR`, `RECONCILIATION_TRIGGER_FAILED` | `EventProcessor` | `RETRYING` | инфраструктура / гонка |

---

# R.2d — `access/*` (UNLOCKER-266)

> Разделы X.1–X.4 — по одному на модуль `access/`. Столбцы «Обяз.» как выше. Сокращения путей: `GQC/` = `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/`, `MCP/` = `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/`, `AC/` = `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/`, `UI/` = `access/admin-ui/src/`. Метки: 🟢 код · 🟡 выведено · 🔴 пробел.
> Словарь узлов и связей Neo4j (метки, свойства) — R.3; здесь только поля, которые читают шаблоны и отдают ответы.

## X.1 graph-query-core: внутренние типы и результат шаблонов

### X.1.1 Records модуля 🟢

| Тип | Поле | Тип | Обяз. | Правило |
|---|---|---|---|---|
| `QueryTemplate` (`GQC/QueryTemplate.java:21-22`) | `id` | String | да | не пусто |
| | `cypher` | String | да | не пусто; оканчивается `LIMIT $limit` (`:74-76`) |
| | `parameters` | Set<String> | да | `Set.copyOf`; без `limit`; равно множеству `$param` в тексте (`:52-56,77-86`) |
| | `kind` | ResultKind | да | `NODES` / `PATHS` (`GQC/ResultKind.java:6-9`) |
| `ResultBudget` (`GQC/ResultBudget.java:17-18`) | `maxDepth`, `maxNodes`, `maxPaths`, `maxResponseBytes` | int / int / int / long | да | ≥ 1 (`:21-23`) |
| | `timeout` | Duration | да | > 0 (`:24-26`) |
| `QueryLimits` (`GQC/QueryLimits.java:14-15`) | те же 5 полей | | да | проверяются через `new ResultBudget(...)` (`:17-20`) |
| `QueryResult` (`GQC/QueryResult.java:19-25`) | `templateId`, `rows`, `truncated`, `rowCount`, `elapsed` | String, List<Map>, boolean, int, Duration | да | `rows` копируется; `rowCount` — размер возвращённого списка (в `execute` задаётся `rows().size()`, `GraphQueryExecutor.java:92`) |
| `BudgetedCollector.Collected` (`GQC/BudgetedCollector.java:25`) | `rows`, `truncated` | | да | — |

Исключения: `QueryTimeoutException(templateId, cause)` (`GQC/QueryTimeoutException.java`).

### X.1.2 Строки результата по шаблонам 🟢

Все значения — скаляры, списки и map; времена отдаются **строкой** `toString(datetime)` (ISO-8601, UTC), `null` если свойства нет.

| Шаблон | Поле | Тип в Java | Источник в графе / примечание |
|---|---|---|---|
| `search_assets` | `gid` | String | `n.gid` |
| | `type` | String | первая метка из `SEARCHABLE_TYPES` (`AssetTemplates.java:16-17`) |
| | `name` | String | `coalesce(name, hostname, url, code)` |
| | `score` | double | `1000.0` для EXACT, иначе fulltext score |
| | `matchType` | String | `EXACT` \| `FULLTEXT` |
| | `sources` | List<String> | `SourceRecord.source` активных записей |
| | `lastSeenAt` | String | |
| `get_asset` | `gid`, `type`, `isCurrent` | String, String, Boolean | |
| | `properties` | Map | `properties(n)` — **все** свойства узла (кроме значений-узлов) 🟡 |
| | `firstSeenAt`, `lastSeenAt`, `deletedAt` | String | |
| | `sources[]` | Map | `source, sourceType, sourceId, sourceVersion, fetchedAt, active, authority` (с неактивными, активные первыми) |
| `asset_relations` | `relationType`, `direction (OUT\|IN)`, `gid`, `type`, `name`, `isCurrent`, `validFrom` | | сосед |
| `explain_provenance` | `gid`, `type`, `isCurrent`, `deletedAt`, `records[]` | | запись: `source, sourceType, sourceId, sourceVersion, fetchedAt, contentHash, active, deletedAt, authority, confidence, conflicts` |
| `trace_downstream`, `trace_upstream`, `trace_impact` | `nodes[]` | Map | `gid, label, name, lastSeenAt, conflicts[{source, sourceType, sourceId, properties}]` |
| | `relations[]` | Map | `type, from, to, validFrom, assertedBySource, assertedByType, assertedById, sourceFetchedAt, sourceActive` |
| `runtime_footprint` | `system`, `service`, `deployment`, `compute`, `host` | Map \| null | `deployment`: `gid, name, version, status, environment, sourceCommit, repositoryUrl, lastSeenAt, sources, hasDeployment{…}, source{repository{gid,name,url}, deployedFrom{source,sourceType,sourceId,validFrom,commit}}`; `compute`: `gid, type, hostname, state, lastSeenAt, sources, runsOn{…}`; `host`: `gid, hostname, state, lastSeenAt, sources, hostedOn{…}` |
| `graph_stats_nodes` | `label`, `count` | String, long | |
| `graph_stats_relations` | `type`, `count` | String, long | |
| `graph_last_update` | `lastUpdatedAt` | String \| null | `max(SourceRecord.fetchedAt)` |
| `console_neighborhood_nodes` | `gid, type, name, isCurrent, distance` | | `distance` = `min(length(p))`, сам узел — 0 |
| `console_neighborhood_edges` | `type, source, target` | | gid концов |
| `console_latest_sync_runs` | `adapter, runId, startedAt, endedAt, status, fetched, applied, failed` | | свойства `SyncRun`; ненаписанные — `null` |

Источники: `GQT` = `GQC/templates/` (`AssetTemplates.java:37-210`, `DependencyTemplates.java:40-69`, `RuntimeTemplates.java:29-90`, `ConsoleTemplates.java:47-179`).

### X.1.3 Allowlist'ы (константы) 🟢

| Константа | Значения | Где |
|---|---|---|
| `AssetTemplates.SEARCHABLE_TYPES` | `ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance` | `AssetTemplates.java:16-17` |
| `AssetTemplates.RELATION_TYPES` | `DECOMPOSED_INTO, IMPLEMENTED_IN, HAS_DEPLOYMENT, IN_ENVIRONMENT, RUNS_ON, DEPENDS_ON, OWNED_BY, DEPLOYED_FROM` | `:102-103` |
| `DependencyTemplates.TRACE_LABELS` | SEARCHABLE_TYPES + `VirtualMachine, PhysicalServer, Namespace, KubernetesCluster` | `DependencyTemplates.java:22-25` |
| `DependencyTemplates.TRACE_RELATION_TYPES` | `DEPENDS_ON, DECOMPOSED_INTO, HAS_DEPLOYMENT, RUNS_ON, HOSTED_ON, OWNED_BY, DEPLOYED_FROM` | `:18-19` |
| `DependencyTemplates.IMPACT_RELATION_TYPES` | `RUNS_ON, HOSTED_ON, HAS_DEPLOYMENT, DECOMPOSED_INTO, DEPENDS_ON` | `:98-99` |
| `DependencyTemplates.IMPACT_TARGET_LABELS` | `Service, ITSystem` | `:102` |
| `ConsoleTemplates.STATS_LABELS` | TRACE_LABELS + `SourceRecord` | `ConsoleTemplates.java:19-25` |
| `ConsoleTemplates.STATS_RELATION_TYPES` | RELATION_TYPES + `HOSTED_ON, ASSERTS` | `:28-35` |
| `ConsoleTemplates.NEIGHBORHOOD_RELATION_TYPES` | RELATION_TYPES + `HOSTED_ON` | `:38` |

---

## X.2 mcp-server: ответы tools, аудит, свойства

Ответ каждого tool — record из `MCP/tools/`; Spring AI отдаёт его JSON в `result.content[0].text`. Ошибка валидации — `isError=true` без значений ввода (`docs/03-interfaces.md` §8 п.10 описывает дублирование текста). Все времена — строки ISO-8601 UTC. Поля не имеют аннотаций обязательности: «Обяз.» ниже — «заполняется всегда» по коду сборки.

### X.2.1 Ответы 🟢

| Тип | Поле | Тип | Обяз. | Примечание |
|---|---|---|---|---|
| `SearchAssetsResult` (`MCP/tools/SearchAssetsResult.java:11`) | `items` | List<AssetHit> | да | точные совпадения первыми |
| | `truncated` | boolean | да | |
| `AssetHit` (`:24-31`) | `gid, type, name, matchType, lastSeenAt` | String | да (`name`, `lastSeenAt` могут быть `null`) | |
| | `score` | double | да | 1000 для `EXACT` |
| | `sources` | List<String> | да | `source` активных записей |
| `GetAssetResult` (`MCP/tools/GetAssetResult.java:21-31`) | `gid, type, firstSeenAt, lastSeenAt` | String | да | |
| | `properties` | Map<String,Object> | да | без `gid, isCurrent, firstSeenAt, lastSeenAt, deletedAt` (`GetAssetTool.java:25-26`) |
| | `deletedAt` | String | нет | |
| | `isCurrent` | boolean | да | `false` у закрытого узла |
| | `sources` | List<SourceRef> | да | `source, sourceType, sourceId, sourceVersion, fetchedAt, active, authority` (`:39-46`) |
| | `relations` | List<RelationRef> | да | пусто при `includeRelations=false`; `relationType, direction, gid, type, name, isCurrent, validFrom` (`:57-64`) |
| | `truncated` | boolean | да | только про связи |
| `ExplainProvenanceResult` (`MCP/tools/ExplainProvenanceResult.java:19-27`) | `gid, type` | String | да | |
| | `property` | String | нет | прошло регулярное выражение |
| | `isCurrent`, `deletedAt` | boolean, String | да, нет | |
| | `authorities` | List<String> | да | имена `SourceSystemCode`, отсортированы |
| | `conflictState` | String | да | `OPEN` \| `NONE` |
| | `records` | List<RecordRef> | да | `source, sourceType, sourceId, sourceVersion, fetchedAt, contentHash, confidence (Double), authority, authoritative, active, deletedAt, conflictProperties` (`:39-51`) |
| `TraceDependenciesResult` (`MCP/tools/TraceDependenciesResult.java:25-35`) | `startGid, mode, direction, staleAfter` | String | да | `staleAfter` — ISO-8601 Duration (`PT168H`) |
| | `environment` | String | нет | только `impact` |
| | `nodes` | List<NodeRef> | да | `gid, label, name, lastSeenAt, stale, conflicts[{source, sourceType, sourceId, properties[]}]` (`:47,57`) |
| | `relations` | List<RelationRef> | да | `type, from, to, validFrom, assertedBySource/Type/Id, sourceFetchedAt, sourceActive, stale` (`:73-83`) |
| | `paths` | List<TracePath> | да | `nodes[]` (gid), `relations[]` (`RelationKey{type, from, to}`) (`:91-94`) |
| | `affected` | List<String> | да | пусто при `trace` |
| | `truncated` | boolean | да | |
| `FindRuntimeFootprintResult` (`MCP/tools/FindRuntimeFootprintResult.java:24-34`) | `systemGid, systemName, systemLastSeenAt, systemStale, systemSources, environment, services, staleAfter, warnings, truncated` | | см. код | `warnings` — «stale: <Type> <gid> lastSeenAt=<…>» |
| `ServiceFootprint` → `DeploymentFootprint` → `ComputeFootprint` → `HostFootprint` (`:37-92`) | | | | `DeploymentFootprint` несёт `sourceCommit, repositoryUrl, repository{gid,name,url}, deployedFrom{source,sourceType,sourceId,validFrom,commit}, hasDeployment{EdgeProvenance}`; `ComputeFootprint.type` ∈ `VirtualMachine, PhysicalServer, ComputeInstance` |
| `EdgeProvenance`, `FactSource` (`:95-98`) | `source, sourceType, sourceId, validFrom` / `…, authority` | | | `validFrom` — только у темпоральных связей |

### X.2.2 Аудит-запись `archrag.audit.mcp` (`MCP/audit/ToolCallAuditor.java:108-119`) 🟢

Структурированный лог INFO, сообщение `mcp tool call`; ключи: `principal` (`sub=… client=…` или `anonymous`), `tool` (очищен, ≤ 64), `arguments` (нормализованная map, `ToolArguments`), `templates` (список ID шаблонов), `durationMs`, `rows`, `truncated`, `decision` (`ALLOWED, DENIED_SCOPE, DENIED_UNKNOWN_TOOL, TIMEOUT, ERROR` — `MCP/audit/ToolDecision.java:4-15`), `errorClass` (имя класса исключения или `null`). Метрики: `archrag.mcp.tool.call` (теги `tool`, `decision`; гистограмма включена, `access/mcp-server/src/main/resources/application.yml:71-72`), `archrag.mcp.tool.rows` (тег `tool`).

### X.2.3 Свойства mcp-server 🟢

| Свойство | Env | Умолчание | Класс |
|---|---|---|---|
| `spring.ai.mcp.server.{name,version,protocol,streamable-http.mcp-endpoint}` | — | `arch-rag`, `0.1.0`, `STATELESS`, `/mcp` | Spring AI |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `ARCHRAG_OIDC_ISSUER_URI` | обязательно | Spring Boot |
| `spring.security.oauth2.resourceserver.jwt.audiences` | = `archrag.mcp.security.resource-uri` | | |
| `archrag.mcp.security.resource-uri` | `ARCHRAG_MCP_RESOURCE_URI` | обязательно | `McpSecurityProperties` (`MCP/security/McpSecurityProperties.java:16-17`) |
| `archrag.mcp.security.max-request-bytes` | — | `1MB` | |
| `archrag.mcp.security.tool-scopes.<tool>` | — | 6 записей `architecture.read` | |
| `archrag.neo4j.reader.{uri,username,password}` | `ARCHRAG_NEO4J_READER_URI/USERNAME/PASSWORD` | обязательно | `GraphReaderProperties` (`MCP/graph/GraphReaderProperties.java:14-15`) |
| `archrag.query.stale-after` | — | `P7D` | `StalenessProperties` (`MCP/graph/StalenessProperties.java:12-13`) |
| `archrag.query.limits.{max-depth,max-nodes,max-paths,timeout,max-response-bytes}` | — | `6, 500, 50, 5s, 512KB` | `GraphReaderProperties.Limits` |
| `management.endpoints.web.exposure.include` | — | `health` (+ probes) | |
| `management.otlp.metrics.export.{enabled,step}` | `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED`, `…_URL` | `false`, `10s` | Micrometer |

---

## X.3 admin-console: ответы REST, чтение PostgreSQL, конфигурация

`AC/` = `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/`. Времена `Instant` сериализуются Jackson в ISO-8601 UTC; времена из графа приходят строкой. 🟢

### X.3.1 Ответы `/api/graph/**` (`AC/api/GraphResponses.java`)

| Тип | Поля |
|---|---|
| `Stats` (`:21`) | `nodes: [Count{name, count}]`, `relations: [Count]`, `lastUpdatedAt: String\|null` |
| `SearchResponse` (`:29`) | `items: [AssetHit]`, `truncated` |
| `AssetHit` (`:32-39`) | `gid, type, name, score (double), matchType, sources[String], lastSeenAt` |
| `NodeCard` (`:47-55`) | `gid, type, properties (Map, без gid/isCurrent/firstSeenAt/lastSeenAt/deletedAt), firstSeenAt, lastSeenAt, deletedAt, isCurrent, sources: [Map]` — записи `explain_provenance` (`source, sourceType, sourceId, sourceVersion, fetchedAt, contentHash, active, deletedAt, authority, confidence, conflicts`) |
| `Neighborhood` (`:68`) | `center, nodes: [GraphNode{gid, type, name, isCurrent, distance}], edges: [GraphEdge{type, source, target}], truncated` |
| `ApiError` (`AC/api/ApiExceptionHandler.java:21`) | `error` (`invalid_request`, `not_found`, `query_timeout`), `message` |
| `ConsoleConfig` (`AC/config/ConsoleConfigController.java:18`) | `issuer, clientId, scope` |

### X.3.2 Ответы `/api/sync|identity|audit` (`AC/pg/Rows.java`, `AC/pg/Page.java`)

`Page<T>{items, page (с 0), size, total}` (`Page.java:13`); запрос `page ≥ 0`, `size 1..200`, по умолчанию `0`/`50` (`PageRequest.java:10-24`).

| Тип | Поля | Таблица / колонки (`ConsoleReadRepository`) |
|---|---|---|
| `Event` (`Rows.java:15-32`) | `source` (короткий код), `eventId, type, subject, sourceType, sourceId, sourceVersion, correlationId, syncRunId, schemaVersion, status, attempts, errorCode, payloadRef, contentHash, receivedAt, updatedAt` | `inbox_event` (`payloadRef` = `payload_key`, `contentHash` = `payload_hash`) — `ConsoleReadRepository.java:38-40` |
| `EventDetail` (`:35`) | `event, errorReason (≤ 500), dlqEntries[], historyNote` | `inbox_event.error_reason`, `dlq_entry` |
| `DlqEntry` (`:38-46`) | `id, source, eventId, errorCode, reason (≤ 500), payloadRef, createdAt, replayedAt` | `dlq_entry` |
| `Conflict` (`:49-64`) | `gid, property, dissentSource/Type/Id, dissentValue (≤ 500), masterSource/Type/Id, masterValue (≤ 500), status, openedAt, updatedAt, resolvedAt, kind` | `source_conflict` |
| `Candidate` (`:67-79`) | `leftSource/Type/Id, rightSource/Type/Id, labelFamily, matched (JSON), score, status, firstSeenAt, updatedAt` | `identity_candidate` (`matched::text`) |
| `Audit` (`:82-94`) | `id, operation, actor, status, replayId, request (JSON\|null), requestTruncated, result (JSON\|null), resultTruncated, error (≤ 500), startedAt, finishedAt` | `admin_audit` (`request`/`result` > 4000 символов → `null` + флаг) |
| `Checkpoint` (`:97`) | `consumer, cursor, updatedAt` | `consumer_checkpoint` |
| `SyncRunInfo` (`:100-107`) | `runId, startedAt, endedAt, status, fetched, applied, failed` | граф: `SyncRun` (`console_latest_sync_runs`) |
| `SourceStatus` (`:110-116`) | `source, checkpoints[], lastSyncRun, eventsByStatus (Map всех 10 статусов), lastProjectedAt, lagSeconds` | агрегат |

Читаемые колонки согласованы с миграциями V1/V4/V5/V7/V3 (`ingestion/*/src/main/resources/db/migration/`, проверено поиском колонок `error_reason, payload_hash, attempts, replayed_at, kind, matched, label_family, dissent_value, payload_key, sync_run_id, correlation_id`), права — с V6 (`SELECT` на 6 таблиц) 🟢.

Фильтры (константные значения): `SyncSources.CODES = [eam, scm, cmdb, deploymap]`, `URN_PREFIX = "urn:corp:"`, `STATUSES = RECEIVED, VALIDATED, NORMALIZED, RESOLVED, PROJECTED, QUARANTINED, RETRYING, SUPERSEDED, DUPLICATE, IGNORED_OLD_VERSION` (`AC/pg/SyncSources.java:16-33`).

### X.3.3 Свойства admin-console (`access/admin-console/src/main/resources/application.yml`)

| Свойство | Env | Умолчание | Класс |
|---|---|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `ARCHRAG_OIDC_ISSUER_URI` | обязательно | Spring Boot |
| `spring.security.oauth2.resourceserver.jwt.audiences` | = `archrag.console.resource-uri` | | |
| `spring.web.resources.add-mappings` | — | `false` | Spring MVC |
| `archrag.console.resource-uri` | `ARCHRAG_CONSOLE_RESOURCE_URI` | обязательно (пусто → не стартует) | `ConsoleProperties` (`AC/config/ConsoleProperties.java:13-17`) |
| `archrag.console.client-id` | — | `archrag-admin-console` | |
| `archrag.console.scope` | — | `openid architecture.admin` | |
| `archrag.neo4j.reader.{uri,username,password}` | `ARCHRAG_NEO4J_READER_URI/USERNAME/PASSWORD` | обязательно | `GraphReaderProperties` (`AC/graph/GraphReaderProperties.java:14-15`) |
| `archrag.console-pg.{url,username,password}` | `ARCHRAG_CONSOLE_PG_URL`, `…_USERNAME`, `…_PASSWORD` | `username` = `archrag_console_ro` | `ConsolePgProperties` (`AC/pg/ConsolePgProperties.java:12-13`) |
| `archrag.query.limits.{max-depth,max-nodes,max-paths,timeout,max-response-bytes}` | — | `6, 500, 50, 5s, 512KB` | `GraphReaderProperties.Limits` |
| `management.health.db.enabled` | — | `false` | Spring Boot |
| `management.endpoints.web.exposure.include` | — | `health` (+ probes) | |

---

## X.4 admin-ui: типы ответов, константы, состояние

`UI/` = `access/admin-ui/src/`. Поля — зеркало Java-records `admin-console` (§X.3); `T | null` означает «может прийти `null`». 🟢

### X.4.1 Типы API (`UI/api/types.ts`)

| Интерфейс | Поля (строки `types.ts`) | Java-источник |
|---|---|---|
| `Count`, `Stats` (`:3-12`) | `name, count`; `nodes[], relations[], lastUpdatedAt\|null` | `GraphResponses.Count/Stats` |
| `AssetHit`, `SearchResponse` (`:14-27`) | `gid, type, name, score, matchType, sources[], lastSeenAt\|null`; `items[], truncated` | `GraphResponses.AssetHit/SearchResponse` |
| `SourceRecordInfo` (`:30-42`) | `source, sourceType, sourceId, sourceVersion\|null, fetchedAt\|null, contentHash\|null, active\|null, deletedAt\|null, authority (string\|number)\|null, confidence\|null, conflicts[]\|null` | запись `explain_provenance` |
| `NodeCard` (`:44-53`) | `gid, type, properties: Record<string, unknown>, firstSeenAt, lastSeenAt, deletedAt, isCurrent, sources: SourceRecordInfo[]` | `GraphResponses.NodeCard` |
| `GraphNode`, `GraphEdge`, `Neighborhood` (`:55-74`) | `gid, type, name, isCurrent, distance`; `type, source, target`; `center, nodes, edges, truncated` | `GraphResponses` |
| `Page<T>` (`:76-81`) | `items, page, size, total` | `pg.Page` |
| `Checkpoint`, `SyncRunInfo`, `SourceStatus` (`:83-106`) | как §X.3.2 | `Rows` |
| `SyncEvent` (`:109-127`) | 17 полей `Event`; `receivedAt`, `updatedAt` — обязательные строки | `Rows.Event` |
| `DlqEntry`, `EventDetail` (`:129-145`) | как §X.3.2 | `Rows` |
| `Conflict` (`:147-164`) | как `Rows.Conflict`; `kind?: 'PROPERTY' \| 'RELATION'` (в UI необязательное: «поля нет у старых ответов») | `Rows.Conflict` |
| `MatchedFeature`, `Candidate` (`:166-184`) | `feature, value`; `matched: MatchedFeature[]\|null` | `Rows.Candidate` |
| `Audit` (`:186-199`) | как `Rows.Audit`; `request`, `result` — `unknown` | `Rows.Audit` |

Расхождения между сторонами: `authority` в UI допускает число (`string \| number`), сервер отдаёт строку `MASTER`/`SUPPLEMENTARY` 🟡; `Conflict.kind` в UI необязателен, в БД (`V7__source_conflict_kind.sql`) и Java-record — всегда задан 🟡; `AssetHit.lastSeenAt` в UI допускает `null`, в Java — `String` без проверок 🟢.

### X.4.2 Константы и состояние

| Имя | Значение | Где |
|---|---|---|
| `ASSET_TYPES` | `ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance` | `UI/api/constants.ts:5-13` |
| `RELATION_TYPES` | 9 типов (`DECOMPOSED_INTO … DEPLOYED_FROM`, `HOSTED_ON`) | `:15-25` |
| `SOURCES` | `eam, scm, cmdb, deploymap` | `:27` |
| `EVENT_STATUSES` | 10 статусов `ProcessingStatus` | `:29-40` |
| `STALE_AFTER_MS` | 7 суток | `:56` |
| `SOURCES_REFRESH_MS` | 30 000 | `:59` |
| `LAG_WARN_SECONDS`, `LAG_BAD_SECONDS` | 900, 3600 | `:62-63` |
| `Route` | `'graph' \| 'sync'` | `UI/useHashRoute.ts:3` |
| `AsyncState<T>` | `loading \| error{message} \| ready{data}` | `UI/hooks/useAsync.ts:4-7` |
| `AuthState` | `pending \| ready \| failed` | `UI/App.tsx:9` |
| `ConsoleConfig` | `issuer, clientId, scope` | `UI/auth/authService.ts:4-8` |
