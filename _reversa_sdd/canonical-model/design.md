# contracts/canonical-model — Дизайн

> Reversa Writer, R.6a. Сокращения: `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `CT/` = `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/`. Требования — [`requirements.md`](requirements.md).

## Интерфейс

Пакеты (корень `io.github.unlocker.archrag.canonicalmodel`): `node` (19 файлов), `relation` (2), `command` (6), `provenance` (7), `authority` (1), корень (`Invariants`, `CanonicalModelModule`, `package-info`) — всего 38 файлов main 🟢.

### Узлы (`node`)

| Символ | Сигнатура (поля) | Метка | Проверки конструктора |
|--------|------------------|-------|-----------------------|
| `NodeData` (sealed) | `NodeLabel label()`; permits 11 типов (`CM/node/NodeData.java:9-13`) | — | — |
| `ITSystem` | `(String name, String status, Criticality criticality, String description, String eamCode, String criticalityRaw, Long platformId)` + перегрузки на 6 и 4 арг. (`:13-36`) | `IT_SYSTEM` | `name` обязателен; `status, description, eamCode, criticalityRaw` — не blank; `platformId > 0` (`:17-24`) |
| `Solution` | `(name, lifecycle, description, Long platformId)` + перегрузка на 3 арг. (`CM/node/Solution.java:12-26`) | `SOLUTION` | `name`; `lifecycle, description` не blank; `platformId > 0` (`:15-20`) |
| `Platform` | `(name, type, version, lifecycle, description)` (`CM/node/Platform.java:10`) | `PLATFORM` | `name`; остальные не blank (`:14-18`) |
| `Service` | `(name, serviceType, language, status)` (`CM/node/Service.java:7`) | `SERVICE` | `name`; остальные не blank (`:10-13`) |
| `Repository` | `(String url, String defaultBranch, Boolean archived)` (`CM/node/Repository.java:7`) | `REPOSITORY` | `url` обязателен, формат НЕ проверяется; `defaultBranch` не blank (`:10-11`) |
| `Team` | `(name, type)` (`CM/node/Team.java:7`) | `TEAM` | `name`; `type` не blank (`:10-11`) |
| `Environment` | `(code, name, EnvironmentClass environmentClass)` (`CM/node/Environment.java:8`) | `ENVIRONMENT` | все обязательны (`:11-13`) |
| `Deployment` | `(deploymentKey, name, version, status, sourceCommit, repositoryUrl, Long eamSystemId, Instant observedAt)` (`CM/node/Deployment.java:17-18`) | `DEPLOYMENT` | `deploymentKey, name`; `version, status` не blank; `sourceCommit` = `[0-9a-f]{40}` (`:20, 27-29`); `repositoryUrl.equals(GitUrls.normalize(repositoryUrl))` (`:30-32`); `eamSystemId > 0` (`:33-35`) |
| `ComputeInstance` | `(hostname, ComputeKind kind, ip, os, state, hypervisorRef, serialNumber)` (`CM/node/ComputeInstance.java:15-16`) | по `kind`: `VIRTUAL_MACHINE`→`VIRTUAL_MACHINE`, `PHYSICAL_SERVER`→`PHYSICAL_SERVER`, `UNSPECIFIED`→`COMPUTE_INSTANCE` (`:35-41`) | `hostname, kind`; `hypervisorRef` только при `VIRTUAL_MACHINE` (`:26-28`); `serialNumber` только при `PHYSICAL_SERVER` (`:29-31`) |
| `Namespace` | `(name)` (`CM/node/Namespace.java:6`) | `NAMESPACE` | `name` |
| `KubernetesCluster` | `(name, version)` (`CM/node/KubernetesCluster.java:7`) | `KUBERNETES_CLUSTER` | `name`; `version` не blank |
| `CanonicalNode<T extends NodeData>` | `(UUID gid, T data, Lifecycle lifecycle)` (`CM/node/CanonicalNode.java:7`) | — | все три не `null` |
| `Lifecycle` | `(Instant firstSeenAt, Instant lastSeenAt, Instant deletedAt, boolean isCurrent)` (`CM/node/Lifecycle.java:12`) | — | `firstSeenAt ≤ lastSeenAt`; `isCurrent == (deletedAt == null)` (`:17-20`) |
| Enum `Criticality` | `LOW, MEDIUM, HIGH, CRITICAL` (`CM/node/Criticality.java:4-6`) | — | — |
| Enum `EnvironmentClass` | `DEV, TEST, PREPROD, PROD` (`CM/node/EnvironmentClass.java:4-6`) | — | — |
| Enum `ComputeKind` | `VIRTUAL_MACHINE, PHYSICAL_SERVER, UNSPECIFIED` (`CM/node/ComputeKind.java:4-6`) | — | — |

`NodeLabel` (`CM/node/NodeLabel.java:13-72`): значения с Neo4j-строкой — `IT_SYSTEM("ITSystem")`, `SOLUTION("Solution")`, `PLATFORM("Platform")`, `SERVICE("Service")`, `REPOSITORY("Repository")`, `TEAM("Team")`, `ENVIRONMENT("Environment")`, `DEPLOYMENT("Deployment")`, `COMPUTE_INSTANCE("ComputeInstance")`, `VIRTUAL_MACHINE("VirtualMachine", ⊂COMPUTE_INSTANCE)`, `PHYSICAL_SERVER("PhysicalServer", ⊂COMPUTE_INSTANCE)`, `NAMESPACE("Namespace")`, `KUBERNETES_CLUSTER("KubernetesCluster")`, `SOURCE_SYSTEM("SourceSystem")`, `SOURCE_RECORD("SourceRecord")`, `SYNC_RUN("SyncRun")`. Порядок значений (`ordinal`) — публичный де-факто контракт: `Neo4jSchema` сортирует канонические метки по `ordinal` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:43-44`) 🟢.

