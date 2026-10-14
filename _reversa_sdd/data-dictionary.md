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
| `PROJECTED` | конечное | — |
| `QUARANTINED` | ждёт оператора | RETRYING |
| `RETRYING` | повтор | VALIDATED, NORMALIZED, RESOLVED, PROJECTED, QUARANTINED, IGNORED_OLD_VERSION |
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
> Схема PostgreSQL собирается из миграций **трёх модулей** в одном каталоге `classpath:db/migration` (`EJ/java/.../JournalMigrations.java:11-14`): `V1` — event-journal, `V2/V4/V5/V7` — identity-resolution, `V3/V6` — ingestion-service. Полный словарь схемы (Neo4j, PostgreSQL, роли, S3) и ERD — R.3: [`_reversa_sdd/database/data-dictionary.md`](database/data-dictionary.md), [`_reversa_sdd/database/erd.md`](database/erd.md); здесь — таблицы, через которые проходит логика модулей.

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
| `replayed_at` | timestamptz | да | 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] **в коде не заполняется** (code-analysis §Q1.5) |

Частичный уникальный индекс `uq_dlq_entry_open (source,event_id) WHERE replayed_at IS NULL` — «одна открытая запись на событие» (`V1__event_journal.sql:47-48`).

### Q.1.4 Raw payload в S3 🟢

Ключ `raw/<source>/<sha256 hex>` (`EJ/java/.../S3RawPayloadStore.java:74`); `source` по `[A-Za-z0-9:._-]{1,200}` и не только из точек (`:33, :70`). Метаданные объекта не задаются; целостность — сравнением SHA-256 при `get` (`:85`).

## Q.2 normalizer: вход, выход и форматы payload

### Q.2.1 Выходные типы 🟢

| Тип | Поля | Правила |
|---|---|---|
| `NormalizationResult.Normalized` (`NZ/NormalizationResult.java:19-27`) | `commands: List<GraphCommand>`, `unresolved: List<UnresolvedReference>`, `warnings: List<String>` | списки копируются `List.copyOf`; по Javadoc (`NZ/NormalizationResult.java:12`) `commands` начинается с `UpsertNode` записи события, но для EAM `SERVICE_DEPENDENCY` маппер выдаёт только `DEPENDS_ON` без `UpsertNode`, а при двух неизвестных концах `commands` пуст (`NZ/EamMapper.java:100-115`) 🔴 расхождение Javadoc и кода, см. `normalizer/questions.md` Q-01 |
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
| `status` | text NOT NULL DEFAULT `'OPEN'` | 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 3)] других значений код не выставляет |
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

---

# R.2e — `platform/*` (UNLOCKER-267)

## Y.1 source-stubs: форматы заглушек, HTTP-эндпоинты, seed

`SS/` = `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/`, `DL/` = `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/`. Времена — `Instant` в ISO-8601 UTC (в JSON через `toString()`, `SS/Json.java:209`). Форматы `DeployMapFormat` и `EamApiFormat` — `code-analysis.md` §P7, `data-dictionary.md` §P; здесь только то, что не описано там. Метки: 🟢 код · 🟡 выведено · 🔴 пробел.

### Y.1.1 Внутренние типы

| Тип | Поля | Файл |
|---|---|---|
| `StubSource.Entry` (record, private) | `seq: long` (с 1, `= размер журнала + 1`), `change: SourceChange`, `notified: boolean` (= не были подавлены webhook в момент записи) | `SS/StubSource.java:31,188` |
| `SourceChange` (из `source-spi`) | `sourceType, sourceId, sourceVersion: long, operation: ChangeOperation, completeness: Completeness, updatedAt: Instant, payload: Map<String,Object>` (копия `LinkedHashMap`, `null` → `{}`; `null`-значения полей сохраняются) | `contracts/source-spi/.../SourceChange.java:24-43` |
| `ChangePage` | `changes, nextCursor, hasMore, snapshotComplete`; инвариант: `snapshotComplete && hasMore` запрещено | `contracts/source-spi/.../ChangePage.java:17-23` |
| `WebhookEvent` (создаётся в `toEvent`) | `eventId = "<code>-evt-<seq>"`, `source = code`, `sourceType, sourceId, sourceVersion, operation, occurredAt = updatedAt`; `code` — `SourceSystem.code()`: `eam`, `scm`, `cmdb`, `deploymap` | `SS/StubSource.java:192-202`; `contracts/source-spi/.../SourceSystem.java:5-9` |
| `EamApiStore.Entry` (record, package-private) | `id: long, ver: long, ctime: Instant, mtime: Instant, attrs: Map<String,Object>` | `SS/EamApiStore.java:96` |
| `StubSourceServer.Reply`, `EamApiStub.Reply` | `status: int, body: String` | `SS/StubSourceServer.java:182`, `SS/EamApiStub.java:239` |

### Y.1.2 Курсоры `StubSource` (непрозрачны для потребителя)

| Формат | Смысл | Где создаётся / разбирается |
|---|---|---|
| `null` | начать snapshot; `bound = число записей журнала` | `SS/StubSource.java:134-136` |
| `s<after>:<bound>` | продолжение snapshot: вернуть живые объекты на момент `bound` с `seq > after` | `:137-140,166` |
| `i<seq>` | инкремент: записи журнала с `seq > <seq>` (включая `DELETE`) | `:113-114,130,166` |
| иной префикс | `IllegalArgumentException("unknown cursor")` → `400 {}` на HTTP | `:141-143` |
| `s…` без `:` | `ArrayIndexOutOfBoundsException` — не `400` (🟡, см. `code-analysis.md` §Y1.8) | `:138-139` |

После последней страницы snapshot следующий курсор — `i<bound>` и `snapshotComplete=true`. 🟢

### Y.1.3 HTTP `StubSourceServer` (`SS/StubSourceServer.java`)

Базовый URL по умолчанию — `http://127.0.0.1:<свободный порт>`; в контейнере слушает `0.0.0.0:ARCHRAG_STUB_PORT` (Y.2.1). Аутентификации нет. 🟢

| Метод и путь | Параметры | Успех | Ошибки |
|---|---|---|---|
| `GET /changes` | `cursor` (необяз., пустой = `null`), `limit` (необяз., целое, умолч. **100**; `≤ 0` недопустимо) (`:33,83-85`) | `200`, тело ниже | `400 {}` (нечисловой/`≤ 0` `limit`, разобранный, но неизвестный курсор); `405 {}` не-`GET`; `429`/`5xx` из запланированного сбоя; `504` при статусе сбоя `0` |
| `GET /objects/{type}/{id}` | два сегмента пути, URL-декодируются | `200` — описание изменения (последняя запись журнала, **в том числе `DELETE`**) | `404 {}` (неизвестный объект или иное число сегментов); `405`; сбои как выше |
| любой другой путь | — | — | `404` без тела (поведение `HttpServer`) 🟡 |

Тело `GET /changes` (`pageJson`, `:136-145`):

| Поле | Тип | Источник |
|---|---|---|
| `changes` | массив описаний изменения | `ChangePage.changes()` |
| `nextCursor` | строка | Y.1.2 |
| `hasMore` | boolean | есть ещё записи |
| `snapshotComplete` | boolean | `true` только на последней странице snapshot |

Описание изменения (`changeJson`, `:147-157`): `sourceType` (строка), `sourceId` (строка), `sourceVersion` (целое), `operation` (`UPSERT`/`DELETE`), `completeness` (`COMPLETE`/`PARTIAL`), `updatedAt` (ISO-8601 UTC), `payload` (объект, `null`-значения полей сохраняются). 🟢

Сбои: `SourceUnavailableException(status, retryAfter)` → HTTP-статус `status` (`0` → `504`), заголовок `Retry-After: <секунды>`, если `retryAfter ≠ null` (`:113-117`); расписание — `failOnCall(n, status, retryAfter)`, нумерация вызовов с 1 общая для `/changes` и `/objects/`, считаются только `GET` (`:74-77,107-111,129-134`). Источник-уровня сбой `StubSource.failNext(times, …)` отдаётся тем же путём. 🟢

### Y.1.4 Seed и fixtures (`StubSources`, `EamApiSeed`)

#### StubSources (по источникам, `SS/StubSources.java`)

Часы в тестах — фиксированные (`2026-10-04T12:00:00Z`), в `DemoLauncher` — `Clock.systemUTC()`. Константы типов: `IT_SYSTEM`, `TEAM`, `SERVICE`, `REPOSITORY`, `COMPUTE_INSTANCE`, `SERVICE_DEPENDENCY` (`:18-24`).

| Источник | `sourceType` | `sourceId` | Поля payload | Строки |
|---|---|---|---|---|
| EAM | `TEAM` | `TEAM-PAY` | `name = "Payments Team"` | `:40` |
| EAM | `IT_SYSTEM` | `EAM-2001` | `name = "Ledger"`, `ownerTeam = "TEAM-PAY"` | `:41` |
| EAM | `IT_SYSTEM` | `EAM-1042` | `name = "Payments Core"`, `ownerTeam = "TEAM-PAY"` | `:42` |
| EAM | `SERVICE_DEPENDENCY` | `dep-payments-api-ledger-api` | `from = svc-payments-api`, `to = svc-ledger-api`, `kind = SYNC`, `protocol = HTTP`, `criticality = HIGH` | `:43-51` |
| SCM | `REPOSITORY` | `repo-payments-api` | `url = https://git.example.org/pay/payments-api`, `defaultBranch = main` | `:57-60` |
| SCM | `SERVICE` | `svc-payments-api` | `name = payments-api`, `systemCode = EAM-1042`, `repositoryId = repo-payments-api` | `:61-65` |
| SCM | `SERVICE` | `svc-ledger-api` | `name = ledger-api`, `systemCode = EAM-2001` | `:66-69` |
| CMDB | `COMPUTE_INSTANCE` | `vm-pay-01` | `hostname = vm-pay-01.prod.example.org`, `kind = VIRTUAL_MACHINE`, `state = RUNNING`, `environment = prod` | `:75-79` |
| DEPLOY_MAP | `ENVIRONMENT` | `prod` | `format = deploymap-poc-0`, `name = Production` | `:92-93` |
| DEPLOY_MAP | `DEPLOYMENT` | `dep-payments-api-prod` | формат `deploymap-poc-1` (см. ниже) | `:94-103,181-200` |

