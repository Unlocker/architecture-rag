# Data Dictionary → Словарь данных

> Archaeologist, этапы R.2a (UNLOCKER-263): разделы A (`canonical-model`), E (`event-schemas`), S (`source-spi`); R.2b (UNLOCKER-264): раздел P (`adapters/*`). Разделы остальных модулей добавят R.2c–R.2e.
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

### Q.1.1 `inbox_event` (`V1__event_journal.sql:2-20`)

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

Ограничения и индексы: `uq_inbox_event UNIQUE (source,event_id)`; `ix_inbox_event_status (status, received_at)`; `ix_inbox_event_object (source, source_type, source_id)` (`:19, :22-23`). Индекса под `pending` с условием `updated_at` нет 🟡; фильтр `event_id NOT LIKE 'replay:%'` и `starts_with(event_id, …)` индексом по `(source, event_id)` (btree по `uq_inbox_event`) поддерживается только для `starts_with` с `text_pattern_ops`/C-collation — зависит от коллации БД 🟡.

### Q.1.2 `consumer_checkpoint` (`:25-30`)

`consumer` + `source` (PK), `cursor` text NOT NULL (формат свободный: курсор poll-а, `run:<id>`, `done:<id>`, `eventId` последнего спроецированного события), `updated_at`. Потребители: `poller:*`/`snapshot:*` адаптеров (`adapters/adapter-core/.../Poller.java:93,136,170-186`), проектор (`ingestion/graph-projector/.../EventProcessor.java:615`) 🟢.

### Q.1.3 `dlq_entry` (`:32-42`)

| Колонка | Тип | NULL | Примечание |
|---|---|---|---|
| `id` | bigserial | нет | PK |
| `source`, `event_id` | text | нет | FK → `inbox_event(source,event_id)` |
| `reason` | text | нет | ≤ 500 символов; если причины нет — подставляется `error_code` |
| `error_code` | text | нет | обязателен (`toDlq` бросает `IllegalArgumentException` при пустом) |
| `payload_key` | text | да | копия ссылки на raw |
| `created_at` | timestamptz | нет | |
| `replayed_at` | timestamptz | да | 🔴 **в коде не заполняется** (code-analysis §Q1.5) |

Частичный уникальный индекс `uq_dlq_entry_open (source,event_id) WHERE replayed_at IS NULL` — «одна открытая запись на событие» (`:41`).

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
