# ingestion/normalizer — внешний контракт

> Модуль не имеет HTTP/SQL-интерфейса. Контракт — Java API (вход/выход `Normalizer.normalize`) и форматы `payload` по источникам. `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`. Метки 🟢/🟡/🔴. Нормативный формат источников — `docs/06-eam-integration.md` и `platform/source-stubs`; ниже описано то, что **читает** маппер.

## 1. Вход

`normalize(CanonicalEvent event, RawPayloadRef raw)` 🟢 (`NZ/Normalizer.java:54`).

| Поле | Используется как | Ограничение |
|---|---|---|
| `event.type` | допускается только `architecture.asset.upserted.v1` | иначе `UNSUPPORTED_EVENT_TYPE` |
| `event.source` | `urn:corp:eam` \| `urn:corp:scm` \| `urn:corp:cmdb` \| `urn:corp:deploymap` | иначе `UNKNOWN_SOURCE` |
| `event.dataschema` | `urn:corp:schema:asset-upserted:<версия>`; версия `1` | иначе `UNKNOWN_SCHEMA_VERSION` |
| `event.time` | `SourceRecord.fetchedAt`, `Input.time`, `Deployment.observedAt` | `Instant`, UTC (`ES/CanonicalEvent.java:38-40`: не `null`) |
| `event.data.sourceType/sourceId` | часть `SourceKey` | непустые (`ES/AssetEventData.java:17-18`) |
| `event.data.sourceVersion.value()` | `SourceRecord.sourceVersion` | только цифры `[0-9]{1,40}` (`ES/SourceVersion.java:13`) |
| `event.data.payload` | `Map<String,Object>`; `null` → `{}`; значения `null` сохраняются как «не передано» (`ES/AssetEventData.java:23`) | недоверенные данные |
| `raw.contentHash` | `SourceRecord.contentHash` | непустая строка (`ES/RawPayloadRef.java:10`) |
| `event.id`, `subject`, `correlationid` | **не используются** нормализатором | — |

## 2. Выход

```
NormalizationResult (sealed)
├─ Normalized(List<GraphCommand> commands, List<UnresolvedReference> unresolved, List<String> warnings)   // списки неизменяемы
└─ Quarantined(String errorCode, String reason)
UnresolvedReference(SourceKey from, String field, RelationType relationType, SourceKey target, UpsertRelation relation)
```
🟢 (`NZ/NormalizationResult.java:19-34`, `NZ/UnresolvedReference.java:19-28`). Потребитель превращает `unresolved` в `DeferRelation(relation)`, дедуплицируя по `relation` (`EventProcessor.java:223`) 🟢.

`SourceRecord(key, sourceVersion, contentHash, fetchedAt, active)`: `key=(SourceSystemCode, data.sourceType, data.sourceId)`; `active` всегда `true` 🟢 (`NZ/Normalizer.java:68-74`).

## 3. Коды ошибок (`Quarantined.errorCode`) и их причины

| Код | Когда | `reason` (фикс. текст или шаблон) | Источник |
|---|---|---|---|
| `UNSUPPORTED_EVENT_TYPE` | тип ≠ `upserted.v1` | `event type is not supported` | `Normalizer.java:56` |
| `UNKNOWN_SOURCE` | URN не из списка / нет маппера | `no mapper for event source` | `:61` |
| `UNKNOWN_SCHEMA_VERSION` | нет префикса `urn:corp:schema:asset-upserted:` или версия не в `supportedSchemaVersions()` | `dataschema version is not supported` | `:65` |
| `UNKNOWN_SOURCE_TYPE` | `sourceType` не обслуживается маппером | `unknown EAM sourceType` / `unknown SCM sourceType` / `unknown CMDB sourceType` / `unknown deploymap sourceType` | `EamMapper.java:116`, `ScmMapper.java:95`, `CmdbMapper.java:33`, `DeployMapMapper.java:134` |
| `MISSING_REQUIRED_FIELD` | обязательное поле не передано | `required field is missing: <поле>` | `Payload.java:27`; `EamMapper.java:137` (`attrs`); `DeployMapMapper.java:152` (`chartMeta`) |
| `INVALID_PAYLOAD` | неверный тип/значение/формат | `field has unexpected type: <поле>`; `field has unknown value: kind`; `repository url is not a recognized git url`; `annotation has invalid format: <аннотация>`; `unknown deploymap format`; `cannot determine environment class`; `payload violates canonical model invariants` | `Payload.java:125`; `EamMapper.java:124`; `CmdbMapper.java:55`; `ScmMapper.java:110`; `DeployMapMapper.java:145,189,250`; `Normalizer.java:83` |