Каждый объект создаётся один раз, значит `sourceVersion = 1`, `seq` — по порядку строк выше; для EAM `eventId` webhook'ов: `eam-evt-1` (TEAM) … `eam-evt-4` (зависимость) (тест `SST/StubWebhookSenderTest.java:63`). 🟢

`DEPLOYMENT` в `deploymap-poc-1` (`deploymentV1`, `:181-200`; формат — `code-analysis.md` §P7.1):

| Поле payload | Значение seed `dep-payments-api-prod` |
|---|---|
| `format` | `deploymap-poc-1` |
| `name`, `chart` | `payments-api` (🟡 `name` не описан в Javadoc `DeployMapFormat`) |
| `service` | `svc-payments-api` |
| `environment` | `prod` |
| `chartVersion` | `1.4.2` |
| `chartMeta` | `{name = chart, version = chartVersion, appVersion = chartVersion, annotations}` |
| `annotations["archrag.io/eam-system-id"]` | `"5101"` (строка; `EamApiSeed.SYSTEM_PAYMENTS_CORE`) |
| `annotations["archrag.io/git-repo-url"]` | `git@git.example.org:pay/payments-api.git` (`PAYMENTS_API_SSH_URL`, `:111`) |
| `annotations["archrag.io/git-commit"]` | `9fceb02d0ae598e95dc970b74767f19372d61af8` (`PAYMENTS_API_COMMIT`, `:108`) |
| `hosts` | `["vm-pay-01"]` |

Негативные сценарии (не входят в seed, добавляются хелперами; все — `DEPLOYMENT` в окружении `prod`):

| Хелпер (`SS/StubSources.java`) | `sourceId` | Отличие от seed | Что проверяет в системе |
|---|---|---|---|
| `addDeploymentWithUnknownRepository` (`:121`) | `dep-unknown-repo-prod` | chart `unknown-repo`, `chartVersion = 0.1.0`, URL `https://git.example.org/pay/unknown-repo.git` | репозитория нет в SCM |
| `addDeploymentWithSystemMismatch` (`:138`) | `dep-payments-api-mismatch` | `eam-system-id = "5102"` при репозитории системы 5101 | конфликт системы (ADR [0030](../docs/adr/0030-deployment-system-conflict.md)) |
| `addDeploymentWithInvalidCommit` (`:153`) | `dep-payments-api-bad-commit` | `git-commit = "not-a-commit"` | невалидный commit |
| `addDeploymentRedeploy` (`:169`) | `dep-payments-api-prod` (тот же) | `chartVersion = 1.4.3`, commit `1b4f0e9851971998e732078544c96b36c3d01ced` (`PAYMENTS_API_NEW_COMMIT`, `:114`) | смена commit, `sourceVersion+1` |
| `addServiceWithBrokenReference` (SCM, `:207`) | `svc-orphan` (`SERVICE`) | `name = orphan`, `systemCode = EAM-UNKNOWN` | битая ссылка на систему EAM |

#### EamApiSeed (`SS/EamApiSeed.java:49-68`)

Ключ — целое `id` внутри типа. `ver = 1`, `ctime = mtime = Clock.instant()` при создании.

| Тип | `id` | `attrs` |
|---|---|---|
| `platforms` | `3101` | `name = Kubernetes Platform`, `status = ACTIVE`, `lifecycle = PRODUCTION`, `description = Shared container runtime` |
| `platforms` | `3102` | `name = PostgreSQL Platform`, `status = ACTIVE`, `lifecycle = PRODUCTION`, `description = Managed relational database` |
| `itproducts` (ИТ-решение, 🟡 допущение) | `7201` | `name = Card Processing`, `status`, `lifecycle`, `description = Card payments processing solution`, `platform_id = 3101` |
| `itproducts` | `7202` | `name = Ledger Engine`, `status`, `lifecycle`, `description = Double-entry ledger solution`, `platform_id = 3102` |
| `itsystems` | `5101` | `name = Payments Core`, `eam_id = EAM-1042`, `status = ACTIVE`, `criticality = HIGH`, `description = Payments processing system`, `product_id = 7201`, `platform_id = 3101`, `ownerTeam = TEAM-PAY` |
| `itsystems` | `5102` | `name = Ledger`, `eam_id = EAM-2001`, `status`, `criticality = HIGH`, `description = General ledger system`, `product_id = 7202`, `platform_id = 3102`, `ownerTeam = TEAM-PAY` |

Константы: `PLATFORM_TYPE = "platforms"`, `IT_SYSTEM_TYPE = "itsystems"`, `SOLUTION_TYPE = "itproducts"` (допущение, кандидаты `itproducts`, `msys`, `okita`, `aknsd`, `:23-27`), `OWNER_TEAM = "TEAM-PAY"` (допущение 9, `:37`). `systemCode` SCM → `eam_id` → `id` (`docs/06-eam-integration.md`). 🟢

### Y.1.5 HTTP `EamApiStub` (формат `docs/eam-api.yaml`, `SS/EamApiStub.java`)

Заголовок `Authorization: Token <токен>` обязателен на всех путях (`:93-95,186-191`). Умолчание — loopback, порт 0; в контейнере `0.0.0.0:ARCHRAG_STUB_PORT` (Y.2.1). Ответы `Content-Type: application/json`. Ошибки — `{"detail":"<текст>"}`. 🟢

| Метод и путь | Параметры query | Успех | Ошибки |
|---|---|---|---|
| `GET /api/{type}/` | `page` (целое ≥ 1, умолч. 1), `page_size` (целое ≥ 1, умолч. **50**, зажимается до **500**), `sort` (`id`/`ctime`/`mtime`, список через запятую, `-` = убывание), `fields` (список ключей `attrs` через запятую, без пробелов) | `200`, **голый массив** `ArchObject[]` (без `total`/`next`); страница за концом — `[]` | `400` (нечисловой/`< 1` `page`/`page_size`, другой ключ `sort`); `404` (тип неизвестен хранилищу) |
| `GET /api/{type}/{id}/` | `fields` | `200`, один `ArchObject` | `400` (`id` не целое), `404` (нет объекта / архив / тип) |
| `GET /api/types/` | — | `200`, массив `typeObject` по возрастанию имени типа | — |
| `GET /api/types/{id\|name}/` | — | `200`, `typeObject` (поиск по числовому id **или** по имени типа) | `404` |
| `GET /api/types/{id\|name}/options/` | `kind` ∈ {`fields`, `system`} (обязателен) | `200`, `{"options":[…]}` | `400` (`kind must be one of [...]`), `404` |
| не-`GET` | — | — | `405 {"detail":"Method not allowed"}` (только после проверки токена) |
| без/с неверным токеном, схема не `Token` | — | — | `401 {"detail":"Authentication credentials were not provided."}` |
| глубже `/api/{type}/{id}/…` | — | — | `404` |

`ArchObject` в ответе (`archObject`, `:167-184`; разбор и проверка этой формы — `EamApiFormat`, и вход адаптера — `data-dictionary.md` §P, «ArchObject»):

| Поле | Тип | Значение |
|---|---|---|
| `id` | integer | ключ объекта |
| `ver` | integer | 1 при создании, `+1` при каждом **изменении** `attrs` (повторная запись тех же `attrs` — no-op) (`SS/EamApiStore.java:28-37`) |
| `datetime` | string (ISO-8601 UTC) | `mtime` объекта |
| `attrs` | object | все поля либо подмножество по `fields` |

`typeObject` (`:158-165`): `id` = `TYPE_IDS` (`platforms` = 11, `itsystems` = 12, `itproducts` = 13; допущение, `SS/EamApiSeed.java:30`), `ver = 1`, `datetime = "2026-01-01T00:00:00Z"`, `attrs = {name: <заголовок>, typeurl: <имя типа>}`; заголовки: `platforms` — «ИТ-платформы», `itsystems` — «ИТ-системы», иначе — «ИТ-решения» (`SS/EamApiTypeMeta.java:17-23`).

Опции типа (`SS/EamApiTypeMeta.java:25-61`, 🟡 состав ключей — допущение): элемент `{name, type[, target, multiple]}`; `target`/`multiple` есть только у `reference`.

| `kind` | Тип | Опции (`name: type[→target]`) |
|---|---|---|
| `system` | все | `eam_id: string`, `eam_name: string`, `eam_permalink: string` |
| `fields` | все | `name: string`, `status: select`, `description: text` |
| `fields` | `platforms` | + `lifecycle: select` |
| `fields` | `itsystems` | + `criticality: select`, `product_id: reference→itproducts`, `platform_id: reference→platforms` (`multiple=false`) |
| `fields` | `itproducts` (прочие) | + `lifecycle: select`, `platform_id: reference→platforms` |

Расхождения со спекой (🔴/🟡, подробнее `code-analysis.md` §Y1.8): `kind=groups|computed` и параметры `search*`, `enrich`, `only_names` спеки заглушкой не поддержаны (`docs/eam-api.yaml:15-58,18075-18079`). `sort=","` приводит к `NullPointerException` (необработано).

### Y.1.6 Webhook `StubWebhookSender`

