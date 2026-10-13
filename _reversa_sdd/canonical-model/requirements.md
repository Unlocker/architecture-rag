# contracts/canonical-model — Требования

> Reversa Writer, R.6a. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел.
> Сокращения путей: `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `CT/` = `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/`.
> Проектирование — в [`design.md`](design.md); внешний контракт — в [`contracts.md`](contracts.md); сценарии — в подпапках ([`relation-command-validation/`](relation-command-validation/requirements.md), [`authority-lookup/`](authority-lookup/requirements.md), [`git-url-normalization/`](git-url-normalization/requirements.md), [`node-model-invariants/`](node-model-invariants/requirements.md)).

## Обзор

Модуль-контракт без логики хранения и ввода-вывода: каноническая модель графа Arch RAG. Он задаёт (1) типы данных узлов (`NodeData`, 11 records), (2) метки Neo4j и иерархию меток (`NodeLabel`), (3) типы связей с допустимыми концами и признаком темпоральности (`RelationType`), (4) команды проекции (`GraphCommand`, 5 records), (5) provenance-типы (`SourceKey`, `SourceRecord`, `SyncRun`, …), (6) матрицу авторитетности источников (`AuthorityMatrix`), (7) каноническую форму Git URL (`GitUrls`). Все инварианты проверяются в конструкторах: некорректный объект создать нельзя 🟢 (`CM/Invariants.java:9-14`, `CM/package-info.java:1`).

Модуль не зависит ни от одного проектного модуля и ни от одной сторонней библиотеки в `main` 🟢 (`contracts/canonical-model/pom.xml:1-16` — блока `<dependencies>` нет; тестовые JUnit 5 и AssertJ приходят из корневого `pom.xml:161-173`).

## Ответственности

- Описать структуру 11 типов узлов и отвергать неполные/некорректные данные при создании 🟢.
- Хранить словарь меток Neo4j (16, из них 13 канонических) и подтипы (`VirtualMachine`, `PhysicalServer` ⊂ `ComputeInstance`) 🟢.
- Хранить словарь типов связей (15) с допустимыми метками концов, флагом `temporal` и identity-свойствами 🟢.
- Описать пять команд проекции, которыми нормализатор общается с проектором, и проверить их структурные инварианты 🟢.
- Валидировать и копировать неизменяемо карту свойств связи 🟢.
- Предоставить матрицу «кто мастер» для узла / свойства / типа связи (fail-closed) 🟢.
- Приводить URL Git-репозитория к одному каноническому виду без утечки учётных данных 🟢.
- Выдавать ключи-псевдонимы (`LookupKeys`) для отложенных ссылок 🟢.

Вне ответственности (в модуле отсутствует): запись в Neo4j/PG, выдача `gid` (identity-resolution), решение о приоритете версий, разбор source DTO, проверка имён свойств `DEPENDS_ON` (`kind`/`protocol`/`criticality`) — это зона нормализатора (`CM/command/UpsertRelation.java:18-19`) 🟢.

## Регулирующие правила

- Соглашение об исключениях: `null` → `NullPointerException`, нарушенное правило → `IllegalArgumentException` 🟢 (`CM/Invariants.java:12-13`). Исключение: `Invariants.optionalText` и `requireText` бросают IAE на пустую строку; `GitUrls.normalize` бросает IAE и на `null` 🟢 (`CM/node/GitUrls.java:31-33`).
- Обязательное текстовое поле: не `null` и не blank; необязательное: `null` допустим, пустая или blank-строка — нет 🟢 (`CM/Invariants.java:21-35`).
- Время — только `java.time.Instant` (UTC) 🟢 (`CM/node/Lifecycle.java:12`, `CM/provenance/SourceRecord.java:9`, `CM/relation/Validity.java:8`).
- Авторитетность: поиск точный; источник вне списка не авторитетен; тип вне матрицы — пустое множество; `MANUAL` не мастер ничего 🟢 (`CM/authority/AuthorityMatrix.java:17-22, 63-74`).
- Свойство без собственного правила наследует правило метки; суперметка (`ComputeInstance` для `VirtualMachine`) НЕ опрашивается 🟢 (`CM/authority/AuthorityMatrix.java:68-70`).
- Допустимость связи считается с учётом подтипов меток: `RUNS_ON → VirtualMachine` допустима через `ComputeInstance` 🟢 (`CM/relation/RelationType.java:73-75`, `:19`).
- `validity` в `UpsertRelation` допустим только для темпоральных типов; `CloseAssertion` — только для темпоральных 🟢 (`CM/command/UpsertRelation.java:35-37`, `CM/command/CloseAssertion.java:18-20`).
- Identity-свойства допускаются только у темпоральных типов, иначе падает инициализация enum `RelationType` 🟢 (`CM/relation/RelationType.java:42-44`).
- Свойство `commit` — идентифицирующее для `DEPLOYED_FROM`; `UpsertRelation` без него отвергается 🟢 (`CM/relation/RelationType.java:20`, `CM/command/UpsertRelation.java:39-41`).
- `null` в необязательном поле узла означает «не передано» (а не «очистить»); в частности `Repository.archived == null` — неизвестно и не должно перезаписывать известное значение 🟢 (`CM/node/Repository.java:6`); применение этой семантики — в проекторе 🟡.
- Нормализованный Git URL — `<scheme>://<host>[:port]/<path>`: схема и хост в нижнем регистре, путь с сохранением регистра, без учётных данных, `.git` и хвостового `/`; `ssh`/`git`/scp → `https`, `http` остаётся `http`, порт только у http(s) 🟢 (`CM/node/GitUrls.java:9-16`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|----|------------|-----------|------------------|
| RF-01 | `NodeData` — sealed-интерфейс ровно из 11 вариантов (`ITSystem, Solution, Platform, Service, Repository, Team, Environment, Deployment, ComputeInstance, Namespace, KubernetesCluster`); каждый возвращает свою `NodeLabel` через `label()` 🟢 (`CM/node/NodeData.java:9-13`) | Must | `NodeTest.requiredFieldsRejectNullAndBlank`, `solutionAndPlatformRequireNameAndHaveLabels` |
| RF-02 | Обязательные поля узлов отвергают `null` (NPE) и blank (IAE); необязательные — `null` ок, `""`/blank → IAE 🟢 (`CM/node/*.java`; сводка — [`node-model-invariants`](node-model-invariants/requirements.md)) | Must | `NodeTest.requiredFieldsRejectNullAndBlank`, `optionalFieldRejectsEmptyString` |
| RF-03 | `NodeLabel`: 16 значений; строка метки Neo4j (`label()`); `supertype()`; `isA` транзитивно и рефлексивно; `isCanonical()`/`canonical()` исключают `SOURCE_SYSTEM`, `SOURCE_RECORD`, `SYNC_RUN` 🟢 (`CM/node/NodeLabel.java:14-71`) | Must | `NodeTest.labelSupertypes`, `solutionAndPlatformRequireNameAndHaveLabels`; `AuthorityMatrixTest.everyCanonicalLabelAndDomainRelationHasExactlyOneMaster` |
| RF-04 | `RelationType`: 15 значений с `sources`, `targets`, `temporal`, `identityProperties`; `allows(from,to)` с подтипами 🟢 (`CM/relation/RelationType.java:12-76`) | Must | `RelationTypeTest.*` (7 тестов) |
| RF-05 | `GraphCommand` — sealed из 5 вариантов (`UpsertNode, UpsertRelation, CloseAssertion, TombstoneSourceRecord, DeferRelation`); `switch` по нему исчерпывающ без `default` 🟢 (`CM/command/GraphCommand.java:7-9`) | Must | `GraphCommandTest.switchIsExhaustiveWithoutDefault` |
| RF-06 | `UpsertRelation` проверяет: обязательные поля (кроме `validity`, `properties` отдельно), допустимость пары меток, `validity` только у temporal, валидность `properties`, наличие всех identity-свойств 🟢 (`CM/command/UpsertRelation.java:25-42`) | Must | `GraphCommandTest.validityOnlyForTemporal`, `disallowedLabelsRejected`, `propertiesValidatedAndImmutable` |
| RF-07 | `Invariants.copyProperties`: значения — `String, Boolean, Long, Integer, Double (конечный), Instant` или `List` из них (без вложенных списков); ключ — непустой текст; результат неизменяем и не зависит от исходной карты/списков 🟢 (`CM/Invariants.java:44-77`) | Must | `GraphCommandTest.nonFiniteDoublesRejected`, `propertiesValidatedAndImmutable` |
| RF-08 | `CloseAssertion` — только для темпоральных типов, все 5 полей обязательны 🟢 (`CM/command/CloseAssertion.java:12-21`) | Should | `GraphCommandTest.closeAssertionOnlyTemporal` |
| RF-09 | `UpsertNode`, `TombstoneSourceRecord`, `DeferRelation` проверяют только `null` 🟢 (`CM/command/UpsertNode.java:10-13`, `CM/command/TombstoneSourceRecord.java:10-13`, `CM/command/DeferRelation.java:14-16`) | Must | `GraphCommandTest.upsertNodeAndTombstoneRequireFields` (`DeferRelation` отдельно не тестируется 🟡) |
| RF-10 | `AuthorityMatrix.defaults()` — единственная встроенная матрица PoC (правила узлов для 13 меток, 9 правил свойств у 7 меток, правила для 12 типов связей); три перегрузки `isAuthoritative` и три метода `*Authorities` 🟢 (`CM/authority/AuthorityMatrix.java:43-125`) | Must | `AuthorityMatrixTest.*` (11 тестов) |
| RF-11 | `GitUrls.normalize(raw)` приводит URL к канону; идемпотентна; отвергает blank/неразбираемое/неподдерживаемую схему/пустой хост/пустой путь без утечки входа 🟢 (`CM/node/GitUrls.java:30-91`) | Must | `GitUrlsTest.*` (9 методов) |
| RF-12 | Provenance: `SourceKey(source, sourceType, sourceId)`, `SourceRecord(key, sourceVersion, contentHash, fetchedAt, active)`, `SourceSystem(code, name)`, `SyncRun(...)`, enum `SourceSystemCode`, `SyncRunStatus` — с проверками конструкторов 🟢 (`CM/provenance/*.java`) | Must | `ProvenanceTest.*` (3 теста) |
| RF-13 | `Lifecycle`: `firstSeenAt ≤ lastSeenAt` и `isCurrent ⇔ deletedAt == null` 🟢 (`CM/node/Lifecycle.java:17-20`); `Validity`: `validFrom` обязателен, `validTo` открыт (`null`) или `≥ validFrom` 🟢 (`CM/relation/Validity.java:10-15`) | Must | `NodeTest.lifecycleInvariants`, `RelationTypeTest.validityInvariants` |
| RF-14 | `LookupKeys.repositoryUrl(url)` → `SourceKey(SCM, "REPOSITORY_URL", url)`; `LookupKeys.itSystemCode(code)` → `SourceKey(EAM, "IT_SYSTEM_CODE", code)`; константы `REPOSITORY_URL`, `IT_SYSTEM_CODE` 🟢 (`CM/provenance/LookupKeys.java:11-30`) | Should | прямого теста нет 🔴 (используется в `DeployMapMapper.java:198`, `ScmMapper.java:77`) |
| RF-15 | `CanonicalModelModule` — приватный маркер, чтобы JAR не был пустым 🟢 (`CM/CanonicalModelModule.java:3-7`) | Could | `CanonicalModelSmokeTest.moduleMarkerIsOnClasspath` |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Неизменяемость / потокобезопасность | Все типы — records или final-классы; карты и множества копируются (`Map.copyOf`, `Set.copyOf`, `List.copyOf`); `AuthorityMatrix` неизменяема, `defaults()` возвращает общий singleton | `CM/Invariants.java:55-57`; `CM/relation/RelationType.java:39-40, 45`; `CM/authority/AuthorityMatrix.java:28, 117-125` | 🟢 |
| Безопасность (секреты) | Сообщения и `cause` `GitUrls.normalize` не содержат вход (учётные данные не попадают в лог/исключение); `URISyntaxException` перехватывается без `cause` | `CM/node/GitUrls.java:49-52`; `CT/node/GitUrlsTest.java:63-76` | 🟢 |
| Безопасность (fail-closed) | Неизвестный источник/метка/тип связи не авторитетны | `CM/authority/AuthorityMatrix.java:63-75` | 🟢 |
| Безопасность (недоверенный текст) | Исключения конструкторов называют поле и тип, но не значения свойств (`property <key> has unsupported type <class>`); исключение: сообщение `UpsertRelation` содержит `RelationType`/метки, не данные источника | `CM/Invariants.java:75-76`, `CM/command/UpsertRelation.java:33` | 🟡 |
| Переносимость | Java 21 (`release 21`), records/sealed/pattern-matching `switch`; без внешних зависимостей | `pom.xml:24-25, 183`; `contracts/canonical-model/pom.xml:1-16` | 🟢 |
| Тестируемость | Модуль целиком покрывается быстрыми unit-тестами (JUnit 5 + AssertJ), контейнеры не нужны; 48 тестовых методов в 7 классах | `CT/**/*Test.java` | 🟢 |