Других кодов нормализатор не выдаёт 🟢. Прочие коды карантина (`INVALID_COMMANDS`, `GRAPH_REJECTED`, …) ставит `EventProcessor`, не модуль.

## 4. Warnings (`Normalized.warnings`)

| Код | Условие | Источник |
|---|---|---|
| `DEPENDS_ON_NOT_SUPPORTED` | плоский `IT_SYSTEM` с непустым `dependsOn` (после отбрасывания пустых элементов) | `EamMapper.java:96-98` |
| `UNMAPPED_CRITICALITY` | `IT_SYSTEM` (ArchObject: не из {HIGH, MEDIUM, LOW}; плоский: не имя `Criticality`), `SERVICE_DEPENDENCY.criticality` вне `Criticality` (регистр не важен; К-10, UNLOCKER-287) | `EamMapper.java:161-168` |
| `DEPLOYMENT_SERVICE_UNRESOLVED` | `deploymap-poc-1`: сервис не определился | `DeployMapMapper.java:222-224` |

## 5. Форматы payload по `sourceType` (🟢)

Условные обозначения: **жирным** — обязательное; «→» — что получается; `*` — необязательное. Все строки `strip()`, пустая = не передано.

### 5.1 EAM (`urn:corp:eam`, `sourceId` для ArchObject — целочисленный `id`)

| `sourceType` | Payload | Результат |
|---|---|---|
| `PLATFORM` | `attrs` (объект, **обязателен**): **`name`**, `type`*, `version`*, `lifecycle`*, `description`* | `UpsertNode(Platform(name,type,version,lifecycle,description))` (`EamMapper.java:139-148`) |
| `SOLUTION` | `attrs`: **`name`**, `lifecycle`*, `description`*, `platform_id`* (целое число, `Long`/`Integer`) | `UpsertNode(Solution(name,lifecycle,description,platformId))`; `USES_PLATFORM` Solution→`(EAM,PLATFORM,<platform_id>)` (`:149-159`) |
| `IT_SYSTEM`, есть `attrs` (объект) | `attrs`: **`name`**, `status`*, `criticality`*, `description`*, `eam_id`*, `platform_id`*, `product_id`*, `ownerTeam`*, `ownerSince`* (ISO-8601) | `UpsertNode(ITSystem(name,status,criticality,description,eamCode=eam_id,criticalityRaw,platformId))`; `USES_SOLUTION` ITSystem→`(EAM,SOLUTION,<product_id>)`; `OWNED_BY` ITSystem→`(EAM,TEAM,<ownerTeam>)` (`:160-194`) |
| `IT_SYSTEM`, нет `attrs` (или `attrs:null`) — плоский | **`name`**, `status`*, `criticality`* (имя `Criticality`, вкл. `CRITICAL`), `description`*, `ownerTeam`*, `ownerSince`*, `dependsOn`* (список строк) | `UpsertNode(ITSystem(name,status,criticality,description))` (без `eamCode`); `OWNED_BY`; `dependsOn` → warning (`:77-98`) |
| `TEAM` | **`name`**, `type`* | `UpsertNode(Team(name,type))` (`:71`) |
| `SERVICE_DEPENDENCY` | **`from`**, **`to`** (`sourceId` сервисов SCM), `kind`*, `protocol`*, `criticality`* (имя `Criticality`), `validFrom`* | **только** `DEPENDS_ON` Service(SCM)→Service(SCM) через `link`; свойства связи — только переданные `kind`,`protocol`,`criticality` (как имя enum) (`:100-115`). `UpsertNode` не выдаётся |

Поля ArchObject `id`, `ver`, `datetime` маппер **не читает**: ключ/версия/время берутся из события 🟢 (`EamMapper.java:134-137` читает только `attrs`).

### 5.2 SCM (`urn:corp:scm`)