| Метод `NodeLabel` | Поведение |
|---|---|
| `label()` | строка метки Neo4j (`:44-46`) |
| `supertype()` | надтип или `null` (`:49-51`) |
| `isA(other)` | цикл вверх по `supertype`; рефлексивна (`:54-61`) |
| `isCanonical()` | `this` не из {`SOURCE_SYSTEM`,`SOURCE_RECORD`,`SYNC_RUN`} (`:64-66`) |
| `canonical()` (static) | новый `EnumSet` канонических меток (13) (`:69-71`) |

### Связи (`relation`)

| Символ | Сигнатура | Примечание |
|---|---|---|
| `RelationType` (enum, 15) | `Set<NodeLabel> sources(), targets(); boolean temporal(); Set<String> identityProperties(); boolean allows(NodeLabel from, NodeLabel to)` (`CM/relation/RelationType.java:48-75`) | таблица ниже |
| `Validity` | `(Instant validFrom, Instant validTo)`; `validFrom` обязателен; `validTo` `null` = открыта, иначе `≥ validFrom` (равенство допустимо) (`CM/relation/Validity.java:8-15`) | — |

Таблица `RelationType` (`CM/relation/RelationType.java:13-27`):

| Тип | Из | В | temporal | identityProperties |
|---|---|---|---|---|
| `USES_SOLUTION` | IT_SYSTEM | SOLUTION | да | — |
| `USES_PLATFORM` | SOLUTION | PLATFORM | да | — |
| `DECOMPOSED_INTO` | IT_SYSTEM | SERVICE | да | — |
| `IMPLEMENTED_IN` | SERVICE | REPOSITORY | да | — |
| `HAS_DEPLOYMENT` | SERVICE | DEPLOYMENT | да | — |
| `IN_ENVIRONMENT` | DEPLOYMENT | ENVIRONMENT | да | — |
| `RUNS_ON` | DEPLOYMENT | COMPUTE_INSTANCE, NAMESPACE | да | — |
| `DEPLOYED_FROM` | DEPLOYMENT | REPOSITORY | да | `commit` |
| `PART_OF` | NAMESPACE | KUBERNETES_CLUSTER | нет | — |
| `HOSTED_ON` | VIRTUAL_MACHINE | PHYSICAL_SERVER | нет | — |
| `DEPENDS_ON` | SERVICE | SERVICE | да | — |
| `OWNED_BY` | IT_SYSTEM, SERVICE | TEAM | да | — |
| `ASSERTS` | SOURCE_RECORD | `NodeLabel.canonical()` | нет | — |
| `OWNS_RECORD` | SOURCE_SYSTEM | SOURCE_RECORD | нет | — |
| `PROCESSED` | SYNC_RUN | SOURCE_RECORD | нет | — |

