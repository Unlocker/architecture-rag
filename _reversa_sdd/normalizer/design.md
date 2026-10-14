# ingestion/normalizer — дизайн

> Сокращения: `NZ/` = `ingestion/normalizer/src/main/java/io/github/unlocker/archrag/normalizer/`, `NT/` = `ingestion/normalizer/src/test/java/io/github/unlocker/archrag/normalizer/`, `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Метки: 🟢 код · 🟡 выведено · 🔴 пробел. Требования — [`requirements.md`](requirements.md).

## 1. Interface (публичная поверхность)

| Символ | Сигнатура | Возвращает | Заметки |
|---|---|---|---|
| `Normalizer(List<CanonicalMapper>, ReferenceResolver)` | `NZ/Normalizer.java:33-36` | — | `resolver` обязателен (`requireNonNull`); мапперы кладутся в `EnumMap<SourceSystemCode,…>` по `m.source()`; **два маппера одного источника — побеждает последний, без ошибки** 🟢 (`:35`) |
| `Normalizer.standard(ReferenceResolver)` | `:39-41` | `Normalizer` | = `standard(resolver, DeploymentLookup.NONE)` 🟢 |
| `Normalizer.standard(ReferenceResolver, DeploymentLookup)` | `:44-47` | `Normalizer` | порядок регистрации: `EamMapper()`, `ScmMapper(lookup)`, `CmdbMapper()`, `DeployMapMapper(lookup)` 🟢 |
| `Normalizer.normalize(CanonicalEvent, RawPayloadRef)` | `:54-86` | `NormalizationResult` | `raw` не проверяется на `null` (NPE на `raw.contentHash()`, `:72`) 🟢 |
| `CanonicalMapper` | `NZ/CanonicalMapper.java:14-43` | — | `source()`, `supportedSchemaVersions()`, `map(Input, CommandSink)`; `Input(SourceRecord record, Map<String,Object> payload, Instant time)`, `Input.key()` 🟢 |
| `CommandSink` | `NZ/CommandSink.java:17-112` | — | публично: `add`, `relation` (2 перегрузки), `link`, `warn`; конструктор и `result()` package-private 🟢 |
| `NormalizationResult` (sealed) | `NZ/NormalizationResult.java:7-35` | — | `Normalized(commands, unresolved, warnings)` — `List.copyOf` всех трёх; `Quarantined(errorCode, reason)` 🟢 |
| `UnresolvedReference` | `NZ/UnresolvedReference.java:19-28` | — | `(from, field, relationType, target, relation)`, все `requireNonNull` 🟢 |
| `ReferenceResolver` | `NZ/ReferenceResolver.java:9-14` | `boolean exists(SourceKey)` | `@FunctionalInterface` 🟢 |
| `DeploymentLookup` | `NZ/DeploymentLookup.java:16-70` | — | см. §4 |
| `NormalizationException` | `NZ/NormalizationException.java:7-20` | — | `RuntimeException`; `code()` + сообщение с именем поля 🟢 |
| `Payload` (package-private) | `NZ/Payload.java:15-127` | — | `required`, `optional`, `optionalObject`, `optionalInstant`, `optionalBoolean`, `optionalList`, `optionalIdRef` 🟢 |
| `NormalizerModule` | `NZ/NormalizerModule.java:4-7` | — | пустой маркер, закрытый конструктор 🟢 |

Протокол вызова: `Normalizer` не знает ни Spring, ни хранилищ. Бин создаётся в `IngestionServiceConfiguration.java:110` как `Normalizer.standard(projector::isActive, projector)` — `ReferenceResolver` = чтение активности записи в Neo4j, `DeploymentLookup` = сам `GraphProjector` (реализует `DeploymentLookup`, `GraphProjector.java:81`) 🟢.

## 2. Основной поток `Normalizer.normalize` (подробно — [`normalize-event/design.md`](normalize-event/design.md))

1. `event.type() == "architecture.asset.upserted.v1"` (`CanonicalEvent.TYPE_ASSET_UPSERTED`, `ES/CanonicalEvent.java:31`), иначе `Quarantined("UNSUPPORTED_EVENT_TYPE", "event type is not supported")` (`NZ/Normalizer.java:55-57`).
2. `sourceCode(event.source())` → `SourceSystemCode` или `null`; маппер из `EnumMap`; нет — `Quarantined("UNKNOWN_SOURCE", "no mapper for event source")` (`:58-62`).
3. Версия схемы = `dataschema` без префикса `urn:corp:schema:asset-upserted:`; если префикса нет или версии нет в `mapper.supportedSchemaVersions()` — `Quarantined("UNKNOWN_SCHEMA_VERSION", "dataschema version is not supported")` (`:63-66,105-107`).
4. Построение `SourceRecord` (`:67-74`) и `CommandSink(record.key(), resolver)` (`:75`).
5. `mapper.map(new Input(record, data.payload(), event.time()), sink)` (`:77`). `NormalizationException` → `Quarantined(e.code(), e.getMessage())` (`:78-79`); `IllegalArgumentException` → `Quarantined("INVALID_PAYLOAD", "payload violates canonical model invariants")` (`:80-83`).
6. `sink.result()` → `Normalized` (`:85`).

## 3. Алгоритмы общего назначения

### 3.1 `Payload` (🟢 `NZ/Payload.java`)

| Метод | Поведение |
|---|---|
| `required(f)` | `optional(f)` или `NormalizationException("MISSING_REQUIRED_FIELD", "required field is missing: " + f)` (`:24-28`) |
| `optional(f)` | `null`/нет ключа → `empty`; не `String` → `INVALID_PAYLOAD`; `strip()`; пусто → `empty` (`:31-41`). **Числа не приводятся к строке**: `name: 42` → `INVALID_PAYLOAD` (`NT/NormalizerTest#wrongFieldTypeIsQuarantined`) |
| `optionalObject(f)` | `null` → `empty`; не `Map` → `INVALID_PAYLOAD`; ключ не `String` → `INVALID_PAYLOAD`; копия в `HashMap` **с сохранением `null`-значений** (`:44-61`) |
| `optionalInstant(f)` | `Instant.parse` ISO-8601; ошибка → `INVALID_PAYLOAD` (`:64-74`) |
| `optionalBoolean(f)` | `null` → `empty`; не `Boolean` → `INVALID_PAYLOAD` (`:77-86`); строка `"true"` не принимается |
| `optionalList(f)` | `null` → `[]`; не `List` → `INVALID_PAYLOAD`; элемент не `String` → `INVALID_PAYLOAD`; пустые/пробельные элементы отбрасываются, остальные `strip()` (`:89-107`) |
| `optionalIdRef(f)` | `null` → `empty`; только `Long`/`Integer` → `toString()`; всё прочее (строка, список, `Short`, `Double`) → `INVALID_PAYLOAD` (`:113-122`) |