| Элемент | Значение | Источник |
|---|---|---|
| Метод и цель | `POST <target>` (URL endpoint адаптера, обычно `…/webhook`) | `SS/StubWebhookSender.java:72-80` |
| Заголовок `Content-Type` | `application/json` | `:75` |
| `X-Archrag-Event-Id` | `eventId` события | `:76`; `WebhookSignature.EVENT_ID_HEADER` |
| `X-Archrag-Timestamp` | секунды Unix (`Instant.getEpochSecond()`) | `:77`; `contracts/source-spi/.../WebhookSignature.java:28` |
| `X-Archrag-Signature` | `sha256=<hex>` от HMAC-SHA256 над `timestamp + "." + body` секретом | `:78`; `WebhookSignature.java:25,33,47-48` |
| Тело | `{"eventId","source","sourceType","sourceId","sourceVersion","operation","occurredAt"}` — **без** payload объекта | `:59-69` |
| Таймауты | подключение 5 с, запрос 10 с | `:26,74` |
| Результат | HTTP-статус ответа; транспортная ошибка → `IllegalStateException("webhook delivery failed: <класс>")` | `:82-89` |

Режимы: `send` (текущее время), `sendAt(event, ts)` (проверка replay window), `sendWithBadSignature` (секрет `secret + "-wrong"`). Секрет в сообщения не попадает. 🟢

### Y.1.7 Константы и ограничения

| Константа | Значение | Файл |
|---|---|---|
| `StubSourceServer.DEFAULT_LIMIT` | 100 | `SS/StubSourceServer.java:33` |
| `EamApiStub.DEFAULT_PAGE_SIZE` / `MAX_PAGE_SIZE` | 50 / 500 | `SS/EamApiStub.java:39,41` |
| `EamApiStub.OPTION_KINDS` | `fields`, `system` | `:43` |
| `EamApiSeed.TYPE_IDS` | `platforms` 11, `itproducts` 13, `itsystems` 12 | `SS/EamApiSeed.java:30` |
| JSON: числа | без `.`, `e`, `E`, `+` → `Long`, иначе `Double` | `SS/Json.java:164-175` |
| JSON: глубина/размер | не ограничены (в отличие от `adapter-core.Json`, `MAX_DEPTH = 64`) 🟡 | `SS/Json.java` |

---

## Y.2 demo-launcher: env-переменные, режимы, управляющие эндпоинты

### Y.2.1 Переменные окружения (`DL/LauncherConfig.java:17-36`)

В стенде значения подаёт compose: обычные — `environment:`, секреты — Docker secrets (`platform/docker/docker-compose.yml:17-26`), которые `platform/docker/entrypoint.sh:4-10` экспортирует в env под именем файла. «Режим»: **A** — `adapter <SRC>`, **S** — `stub <SRC>`, **E** — `eam-api-stub`.

| Переменная | Режим | Обяз. | Умолчание | Тип и проверка | Строка |
|---|---|---|---|---|---|
| `ARCHRAG_PG_URL` | A | да | — | JDBC URL, непустая строка (не логируется) | `:17,109` |
| `ARCHRAG_PG_USER` | A | да | — | непустая строка | `:18,110` |
| `ARCHRAG_PG_PASSWORD` | A | да | — | секрет | `:19,111` |
| `ARCHRAG_S3_ENDPOINT` | A | да | — | URI (`URI.create`) | `:20,112,122` |
| `ARCHRAG_S3_ACCESS_KEY` | A | да | — | секрет | `:21,113` |
| `ARCHRAG_S3_SECRET_KEY` | A | да | — | секрет | `:22,114` |
| `ARCHRAG_S3_BUCKET` | A | да | — | непустая строка | `:23,115` |
| `ARCHRAG_SOURCE_URL` | A | да | — | URI базового API источника (для EAM в режиме `eam-api` — только `TEAM` и `SERVICE_DEPENDENCY`) | `:24,116,123` |
| `ARCHRAG_WEBHOOK_SECRET` | A; S — если задан `WEBHOOK_TARGET` | да (в этих режимах) | — | секрет подписи webhook | `:25,117,148` |
| `ARCHRAG_WEBHOOK_PORT` | A | нет | `8080` | целое 1..65535 | `:26,123,174-188` |
| `ARCHRAG_HEALTH_PORT` | A | нет | `8090` | целое 1..65535 | `:27,123` |
| `ARCHRAG_POLL_INTERVAL_SECONDS` | A | нет | `30` | целое ≥ 1 (секунды) | `:28,124,190-203` |
| `ARCHRAG_RECONCILE_INTERVAL_SECONDS` | A | нет | `21600` (6 ч) | целое ≥ 1 | `:29,124` |
| `ARCHRAG_EAM_MODE` | A (только `EAM`) | нет | `legacy` | `legacy` либо `eam-api`; пусто = `legacy`; иное — ошибка; для SCM/CMDB/DEPLOY_MAP игнорируется | `:33,35-36,128-139` |
| `ARCHRAG_EAM_API_URL` | A `EAM` при `eam-api` | да (в этом режиме) | — | URI базового API (`.strip()`) | `:34,136,138` |
| `ARCHRAG_EAM_API_TOKEN` | A `EAM` при `eam-api`; E | да (в этих режимах) | — | секрет для `Authorization: Token …` | `:31,137,158` |
| `ARCHRAG_STUB_PORT` | S, E | нет | `8080` | целое 1..65535 (слушает все интерфейсы) | `:30,143-152,162` |
| `ARCHRAG_WEBHOOK_TARGET` | S | нет | — (webhook не шлётся) | URI endpoint адаптера (`.trim()`); если задан, нужен `ARCHRAG_WEBHOOK_SECRET` | `:32,143-152` |

Не конфигурируется через env (константы кода): порт управляющего входа **8091** (`DL/DemoLauncher.java:54`), размер страницы polling **100**, окно replay `5 мин` (`AdapterConfig.DEFAULT_REPLAY_WINDOW`), `RetryPolicy.defaults()` (`DL/DemoLauncher.java:114-116`). 🟢 Пустая или пробельная строка приравнивается к «не задано» (`required`/`port`/`seconds`, `LauncherConfig.java:165-203`).

Значения compose, которые подают роли (`platform/docker/docker-compose.yml`): адаптеры — общие `x-adapter-env` (`ARCHRAG_PG_URL`, `ARCHRAG_PG_USER`, `ARCHRAG_S3_ENDPOINT = http://seaweedfs:8333`, `ARCHRAG_S3_BUCKET`) и секреты (`pg_password`, `s3_access_key`, `s3_secret_key`, `webhook_secret`) (`:16-26`); `ARCHRAG_SOURCE_URL = http://stub-<src>:8080` (`:323,345,359,373`); `adapter-eam` дополнительно `ARCHRAG_EAM_MODE = ${ARCHRAG_EAM_MODE:-eam-api}`, `ARCHRAG_EAM_API_URL = http://eam-api-stub:8080`, секрет `eam_api_token` (`:319,324-325`); заглушки — `ARCHRAG_WEBHOOK_TARGET = http://adapter-<src>:8080/webhook` + секрет `webhook_secret` (`:387-389,403-405,414-416,425-427`); `eam-api-stub` — секрет `eam_api_token` (`:439`). 🟢

### Y.2.2 Записи конфигурации (`DL/LauncherConfig.java`)

| Запись | Поля | Замечания |
|---|---|---|
| `Adapter` (`:49`) | `system: SourceSystem, pgUrl, pgUser, pgPassword, s3Endpoint: URI, s3AccessKey, s3SecretKey, s3Bucket, sourceUrl: URI, webhookSecret, webhookPort: int, healthPort: int, pollInterval: Duration, reconcileInterval: Duration, eamApi: EamAdapter.EamApi` | `eamApi == null` в режиме `legacy` и для не-EAM; `toString` печатает `system, sourceUrl, webhookPort, healthPort, eamApi, secrets=***` (`:66-70`) |
| `Stub` (`:80`) | `system, port: int, webhookTarget: URI?, webhookSecret: String?` | секрет задан тогда и только тогда, когда задана цель (`:142-153`); `toString` — `secret=***` |
| `EamApiStub` (`:89`) | `port: int, token: String` | `toString` — `token=***` |
| `EamAdapter.EamApi` (`adapters/eam-adapter/.../EamAdapter.java:47-53`) | `baseUri: URI, token: String` | `toString` — `token=***` |

### Y.2.3 Аргументы командной строки и коды выхода (`DL/DemoLauncher.java:58-83`)

| Вызов | Действие | Выход |
|---|---|---|
| `adapter <EAM\|SCM\|CMDB\|DEPLOY_MAP>` | `runAdapter` (Y.2.4) | блокируется до SIGTERM/SIGINT |
| `stub <EAM\|SCM\|CMDB\|DEPLOY_MAP>` | `runStub`: `StubSourceServer` на `ARCHRAG_STUB_PORT` + `StubControl` на 8091 | блокируется |
| `eam-api-stub` | `runEamApiStub`: `EamApiStub` на `ARCHRAG_STUB_PORT` + `EamApiControl` на 8091 | блокируется |
| иное число/значение аргументов | `usage: DemoLauncher <adapter\|stub> <EAM\|SCM\|CMDB\|DEPLOY_MAP> \| DemoLauncher eam-api-stub` в stderr | `2` |
| неверная/пустая конфигурация (`IllegalArgumentException`) | `configuration error: <сообщение с именами переменных>` в stderr | `2` |
| ошибка старта сервера (`IOException`, bind) | необработанное исключение | `1` (JVM) 🟡 |

Код источника — точное имя элемента `SourceSystem` (`EAM`, `SCM`, `CMDB`, `DEPLOY_MAP`); регистр важен, иначе `unknown source system, expected one of [...]` (`LauncherConfig.java:98-104`).

### Y.2.4 Порты и эндпоинты процесса по ролям