> Производительности, масштабируемости и доступности в модуле нет (чистые функции без I/O) — строки не заполняются.

## Критерии приёмки

```gherkin
Сценарий: Корректная связь принимается
  Дано SourceKey deployment d1 (DEPLOYMAP) и SourceKey host h1 (CMDB)
  Когда создаётся UpsertRelation(RUNS_ON, d1, DEPLOYMENT, h1, VIRTUAL_MACHINE, Map.of(), Validity(T, null), d1)
  Тогда команда создана (подтип VIRTUAL_MACHINE допустим через COMPUTE_INSTANCE)
  # CT/relation/RelationTypeTest.java:13 runsOnTargets; CT/command/GraphCommandTest.java:34 validityOnlyForTemporal

Сценарий: Недопустимая пара меток отвергается
  Дано RelationType RUNS_ON
  Когда создаётся UpsertRelation с toLabel = ENVIRONMENT
  Тогда IllegalArgumentException "RUNS_ON does not allow DEPLOYMENT -> ENVIRONMENT"
  # CT/command/GraphCommandTest.java:43 disallowedLabelsRejected

Сценарий: Неавторитетный источник
  Дано AuthorityMatrix.defaults()
  Когда isAuthoritative(IT_SYSTEM, "criticality", SCM)
  Тогда false; для EAM — true; для MANUAL — false
  # CT/authority/AuthorityMatrixTest.java:29 itSystemCriticalityOnlyEam; :37 dependsOnOnlyEam

Сценарий: Нормализация Git URL
  Дано "git@git.example.org:pay/payments-api.git"
  Когда GitUrls.normalize
  Тогда "https://git.example.org/pay/payments-api"
  # CT/node/GitUrlsTest.java:34

Сценарий: Утечка учётных данных исключена
  Дано "ftp://u:pw123@git.example.org/a/b"
  Когда GitUrls.normalize
  Тогда IAE, сообщение не содержит "pw123"
  # CT/node/GitUrlsTest.java:63

Сценарий: Нарушенный инвариант жизненного цикла
  Дано firstSeenAt = t1, lastSeenAt = t2, deletedAt = t2
  Когда new Lifecycle(t1, t2, t2, true)
  Тогда IllegalArgumentException (isCurrent должен быть false при deletedAt != null)
  # CT/node/NodeTest.java:76 lifecycleInvariants
```