Сообщение ошибки типа — `"field has unexpected type: " + имя_поля` (`:125`), значение не попадает никуда.

### 3.2 `CommandSink` (подробно — [`resolve-references/design.md`](resolve-references/design.md))

- `add(cmd)` — как есть (`:31-33`).
- `relation(type, from, fromLabel, to, toLabel, [properties], validity, field)` — «одна сторона — сама запись»: `other = from.equals(self) ? to : from`; `UpsertRelation(..., assertedBy = self)` строится **до** проверки (его конструктор может бросить `IllegalArgumentException`); `resolver.exists(other)` ложно → `UnresolvedReference(self, field, type, other, relation)`, иначе команда (`:53-69`). Перегрузка без `properties` — `Map.of()` (`:49`).
- `link(...)` — «запись сама является связью»: проверяются обе стороны; на каждую неизвестную — отдельная `UnresolvedReference` (`fromField`, `toField`); команда добавляется лишь если известны обе (`:80-102`). Порядок в `unresolved`: `from`, затем `to` (`NT/NormalizerTest#eamServiceDependencyWithUnknownServiceIsUnresolved`).
- `warn(code)` — добавляет в `warnings` (`:105-107`).

## 4. Порты

### `ReferenceResolver.exists(SourceKey)`
Известен ли системе source record. Проектная реализация — `GraphProjector::isActive` (активная запись). Проверяется ключ «другого конца» связи 🟢.

