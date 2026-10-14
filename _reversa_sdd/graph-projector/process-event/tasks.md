# graph-projector / process-event: задачи

> Сокращения путей как в [`../requirements.md`](../requirements.md). Задачи описывают воспроизведение поведения; общие предпосылки — [`../tasks.md`](../tasks.md).

## Задачи

- [ ] **T-01. Каркас `EventProcessor` и результат.** Класс `final`, поля из `GP/EventProcessor.java:107-116`, конструкторы с делегированием (7→8→9→10 аргументов, `DeploymentLookup.NONE`, `EamLookup.NONE`, `NormalizationWarnings.NONE`), `requireNonNull` на все зависимости; `ProcessingResult(status, errorCode, projection)`.
  Origin: `GP/EventProcessor.java:81-176`, `GP/ProcessingResult.java:13-18`. Критерий готовности: сборка с `ingestion-service`-бином (`IS/IngestionServiceConfiguration.java:103-112`). Уверенность: 🟢.
- [ ] **T-02. Маршрут и конечные статусы.** `FINISHED`, `process`: `find` → `IllegalArgumentException`; конечный статус → вернуть как есть; `switch` по `type`.
  Origin: `GP/EventProcessor.java:99-105, 185-199`. Критерий готовности: AC-11, AC-12 и `finishedEventIsNotProcessedTwice`. Уверенность: 🟢.
- [ ] **T-03. Таблица кодов источников.** `sourceCode(urn)`: префикс `urn:corp:`, `eam`/`scm`/`cmdb`/`deploymap` → `SourceSystemCode`, иначе `null`.
  Origin: `GP/EventProcessor.java:97, 633-645`. Критерий готовности: неизвестный источник → `UNKNOWN_SOURCE`. Уверенность: 🟢 (дубль таблицы, Q-06).
- [ ] **T-04. Решение по версии.** `VersionDecision.forUpsert/forTombstone`; `decideBeforeWrite` (только `RECEIVED`), `ignoreIfOlderOnRetry` (только не-`RECEIVED`, только `OLD`).
  Origin: `GP/VersionDecision.java:20-38`, `GP/EventProcessor.java:303-344`. Критерий готовности: AC-02, AC-03, `retriedOlderVersionDoesNotOverwriteAssertionsOfNewerOne`. Уверенность: 🟢.
- [ ] **T-05. Путь `upsert`.** Шаги 1–10 из [`design.md`](design.md) в указанном порядке; `DeferRelation` на различные `UnresolvedReference.relation()`; `warnings.record` только при непустом списке.
  Origin: `GP/EventProcessor.java:201-252`. Критерий готовности: AC-01, `unresolvedReferenceReachesProjectorAsDeferredRelationOnce`, `relationOnlyEventIsProjectedNotQuarantined`. Уверенность: 🟢.
- [ ] **T-06. `resolveGids`.** Первым — `identity.resolve(key)`; концы `UpsertRelation` — `identity.find`, иначе `ProjectionException(UNRESOLVED_ENDPOINT)`; исчерпывающий `switch` по `GraphCommand`.
  Origin: `GP/EventProcessor.java:568-601`. Критерий готовности: событие с конечной точкой без `gid` → `RETRYING`/`UNRESOLVED_ENDPOINT`, `gid` не создан. Уверенность: 🟢.
- [ ] **T-07. Кандидаты и утверждения.** `recordCandidates`, `assertProperties`, `assertSystemRelation`, `assertSystemPlatform`, `retractProperties`; все вне Neo4j; `IdentityStoreException` → `RETRYING` с кодами `IDENTITY_CANDIDATE_FAILED`/`SOURCE_CONFLICT_FAILED`.
  Origin: `GP/EventProcessor.java:231-249, 394-453, 494-527, 563-566`. Критерий готовности: AC-09, AC-10, `assertsPropertiesBeforeGraphWriteAndPassesMarkersToProjector`. Уверенность: 🟢.
- [ ] **T-08. `write` и таблица ошибок.** Порядок `catch` (см. [`design.md`](design.md)); после успеха — `recheck*` (кроме `IGNORED_OLD_VERSION`); `finish`.
  Origin: `GP/EventProcessor.java:346-388`. Критерий готовности: AC-04, AC-05, `racedOldVersionFromGraphTransactionIsIgnored`, `racedSameVersionFromGraphTransactionIsProjected`. Уверенность: 🟢.
- [ ] **T-09. Перепроверки.** `recheckServiceDeployments`, `recheckSolutionSystems` (маркеры не пишутся).
  Origin: `GP/EventProcessor.java:455-484, 529-561`. Критерий готовности: `serviceEventRechecksItsDeploymentsAfterGraphWrite`, `solutionEventRechecksAllItsSystemsAfterGraphWrite`, `solutionEventDoesNotRecheckOnOldVersionOrTombstone`. Уверенность: 🟢.
- [ ] **T-10. Путь `delete` и `snapshotComplete`.** Как в [`design.md`](design.md); `objectCount` по умолчанию `0`.
  Origin: `GP/EventProcessor.java:254-300, 629-631`. Критерий готовности: AC-06, AC-07, AC-08. Уверенность: 🟢 (проверка полноты — 🟢 (реализовано — UNLOCKER-279) Q-05).
- [ ] **T-11. Статусы и checkpoint.** `advance`, `finish`, `retry`, `quarantine`; `CONSUMER = "projector"`.
  Origin: `GP/EventProcessor.java:84, 603-627`. Критерий готовности: `upsertWalksStatusesStepByStepAndSavesCheckpointAfterWrite`, AC-04 (checkpoint не двигается). Уверенность: 🟢.

## Тесты

- [ ] **TT-01.** Юнит `EventProcessorTest` на фейках журнала/проекции/identity — 46 методов; перечень в [`requirements.md`](requirements.md) AC. Origin: `GPT/EventProcessorTest.java`. Уверенность: 🟢.
- [ ] **TT-02.** `VersionDecisionTest` (4 теста). Origin: `GPT/VersionDecisionTest.java`. Уверенность: 🟢.
- [ ] **TT-03.** IT с реальным Neo4j+PG: `GraphProjectorIT#upsertCreatesNodeWithProvenanceAndMovesEventToProjected`, `webhookAndPollingWithSameVersionAreDuplicateNotOldVersion`, `graphFailureLeavesEventRetryingAndDoesNotMoveCheckpoint`, `invalidPayloadIsQuarantinedWithCodeAndWritesNothing`. Origin: `IT/GraphProjectorIT.java`. Уверенность: 🟢.
- [ ] **TT-04 (пробел).** Теста на маркер без `objectCount`, с неизвестным источником и с `dataschema` маркера нет. Origin: `GP/EventProcessor.java:289-300`. Уверенность: 🔴.

## Порядок

T-01 → T-03 → T-04 → T-11 → T-06 → T-07 → T-05 → T-08 → T-09 → T-10; тесты — параллельно с соответствующей задачей.

## Открытые 🔴

[`../questions.md`](../questions.md): Q-01 (`SUPERSEDED`), Q-02 (crosswalk), Q-05 (`objectCount`), Q-06 (дубли литералов); 🟡 Q-10, Q-11, Q-15.