Итого `temporal = true` у 10 типов из 15 🟢 (подтверждено `CT/relation/RelationTypeTest.java:40`). Конструктор: `Set.copyOf` для множеств; identity-свойства при `temporal == false` → `IllegalArgumentException("identity properties are supported for temporal relations only")` при загрузке enum (`:38-46`).

### Команды (`command`)

| Команда | Поля | Проверки |
|---|---|---|
| `GraphCommand` (sealed) | — | `permits UpsertNode, UpsertRelation, CloseAssertion, TombstoneSourceRecord, DeferRelation` (`CM/command/GraphCommand.java:7-9`) |
| `UpsertNode` | `(SourceRecord record, NodeData data)` | оба `!= null` (`CM/command/UpsertNode.java:10-13`) |
| `UpsertRelation` | `(RelationType type, SourceKey from, NodeLabel fromLabel, SourceKey to, NodeLabel toLabel, Map<String,Object> properties, Validity validity, SourceKey assertedBy)` | см. Основной поток (`CM/command/UpsertRelation.java:21-42`) |
| `CloseAssertion` | `(RelationType type, SourceKey from, SourceKey to, Instant validTo, SourceKey assertedBy)` | все `!= null`; `type.temporal()` (`CM/command/CloseAssertion.java:12-21`); `validTo` не сравнивается с `validFrom` связи |
| `TombstoneSourceRecord` | `(SourceKey key, Instant deletedAt)` | оба `!= null`; «только после полного snapshot» — лишь Javadoc (`CM/command/TombstoneSourceRecord.java:7-13`) |
| `DeferRelation` | `(UpsertRelation relation)` | `!= null` (`CM/command/DeferRelation.java:12-16`) |

### Provenance (`provenance`)

| Символ | Поля | Проверки |
|---|---|---|
| `SourceSystemCode` | `EAM, SCM, CMDB, DEPLOYMAP, MANUAL` (`CM/provenance/SourceSystemCode.java:4-6`) | — |
| `SourceKey` | `(SourceSystemCode source, String sourceType, String sourceId)` | `source != null`; `sourceType`, `sourceId` — непустой текст (`CM/provenance/SourceKey.java:10-14`) |
| `SourceRecord` | `(SourceKey key, String sourceVersion, String contentHash, Instant fetchedAt, boolean active)` | `key`, `fetchedAt` не `null`; `sourceVersion`, `contentHash` — непустой текст (`CM/provenance/SourceRecord.java:11-16`). Числовой формат `sourceVersion` не проверяется 🟢 |
| `SourceSystem` | `(SourceSystemCode code, String name)` | `code != null`, `name` текст (`CM/provenance/SourceSystem.java:10-13`) |
| `SyncRun` | `(UUID runId, String adapter, Instant startedAt, Instant endedAt, SyncRunStatus status, long fetched, long applied, long failed)` | `runId, startedAt, status != null`; `adapter` текст; `endedAt` `null` или `≥ startedAt`; счётчики `≥ 0` (`CM/provenance/SyncRun.java:17-28`) |
| `SyncRunStatus` | `RUNNING, SUCCEEDED, FAILED` (`CM/provenance/SyncRunStatus.java:4-6`) | — |
| `LookupKeys` | константы `REPOSITORY_URL = "REPOSITORY_URL"`, `IT_SYSTEM_CODE = "IT_SYSTEM_CODE"`; `repositoryUrl(String) → SourceKey(SCM, REPOSITORY_URL, url)`; `itSystemCode(String) → SourceKey(EAM, IT_SYSTEM_CODE, code)` | значение проверяет `SourceKey` (непустое) (`CM/provenance/LookupKeys.java:11-30`) |