### `DeploymentLookup` (`NZ/DeploymentLookup.java`)
| Метод | Использует | Дефолт |
|---|---|---|
| `repositoryByUrl(String normalizedUrl)` | `DeployMapMapper.bindToRepository` (`DeployMapMapper.java:194`) | абстрактный |
| `itSystemByEamCode(String)` | `ScmMapper` (`ScmMapper.java:77`) | `Optional.empty()` (`:49-51`) |
| `servicesImplementedIn(SourceKey repository)` | `DeployMapMapper` (`:211`) | абстрактный |
| `eamSystemOfService(SourceKey)` | **не мапперы**, а `EventProcessor` (`EventProcessor.java:443,470`) | `empty` (`:60-62`) |
| `chartDeploymentsOfService(SourceKey)` + `record ChartDeployment(SourceKey key, long eamSystemId)` | **не мапперы**, а `EventProcessor` (`:466`) | `List.of()` (`:68-70`) |
| константа `NONE` | тесты/сборка без графа: пусто на все запросы | (`:19-35`) |

Инварианты порта (Javadoc `:12-14`): только активные записи SCM и открытые связи (`validTo IS NULL`); результат — снимок на момент нормализации, переоценки нет; значения не логируются 🟢 (соблюдение реализацией — вне модуля 🟡).

## 5. Мапперы (кратко; EAM и DEPLOYMAP — в use case)

### 5.1 `ScmMapper` (🟢 `NZ/ScmMapper.java`) — `sourceType` ∈ `REPOSITORY|SERVICE`
- `REPOSITORY` (`:51-58`): `Repository(normalizedUrl(p.required("url")), p.optional("defaultBranch"), p.optionalBoolean("archived"))`. URL → `GitUrls.normalize`; `IllegalArgumentException` → `INVALID_PAYLOAD "repository url is not a recognized git url"` (`:106-112`). Нормальный вид: `<scheme>://<host>[:port]/<path>`, схема/хост в нижнем регистре, **путь сохраняет регистр**, без `user@`, `.git`, `/`; `ssh|git|scp` → `https`, `http` остаётся `http` (`CM/node/GitUrls.java:10-16,53-63`).
- `SERVICE` (`:59-94`): `Service(name*, serviceType, language, status)`; затем
  - `systemCode` → `relation(DECOMPOSED_INTO, from = lookup.itSystemByEamCode(code).orElseGet(() -> LookupKeys.itSystemCode(code)), IT_SYSTEM, to = key, SERVICE, validity, "systemCode")` (`:72-82`). Утверждает связь запись сервиса (`assertedBy = self`), хотя направление «система → сервис»;
  - `repositoryId` → `relation(IMPLEMENTED_IN, key, SERVICE, SourceKey(SCM,REPOSITORY,repo), REPOSITORY, validity, "repositoryId")` (`:83-93`).
  - `validity` = `Validity(validFrom, null)` из `validFrom` payload'а, иначе `null` (`:115-117`).
- иной `sourceType` → `UNKNOWN_SOURCE_TYPE "unknown SCM sourceType"` (`:95`).
- На ключ `(EAM, IT_SYSTEM, <systemCode>)` ссылаться **нельзя**: ключ системы в EAM — целочисленный `id`, а `systemCode` (`eam_id`) — её свойство (`:68-71`; `NT/NormalizerTest#scmServiceNeverReferencesLiteralEamCodeAsItSystemKey`).

