# contracts/canonical-model — Задачи реимплементации

> `CM/` = `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/`, `CT/` = `contracts/canonical-model/src/test/java/io/github/unlocker/archrag/canonicalmodel/`. Требования — [`requirements.md`](requirements.md), дизайн — [`design.md`](design.md). Задачи сценариев: [`relation-command-validation`](relation-command-validation/tasks.md), [`authority-lookup`](authority-lookup/tasks.md), [`git-url-normalization`](git-url-normalization/tasks.md), [`node-model-invariants`](node-model-invariants/tasks.md).

## Пререквизиты
- [ ] Java 21 (`pom.xml:24-25`), JUnit 5 и AssertJ в тест-scope из корневого `pom.xml:161-173`.
- [ ] Родитель `io.github.unlocker.archrag:contracts:0.1.0-SNAPSHOT` (`contracts/canonical-model/pom.xml:6-11`); артефакт `canonical-model`, зависимостей в `main` нет.
- [ ] Корневой пакет `io.github.unlocker.archrag.canonicalmodel`.

## Задачи

- [ ] T-01 — Скелет Maven-модуля и маркер `CanonicalModelModule`.
  - Origin: `contracts/canonical-model/pom.xml:1-16`, `CM/CanonicalModelModule.java:3-7`, `CM/package-info.java:1-2`
  - Критерий готовности: `./mvnw -B -pl contracts/canonical-model -am verify` собирается; `CanonicalModelSmokeTest` зелёный.
  - Уверенность: 🟢
- [ ] T-02 — `Invariants` (текст, порядок времени, `copyProperties`).
  - Origin: `CM/Invariants.java:15-77`
  - Критерий готовности: см. [`node-model-invariants/tasks.md`](node-model-invariants/tasks.md) T-01 и [`relation-command-validation/tasks.md`](relation-command-validation/tasks.md) T-04.
  - Уверенность: 🟢
- [ ] T-03 — `NodeLabel` (16 значений, `supertype`, `isA`, `isCanonical`, `canonical`) с порядком значений как в оригинале (на порядок опирается `Neo4jSchema`).
  - Origin: `CM/node/NodeLabel.java:13-72`, `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:43-44`
  - Критерий готовности: `NodeTest.labelSupertypes`; 13 канонических меток.
  - Уверенность: 🟢
- [ ] T-04 — 11 records `NodeData`, enum `Criticality/EnvironmentClass/ComputeKind`, `CanonicalNode`, `Lifecycle`, `GitUrls`.
  - Origin: `CM/node/*.java`
  - Критерий готовности: [`node-model-invariants/tasks.md`](node-model-invariants/tasks.md) T-02…T-04, [`git-url-normalization/tasks.md`](git-url-normalization/tasks.md).
  - Уверенность: 🟢
- [ ] T-05 — `RelationType` (15 значений, порядок как в оригинале) и `Validity`.
  - Origin: `CM/relation/RelationType.java:12-76`, `CM/relation/Validity.java:8-15`
  - Критерий готовности: `RelationTypeTest.*`; 10 temporal; identity только у `DEPLOYED_FROM{commit}`.
  - Уверенность: 🟢
- [ ] T-06 — Provenance-типы и `LookupKeys`.
  - Origin: `CM/provenance/*.java`
  - Критерий готовности: `ProvenanceTest.*`.
  - Уверенность: 🟢
- [ ] T-07 — `GraphCommand` и 5 команд.
  - Origin: `CM/command/*.java`
  - Критерий готовности: `GraphCommandTest.*`; `switch` без `default` компилируется.
  - Уверенность: 🟢
- [ ] T-08 — `AuthorityMatrix` с таблицами из [`authority-lookup/requirements.md`](authority-lookup/requirements.md).
  - Origin: `CM/authority/AuthorityMatrix.java:26-126`
  - Критерий готовности: `AuthorityMatrixTest.*`.
  - Уверенность: 🟢

## Тесты

- [ ] TT-01 — 48 тестовых методов в 7 классах воспроизведены (`CT/**/*Test.java`): `CanonicalModelSmokeTest` 1, `NodeTest` 10, `GitUrlsTest` 9, `GraphCommandTest` 7, `AuthorityMatrixTest` 11, `ProvenanceTest` 3, `RelationTypeTest` 7 🟢.
- [ ] TT-02 — Отсутствующие тесты (добавить при реимплементации): `UpsertRelation` без identity-свойства `commit` (🔴 в [`relation-command-validation/tasks.md`](relation-command-validation/tasks.md) TT-05); `DeferRelation(null)`; `LookupKeys`; `CanonicalNode`; неизменяемость множеств `AuthorityMatrix`.
- [ ] TT-03 — Контрактный тест «каждое значение `SourceSystemCode` присутствует в таблицах кодов других модулей» (spec-impact-matrix §2 № 2) — вне модуля, предложение 🟡.

## Порядок

1. T-01, T-02. 2. T-03 (метки нужны связям и матрице). 3. T-04, T-05, T-06 (взаимно независимы, кроме `Deployment → GitUrls`). 4. T-07 (нужны T-04…T-06). 5. T-08 (нужны метки, связи, `SourceSystemCode`).

## Открытые 🔴

- Q-1 `SyncRunStatus.SUCCEEDED/FAILED` не выставляются; Q-2 доки `docs/02` без `platformId`; Q-3 `CloseAssertion` (удалён, UNLOCKER-284); Q-4 ADR 0008 без `DECOMPOSED_INTO` — детали и ссылки в [`questions.md`](questions.md).
