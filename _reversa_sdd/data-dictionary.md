# Data Dictionary → Словарь данных

> Archaeologist, этап R.2a (UNLOCKER-263): разделы A (`canonical-model`), E (`event-schemas`), S (`source-spi`). Разделы остальных модулей добавят R.2b–R.2e.
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