### 5.2 `CmdbMapper` (🟢 `NZ/CmdbMapper.java`) — `COMPUTE_INSTANCE`
- Иной `sourceType` → `UNKNOWN_SOURCE_TYPE "unknown CMDB sourceType"` (`:32-34`).
- `kind` читается **первым** и обязателен (`p.required("kind")`, `:37`); значение → `ComputeKind.valueOf(upper)` (`VIRTUAL_MACHINE|PHYSICAL_SERVER|UNSPECIFIED`, регистр не важен), иначе `INVALID_PAYLOAD "field has unknown value: kind"` (`:51-57`). Затем `hostname` обязателен.
- `ComputeInstance(hostname, kind, ip, os, state, hypervisorRef, serialNumber)`; необязательные → `null`; поле `environment` не читается (`:38-48`).
- Инварианты модели, ловимые как `INVALID_PAYLOAD`: `hypervisorRef` только у `VIRTUAL_MACHINE`, `serialNumber` только у `PHYSICAL_SERVER` (`CM/node/ComputeInstance.java:26-30`).
- Связей не порождает 🟢 (привязку к окружению задаёт deploy map через `RUNS_ON`).

### 5.3 `EamMapper` — [`map-eam-objects/design.md`](map-eam-objects/design.md). `DeployMapMapper` — [`bind-deployment/design.md`](bind-deployment/design.md).

## 6. Зависит от / Связанные спецификации

### 6.1 По `pom.xml` (из `traceability/spec-impact-matrix.md` §1 и `dependencies.md`)
| Зависимость | Что используется | Evidence |
|---|---|---|
| `contracts/canonical-model` | `GraphCommand`, `UpsertNode`, `UpsertRelation`, узлы (`ITSystem`, `Platform`, `Solution`, `Team`, `Repository`, `Service`, `ComputeInstance`, `Environment`, `Deployment`), `NodeLabel`, `RelationType`, `Validity`, `SourceKey`, `SourceRecord`, `SourceSystemCode`, `LookupKeys`, `GitUrls`, `Criticality`, `ComputeKind`, `EnvironmentClass` | `ingestion/normalizer/pom.xml:18-21` |
| `contracts/event-schemas` | `CanonicalEvent`, `AssetEventData`, `RawPayloadRef`, `SourceVersion` | `pom.xml:22-25`, `NZ/Normalizer.java:6-8` |
| `contracts/source-spi` | не используется в `main`-коде нормализатора (только `Fixtures` в тестах: `SourceChange`) | `pom.xml:26-29`; `NT/Fixtures.java:7`. 🟡 зависимость в `compile`-scope, но `main` её не импортирует (`grep sourcespi NZ/*.java` пусто) |
| `platform/source-stubs` | `test`-scope: `StubSources`, `StubSource` для фикстур | `pom.xml:30-34`; `NT/NormalizerTest.java:43` |
| Потребители | `ingestion/graph-projector` (`EventProcessor`), `ingestion/ingestion-service` (конфигурация), `platform/integration-tests` (test) | `ingestion/graph-projector/pom.xml:29`, `ingestion/ingestion-service/pom.xml:32`, `platform/integration-tests/pom.xml:70` |

> Spec impact §1: «`ingestion/normalizer` → прямо затрагивает `graph-projector`, `ingestion-service`; через данные: формат deploy map (`deploymap-poc-0/1`) в `source-stubs`; идентичность Git URL; проверить `NormalizerTest`, `DeployMapMapperTest`». 🟢 (подтверждено, см. ниже).