### Прочее

| Символ | Сигнатура | Примечание |
|---|---|---|
| `Invariants.requireText(value, name)` | `String` | NPE на `null`, IAE `"<name> must not be blank"` (`CM/Invariants.java:21-27`) |
| `Invariants.optionalText(value, name)` | `String` | `null` проходит; blank → IAE `"<name> must not be blank when set"` (`:30-35`) |
| `Invariants.requireNotAfter(from, to, fromName, toName)` | `void` | IAE `"<fromName> must not be after <toName>"` если `from.isAfter(to)` (`:38-42`) |
| `Invariants.copyProperties(Map)` | `Map<String,Object>` | см. поток 3 (`:49-58`) |
| `AuthorityMatrix` | `defaults()`, `isAuthoritative(NodeLabel, SourceSystemCode)`, `isAuthoritative(NodeLabel, String, SourceSystemCode)`, `isAuthoritative(RelationType, SourceSystemCode)`, `nodeAuthorities`, `propertyAuthorities`, `relationAuthorities` (`CM/authority/AuthorityMatrix.java:43-75`) | [`authority-lookup`](authority-lookup/design.md) |
| `GitUrls.normalize(String)` | `String` | [`git-url-normalization`](git-url-normalization/design.md) |

## Основной поток

Модуль не исполняет процессов; «поток» — это цепочки проверок в конструкторах и три чистые функции.

1. **Создание `UpsertRelation`** (подробно — [`relation-command-validation`](relation-command-validation/design.md)): `requireNonNull` для `type, from, fromLabel, to, toLabel, assertedBy` → `type.allows(fromLabel,toLabel)` → `validity == null || type.temporal()` → `properties = Invariants.copyProperties(properties)` (NPE на `null`) → `properties.keySet().containsAll(type.identityProperties())` (`CM/command/UpsertRelation.java:25-42`).
2. **`RelationType.allows`**: `sources.stream().anyMatch(from::isA) && targets.stream().anyMatch(to::isA)` (`CM/relation/RelationType.java:73-75`).
3. **`Invariants.copyProperties`**: `requireNonNull(properties)`; для каждой пары: ключ — `requireText`, значение — `checkValue(key, value, listAllowed=true)`: `null` → NPE `"property <key>"`; `Double` NaN/∞ → IAE; допустимые типы `String, Boolean, Long, Integer, Double, Instant`; `List` — только на верхнем уровне, элементы проверяются с `listAllowed=false` (вложенный список → IAE `unsupported type`); прочее → IAE `"property <key> has unsupported type <class>"`. Копия: `List.copyOf` для списков, `Map.copyOf` для карты (`CM/Invariants.java:49-77`). Пустой список допустим 🟢 (проверок на пустоту нет).
4. **Поиск авторитетности**: [`authority-lookup`](authority-lookup/design.md).
5. **Нормализация URL**: [`git-url-normalization`](git-url-normalization/design.md).

## Альтернативные потоки

- **Конструктор нарушен** — исключение; частично созданных объектов нет (compact constructors records) 🟢.
- **Тип связи вне `AuthorityMatrix`** (`ASSERTS`, `OWNS_RECORD`, `PROCESSED`) — пустое множество мастеров → `isAuthoritative == false` (`CM/authority/AuthorityMatrix.java:73-75`; `CT/authority/AuthorityMatrixTest.java:90`) 🟢.
- **`ComputeInstance` с `kind = UNSPECIFIED`** — метка `COMPUTE_INSTANCE`; для неё `HOSTED_ON` невозможна (источник только `VIRTUAL_MACHINE`) 🟡.

