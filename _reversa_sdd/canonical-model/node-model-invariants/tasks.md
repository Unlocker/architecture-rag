# node-model-invariants — Задачи

Общий дизайн — [`../design.md`](../design.md). `CM/` и `CT/` — как в [`../requirements.md`](../requirements.md).

## Пререквизиты
- [ ] `Invariants`, `GitUrls`, `NodeLabel`.

## Задачи

- [ ] T-01 — `Invariants.requireText/optionalText/requireNotAfter` с точными текстами сообщений.
  - Origin: `CM/Invariants.java:21-42`
  - Критерий готовности: `"<name> must not be blank"`, `"<name> must not be blank when set"`, `"<from> must not be after <to>"`.
  - Уверенность: 🟢
- [ ] T-02 — 11 records `NodeData` с полями и проверками из `../design.md`.
  - Origin: `CM/node/ITSystem.java:13-36`, `Solution.java:12-26`, `Platform.java:10-19`, `Service.java:7-14`, `Repository.java:7-12`, `Team.java:7-12`, `Environment.java:8-14`, `Deployment.java:17-36`, `ComputeInstance.java:15-32`, `Namespace.java:6-10`, `KubernetesCluster.java:7-12` (все в `CM/node/`)
  - Критерий готовности: `NodeTest.*` зелёные.
  - Уверенность: 🟢
- [ ] T-03 — `ComputeInstance.label()` через `switch` по `ComputeKind`.
  - Origin: `CM/node/ComputeInstance.java:35-41`
  - Критерий готовности: три значения → три метки.
  - Уверенность: 🟢
- [ ] T-04 — `Lifecycle`, `CanonicalNode`.
  - Origin: `CM/node/Lifecycle.java:12-21`, `CM/node/CanonicalNode.java:7-13`
  - Критерий готовности: комбинации из `NodeTest.lifecycleInvariants`.
  - Уверенность: 🟢
- [ ] T-05 — provenance-records `SourceKey`, `SourceRecord`, `SourceSystem`, `SyncRun`, enum `SourceSystemCode`, `SyncRunStatus`.
  - Origin: `CM/provenance/SourceKey.java:8-14`, `SourceRecord.java:9-16`, `SourceSystem.java:8-13`, `SyncRun.java:14-28`, `SourceSystemCode.java:4-6`, `SyncRunStatus.java:4-6`
  - Критерий готовности: `ProvenanceTest.*`.
  - Уверенность: 🟢
- [ ] T-06 — `LookupKeys` (две константы и две фабрики).
  - Origin: `CM/provenance/LookupKeys.java:8-30`
  - Критерий готовности: `repositoryUrl("x")` = `SourceKey(SCM, "REPOSITORY_URL", "x")`, `itSystemCode("EAM-1")` = `SourceKey(EAM, "IT_SYSTEM_CODE", "EAM-1")`.
  - Уверенность: 🟢

## Тесты

- [ ] TT-01 — обязательные/необязательные поля: есть (`NodeTest.requiredFieldsRejectNullAndBlank`, `optionalFieldRejectsEmptyString`).
- [ ] TT-02 — `ComputeInstance`: есть (`computeInstanceLabelFollowsKind`, `computeInstanceKindSpecificFields`).
- [ ] TT-03 — `Deployment`: есть (`deploymentValidatesCommitUrlAndSystemId`).
- [ ] TT-04 — `Lifecycle`: есть (`lifecycleInvariants`).
- [ ] TT-05 — provenance: есть (`ProvenanceTest.*`).
- [ ] TT-06 — `LookupKeys`, `CanonicalNode`, `Repository` (blank `defaultBranch`): отсутствуют 🟡.

## Порядок
T-01 → T-05 → T-02/T-03/T-04 → T-06.

## Открытые 🔴
- Взаимоисключение `criticality`/`criticalityRaw`: проверять в модуле или оставить нормализатору? (🟡, [`../questions.md`](../questions.md) Q-5).