### 6.2 Связи вне `pom.xml` (spec-impact-matrix §2)
| № матрицы | Связь | Сторона в этом модуле | Сторона вне модуля | Защита |
|---|---|---|---|---|
| 1 | Литералы типов событий `deleted`, `snapshot-complete` | нормализатор знает только `upserted` (`NZ/Normalizer.java:55-57`; `CanonicalEvent.java:31`) и квартирует остальное как `UNSUPPORTED_EVENT_TYPE` | `EventProcessor.java:92-94` (`TYPE_ASSET_DELETED`, `TYPE_SNAPSHOT_COMPLETE`) маршрутизирует их **до** `Normalizer` | теста нет 🔴 (в матрице) |
| 2 | Таблица кодов источников `eam|scm|cmdb|deploymap` | `NZ/Normalizer.java:96-102` | адаптер `EventMapper.java:46-48`, `EventProcessor.java:642`, консоль, UI | нет теста «покрыты все `SourceSystemCode`» 🔴; `MANUAL` без маппера |
| 3 | Регистр источника (URN в журнале / `SourceSystemCode.name()` в графе) | `Normalizer` переводит URN → enum, и далее везде enum | `GraphProjector.java:697-698` | соглашение 🔴 |
| 11 | Два нормализатора URL репозитория | `ScmMapper.java:103-104` (`GitUrls.normalize`, регистр пути сохраняется) | `ingestion/identity-resolution/.../FeatureNormalizer.java:32` (нижний регистр) | TODO в коде 🟡 |
| 15 | Имя модуля ≠ код источника (`asset-adapter` ↔ `cmdb`) | `NZ/Normalizer.java:99` (`cmdb` → `CMDB`, класс `CmdbMapper`) | `adapters/asset-adapter/.../AssetAdapter.java:18` | документация `docs/03-interfaces.md:322` 🟡 |
| 16 | Формат deploy map `deploymap-poc-0/1` | разбор `NZ/DeployMapMapper.java:142-190` | генерация в `platform/source-stubs` (`DeployMapFormat`, `StubSources`) | `DeployMapFixturesContractTest` (в `deploymap-adapter`) 🟢 |
| 9 / 12 | Новая метка узла/тип связи → allowlist'ы в `graph-query-core`, `AuthorityMatrix` | `RelationType.allows` проверяется в конструкторе `UpsertRelation`; новые пары меток в мапперах не пройдут без правки модели | — | `Neo4jSchemaTest`, `QueryTemplateRegistryTest` (вне модуля) |

### 6.3 Сквозные изменения (§3 матрицы), задевающие модуль
- «Новая метка узла или тип связи»: `canonical-model` → **`normalizer`** → `graph-projector` (`Neo4jSchema`) → `graph-query-core` → `mcp-server`/`admin-console`/`admin-ui` (№ 9, 4).
- «Новый источник данных»: `source-spi`, новый `*-adapter`, **`normalizer`** (новый `CanonicalMapper` + `sourceCode`), `identity-resolution`, `graph-projector`, `source-stubs`, `docker` (№ 2, 3, 15, 16).
- «Смена формата raw payload»: `adapter-core` → `ingestion-service` → `graph-projector`; на нормализатор влияет лишь форма `Map<String,Object> payload` (уже разобранная `StoredEventReader` в `AssetEventData`).

### 6.4 ADR/документы
ADR 0003 (`gid` и `SourceRecord`), 0008 (authority matrix), 0009/0029 (deploy map, chart), 0019 (identity без автослияния), 0020 (tombstone), 0024 (формат id), 0028 (структурные связи темпоральны), 0031 (платформа ИТ-системы); `docs/03-interfaces.md` §5.2, `docs/06-eam-integration.md` — см. [`decisions.md`](decisions.md).

