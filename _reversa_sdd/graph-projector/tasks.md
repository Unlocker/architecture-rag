# ingestion/graph-projector: задачи

> Сокращения путей как в [`requirements.md`](requirements.md). Задачи уровня модуля; сценарные — в [`process-event/`](process-event/tasks.md), [`project-request/`](project-request/tasks.md), [`deferred-relations/`](deferred-relations/tasks.md), [`reconcile-missing-set/`](reconcile-missing-set/tasks.md).

## Пре-реквизиты

- [ ] Модуль Maven `ingestion/graph-projector` под родителем `ingestion`; зависимости из `ingestion/graph-projector/pom.xml:17-39` (`canonical-model`, `event-schemas`, `normalizer`, `identity-resolution`, `neo4j-java-driver`); версии — через `dependencyManagement` корня.
- [ ] Java 21 (records, sealed `GraphCommand`, pattern matching в `switch` — `GP/EventProcessor.java:193-198, 572-585`, `GP/NodeProperties.java:49-55`).
- [ ] Neo4j 5.x Community, PostgreSQL (журнал, identity, конфликты), S3-совместимое хранилище — для интеграционных тестов (Testcontainers, `IT/GraphProjectorIT.java:73-77`).
- [ ] Порты соседних модулей: `EventJournal`, `RawPayloadStore` (`event-schemas`), `Normalizer`, `DeploymentLookup`, `IdentityMapping`, `IdentityCandidates`, `SourceConflicts`.

## Задачи

- [ ] **T-01. Схема Neo4j.** `Neo4jSchema.statements()` порождает constraint `<snake>_gid` по каждой `NodeLabel.canonical()` (порядок `ordinal`), затем `source_record_key`, `source_system_code`, `sync_run_id`, `pending_relation_key`, `environment_code`, индекс `it_system_eam_code`, fulltext `asset_text`; `apply(Driver)` — каждый statement в своём `executeWrite`.
  Origin: `GP/schema/Neo4jSchema.java:28-75`. Критерий готовности: `Neo4jSchemaTest` (8 тестов: gid-констрейнт на метку, фиксированные имена, snake_case, провенанс+`Environment`, индекс `eamCode`, идемпотентность, отсутствие enterprise-функций, уникальность имён). Уверенность: 🟢.
- [ ] **T-02. Применение схемы при старте.** `Neo4jSchema.apply(driver)` вызывается при создании `Driver` в `ingestion-service`.
  Origin: `IS/IngestionServiceConfiguration.java:67-74`. Критерий готовности: IT `Neo4jSchemaIT`. Уверенность: 🟢.
- [ ] **T-03. Миграция темпоральности.** `TemporalRelationMigration.migrate(Instant.now())` при старте сервиса; идемпотентна.
  Origin: `GP/TemporalRelationMigration.java:44-65`, `IS/IngestionServiceConfiguration.java:72`. Критерий готовности: IT `temporalRelationMigrationFillsMissingValidFromAndIsIdempotent`. Уверенность: 🟢.
- [ ] **T-04. Порты и типы обмена.** `GraphProjection`, `ReconciliationTrigger`, `NormalizationWarnings`, `EamLookup`, `ProcessingResult`, `ProjectionRequest/Result/Outcome/Exception`, `ConflictMarkers`, `NodeProperties`.
  Origin: `GP/GraphProjection.java`, `GP/ReconciliationTrigger.java`, `GP/NormalizationWarnings.java`, `GP/EamLookup.java`, `GP/ProcessingResult.java`, `GP/ProjectionRequest.java`. Критерий готовности: `ProjectionRequestTest`. Уверенность: 🟢.
- [ ] **T-05. Реализация сценариев.** По очереди: [`project-request`](project-request/tasks.md) (T-01…T-12), [`deferred-relations`](deferred-relations/tasks.md), [`process-event`](process-event/tasks.md), [`reconcile-missing-set`](reconcile-missing-set/tasks.md).
  Origin: см. сценарии. Критерий готовности: все `*Test` модуля и `GraphProjectorIT`/`ReconciliationIT` зелёные. Уверенность: 🟢.
- [ ] **T-06. Сборка бинов.** `GraphProjector(driver, AuthorityMatrix.defaults())`, `Reconciler(journal, rawStore, projector, Clock.systemUTC(), identity, conflicts)`, `EventProcessor(journal, Normalizer.standard(projector::isActive, projector), identity, projector, reconciliation, candidates, conflicts, projector, projector, metrics::recordWarnings)`.
  Origin: `IS/IngestionServiceConfiguration.java:85-112`. Критерий готовности: приложение стартует, `AdminEndpointIT`/`PlatformInvariantIT` зелёные. Уверенность: 🟢.
- [ ] **T-07. Инварианты проекта.** Сессии — `try-with-resources`; драйвер один; Cypher только с параметрами, метки/типы из enum; внутри `executeWrite` нет PG/S3; время UTC.
  Origin: `GP/GraphProjector.java:35-80`. Критерий готовности: ревью по ловушкам №1, 4, 5, 7, 8, 10 из `CLAUDE.md` агента. Уверенность: 🟢.

## Тесты

- [ ] **TT-01. Юнит модуля** (Docker не нужен): `EventProcessorTest` (46), `ReconcilerTest` (12), `ProjectionRequestTest` (12), `Neo4jSchemaTest` (8), `VersionDecisionTest` (4), `PropertyJsonTest` (3), `GraphProjectorSmokeTest` (1) — всего 86 `@Test` по `grep -c` в `GPT/`. Уверенность: 🟢.
- [ ] **TT-02. IT платформы** (Docker): `GraphProjectorIT` (51), `ReconciliationIT` (6), `Neo4jSchemaIT`, `DeployedFromIT`, `SourceConflictIT`, `GraphQueryCoreIT` (читающая сторона схемы). Origin: `IT/`. Уверенность: 🟢.
- [ ] **TT-03 (пробел).** Тесты на 🔴 из [`questions.md`](questions.md): маркер без `objectCount` (Q-05); `finishTombstone` для строки в `QUARANTINED` (Q-12); общий тест имён типов событий и таблицы кодов источников (Q-06). Уверенность: 🔴.

## Порядок

T-04 → T-01 → T-02 → T-05 (в порядке `project-request` → `deferred-relations` → `process-event` → `reconcile-missing-set`) → T-03 → T-06 → T-07.

## Открытые 🔴

Q-01 (`SUPERSEDED` недостижим), Q-02 (crosswalk не обновляет граф до replay), Q-03 (`CloseAssertion` без производителя), Q-04 (`SyncRun` всегда `RUNNING`), Q-05 (`objectCount` по умолчанию 0), Q-06 (дубли литералов), Q-08 (расхождения с docs/ADR) — [`questions.md`](questions.md). Решения: [`decisions.md`](decisions.md).