| Роль | Порт | Эндпоинты | Кто вызывает |
|---|---|---|---|
| `adapter` | `ARCHRAG_WEBHOOK_PORT` (8080) | `POST /webhook` (HMAC), `POST /control/snapshot` (без аутентификации) — `WebhookServer` из `adapter-core` | stub (`/webhook`), ingestion `/admin/reconcile` (`/control/snapshot`) |
| `adapter` | `ARCHRAG_HEALTH_PORT` (8090) | `GET /health` → `200 {"status":"UP"}` после старта webhook, polling и reconciliation, иначе (и на не-`GET`) `503 {"status":"DOWN"}` (`DL/DemoLauncher.java:147-160`) | healthcheck compose |
| `stub` | `ARCHRAG_STUB_PORT` (8080) | `GET /changes`, `GET /objects/{type}/{id}` (Y.1.3) | адаптер |
| `stub` | 8091 | `POST /control/upsert` (Y.2.5) | операторские скрипты стенда изнутри контейнера |
| `eam-api-stub` | `ARCHRAG_STUB_PORT` (8080) | `/api/**` только чтение (Y.1.5) | `adapter-eam` в режиме `eam-api` |
| `eam-api-stub` | 8091 | `POST /control/upsert`, `POST /control/archive` (Y.2.6) | `measure.sh`, `backup-restore-check.sh` |

Порты 8091 не публикуются и не проксируются (`platform/docker/README.md:51`, `docker-compose.yml:432`). 🟢

### Y.2.5 `POST /control/upsert` заглушки источника (`DL/StubControl.java`)

Запрос (тело ≤ **65536** байт, `MAX_BODY_BYTES`, `:31`):

| Поле | Тип | Обяз. | Правило |
|---|---|---|---|
| `type` | строка | да | непустая (`sourceType`) — `:106-111` |
| `id` | строка | да | непустая (`sourceId`) |
| `payload` | объект | да | не объект → `400`; значения произвольного JSON, `null` допускается |
| `notify` | boolean | нет | умолч. `true`; отправка webhook отключается только значением `false` (`:69`) |

Ответ:

| Код | Тело | Когда |
|---|---|---|
| `200` | `{"sourceVersion":N}` | upsert выполнен; без webhook (нет `StubWebhookSender` или `notify:false`) |
| `200` | `{"sourceVersion":N,"webhookStatus":S}` | webhook отправлен; `S` — HTTP-статус адаптера (в т.ч. `401`, `202`) или `0`, если доставить не удалось |
| `400` | `{"error":"invalid request"}` | не JSON, не объект, тело > 64 KiB, не заполнены `type`/`id`, `payload` не объект — без эха ввода |
| `405` | `{"error":"POST expected"}` | любой метод, кроме `POST` |

Upsert всегда `COMPLETE` (`StubSource.upsert`); удаление и `PARTIAL` через control недоступны 🟡. Событие webhook — последнее для `(type, id)` из `webhookEvents()` (`eventId = <code>-evt-<seq>`), поэтому `notify` при подавленных webhook (недоступно через control) вернёт `0` (`:90-95`).

### Y.2.6 `POST /control/upsert` и `/control/archive` заглушки EAM (`DL/EamApiControl.java`)

| Путь | Тело (≤ 65536 байт, `:25`) | Успех | Ошибки |
|---|---|---|---|
| `POST /control/upsert` | `{"type": "platforms"\|"itproducts"\|"itsystems", "id": <целое>, "attrs": <объект>}` | `200 {"ver":N}` — `attrs` **заменяются целиком**; `ver=1` у нового, `+1` при изменении, прежнее при идентичных `attrs` | `400 {"error":"invalid request"}` (не JSON, неизвестный `type`, нецелый `id`, `attrs` не объект, тело слишком большое); `405 {"error":"POST expected"}` |
| `POST /control/archive` | `{"type": …, "id": <целое>}` | `200 {}` — объект исчезает из `GET /api/{type}/` и `/{id}/`; для неизвестного объекта тоже `200` | те же `400`/`405` |

Допустимые `type` зашиты литералами `Set.of("platforms","itproducts","itsystems")` (`:27`), а не константами `EamApiSeed` (🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 20)] `code-analysis.md` §Y2.7). `id` принимается только как JSON-целое (`Long` из `adapter-core.Json`), дробное и строковое значения отвергаются. 🟢

### Y.2.7 Лимиты и таймауты

| Параметр | Значение | Файл |
|---|---|---|
| Максимальное тело control | 64 KiB (оба control) | `DL/StubControl.java:31`, `DL/EamApiControl.java:25` |
| Глубина JSON тела control | ≤ 64 (`adapter-core.Json.MAX_DEPTH`) | `adapters/adapter-core/.../Json.java:18` |
| Таймаут webhook-отправки из control | подключение 5 с / запрос 10 с (синхронно в обработчике) | `SS/StubWebhookSender.java:26,74` |
| Порог готовности адаптера | `/health` = 200 после `startWebhook` + `startPolling` + `startReconciliation` | `DL/DemoLauncher.java:124-127` |


## Z.1 integration-tests: фикстуры, образы, хелперы

`IT/` = `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/`. Раздел описывает только то, что утверждают тесты (форматы входов, константы, образы, свойства, claims); поведение — `code-analysis.md` §Z1. Фикстуры целиком не приводятся: даны формат и назначение. 🟢 — код; 🟡 — выведено; 🔴 — пробел.

### Z.1.1 Образы контейнеров

| Сервис | Образ | Строка | Примечание |
|---|---|---|---|
| Neo4j Community | `neo4j:5.26.31-community` | `IT/TestImages.java:9` (`TestImages.NEO4J`, `DockerImageName`) | 5.26 LTS, точный тег; только Community-возможности |
| PostgreSQL | `postgres:16` | `IT/ContainersSmokeIT.java:41` и литерал `new PostgreSQLContainer("postgres:16")` в остальных 22 классах | мажорный тег, не точный 🟡 |
| S3-совместимое | `chrislusf/seaweedfs:4.48` | `IT/ContainersSmokeIT.java:35` (`S3_IMAGE`) | SeaweedFS, не MinIO — ADR 0007; запуск `server -s3 -dir=/data`, порт 8333 (`:52-60`) |

Фабрика S3-контейнера `ContainersSmokeIT.s3Container()` (`:52`) общая для 11 классов; ключи `S3_ACCESS_KEY = "test-access-key"` (`:44`), `S3_SECRET_KEY = "test-secret-key"` (`:46`) передаются контейнеру как `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` (`:55-56`). Ожидание: HTTP `GET /` на 8333 с кодом < 500, `withStartupTimeout(120 s)` (`:58-59`). Версия Testcontainers `2.0.5` (`pom.xml:30`), пакеты `org.testcontainers.neo4j.Neo4jContainer`, `org.testcontainers.postgresql.PostgreSQLContainer`. 🟢

### Z.1.2 Системные свойства, флаги и профили

| Имя | Где | Значение / эффект |
|---|---|---|
| Maven-профиль `integration-tests` | `platform/integration-tests/pom.xml:138-157` | подключает `maven-failsafe-plugin` с целями `integration-test`, `verify`; без профиля `*IT` не выполняются |
| `archrag.it.queryTimeoutSeconds` (JVM `-D`, `Long.getLong`) | `IT/GetAssetIT.java:46`, `SearchAssetsIT.java:51`, `TraceDependenciesIT.java:47`, `DeploymentCommitReadIT.java:39`, `ExplainProvenanceIT.java:68` | таймаут запроса в `QueryLimits`, по умолчанию 5 с; в `GraphQueryCoreIT:40`, `RuntimeFootprintIT:31`, `AdminConsoleIT:66` — фиксированные 5 с 🟡 (флаг нигде не документирован и в `pom.xml`/CI не передаётся) |
| Отбор по имени | CLI | `-Dit.test=<Class>[#method]` (failsafe); для `@Order`-классов одиночный метод невозможен (см. §Z1.6.2) |

### Z.1.3 Бюджет запросов в IT (`QueryLimits`)