## Зависит от / Связанные спецификации

**Входящие зависимости (кто использует этот модуль)** — по `pom.xml`: `contracts/event-schemas`, `contracts/source-spi`, `ingestion/normalizer`, `ingestion/identity-resolution`, `ingestion/graph-projector`, `ingestion/ingestion-service`, `access/graph-query-core`, `platform/integration-tests` (тест-scope), `adapters/deploymap-adapter` (тест-scope) 🟢 (`contracts/event-schemas/pom.xml:20`, `contracts/source-spi/pom.xml:20`, `ingestion/normalizer/pom.xml:20`, `ingestion/identity-resolution/pom.xml:20`, `ingestion/graph-projector/pom.xml:20`, `ingestion/ingestion-service/pom.xml:20`, `access/graph-query-core/pom.xml:20`, `platform/integration-tests/pom.xml:20`, `adapters/deploymap-adapter/pom.xml:33`). Транзитивно: `mcp-server`, `admin-console` 🟡. Исходящих зависимостей у модуля нет.

**Матрица влияния (`_reversa_sdd/traceability/spec-impact-matrix.md` §1)**: при изменении `NodeLabel`, `RelationType`, `GraphCommand`, `AuthorityMatrix`, `SourceRecord` пересматриваются спецификации `event-schemas`, `source-spi`, `normalizer`, `identity-resolution`, `graph-projector`, `graph-query-core`; через данные — `mcp-server`, `admin-console`, `Neo4jSchema`, allowlist'ы шаблонов, константы UI. Проверять: `Neo4jSchemaTest`, `QueryTemplateRegistryTest`, фикстуры нормализатора, `AdminConsoleIT`.

**Связи вне `pom.xml` (§2) — затрагивающие этот модуль**:

| № §2 | Связь | Что относится к `canonical-model` |
|---|---|---|
| 2 | Таблица кодов источников `eam / scm / cmdb / deploymap` разнесена по шести местам | `SourceSystemCode` (`CM/provenance/SourceSystemCode.java:5`) — одна из копий; теста «покрыты все значения» нет 🔴 |
| 3 | Регистр источника: URN в журнале, ВЕРХНИЙ регистр в графе, нижний в консоли | проектор пишет `SyncRun.adapter = SourceKey.source().name()`, т.е. имя `SourceSystemCode` в верхнем регистре; сам `SyncRun.adapter` в модуле — произвольный непустой текст (`CM/provenance/SyncRun.java:19`) 🟡 |
| 4 | Индекс `asset_text` | зависимость косвенная: набор меток индекса не берётся из `NodeLabel` 🟡 (`access/graph-query-core/.../AssetTemplates.java:16-17` — литералы строк) |
| 9 | Allowlist'ы меток и типов связей в `graph-query-core`/UI | `NodeLabel` и `RelationType` — первоисточник; allowlist'ы продублированы литералами; новая метка/тип связи требует правки в трёх местах 🔴 |
| 11 | Два нормализатора URL репозитория | `GitUrls.normalize` (регистр пути сохраняется, `CM/node/GitUrls.java:72-73`) vs `FeatureNormalizer.repositoryUrl` (нижний регистр, `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/FeatureNormalizer.java:33`) 🟡; TODO в `ingestion/normalizer/.../ScmMapper.java:103-104`, вызов `GitUrls.normalize` — `:108` |
| 12 | Три экземпляра `AuthorityMatrix.defaults()` | `IngestionServiceConfiguration.java:64, 86`, `ExplainProvenanceTool.java:28`; таблица одна — singleton `DEFAULTS` (`CM/authority/AuthorityMatrix.java:28`) 🟢 |
| 13 | `mcp-server` использует `canonical-model` без явной зависимости | `ExplainProvenanceTool.java:3-5` импортирует `AuthorityMatrix`, `NodeLabel`, `SourceSystemCode`; в `access/mcp-server/pom.xml:20` только `graph-query-core` 🟡 |
| 15 | `asset-adapter` ↔ `cmdb`, `deploymap-adapter` ↔ `DEPLOY_MAP`/`deploymap` | коды в модуле — `CMDB`, `DEPLOYMAP` (`CM/provenance/SourceSystemCode.java:5`); `source-spi` называет `DEPLOY_MAP` 🟡 |

