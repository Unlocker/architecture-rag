# node-model-invariants — Требования

> Сценарий модуля [`canonical-model`](../requirements.md): создание данных узла (`NodeData`), хранимого узла (`CanonicalNode`, `Lifecycle`) и provenance-записей. `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `CT/` = `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/`. Дизайн — [`design.md`](design.md).

## Обзор

Каждый `NodeData` — record, который при создании проверяет свои поля. Нормализатор, получивший плохое значение источника, получает исключение до отправки команды в проектор. Общая схема: обязательное поле — `Invariants.requireText` (NPE на `null`, IAE на blank), необязательное — `Invariants.optionalText` (`null` допустим, blank — нет) 🟢 (`CM/Invariants.java:21-35`).

## Правила

- Полный перечень полей и ограничений — [`../design.md`](../design.md), раздел «Узлы».
- `Deployment.sourceCommit`: ровно 40 символов `[0-9a-f]` (нижний регистр) 🟢 (`CM/node/Deployment.java:20, 27-29`); `repositoryUrl` равен своей нормализации 🟢 (`:30-32`); `eamSystemId > 0` 🟢 (`:33-35`).
- `ComputeInstance`: `hypervisorRef` только у VM, `serialNumber` только у физического сервера; метка определяется `kind` 🟢 (`CM/node/ComputeInstance.java:26-31, 35-41`).
- `ITSystem.platformId` и `Solution.platformId` положительны, если заданы 🟢 (`CM/node/ITSystem.java:22-24`, `CM/node/Solution.java:18-20`).
- `Repository.archived == null` — «неизвестно» и не должно перезаписывать известное значение (применяет проектор) 🟢/🟡 (`CM/node/Repository.java:6`).
- `Lifecycle`: `firstSeenAt ≤ lastSeenAt`, `isCurrent ⇔ deletedAt == null` 🟢 (`CM/node/Lifecycle.java:17-20`).
- `SourceKey`: `source` не `null`, `sourceType` и `sourceId` — непустой текст; `sourceId` — непрозрачная строка 🟢 (`CM/provenance/SourceKey.java:7-14`).
- `SourceRecord`: `sourceVersion` и `contentHash` — непустой текст, `fetchedAt` не `null` 🟢 (`CM/provenance/SourceRecord.java:11-16`).
- `SyncRun`: `startedAt ≤ endedAt` (если задан), счётчики `≥ 0` 🟢 (`CM/provenance/SyncRun.java:22-27`).
- `criticality` и `criticalityRaw` не проверяются на взаимоисключение 🟢 (нет проверки в `CM/node/ITSystem.java:16-25`); `Repository.url` не проверяется на формат 🟢 (`CM/node/Repository.java:9-12`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|----|------------|-----------|------------------|
| RF-01 | Обязательные поля: `null` → NPE, blank → IAE для `ITSystem.name`, `Service.name`, `Repository.url`, `Team.name`, `Environment.{code,name,environmentClass}`, `Deployment.{deploymentKey,name}`, `ComputeInstance.{hostname,kind}`, `Namespace.name`, `KubernetesCluster.name` 🟢 | Must | `NodeTest.requiredFieldsRejectNullAndBlank` |
| RF-02 | Необязательные поля: `""` → IAE, `null` ок 🟢 | Must | `NodeTest.optionalFieldRejectsEmptyString`, `itSystemEamCodeAndRawCriticalityAreOptionalButNotBlank` |
| RF-03 | `ComputeInstance.label()` = `VIRTUAL_MACHINE` / `PHYSICAL_SERVER` / `COMPUTE_INSTANCE` по `kind` 🟢 | Must | `NodeTest.computeInstanceLabelFollowsKind` |
| RF-04 | `hypervisorRef` разрешён только при `VIRTUAL_MACHINE`; `serialNumber` — только при `PHYSICAL_SERVER` 🟢 | Must | `NodeTest.computeInstanceKindSpecificFields` |
| RF-05 | `Deployment`: commit 40 hex в нижнем регистре; `repositoryUrl` нормализован; `eamSystemId > 0` 🟢 | Must | `NodeTest.deploymentValidatesCommitUrlAndSystemId` |
| RF-06 | `platformId > 0`, если задан 🟢 | Must | `NodeTest.platformIdMustBePositiveWhenSet` |
| RF-07 | `Lifecycle`: инварианты порядка времени и `isCurrent` 🟢 | Must | `NodeTest.lifecycleInvariants` |
| RF-08 | `NodeLabel.isA`, `label()` ("VirtualMachine"), `canonical()` 🟢 | Must | `NodeTest.labelSupertypes`, `solutionAndPlatformRequireNameAndHaveLabels` |
| RF-09 | `SourceKey`, `SourceRecord`, `SourceSystem`, `SyncRun` проверяют поля 🟢 | Must | `ProvenanceTest.sourceKeyRequiresAllParts`, `sourceRecordRequiredFields`, `sourceSystemAndSyncRun` |
| RF-10 | `CanonicalNode` требует `gid`, `data`, `lifecycle` 🟢 (`CM/node/CanonicalNode.java:9-13`) | Should | теста нет 🟡 |

## Нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Время | Только `Instant` (UTC), `LocalDateTime` не используется | `CM/node/Lifecycle.java:12`, `CM/provenance/SourceRecord.java:9`, `CM/provenance/SyncRun.java:14` | 🟢 |

## Критерии приёмки

```gherkin
Дано commit "9fceb02d0ae598e95dc970b74767f19372d61af8" (40 hex) и repositoryUrl "https://git.example.org/pay/api"
Когда new Deployment("d","n",null,null,commit,url,5101L,null)
Тогда объект создан
# CT/node/NodeTest.java:88

Дано тот же commit в верхнем регистре, либо 39 символов
Когда new Deployment(...)
Тогда IllegalArgumentException
# CT/node/NodeTest.java:88

Дано repositoryUrl "git@git.example.org:pay/api.git" (не нормализован)
Когда new Deployment(...)
Тогда IllegalArgumentException
# CT/node/NodeTest.java:88

Дано kind = PHYSICAL_SERVER и hypervisorRef = "hv"
Когда new ComputeInstance(...)
Тогда IllegalArgumentException
# CT/node/NodeTest.java:56

Дано Lifecycle(t1, t2, deletedAt=t2, isCurrent=true)
Когда конструктор
Тогда IllegalArgumentException
# CT/node/NodeTest.java:76

Дано SyncRun с endedAt раньше startedAt или fetched < 0
Когда конструктор
Тогда IllegalArgumentException
# CT/provenance/ProvenanceTest.java:32 (sourceSystemAndSyncRun)

Дано SourceRecord с sourceVersion "" или contentHash null
Когда конструктор
Тогда IAE для пустой строки и NPE для null
# CT/provenance/ProvenanceTest.java:23
```

## MoSCoW

| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01…RF-09 | Must | Используются при каждом разборе источника |
| RF-10 | Should | `CanonicalNode` — «хранимый» узел, читается `graph-query-core`/тестами |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `CM/node/*.java` | 11 `NodeData`, `CanonicalNode`, `Lifecycle`, enum | 🟢 |
| `CM/provenance/*.java` | `SourceKey`, `SourceRecord`, `SourceSystem`, `SyncRun` | 🟢 |
| `CM/Invariants.java` | `requireText`, `optionalText`, `requireNotAfter` | 🟢 |
