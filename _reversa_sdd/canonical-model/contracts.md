# contracts/canonical-model — Внешний контракт

> Модуль выставляет не HTTP, а Java-API и строковые константы, которые попадают в Neo4j и в сообщения других модулей. `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`. Проектирование — [`design.md`](design.md).

## 1. Строки меток Neo4j (`NodeLabel.label()`)

`ITSystem, Solution, Platform, Service, Repository, Team, Environment, Deployment, ComputeInstance, VirtualMachine, PhysicalServer, Namespace, KubernetesCluster` (канонические) и `SourceSystem, SourceRecord, SyncRun` (провенанс) 🟢 (`CM/node/NodeLabel.java:14-29`). Изменение любой строки меняет схему графа (проектор создаёт `<label>_gid` UNIQUE-констрейнты для канонических меток: `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:17, 43-44`) 🟢.

## 2. Имена типов связей (`RelationType.name()`)

`USES_SOLUTION, USES_PLATFORM, DECOMPOSED_INTO, IMPLEMENTED_IN, HAS_DEPLOYMENT, IN_ENVIRONMENT, RUNS_ON, DEPLOYED_FROM, PART_OF, HOSTED_ON, DEPENDS_ON, OWNED_BY, ASSERTS, OWNS_RECORD, PROCESSED` (`CM/relation/RelationType.java:13-27`) 🟢. Имя константы — идентификатор типа в Cypher (подставляется из enum, не из ввода) 🟡. Identity-свойство `commit` у `DEPLOYED_FROM` — часть контракта с нормализатором (`CM/relation/RelationType.java:20`).

## 3. Коды источников (`SourceSystemCode`)

`EAM, SCM, CMDB, DEPLOYMAP, MANUAL` (`CM/provenance/SourceSystemCode.java:5`) 🟢. В графе — верхний регистр (`SourceRecord.source`, `SyncRun.adapter`), в журнале — URN/нижний регистр (spec-impact-matrix §2 №2, №3).

## 4. Ключи-псевдонимы (`LookupKeys`)

| Константа | Значение | Фабрика | Результат |
|---|---|---|---|
| `REPOSITORY_URL` | `"REPOSITORY_URL"` | `repositoryUrl(normalizedUrl)` | `SourceKey(SCM, "REPOSITORY_URL", normalizedUrl)` |
| `IT_SYSTEM_CODE` | `"IT_SYSTEM_CODE"` | `itSystemCode(eamCode)` | `SourceKey(EAM, "IT_SYSTEM_CODE", eamCode)` |

🟢 (`CM/provenance/LookupKeys.java:11-30`). Реальной `SourceRecord` с таким ключом нет: псевдонимы нужны для `DeferRelation`, проектор достраивает связь при появлении записи со свойством `url`/`eamCode`.

## 5. Команды проекции

Sealed `GraphCommand` из пяти записей — формат обмена нормализатор → проектор (`CM/command/GraphCommand.java:7-9`). Поля и инварианты — в [`design.md`](design.md), раздел «Команды». Проектор принимает только `GraphCommand` (не source DTO) — требование CLAUDE.md, выполняется в `ProjectionRequest` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/ProjectionRequest.java:84-85`) 🟡.

## 6. Допустимые типы свойств связи

`String, Boolean, Long, Integer, Double (конечный), Instant`, `List<…>` (одноуровневый) (`CM/Invariants.java:44-77`). Эти типы транслируются в параметры Cypher/Neo4j (`datetime` для `Instant`); `LocalDateTime` и вложенные структуры не поддерживаются 🟢.

## 7. Формат Git URL

Канон `<scheme>://<host>[:port]/<path>` (см. [`git-url-normalization/requirements.md`](git-url-normalization/requirements.md)); значения `Deployment.repositoryUrl` и `Repository.url` (после нормализации в `ScmMapper`) сопоставляются как строки 🟢/🟡.

## 8. Сообщения исключений как наблюдаемый контракт

Тексты IAE/NPE видны вызывающему и могут попасть в `INVALID_PAYLOAD`-причину нормализатора: `"<name> must not be blank"`, `"<name> must not be blank when set"`, `"<TYPE> does not allow <FROM> -> <TO>"`, `"validity is allowed only for temporal relation <TYPE>"`, `"<TYPE> requires identity properties [...]"`, `"only temporal relations can be closed: <TYPE>"`, `"property <key> has unsupported type <class>"`, `"property <key> must be a finite number"`, `"git url is blank"`, `"unrecognized git url"`, `"unsupported git url scheme"`, `"git url has no host"`, `"git url has no repository path"` 🟢. Тесты проверяют тип исключения, а не текст (кроме отсутствия `pw123`) 🟡.