## 7. Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---|---|---|
| Чистая функция + порты вместо прямого доступа к хранилищам | `NZ/ReferenceResolver.java:5-8`, `NZ/DeploymentLookup.java:7-10` | 🟢 |
| Ошибка = `Quarantined` значение, а не исключение | `NZ/NormalizationResult.java:30-34`, `NZ/Normalizer.java:78-84` | 🟢 |
| Отложенные ссылки через `UnresolvedReference` (+alias-ключи) вместо фиктивных узлов | `NZ/CommandSink.java:62-67`, `NZ/ScmMapper.java:68-82`, `DeployMapMapper.java:195-198` | 🟢 |
| `null` вместо значений по умолчанию; обязательные `kind`/`name` | `NZ/CmdbMapper.java:36-37`, `NT/NormalizerTest#absentKindIsQuarantinedNotDefaulted` | 🟢 |
| Строгий разбор ссылок EAM: только одиночный целый `id` | `NZ/Payload.java:109-122`; docs/06 решение 3 | 🟢 |
| Критичность EAM: две таблицы (ArchObject — словарь HIGH/MEDIUM/LOW с `criticalityRaw`; плоский/`SERVICE_DEPENDENCY` — `Criticality.valueOf`) | `NZ/EamMapper.java:120-126,129-130,161-168` | 🟢 факт, 🟡 намеренность; К-10 (UNLOCKER-287): неизвестное значение у плоского/`SERVICE_DEPENDENCY` → warning + `criticalityRaw`, не карантин |
| `validity` только из источника | `NZ/EamMapper.java:94,112,192` | 🟢 |
| Причины без значений (фиксированные строки) | `NZ/Normalizer.java:81-83` | 🟢 |

## 8. Estado Interno (Состояние)
Нет изменяемого состояния после конструктора (`Normalizer.mappers` заполняется один раз, `NZ/Normalizer.java:35`). `CommandSink` — три изменяемых списка на один вызов (`:21-23`). `DeployMapMapper` хранит только `lookup`; `ScmMapper` — только `lookup`; `EamMapper`/`CmdbMapper` без полей (кроме статических констант и `LOG`) 🟢.

## 9. Observabilidade (Наблюдаемость)
- Логов почти нет: `System.Logger` `EamMapper.class.getName()`, `DEBUG` «EAM IT_SYSTEM has a criticality value outside the dictionary» без значения (`NZ/EamMapper.java:47,167`) 🟢.
- Метрики — снаружи: `warnings` передаются в `NormalizationWarnings.record(source, warnings)` (`EventProcessor.java:218-220`), счётчик `archrag.normalizer.unmapped.values{source,property}` растёт только для кода `UNMAPPED_CRITICALITY` (`IngestionMetrics.java:47`; по `code-analysis.md` §Q5 п.8) 🟢. Коды ошибок попадают в журнал как `errorCode` статуса `QUARANTINED` (`EventProcessor.java:214-216`) 🟢.

## 10. Riscos e Lacunas (Риски и пробелы)
- 🔴 Q-01. `NormalizationResult.java:12` («`commands` начинаются с `UpsertNode` записи события») неверно для `SERVICE_DEPENDENCY`: маппер выдаёт только `DEPENDS_ON` (`EamMapper.java:100-115`; `NT/NormalizerTest#eamServiceDependencyMapsToDependsOnBetweenServices` проверяет отсутствие `UpsertNode`). Если обе стороны неизвестны — `commands` пуст.
- 🟡 Q-02. Любое не-`IllegalArgumentException` (NPE, ошибка порта) выходит из `normalize`; ошибочный `IllegalArgumentException` изнутри `ReferenceResolver`/`DeploymentLookup` был бы ошибочно показан как `INVALID_PAYLOAD` (оба вызова внутри `try`: `NZ/CommandSink.java:64,91-92`, `ScmMapper.java:77`, `DeployMapMapper.java:194,211`).
- 🟡 «Чистая» функция — только при неизменном состоянии графа: результат зависит от `exists`/`lookup` (снимок), поэтому replay может дать иные `commands`/`unresolved` (`NZ/DeploymentLookup.java:12-14`).
- 🟡 `Completeness.PARTIAL` из `SourceChange` в `Input` не передаётся; нормализатор не знает о неполноте (`NZ/CanonicalMapper.java:36`; `tech-debt.md` Н-9).
- 🟡 Конструктор молча допускает два маппера одного источника (`:35`).
- 🟡 `contracts/source-spi` в `compile`-scope без использования в `main` (`pom.xml:26-29`).
- Остальные — [`questions.md`](questions.md).