`QueryLimits(maxDepth, maxNodes, maxPaths, timeout, maxResponseBytes)` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java:14-15`).

| Контекст | Значения | Где |
|---|---|---|
| Прод-подобный потолок для чтения напрямую | `(6, 500, 50, 5 s, 512 KiB)` | `IT/GraphQueryCoreIT.java:40`, `GetAssetIT.java:46`, `SearchAssetsIT.java:51`, `TraceDependenciesIT.java:46-47`, `RuntimeFootprintIT.java:31`, `AdminConsoleIT.java:66` |
| Жёсткий потолок для проверки clamp | `(6, 3, 50, 5 s, 512 KiB)` | `GraphQueryCoreIT.java:189` |
| Тот же потолок для MCP-приложения | `archrag.query.limits.max-depth=6, max-nodes=500, max-paths=50, timeout=5s, max-response-bytes=512KB` | `F1AcceptanceIT.java:213-218` (аналогично F2–F4) |
| Консоль с малым бюджетом | `archrag.query.limits.max-nodes=3` (контекст `tinyConsole`) | `AdminConsoleIT.java:84-86` |
| Прогрев холодного Neo4j | таймаут 120 с | `DeploymentCommitReadIT.java:63` |

### Z.1.4 Константы фикстур

| Константа | Значение | Где | Назначение |
|---|---|---|---|
| `McpTestJwt.RESOURCE` | `https://mcp.test/mcp` | `IT/McpTestJwt.java:33` | и issuer, и audience по умолчанию в токенах; `archrag.mcp.security.resource-uri` MCP |
| `AUDIENCE` (консоль) | `urn:archrag:console` | `AdminConsoleIT.java:57`, `SyncConsoleIT.java:41` | audience токенов консоли |
| `ADMIN` / `READ` / `architecture.other` | `architecture.admin` / `architecture.read` / `architecture.other` | `AdminConsoleIT.java:56`, `F1AcceptanceIT.java:85`, `F1AcceptanceIT.java:354` | scope'ы: админ консоли и ingestion, чтение MCP, заведомо чужой |
| `ADMIN_RESOURCE` | `McpTestJwt.RESOURCE` | `AdminEndpointIT.java:59` | audience/issuer admin REST в этом тесте |
| Чужой audience | `https://other.test/api` | `AdminEndpointIT.java:376` | негатив 401 |
| `azp`: допустимый / недопустимый | `archrag-demo` / `archrag-admin-console` | `McpTestJwt.java:64-69` (умолчание), `AdminEndpointIT.java:384,387` | проверка `AuthorizedPartyValidator` |
| `KEY` (HS256) | `SecretKeySpec("0123456789abcdef0123456789abcdef", "HmacSHA256")` | `F1AcceptanceIT.java:83`, `F2–F4`, `SyncAcceptanceIT.java:92`, `EamKeyMigrationIT.java:83`, `JournalDispatcherIT.java:66` | ключ подменённого `JwtDecoder` (тестовая константа, не секрет) |
| Секрет webhook | `it-webhook-secret` (`AdapterInboxIT:42`), `f1-webhook-secret` … `f4-webhook-secret` (`F1AcceptanceIT:81` и далее), `acceptance-webhook-secret` (`SyncAcceptanceIT:90`), `migration-webhook-secret` (`EamKeyMigrationIT:81`) | — | HMAC-подпись `StubWebhookSender` |
| Бакеты S3 | `f1-acceptance-it-bucket` … `f4-…`, `acceptance-it-bucket`, `migration-it-bucket`, `admin-it-bucket` (`AdminEndpointIT:94`), `dispatcher-it-bucket`, `smoke-bucket` (`ContainersSmokeIT:81`) | — | свой бакет на класс |
| Пароли учёток | `reader-pass` (Neo4j `mcp-reader` в `F1–F4`, `console-reader`: `AdminConsoleIT:78`); `ro-pass` (роль PG `archrag_console_ro`: `SyncConsoleIT.java:42`); `unused` (пароль PG-пула в `AdminConsoleIT:146`) | — | тестовые, не секреты 🟢 |
| `EamApiSupport.TOKEN` | `it-eam-api-token` | `IT/EamApiSupport.java:19` | Bearer-токен заглушки `eam-api` |
| Источники | `urn:corp:eam`, `urn:corp:scm`, `urn:corp:cmdb`, `urn:corp:deploymap` | `AdapterInboxIT:43`, `JournalDispatcherIT:65`, `ReconciliationIT:67` | значения `inbox_event.source` |
| Raw-ссылка в unit-подобных IT | `new RawPayloadRef("raw/1", "sha256:abc")` | `GraphProjectorIT.java:69`, `EamApiProjectionIT.java:48`, `DeployedFromIT.java:49`, `SourceConflictIT.java:62`, `PlatformInvariantIT.java:49`, `ExplainProvenanceIT.java:65`, `IdentityCandidateIT.java:49` | заглушка ссылки на raw в S3: хранилище не используется, `NoopRawStore` в `ReconciliationIT`/`SourceConflictIT`/`ExplainProvenanceIT` |
| Время событий | `T1 = 2026-10-01T10:00:00Z`, `T2 = …-02`, `T3 = …-03`, `T4 = …-04`; `2026-01-01/02/03T10:00:00Z` в чтении | `GraphProjectorIT:70-71`, `PlatformInvariantIT:50-53`, `GetAssetIT:77-78` | UTC `Instant`, часы не подменяются; реальные часы — в классах с адаптерами |
| Бюджеты времени | `SLA_P95 = 30 s`, `LATENCY_SAMPLES = 20`, `DISPATCHER_POLL = 1 s` | `SyncAcceptanceIT.java:94-97` | критерий 2 |
| | `BUDGET_P95 = 5 s`, `BUDGET_COLD = 15 s`, `WARM_CALLS = 10` | `F3AcceptanceIT.java:248-250` | задержка MCP |
| | `TIMEOUT = 90 s` | `F1–F4AcceptanceIT` (`F1:84`, `F2:88`, `F3:87`, `F4:106`) | `awaitTrue` |

Фиксированные gid (UUID-литералы вида `11111111-…`, `22222222-…`) используются в чтении без проектора: `AdminConsoleIT:58-62`, `GetAssetIT:47-53`, `SearchAssetsIT:52-59`, `TraceDependenciesIT:50-57` (+ `aaaaaaaa-…` и `eeeeeeee-0000-…-0001/0002` — `:222-224`), `RuntimeFootprintIT:64`, `DeploymentCommitReadIT:40`. В `DeploymentCommitReadIT` и `TraceDependenciesIT.seedDenseGraph` встречаются gid-строки не-UUID (`dep-1`, `repo-1`, `d1`, `ci`), т. е. граф вставляется в обход constraint-схемы по формату gid 🟡.

### Z.1.5 Форматы входов фикстур

**Событие журнала (`CanonicalEvent`)**, собираемое в IT вручную 🟢 (`GraphProjectorIT.java:130-143`, `EamApiProjectionIT.java:95-110`, `PlatformInvariantIT.java:110-120`):

| Поле | Значение |
|---|---|
| `eventId` | `"e-" + UUID` (новый на каждый вызов); `"del-3102"` для удаления вручную (`EamApiProjectionIT:263`); в сквозных тестах — настоящие `poll:`/`snap:<run>:`/`replay:<id>:`/`reconcile:…` из адаптеров и сервиса |
| `source` | `"urn:corp:" + eam\|scm\|cmdb\|deploymap` |
| `type` | `CanonicalEvent.TYPE_ASSET_UPSERTED` (`architecture.asset.upserted.v1`), `"architecture.asset.deleted.v1"` для tombstone, `architecture.sync.snapshot-complete.v1` для маркера; `architecture.unknown.v1` — негатив (`JournalDispatcherIT`) |
| `subject` | `<type>/<id>` с типом в верхнем регистре (`TEAM/…`); `"platform/3102"` в нижнем регистре в `EamApiProjectionIT:263` 🟡 |
| `schema` | `urn:corp:schema:asset-upserted:1` / `urn:corp:schema:asset-deleted:1` |
| `data` | `AssetEventData(type, id, SourceVersion(version), payload)`; для deleted payload `Map.of()` |

Ссылка на raw при `journal.append(event, RAW, syncRunId)` — `RAW` из таблицы выше; `syncRunId` — `null` (одиночное событие) либо идентификатор прогона для snapshot (`GraphProjectorIT:144-150`). После `append` вызывается `processor.process(event, RAW)` — синхронно, без диспетчера.

**Payload eam-api (`EamApiProjectionIT`, `PlatformInvariantIT`)** 🟢: `{id: long, ver: long, datetime: ISO-8601 string, attrs: {name, eam_id?, product_id?, platform_id?, criticality?, …}}` (`EamApiProjectionIT:95-110`, `PlatformInvariantIT:101-108`); ИТ-система в формате `ArchObject` (`GraphProjectorIT:126`): `{id:1, ver:1, attrs:{name, eam_id}}`, ключ записи берётся из события, а не из payload 🟡. Типы объектов в IT: `IT_SYSTEM`, `SOLUTION`, `PLATFORM`, `TEAM`, `SERVICE_DEPENDENCY`.

**Платформенный инвариант** (`PlatformInvariantIT:168`): ключи `5104` (система), `7201` (решение), `3101/3102` (платформы); строка конфликта читается как `status|property|dissent_id|dissent_value|master_id|master_value`, например `OPEN|platformId|5104|3102|7201|3101`.

**Deploy map** (`DeployedFromIT:53-55`, `EamApiProjectionIT:52-54`, `DeploymentCommitReadIT:43-45`): `COMMIT_A = 9fceb02d0ae598e95dc970b74767f19372d61af8`, `COMMIT_B = 0123456789abcdef0123456789abcdef01234567` (40 hex, нижний регистр; верхний регистр — негатив `INVALID_PAYLOAD`), `URL = https://git.example.org/pay/payments-api`.

**Засев графа проектором (`AssetFixtures`)** 🟢 (`IT/AssetFixtures.java:30-95`): `node(gid, sourceType, sourceId, data, at)` → `ProjectionRequest(key, SourceVersion("1"), at, null, {key→gid}, [UpsertNode(SourceRecord(key,"1","sha256:"+sourceId,at,true), data)])`; мастер выбирается как первый из `AuthorityMatrix.defaults().nodeAuthorities(label)`; связь утверждает `SourceKey(master, "relation", "<TYPE>/<fromGid>/<toGid>")`. Сид окружений и закрытых связей — Cypher админ-драйвером (формы совпадают с продовыми: `lastSeenAt`, `isCurrent`, `SourceRecord.active`, `ASSERTS.authority`).

**Заглушки источников для сквозных тестов** (`platform/source-stubs`): `StubSources.seeded(clock)` — карта `SourceSystem → StubSource`; в F1–F4 ожидаются: `ITSystem "Payments Core"` (EAM id 5101, `eamCode EAM-1042`), `Service payments-api`/`ledger-api`, `Team "Payments Team"`, `Deployment dep-payments-api-prod` (версия `1.4.2`), `ComputeInstance vm-pay-01.prod.example.org`, окружение `prod` (`F2AcceptanceIT:90-92`, `F1AcceptanceIT:251-300`); `EamApiSeed.seeded(clock)` — стартовые данные `eam-api` (`IT/EamApiSupport.java:25`). Схема payload заглушек описана в `data-dictionary.md` §P.

### Z.1.6 Свойства приложений, передаваемые тестами

`@DynamicPropertySource` (классы `SB`) и аргументы командной строки (классы `SA`).