## Приоритет (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| RF-01…RF-07 (узлы, метки, связи, команды, свойства) | Must | Используются normalizer, projector, identity-resolution, graph-query-core (`pom.xml` модулей) |
| RF-10 (AuthorityMatrix) | Must | Определяет, какое значение попадает в canonical; используется проектором, `PostgresSourceConflicts`, `ExplainProvenanceTool` |
| RF-11 (GitUrls) | Must | Ключ сопоставления репозиториев в deploy map |
| RF-12, RF-13 | Must | Базовые провенанс-инварианты |
| RF-08 (`CloseAssertion`) | Should | В `main` нет производителя команды (см. 🔴-3 ниже); потребители есть |
| RF-14 (`LookupKeys`) | Should | Нужны для отложенных связей; прямого теста нет |
| RF-15 (маркер) | Could | Технический |
| Валидация состава `SyncRunStatus.SUCCEEDED/FAILED` | Won't (не реализовано проектором) | Значения объявлены, но никем не выставляются (🔴-1) |

## Открытые вопросы и расхождения (кратко; детали в [`questions.md`](questions.md))

- 🔴-1 `SyncRunStatus.SUCCEEDED`/`FAILED` никем не выставляются (`CM/provenance/SyncRunStatus.java:5`; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java:698`).
- 🔴-2 `docs/02-data-model.md:170` перечисляет явные правила свойств `ITSystem` только `criticality`, `eamCode`; в коде также `platformId` (`CM/authority/AuthorityMatrix.java:95-96`).
- 🔴-3 `CloseAssertion` нигде в `main` не создаётся (потребители: `GraphProjector.java:347`, `EventProcessor.java:577`).
- 🔴-4 ADR 0008 не называет мастера `DECOMPOSED_INTO` (SCM) — в коде `AuthorityMatrix.java:108`.

## Трассировка кода

| Файл | Класс / функция | Покрытие |
|------|-----------------|----------|
| `CM/Invariants.java` | `requireText`, `optionalText`, `requireNotAfter`, `copyProperties` | 🟢 |
| `CM/node/*` (19 файлов) | `NodeData` + 11 records, `NodeLabel`, `Lifecycle`, `CanonicalNode`, `GitUrls`, 4 enum | 🟢 |
| `CM/relation/RelationType.java`, `Validity.java` | типы связей, интервал валидности | 🟢 |
| `CM/command/*` (6 файлов) | `GraphCommand` + 5 команд | 🟢 |
| `CM/provenance/*` (7 файлов) | `SourceKey`, `SourceRecord`, `SourceSystem`, `SyncRun`, `SourceSystemCode`, `SyncRunStatus`, `LookupKeys` | 🟢 |
| `CM/authority/AuthorityMatrix.java` | `defaults()`, `isAuthoritative`, `*Authorities` | 🟢 |
| `CM/CanonicalModelModule.java`, `CM/package-info.java` | маркер | 🟢 |
