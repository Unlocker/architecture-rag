# graph-projector / project-request: задачи

> Сокращения путей как в [`../requirements.md`](../requirements.md). Общие предпосылки — [`../tasks.md`](../tasks.md).

## Задачи

- [ ] **T-01. `ProjectionRequest` и результаты.** Record с компактным конструктором-валидатором (PR-01), `isTombstone`, `isRelationOnly`, второй конструктор без маркеров; `ProjectionResult` (неизменяемые списки, `of(outcome)`), `ProjectionOutcome`, `ProjectionException(code)` с константой `UNRESOLVED_ENDPOINT`, `ConflictMarkers` (`none`, `of(gid, Map)`, `of(gid, List<SourceConflict>)`, `isNone`).
  Origin: `GP/ProjectionRequest.java:37-120`, `GP/ProjectionResult.java:13-25`, `GP/ProjectionOutcome.java:4-11`, `GP/ProjectionException.java:7-23`, `GP/ConflictMarkers.java:22-63`. Критерий готовности: `ProjectionRequestTest` (12 тестов) зелёный. Уверенность: 🟢.
- [ ] **T-02. `NodeProperties`.** `of(NodeData)` по компонентам record без `null`; `of(Map)` для связей; `value` (`Instant`→UTC, `Enum`→имя, списки); `utc`.
  Origin: `GP/NodeProperties.java:20-59`. Критерий готовности: `ProjectionRequestTest#nodePropertiesDropNullsAndConvertTypes`; IT `partialRecordDoesNotEraseKnownFields`. Уверенность: 🟢.
- [ ] **T-03. `VersionDecision`.** `forUpsert`, `forTombstone` (равная версия применяется только к активной записи); числовое сравнение `SourceVersion`.
  Origin: `GP/VersionDecision.java:14-39`. Критерий готовности: `VersionDecisionTest`. Уверенность: 🟢.
- [ ] **T-04. Скелет транзакции.** `project` (сессия на вызов, `executeWrite`), `write`: блокировка, решение, очистка отложенных, диспетчеризация команд, хвост.
  Origin: `GP/GraphProjector.java:106-111, 302-359`. Критерий готовности: AC-01, AC-02, AC-03; `concurrentUpsertsOfOneObjectKeepOneRecordAtTheHighestVersion`. Уверенность: 🟢.
- [ ] **T-05. `upsertNode`.** Авторитетность свойств, корневая метка и подтипы, `firstSeenAt/lastSeenAt`, `isCurrent` по мастерству, `ASSERTS` с `authority`/`confidence`.
  Origin: `GP/GraphProjector.java:417-465, 729-745`. Критерий готовности: AC-01, AC-04, AC-05; `computeInstanceSubtypeChangeKeepsOneNode`. Уверенность: 🟢.
- [ ] **T-06. `upsertRelation` и идентичность связи.** Авторитетность типа, `MERGE` с id-свойствами, правила `opening`, `UNRESOLVED_ENDPOINT`.
  Origin: `GP/GraphProjector.java:552-602, 649-660`. Критерий готовности: AC-05, AC-08; IT `temporalRelationGetsValidFromAndIsNotReopenedByRepeat`, `hostReturningInLaterVersionReopensRunsOn`, `DeployedFromIT`. Уверенность: 🟢.
- [ ] **T-07. `closeReplacedRelations`.** Тройки, `kept` по типам (включая `DeferRelation`), закрытие вне набора.
  Origin: `GP/GraphProjector.java:604-647, 662-666`. Критерий готовности: AC-06, AC-07. Уверенность: 🟢.
- [ ] **T-08. `tombstone`.** Четыре запроса (запись, связи через `ASSERTS`, связи «только связь» по enum, архивация узла без других MASTER).
  Origin: `GP/GraphProjector.java:507-550, 747-749`. Критерий готовности: AC-09, AC-10; `tombstoneClosesStructuralEdges`. Уверенность: 🟢.
- [ ] **T-09. `markRelationOnlyRecord`, `writeConflictMarkers`, `linkSyncRun`.**
  Origin: `GP/GraphProjector.java:467-505, 687-703`. Критерий готовности: `relationOnlyEventIsProjectedNotQuarantined` (юнит); AC-12; IT `SourceConflictIT` (маркеры `ASSERTS.conflicts`). Уверенность: 🟢 (IT конфликтов — 🟡: тест вне этого модуля).
- [ ] **T-10. `closeAssertion`.** Для полноты контракта; нет производителя.
  Origin: `GP/GraphProjector.java:668-685`. Критерий готовности: IT `closeAssertionClosesOnlyTheOpenRelationAssertedByTheRecordAndIsIdempotent`. Уверенность: 🟢 (поведение) / 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 5)] (нужность, Q-03).
- [ ] **T-11. Порты чтения.** `applied`, `isActive`, `activeRecords`, `DeploymentLookup`, `EamLookup` — `executeRead`, параметры, `try-with-resources`.
  Origin: `GP/GraphProjector.java:113-300`. Критерий готовности: AC-13; `eamSystemOfServiceFindsTheSingleDecomposingSystem`, `eamSystemOfServiceIsEmptyWithoutSystem`. Уверенность: 🟢.
- [ ] **T-12. `TemporalRelationMigration`.** По типам `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `DECOMPOSED_INTO`, `IMPLEMENTED_IN`: отдельная сессия и `executeWrite` на тип; `validFrom = coalesce(min(firstSeenAt узлов записи), $now)` только где `validFrom IS NULL`.
  Origin: `GP/TemporalRelationMigration.java:22-65`. Критерий готовности: IT `temporalRelationMigrationFillsMissingValidFromAndIsIdempotent`. Уверенность: 🟢.

## Тесты

- [ ] **TT-01.** `ProjectionRequestTest` (12), `VersionDecisionTest` (4), `GraphProjectorSmokeTest` (1). Origin: `GPT/`. Уверенность: 🟢.
- [ ] **TT-02.** `GraphProjectorIT` (51 `@Test`, Neo4j `TestImages.NEO4J` + PostgreSQL 16 через Testcontainers). Origin: `IT/GraphProjectorIT.java:73-77`. Уверенность: 🟢; требует Docker.
- [ ] **TT-03 (пробел).** Юнит-тестов `GraphProjector` без Docker нет — Cypher проверяется только IT. Уверенность: 🔴 (организационный, не поведение).

## Порядок

T-01 → T-02 → T-03 → T-04 → T-05 → T-06 → T-07 → T-08 → T-09 → T-11 → T-12; T-10 по необходимости.

## Открытые 🔴

[`../questions.md`](../questions.md): Q-03 (`CloseAssertion`), Q-04 (`SyncRun`); 🟡 Q-09, Q-13, Q-14, Q-16, Q-17.