| Свойство | Значение в тестах | Где |
|---|---|---|
| `spring.datasource.url/username/password` | из `PostgreSQLContainer` | `AdminEndpointIT:85-87` |
| `archrag.neo4j.uri/username/password` | `getBoltUrl()`, `neo4j`, `getAdminPassword()` | `:88-90` |
| `archrag.neo4j.reader.uri/username/password` (MCP, консоль) | `getBoltUrl()`, `mcp-reader` / `console-reader`, `reader-pass` | `F1AcceptanceIT:210-212`, `AdminConsoleIT:147-149` |
| `archrag.s3.endpoint/access-key/secret-key/bucket` | `http://<host>:<mapped 8333>`, ключи SeaweedFS, бакет класса | `AdminEndpointIT:91-94` |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri/issuer-uri/audiences` | `McpTestJwt.jwkSetUri()`, `McpTestJwt.RESOURCE` (MCP, admin REST) / `urn:archrag:console` (консоль) / `http://unused.invalid` (классы с подменённым `JwtDecoder`) | `AdminEndpointIT:96-99`, `AdminConsoleIT:139-141`, `SyncAcceptanceIT:156-157` |
| `archrag.mcp.security.resource-uri`, `archrag.mcp.security.tool-scopes.<tool>` | `McpTestJwt.RESOURCE`, `architecture.read` (по строке на каждый tool класса) | `F1AcceptanceIT:206-209` |
| `spring.ai.mcp.server.protocol`, `…streamable-http.mcp-endpoint` | `STATELESS`, `/mcp` | `F1AcceptanceIT:201-202` |
| `archrag.query.limits.*`, `archrag.query.stale-after` | см. §Z.1.3; `P7D` в F2 | `F2AcceptanceIT:217` |
| `archrag.dispatcher.enabled`, `poll-interval`, `batch-size`, `retry-delay` | `false` (AdminEndpointIT); `100ms/1/1s` (JournalDispatcherIT); умолчания, `retry-delay=1s` (SyncAcceptanceIT, EamKeyMigrationIT) | `AdminEndpointIT:101`, `JournalDispatcherIT:86-104`, `SyncAcceptanceIT:162` |
| `archrag.adapters.<src>.control-url` | `http://127.0.0.1:<свободный порт>/control/snapshot` (EAM в AdminEndpointIT/SyncAcceptanceIT); `http://127.0.0.1:1/control/snapshot` — «недоступный» адаптер | `AdminEndpointIT:102-104`, `EamKeyMigrationIT` |
| `--server.port=0`, `--spring.autoconfigure.exclude=DataSource…,Flyway…`, `--management.endpoints.web.exposure.include=health` | MCP-контекст внутри F1–F4 и консоль | `F1AcceptanceIT:193-199` |

### Z.1.7 Claims тестового JWT (`McpTestJwt`)

`IT/McpTestJwt.java` (91 строка) 🟢. Подпись RS256, ключ RSA-2048 генерируется в конструкторе (`:40-42`), JWKS — `com.sun.net.httpserver.HttpServer` на `getLoopbackAddress():0`, `GET /jwks`, `Content-Type: application/json`, `kid=test` (`:43-52`); `jwkSetUri()` = `http://localhost:<port>/jwks` (`:59`) 🟡 (хост `localhost` vs привязка к loopback-адресу).

| Claim | Значение | Строка |
|---|---|---|
| `iss` | `RESOURCE` = `https://mcp.test/mcp` | `:76` |
| `sub` | `test-user` (для `token(...)`), либо параметр `tokenFor` / `tokenForClient` | `:64-69` |
| `aud` | `RESOURCE` по умолчанию; параметр `audience` | `:76` |
| `azp` | `archrag-demo` по умолчанию; параметр `tokenForClient(azp, …)` | `:70-74,76` |
| `iat` | `now − 60 s` | `:77` |
| `exp` | `now + 5 min` (не настраивается; просроченный токен получить нельзя 🟡) | `:78` |
| `scope` | пробел-разделённая строка (`String.join(" ", scopes)`); может быть пустой | `:79` |

API: `token(String... scopes)` (`:64`), `tokenFor(subject, audience, scopes…)` (`:69`), `tokenForClient(azp, subject, audience, scopes…)` (`:74`), `jwkSetUri()` (`:59`), `close()` (`:88`). Клиент MCP и HTTP-вызовы передают его как `Authorization: Bearer <jwt>` (`F1AcceptanceIT:363-367`).

**Токен HS256 для admin ingestion** (классы с подменой декодера): собирается `NimbusJwtEncoder(ImmutableSecret(KEY))`; claims — `sub=operator-1`, `iat=now`, `exp=now+600 s`, `scope=architecture.admin`; `iss`, `aud`, `azp` не задаются (`EamKeyMigrationIT:295-301`, `SyncAcceptanceIT:506-511`, `F4AcceptanceIT:504-513`) 🟢.

### Z.1.8 Хелперы и их контракты

| Хелпер | Сигнатура / контракт | Файл |
|---|---|---|
| `TestImages.NEO4J` | `DockerImageName` точного тега | `IT/TestImages.java:9` |
| `ContainersSmokeIT.s3Container()` / `S3_IMAGE` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | фабрика и константы S3 для 11 классов | `ContainersSmokeIT.java:35,44,46,52` |
| `McpTestJwt` | см. §Z.1.7 | `IT/McpTestJwt.java` |
| `AssetFixtures(GraphProjector)` | `node`, `nodeFrom`, `tombstone`, `relation`; исход проекции обязан быть `APPLIED` иначе `IllegalStateException("Fixture was not applied: …")` | `AssetFixtures.java:38-94` |
| `CanonicalGraph.dump(Driver) : List<String>` | отсортированный дамп: узлы 7 меток (`ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance`) с `labels+properties`; `SourceRecord`; связи `DECOMPOSED_INTO\|IMPLEMENTED_IN\|HAS_DEPLOYMENT\|IN_ENVIRONMENT\|RUNS_ON\|PART_OF\|HOSTED_ON\|DEPENDS_ON\|OWNED_BY\|ASSERTS` (без `SyncRun`); строки `N …`, `S …`, `R …`; `EXCLUDED_KEYS = Set.of()` | `CanonicalGraph.java:17-62` |
| `EamApiSupport(Clock)` | `store: EamApiStore`; `adapter(URI legacyUri, String webhookSecret, EventJournal, RawPayloadStore) : SourceAdapter`; `close()` останавливает заглушку | `EamApiSupport.java:17-38` |
| `ScmItSystemMapper` | `CanonicalMapper`: источник SCM, версии схем — как у `ScmMapper`; `IT_SYSTEM` → `UpsertNode(ITSystem(name, null, Criticality.valueOf(payload.criticality), null))`, прочее — `ScmMapper` | `ScmItSystemMapper.java:16-45` |
| Локальные `uid()`, `awaitTrue(BooleanSupplier[, Duration])`, `count(cypher)`, `scalarLong(sql, args…)`, `post(...)` | копии в каждом классе (в F1–F4 — идентичные ≈ 200 строк) 🟡 | `F1AcceptanceIT:413-427`, `SyncAcceptanceIT:524-538` |

### Z.1.9 Что тесты создают в хранилищах (контрольный список для чтения IT)

| Хранилище | Объекты |
|---|---|
| Neo4j | схема от `Neo4jSchema.apply` (13 `*_gid`, `source_record_key`, `source_system_code`, `sync_run_id`, `pending_relation_key`, `environment_code`, индексы `asset_text`, `it_system_eam_code`); пользователи `mcp-reader` / `console-reader` (`CREATE USER` в БД `system`, без ролей: Community) — `GraphQueryCoreIT:48-55`, `AdminConsoleIT:77-79`, `SyncConsoleIT:66` |
| PostgreSQL | таблицы Flyway V1–V7: `inbox_event`, `dlq_entry`, `consumer_checkpoint` (V1), identity-таблицы (V2), `admin_audit` (V3), `identity_candidate` (V4), `source_conflict` (V5, `kind` — V7), роль `archrag_console_ro` (V6); пароль роли выставляет сам тест `ALTER ROLE` (`SyncConsoleIT:60`) |
| S3 | бакет класса (`ensureBucket`), объекты `raw/<source>/<hash>` (`EventJournalIT`) |


## W.1 docker: переменные окружения, порты, тома, клиенты Keycloak

> Archaeologist, платформа стенда. `PD` = `platform/docker`, `DC` = `PD/docker-compose.yml`. Значения секретов, паролей и ключей **не приводятся** (в `.env.example` лежат демо-значения, в `archrag-realm.json` — плейсхолдеры `${VAR}`). Указано только: имя, источник, потребитель. Метки: 🟢 код/конфиг · 🟡 выведено · 🔴 расхождение. Семантика приложений — `code-analysis.md` §W1, X2, X3, Q5.

### W.1.1 Переменные `.env` (источник — `PD/.env.example`; реальный `.env` вне Git, `.gitignore:31`)

«Обяз.»: **да** = `${VAR:?}` в compose (без значения `docker compose` не стартует); **нет** = есть значение по умолчанию.