| `sourceType` | Payload | Результат |
|---|---|---|
| `REPOSITORY` | **`url`** (→`GitUrls.normalize`), `defaultBranch`*, `archived`* (boolean) | `UpsertNode(Repository(url,defaultBranch,archived))` (`ScmMapper.java:51-58`) |
| `SERVICE` | **`name`**, `serviceType`*, `language`*, `status`*, `systemCode`*, `repositoryId`*, `validFrom`* | `UpsertNode(Service(...))`; `DECOMPOSED_INTO` ITSystem→Service (alias `(EAM,IT_SYSTEM_CODE,<systemCode>)` если система неизвестна); `IMPLEMENTED_IN` Service→`(SCM,REPOSITORY,<repositoryId>)` (`:59-94`) |

### 5.3 CMDB (`urn:corp:cmdb`)

| `sourceType` | Payload | Результат |
|---|---|---|
| `COMPUTE_INSTANCE` | **`hostname`**, **`kind`** (`VIRTUAL_MACHINE`\|`PHYSICAL_SERVER`\|`UNSPECIFIED`, регистр не важен), `ip`*, `os`*, `state`*, `hypervisorRef`* (только VM), `serialNumber`* (только PHYSICAL_SERVER); `environment` игнорируется | `UpsertNode(ComputeInstance(...))` (`CmdbMapper.java:31-48`) |

### 5.4 DEPLOYMAP (`urn:corp:deploymap`) — подробно в [`bind-deployment/`](bind-deployment/)

| `sourceType` | Payload | Результат |
|---|---|---|
| `ENVIRONMENT` | **`name`**, `class`* (иначе класс выводится из `sourceId`) | `UpsertNode(Environment(code=sourceId, name, class))` (`DeployMapMapper.java:84-91`) |
| `DEPLOYMENT` | **`name`**, `chartVersion`*, `status`*, `service`*, `environment`*, `hosts`* (список), `validFrom`*, `format`* (`deploymap-poc-0`\|`deploymap-poc-1`), `chartMeta.annotations.{archrag.io/eam-system-id, archrag.io/git-repo-url, archrag.io/git-commit}` (обязательны при `poc-1`) | `UpsertNode(Deployment(...))` + `HAS_DEPLOYMENT`, `DEPLOYED_FROM{commit}` (poc-1), `IN_ENVIRONMENT`, `RUNS_ON` на хост (`:92-133`) |

Классы окружения: `dev|development`→DEV, `test|qa`→TEST, `preprod|stage|staging`→PREPROD, `prod|production`→PROD (регистр не важен); иначе `INVALID_PAYLOAD` (`:244-252`).

## 6. Ключи ссылок (соглашения о `SourceKey`)

| Ссылка | Целевой ключ | Источник |
|---|---|---|
| `ownerTeam` | `(EAM, TEAM, <ownerTeam>)` | `EamMapper.java:92,190` |
| `product_id` | `(EAM, SOLUTION, <id>)` | `:181` |
| `platform_id` решения | `(EAM, PLATFORM, <id>)` | `:158` |
| `from`/`to` зависимости | `(SCM, SERVICE, <код>)` | `:107,109` |
| `repositoryId` | `(SCM, REPOSITORY, <repo>)` | `ScmMapper.java:90` |
| `systemCode` | реальный ключ из `lookup.itSystemByEamCode` либо alias `LookupKeys.itSystemCode(code)` = `(EAM, IT_SYSTEM_CODE, code)` | `ScmMapper.java:77`, `CM/provenance/LookupKeys.java:28-30` |
| `git-repo-url` | реальный ключ из `lookup.repositoryByUrl` либо alias `LookupKeys.repositoryUrl(url)` = `(SCM, REPOSITORY_URL, url)` | `DeployMapMapper.java:194-198`, `LookupKeys.java:20-22` |
| `service` | `(SCM, SERVICE, <service>)` | `DeployMapMapper.java:210,231` |
| `environment` | `(DEPLOYMAP, ENVIRONMENT, <env>)` | `:119` |
| `hosts[]` | `(CMDB, COMPUTE_INSTANCE, <host>)` | `:128` |

## 7. Порты (контракт для реализаторов)

`ReferenceResolver.exists(SourceKey): boolean` и `DeploymentLookup` (`repositoryByUrl`, `itSystemByEamCode`, `servicesImplementedIn`; для `EventProcessor` ещё `eamSystemOfService`, `chartDeploymentsOfService`) — см. `design.md` §4. Реализатор обязан: не логировать значения, возвращать только активные записи, не бросать `IllegalArgumentException` (исчезнет в `INVALID_PAYLOAD`) 🟡.