**Сквозное последствие (§3)**: «Новая метка узла или тип связи» затрагивает `canonical-model` → `normalizer` → `graph-projector (Neo4jSchema)` → `graph-query-core` (allowlist'ы) → `mcp-server` → `admin-console` → `admin-ui`; неявные связи № 9, № 4. Отдельно: `Neo4jSchema` генерирует `<label>_gid` UNIQUE-констрейнты из `NodeLabel.canonical()`, поэтому добавление канонической метки автоматически создаёт constraint (`ingestion/graph-projector/.../schema/Neo4jSchema.java:17, 43-44`) 🟢.

**Связанные спецификации**: `_reversa_sdd/event-schemas/` (`CanonicalEvent` не зависит от моделей, но зависит от модуля), `_reversa_sdd/source-spi/`, `_reversa_sdd/normalizer/` (производитель `GraphCommand`), `_reversa_sdd/graph-projector/` (потребитель, исполняет семантику `CloseAssertion`/`DeferRelation`/`TombstoneSourceRecord`), `_reversa_sdd/identity-resolution/` (`SourceKey`, `AuthorityMatrix` в `PostgresSourceConflicts`), `_reversa_sdd/graph-query-core/`. Документы: `docs/02-data-model.md` §2–4; ADR [0003](../../docs/adr/0003-gid-and-source-record-identity.md), [0008](../../docs/adr/0008-authority-matrix-field-level.md), [0011](../../docs/adr/0011-namespace-cluster-modeled-not-populated.md), [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md), [0024](../../docs/adr/0024-open-source-id-format.md), [0028](../../docs/adr/0028-structural-relations-temporal.md), [0029](../../docs/adr/0029-helm-chart-system-repo-commit.md), [0031](../../docs/adr/0031-it-system-platform-invariant.md) (см. [`decisions.md`](decisions.md)).

## Решения дизайна

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Records с compact constructor: инварианты проверяются при создании, невалидных экземпляров нет | `CM/node/*.java`, `CM/command/*.java` | 🟢 |
| Sealed `NodeData` и `GraphCommand`; исчерпывающий `switch` без `default` | `CM/node/NodeData.java:9-11`, `CM/command/GraphCommand.java:7-8`, `CT/command/GraphCommandTest.java:99-108` | 🟢 |
| Метка узла из `NodeLabel`, а не из строки: Cypher-метки подставляются из enum (ловушка 1 CLAUDE.md) | `CM/node/NodeLabel.java:44-46` | 🟡 |
| Авторитетность — таблица в коде, не конфигурация; три «слоя» (узел, свойство, связь) | `CM/authority/AuthorityMatrix.java:26-118` | 🟢 |
| Иерархия меток через `supertype` (одноуровневая), `isA` общий цикл | `CM/node/NodeLabel.java:23-24, 54-61` | 🟢 |
| Identity-свойства связи объявляются в enum и не берутся из payload | `CM/relation/RelationType.java:49-52` | 🟢 |
| Обратная совместимость конструкторов `ITSystem` (4/6/7 арг.) и `Solution` (3/4 арг.) — перегрузки, а не builder | `CM/node/ITSystem.java:28-36`, `CM/node/Solution.java:24-26` | 🟢 |
| Единое соглашение исключений NPE/IAE зафиксировано в Javadoc `Invariants` | `CM/Invariants.java:12-13` | 🟢 |
| Единый `AuthorityMatrix.DEFAULTS` singleton, `poc()` вызывается один раз при загрузке класса | `CM/authority/AuthorityMatrix.java:28, 77-118` | 🟢 |

## Состояние

Состояния нет: все типы неизменяемы. Единственная «глобальная» величина — `AuthorityMatrix.DEFAULTS`, неизменяемый singleton (`CM/authority/AuthorityMatrix.java:28`) 🟢.

Единственная диаграмма состояний относится к `Lifecycle` и применяется проектором (не модулем): `Current (deletedAt == null, isCurrent=true) → Closed (deletedAt != null, isCurrent=false)`; модуль фиксирует только допустимые сочетания полей 🟡 (`CM/node/Lifecycle.java:14-21`; переходы — `_reversa_sdd/state-machines.md` §6).

## Наблюдаемость

В модуле нет логирования, метрик и трейсов (нет зависимостей на SLF4J/Micrometer; `contracts/canonical-model/pom.xml:1-16`) 🟢. Сообщения исключений — единственный диагностический канал; они не содержат значений из источников, кроме имени поля/типа (`CM/Invariants.java:75-76`) и сообщения `UpsertRelation` с типом/метками (`CM/command/UpsertRelation.java:33, 36, 40`) 🟢.

## Риски и пробелы

- 🟢 [UNLOCKER-280: реализовано] `SyncRunStatus.SUCCEEDED/FAILED`, `SyncRun.endedAt` и счётчики выставляет закрытие прогона (`SyncRunWriter`, вызывает `EventProcessor` по маркеру `snapshot-complete`); проектор создаёт узел только со `status = 'RUNNING'` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java:698`; `CM/provenance/SyncRunStatus.java:5`). Источник: `_reversa_sdd/detective-gaps.md` Р-5. Формула счётчиков (`SyncRunCounts.of`, по журналу без маркера): `fetched` — все события прогона, `applied` — `PROJECTED`/`DUPLICATE`/`IGNORED_OLD_VERSION`, `failed` — `QUARANTINED` + `RETRYING`.
- 🔴 `docs/02-data-model.md:170` не называет `platformId` среди явных правил `ITSystem`; код — называет (`CM/authority/AuthorityMatrix.java:95-96`, ADR 0031).
- 🔴 ADR 0008 (`docs/adr/0008-authority-matrix-field-level.md:14`) называет только `DEPENDS_ON` — EAM; мастер `DECOMPOSED_INTO` = SCM существует лишь в коде (`CM/authority/AuthorityMatrix.java:108`) и `docs/02`.
- 🔴 `CloseAssertion` нигде в `main` не создаётся (поиск по `ingestion/`, `adapters/`, `access/`, `platform/`): потребляют `ingestion/graph-projector/.../GraphProjector.java:347`, `EventProcessor.java:577`; производителя нет — неясно, мёртвая ли команда или задел.
- 🟡 `UpsertRelation` допускает `validity == null` и у темпоральных типов (`CM/command/UpsertRelation.java:35`); обработку `null` определяет проектор.
- 🟡 `CloseAssertion.validTo` не сравнивается с `validFrom` связи в модуле; `validTo = max(validFrom, validTo)` делает проектор (`docs/02-data-model.md` §4).
- 🟡 `ITSystem.criticality` и `criticalityRaw` взаимоисключающи по Javadoc (`CM/node/ITSystem.java:6-11`), но конструктор этого не проверяет.
- 🟡 `Repository.url` не нормализуется и не проверяется в конструкторе, тогда как `Deployment.repositoryUrl` обязан быть нормализован (`CM/node/Repository.java:10`, `CM/node/Deployment.java:30-32`).
- 🟡 `HOSTED_ON` объявлена на `VIRTUAL_MACHINE → PHYSICAL_SERVER`; для `UNSPECIFIED` (`COMPUTE_INSTANCE`) недостижима; нормализатор её вообще не строит (`docs/02-data-model.md:79`).
- 🟡 `DeferRelation`, `LookupKeys` и `CanonicalNode` не имеют собственных unit-тестов в модуле (`grep` по `CT/`: только `case DeferRelation d` в `GraphCommandTest.java:106`).