| Переменная | `.env.example` | Обяз. | Тип | Потребитель (compose) |
|---|---|---|---|---|
| `ARCHRAG_PUBLIC_PORT` | `:5` | нет (`8443`) | порт хоста | порт прокси (`DC:451`), входит в `ARCHRAG_MCP_RESOURCE_URI` mcp-server и Keycloak (`DC:150,232`) |
| `ARCHRAG_CONSOLE_PORT` | `:9` | нет (`8090`) | порт хоста | публикация `admin-console` (`DC:293`), redirect клиента консоли в realm (`DC:152`) |
| `ARCHRAG_KEYCLOAK_PORT` | `:10` | нет (`8080`) | порт хоста | публикация Keycloak (`DC:161`); должен быть 8080 для браузера (`PD/README.md:121`) |
| `POSTGRES_DB`, `POSTGRES_USER` | `:12-13` | да | конфиг | `postgres`, JDBC-URL/пользователь `ingestion` и адаптеров, `console-pg-init`, `admin-console` URL (`DC:17-18,105-106,188-189,261,286`), скрипты (`lib.sh:63`) |
| `POSTGRES_PASSWORD` | `:14` | да | **секрет** → `pg_password` | `postgres`, `ingestion`, адаптеры, `console-pg-init` |
| `NEO4J_PASSWORD` | `:17` | да | **секрет** → `neo4j_password` (writer, пользователь `neo4j`) | `neo4j`, `neo4j-init`, `ingestion` |
| `NEO4J_READER_PASSWORD` | `:19` | да | **секрет** → `neo4j_reader_password` (пользователь `archrag_reader`) | `neo4j-init`, `mcp-server`, `admin-console` |
| `CONSOLE_PG_PASSWORD` | `:22` | да | **секрет** → `console_pg_password` (роль `archrag_console_ro`) | `console-pg-init`, `admin-console` |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | `:24-25` | да | **секрет** → `s3_access_key`, `s3_secret_key`; также в `environment` SeaweedFS как `AWS_*` (`DC:124-125`) | `seaweedfs`, `ingestion`, адаптеры, `lib.sh:aws_s3` |
| `S3_BUCKET` | `:26` | да | конфиг | `ARCHRAG_S3_BUCKET` у `ingestion` и адаптеров |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | `:28-29` | да | bootstrap-админ Keycloak (в `environment`, видим в `docker inspect`, `PD/README.md:215`) | `keycloak` (`DC:141-142`) |
| `KEYCLOAK_CLIENT_SECRET` | `:31` | да | секрет клиента `archrag-demo`; в `environment` Keycloak для подстановки в realm 🔴 (README этого не упоминает) | `keycloak` (`DC:149`); хост-скрипты `smoke.sh`, `measure.sh`, `lib.sh:run_replay`, `deployment-commit-check.sh` |
| `KEYCLOAK_CONSOLE_USER`, `KEYCLOAK_CONSOLE_PASSWORD` | `:34-35` | да | демо-пользователь консоли (пароль в `environment` Keycloak) | `keycloak` (`DC:154-155`), `console-smoke.sh` |
| `WEBHOOK_SECRET` | `:38` | да | **секрет** → `webhook_secret` (HMAC подписи вебхука) | адаптеры, заглушки |
| `EAM_API_TOKEN` | `:41` | да | **секрет** → `eam_api_token` (заголовок `Authorization: Token`) | `eam-api-stub`, `adapter-eam` |
| `ARCHRAG_EAM_MODE` | `:45` | нет (`eam-api`) | конфиг `eam-api` \| `legacy` | `adapter-eam` (`DC:324`) |
| `BACKUP_BUCKET` | `:48` | нет (`archrag-backups`, `lib.sh:14`) | конфиг | `backup.sh`/`restore.sh` (в compose не используется) |
| `ARCHRAG_OBSERVABILITY` | `:51` | нет (`false`) | флаг | `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` у `mcp-server` и `ingestion` (`DC:200,235`) |
| `GRAFANA_PORT` | `:53` | нет (`3000`) | порт хоста | публикация Grafana (`DC:516`) |
| `GRAFANA_ADMIN_PASSWORD` | `:54` | да только при профиле | **секрет** → `grafana_admin_password` | `grafana` |

Секретов 9 (блок `secrets:`, `DC:546-564`): `pg_password`, `neo4j_password`, `neo4j_reader_password`, `s3_access_key`, `s3_secret_key`, `webhook_secret`, `grafana_admin_password`, `eam_api_token`, `console_pg_password`; источник — одноимённые переменные окружения хоста (`environment: VAR`), в контейнере — файлы `/run/secrets/<target>`.
🟡 Переменные `ARCHRAG_ADMIN_ALLOWED_CLIENTS`, `ARCHRAG_POLL_INTERVAL_SECONDS`, `RECONCILE_INTERVAL_SECONDS`, `COMPOSE_PROJECT_NAME`, `GRAPH_START_TIMEOUT`, `N/M/K/P/E2E_PATH/E2E_JITTER_MAX/EVENT_WAIT_SECONDS` в `.env.example` отсутствуют и используют умолчания (`ingestion-service application.yml:22`, `LauncherConfig.java:124`, `lib.sh:13,90`, `measure.sh:6-9`, `backup-restore-check.sh:20`).

### W.1.2 Переменные окружения приложений (задаёт compose)

| Сервис | Переменная | Значение / источник |
|---|---|---|
| `ingestion` | `ARCHRAG_ADMIN_RESOURCE_URI` | `urn:archrag:admin` (литерал, `DC:187`) |
| | `ARCHRAG_PG_URL`, `ARCHRAG_PG_USER` | `jdbc:postgresql://postgres:5432/${POSTGRES_DB}`, `${POSTGRES_USER}` (`DC:188-189`); пароль — secret → env `ARCHRAG_PG_PASSWORD` |
| | `ARCHRAG_NEO4J_URI`, `ARCHRAG_NEO4J_USER` | `bolt+ssc://neo4j:7687`, `neo4j` (writer) (`DC:191-192`); пароль — `ARCHRAG_NEO4J_PASSWORD` |
| | `ARCHRAG_S3_ENDPOINT`, `ARCHRAG_S3_BUCKET` | `http://seaweedfs:8333` (plain HTTP внутри `backend`), `${S3_BUCKET}`; ключи — `ARCHRAG_S3_ACCESS_KEY/SECRET_KEY` |
| | `ARCHRAG_OIDC_ISSUER_URI` | `http://keycloak:8080/realms/archrag` |
| | `ARCHRAG_{EAM,SCM,CMDB,DEPLOYMAP}_CONTROL_URL` | `http://adapter-<x>:8080/control/snapshot` (для `POST /admin/reconcile/{source}`, `DC:196-199`) |
| | `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED/_URL` | `${ARCHRAG_OBSERVABILITY:-false}`, `http://otel-collector:4318/v1/metrics` |
| `mcp-server` | `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_MCP_RESOURCE_URI` | как у Keycloak; resource URI = `https://localhost:${ARCHRAG_PUBLIC_PORT}/mcp` (`DC:231-232`) |
| | `ARCHRAG_NEO4J_READER_URI`, `ARCHRAG_NEO4J_READER_USERNAME` | `bolt+ssc://neo4j:7687`, `archrag_reader`; пароль — `ARCHRAG_NEO4J_READER_PASSWORD` (единственный secret) |
| | `MANAGEMENT_OTLP_METRICS_EXPORT_*` | как у `ingestion` |
| `admin-console` | `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_CONSOLE_RESOURCE_URI` | issuer; `urn:archrag:console` (`DC:282-283`) |
| | `ARCHRAG_NEO4J_READER_URI/USERNAME` | как у `mcp-server`; пароль `ARCHRAG_NEO4J_READER_PASSWORD` |
| | `ARCHRAG_CONSOLE_PG_URL` | `jdbc:postgresql://postgres:5432/${POSTGRES_DB}`; пользователь по умолчанию `archrag_console_ro` (`application.yml:ARCHRAG_CONSOLE_PG_USERNAME`); пароль `ARCHRAG_CONSOLE_PG_PASSWORD` |
| адаптеры (4) | `ARCHRAG_PG_URL/USER`, `ARCHRAG_S3_ENDPOINT/BUCKET` | якорь `x-adapter-env` (`DC:16-20`); секреты: PG, S3 ×2, `ARCHRAG_WEBHOOK_SECRET` |
| | `ARCHRAG_SOURCE_URL` | `http://stub-<eam|scm|cmdb|deploymap>:8080` |
| `adapter-eam` (+) | `ARCHRAG_EAM_MODE`, `ARCHRAG_EAM_API_URL` | `${ARCHRAG_EAM_MODE:-eam-api}`, `http://eam-api-stub:8080`; секрет `ARCHRAG_EAM_API_TOKEN` |
| заглушки (4) | `ARCHRAG_WEBHOOK_TARGET` | `http://adapter-<x>:8080/webhook` (напрямую, мимо TLS-прокси — допущение демо, `PD/README.md:220-224`); секрет `ARCHRAG_WEBHOOK_SECRET` |
| `eam-api-stub` | секрет `ARCHRAG_EAM_API_TOKEN` | — |
| `keycloak` | `KC_BOOTSTRAP_ADMIN_USERNAME/PASSWORD`, `KC_HOSTNAME` (`http://keycloak:8080`), `KC_HTTP_ENABLED`, `KC_HEALTH_ENABLED` | `DC:141-147` |
| | подстановки в realm | `KEYCLOAK_CLIENT_SECRET`, `ARCHRAG_MCP_RESOURCE_URI`, `ARCHRAG_ADMIN_RESOURCE_URI`, `ARCHRAG_CONSOLE_PORT`, `ARCHRAG_CONSOLE_RESOURCE_URI`, `KEYCLOAK_CONSOLE_USER/PASSWORD` (`DC:149-155`) |
| `neo4j` | `NEO4J_server_bolt_tls__level=REQUIRED`, `NEO4J_dbms_ssl_policy_bolt_*` (enabled, base__directory `/certs/neo4j`, private__key, public__certificate, client__auth=NONE) | `DC:62-67`; `NEO4J_AUTH` собирается в entrypoint из secret (`DC:57`) |
| `postgres` | `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD_FILE=/run/secrets/pg_password` | `DC:105-107` |
| `seaweedfs` | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | из `S3_ACCESS_KEY/S3_SECRET_KEY` (`DC:124-125`) |
| `grafana` | `GF_SECURITY_ADMIN_USER=admin`, `GF_SECURITY_ADMIN_PASSWORD__FILE`, `GF_AUTH_ANONYMOUS_ENABLED=false`, `GF_USERS_ALLOW_SIGN_UP=false` | `DC:505-508` |
| `console-pg-init` | `POSTGRES_DB`, `POSTGRES_USER` | `DC:260-261` |

Правило передачи секретов Java-сервисам: файл `/run/secrets/<ИМЯ>` → переменная `<ИМЯ>` (`PD/entrypoint.sh:6-9`), поэтому `target:` секрета в compose = имя свойства (`DC:23-26,203-206,239,288-289`). 🟢

### W.1.3 Порты

| Порт | Сервис | Публикация на хост | Назначение |
|---|---|---|---|
| 8443 (TLS) | `reverse-proxy` | `127.0.0.1:${ARCHRAG_PUBLIC_PORT}` | `/mcp`, `/.well-known/oauth-protected-resource`, `/realms/archrag/protocol/openid-connect/`, `/webhooks/{eam,scm,cmdb,deploymap}`, `/healthz` (`nginx.conf:13-62`) |
| 8080 | `keycloak` | `127.0.0.1:${ARCHRAG_KEYCLOAK_PORT}` | HTTP Keycloak (включая master-админку, 🟡 `DC:158-159`); mgmt-порт 9000 не публикуется (healthcheck) |
| 8080 → 8090 | `admin-console` | `127.0.0.1:${ARCHRAG_CONSOLE_PORT}` | UI и `/api/**` консоли, HTTP |
| 3000 | `grafana` (профиль) | `127.0.0.1:${GRAFANA_PORT}` | дашборд |
| 8080 | `mcp-server` | нет | `/mcp`, `/actuator/health` (масштабируется) |
| 8081 | `ingestion` | нет | admin REST `/admin/**`, порт `ARCHRAG_ADMIN_PORT` (`application.yml:4`) |
| 8080 / 8090 | адаптеры | нет | webhook `/webhook`, `/control/snapshot` / `/health` (`LauncherConfig.java:123`) |
| 8080 / 8091 | заглушки, `eam-api-stub` | нет | источник `/changes`, `/api/**` / управление `/control/upsert`, `/control/archive` (`DemoLauncher.java:54`) |
| 5432 / 7687 / 7474 | `postgres` / `neo4j` | нет (проверяется `smoke.sh:37-40`) | PostgreSQL / Bolt TLS / Browser (не используется) |
| 8333 | `seaweedfs` | нет | S3 API |
| 4318, 8889, 13133 / 9090 | `otel-collector` / `prometheus` | нет | OTLP/HTTP вход, Prometheus-экспорт, health_check (не используется) / API |

### W.1.4 Тома

| Том | Кто монтирует | Содержимое |
|---|---|---|
| `certs` | `certs` (rw), `neo4j` (`/certs:ro`), `reverse-proxy` (`/certs:ro`) | `ca.crt`, `proxy/tls.{crt,key}`, `neo4j/{public.crt,private.key,trusted,revoked}`, маркер `.ready` |
| `ca-key` | только `certs` | `ca.key` (ключ CA не доступен остальным) |
| `neo4j-data` | `neo4j` (`/data`) | база Neo4j (включая `system` с пользователем `archrag_reader`) |
| `backups` | `neo4j` (`/backups`; `docker compose run neo4j` наследует), разовые `aws-cli` из `lib.sh:55-56` | `<метка>/{neo4j.dump,postgres.dump,manifest.json}` до выгрузки в S3 |
| `pg-data` | `postgres` | PostgreSQL |
| `s3-data` | `seaweedfs` | raw payload и бакет резервных копий `$BACKUP_BUCKET` |
| `prometheus-data`, `grafana-data` | профиль observability | TSDB (retention 2 дня), данные Grafana |
| (нет тома) | `keycloak` | H2 dev-хранилище живёт в слое контейнера 🟡 |

Bind-монты (read-only): `gen-certs.sh`, `init-reader.sh`, `init-console-ro.sh`, `nginx.conf`, `archrag-realm.json`, `otel-collector.yml`, `prometheus.yml`, `grafana/provisioning`, `grafana/dashboards` (`DC:47,96,266,453,157,478,488,512-513`).

### W.1.5 Сети

| Сеть | `internal` | Участники |
|---|---|---|
| `edge` | нет | `reverse-proxy`, `mcp-server`, `keycloak`, 4 адаптера, 4 заглушки, `eam-api-stub` |
| `backend` | да | `certs`, `postgres`, `seaweedfs`, `keycloak`, `ingestion`, `console-pg-init`, `admin-console`, 4 адаптера |
| `graph` | да | `neo4j`, `neo4j-init`, `ingestion`, `mcp-server`, `admin-console` |
| `observability` | да | `ingestion`, `mcp-server`, `otel-collector`, `prometheus`, `grafana` |
| `obs-edge` | нет | `grafana` (только для публикации порта) |
| `console-edge` | нет | `admin-console` (только для публикации порта) |

🔴 `PD/README.md:96-101` не содержит `observability`, `obs-edge` и не перечисляет `admin-console`/`console-pg-init` среди участников `backend`.

### W.1.6 Клиенты, scopes, роли и пользователи Keycloak (`PD/keycloak/archrag-realm.json`)

Realm `archrag`: `enabled`, `sslRequired: none` (`:2-4`). Секретов и паролей в файле нет — подстановки окружения (🟢 факт; значения не копировались).

| Объект | Ключевые поля | Строки |
|---|---|---|
| client scope `architecture.read` | протокол OIDC, `include.in.token.scope=true`, без mapper'ов | `:7-11` |
| client scope `architecture.admin` | то же + `oidc-audience-mapper` `admin-audience`: `included.custom.audience=${ARCHRAG_ADMIN_RESOURCE_URI}`, `access.token.claim=true`, `id.token.claim=false` | `:12-28` |
| realm-роль `archrag-console-admin` | «Администратор админ-консоли (read-only)» | `:30-32` |
| пользователь `${KEYCLOAK_CONSOLE_USER}` | включён, email `…@archrag.invalid`, пароль `${KEYCLOAK_CONSOLE_PASSWORD}` (не временный), роль `archrag-console-admin` | `:37-46` |
| клиент `archrag-demo` | confidential, `secret=${KEYCLOAK_CLIENT_SECRET}`, service account, `standardFlow`/`directAccessGrants` выключены; default scope `architecture.read`, optional `architecture.admin`; mapper `mcp-audience` → `${ARCHRAG_MCP_RESOURCE_URI}` | `:51-75` |
| клиент `archrag-admin-console` | public, Authorization Code, PKCE `S256`, implicit/direct/service account выключены; redirect `http://127.0.0.1:${ARCHRAG_CONSOLE_PORT}/*`, web origin — тот же адрес; default scopes пусты, optional `architecture.admin` и `architecture.read`; mappers `console-audience` → `${ARCHRAG_CONSOLE_RESOURCE_URI}` и `sub` | `:77-109` |

Аудитории в выдаваемых токенах (🟢 по конфигурации; проверяется `smoke.sh:59-79`, `console-smoke.sh`):

| Токен | Откуда | `aud` | scope | Принимает |
|---|---|---|---|---|
| агентский | `archrag-demo`, `client_credentials`, scope по умолчанию | MCP URI | `architecture.read` | `mcp-server` (все 6 tools); `ingestion` admin → 401 (нет aud admin) |
| операторский | `archrag-demo` + `scope=architecture.admin` | MCP URI + `urn:archrag:admin` | read + admin | `ingestion` `/admin/**` (с проверкой `azp=archrag-demo`); использует `lib.sh:run_replay` |
| консоли | `archrag-admin-console`, Authorization Code + PKCE, `scope=openid architecture.admin` | `urn:archrag:console` + `urn:archrag:admin` | admin | `admin-console` `/api/**`; `ingestion` отклоняет по `azp` |
| консоли «только чтение» | тот же клиент, `scope=openid architecture.read` | `urn:archrag:console` | read | `admin-console` → 403 |

Параметры, которые должны совпадать между Keycloak и приложениями: issuer (`KC_HOSTNAME`), `ARCHRAG_MCP_RESOURCE_URI`, `ARCHRAG_ADMIN_RESOURCE_URI`, `ARCHRAG_CONSOLE_RESOURCE_URI`, `client-id` консоли, имя scope `architecture.admin` (`ConsoleProperties.java:20`, `SecurityConfiguration.java:28`) — расхождений не найдено. 🟢

### W.1.7 Артефакты резервной копии (`scripts/backup.sh`, S3 `$BACKUP_BUCKET/<метка>/`)

| Файл | Содержимое | Контроль |
|---|---|---|
| `neo4j.dump` | `neo4j-admin database dump neo4j` (только пользовательская БД, `system` не включена) | sha256 в manifest, точная версия Neo4j |
| `postgres.dump` | `pg_dump -Fc` (при restore **не** применяется) | sha256 в manifest |
| `manifest.json` | поля `label`, `backup_started_at` (UTC), `neo4j_version`, `neo4j_dump_sha256`, `postgres_dump_sha256` (`backup.sh:37-41`) | `restore.sh:24-37` проверяет формат `YYYY-MM-DDTHH:MM:SSZ` |

Допустимая метка: `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` (`lib.sh:31-33`).

### W.1.8 Ограничения образов

| Образ | Тег | Фиксация | Замечание |
|---|---|---|---|
| `neo4j` | `5-community` | 🔴 плавающий | CLAUDE.md репозитория требует точный; ITs — `5.26.31-community` |
| `postgres` | `16` | 🟡 мажорный | ITs — тот же |
| `chrislusf/seaweedfs` | `4.48` | 🟢 | совпадает с ITs; вместо MinIO по ADR 0007 |
| `quay.io/keycloak/keycloak` | `26.4` | 🟢 минорный | `start-dev` |
| `nginx` | `1.27-alpine` | 🟡 | |
| `eclipse-temurin` | `21-jre` | 🟡 | Java 21 соответствует стеку |
| `alpine/openssl`, `otel/opentelemetry-collector-contrib`, `prom/prometheus`, `grafana/grafana`, `amazon/aws-cli` | `3.5.4`, `0.140.1`, `v3.7.3`, `12.2.1`, `2.27.50` | 🟢 | |
