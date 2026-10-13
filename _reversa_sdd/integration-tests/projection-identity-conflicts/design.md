# projection-identity-conflicts: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (стиль запуска M, изоляция, время). Требования: [`requirements.md`](requirements.md).

## Interface

Тестовые классы package-private. Общая сборка (стиль M) повторяется почти дословно в каждом классе:

| Символ | Сигнатура | Назначение | Трассировка |
|--------|-----------|------------|-------------|
| `GraphProjectorIT.setUp` | `@BeforeAll static void` | драйвер Neo4j, `Neo4jSchema.apply`, `PGSimpleDataSource`, `JournalMigrations.apply`, `PostgresEventJournal`, `PostgresIdentityMapping`, `PostgresIdentityCandidates`, `PostgresSourceConflicts`, `GraphProjector`, `EventProcessor` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:88-111` 🟢 |
| `GraphProjectorIT.handle(event[, syncRunId])` | `-> ProcessingResult` | `journal.append(event, RAW, runId)` + `processor.process(event, RAW)` синхронно, без диспетчера | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:144-151` 🟢 |
| `GraphProjectorIT.upsertEvent` / `deleteEvent` | `-> CanonicalEvent` | событие `asset.upserted.v1` / `asset.deleted.v1` с `urn:corp:<source>` и `AssetEventData(type, id, SourceVersion, payload)` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:130-142` 🟢 |
| `GraphProjectorIT.dump()` | `-> List<String>` | полный дамп узлов и связей (`elementId`, метки, свойства), отсортированный | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:171-181` 🟢 |
| `GraphProjectorIT.processor(GraphProjection)` | `-> EventProcessor` | сборка процессора над подменяемой проекцией (для имитации сбоя) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:106-111` 🟢 |
| `SourceConflictIT.send/eam/cmdb/link/marker` | | событие `IT_SYSTEM` от источника; crosswalk до первого события; чтение маркера `ASSERTS.conflicts` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java:150-196` 🟢 |
| `SourceConflictIT.CmdbSystemMapper` | `CanonicalMapper` | CMDB, присылающий `ITSystem` (в PoC такого пути в нормализаторе нет) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java:79-108` 🟡 |
| `PostgresSourceConflictsIT.master/dissent/row` | | вызовы `store.assertProperties(...)` и чтение строки `source_conflict` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/PostgresSourceConflictsIT.java:53-79` 🟢 |
| `IdentityMappingIT`, `IdentityCandidateIT` | | `PostgresIdentityMapping`, `PostgresIdentityCandidates` над реальным PG; гонки через пул потоков | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityMappingIT.java:35-52`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IdentityCandidateIT.java:64-140` 🟢 |
| `Neo4jSchemaIT.names/run` | | чтение имён constraint и индексов | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:37-46` 🟢 |

## Fluxo Principal

Типовой сценарий проектора:

1. `@BeforeEach`: `MATCH (n) DETACH DELETE n` (граф очищается; PG и identity не очищаются, поэтому ключи уникализируются `UUID`) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:113-117`).
2. Подготовка: `upsert(source, type, id, version, payload)` создаёт `CanonicalEvent`, `journal.append`, `processor.process`.
3. Проверка исхода: `ProcessingResult.status()`/`errorCode()` и состояние графа (`single(...)`, `query(...)`, `dump()`); для PG — SQL (`conflicts.openConflicts(gid)`, `row(...)`).
4. Негатив: тот же сценарий с нарушением (старая версия, пустой payload, недоступный граф) и проверка, что граф и checkpoint не изменились.

Сбой графа (`graphFailureLeavesEventRetryingAndDoesNotMoveCheckpoint`): анонимная `GraphProjection`, делегирующая чтения проектору и бросающая `ServiceUnavailableException("neo4j is down")` в `project` → процессор `processor(down)` возвращает `RETRYING`/`GRAPH_UNAVAILABLE`, `loadCheckpoint` не меняется; затем штатный `processor.process(event, RAW)` → `PROJECTED`, checkpoint = `event.id()` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:875-913`) 🟢.

Конфликты в графе (`SourceConflictIT`): crosswalk `link(id)` до событий → `eam(id, "1", "HIGH")` и `cmdb(id, "1", "LOW")` → `criticalityOf(gid) = HIGH`, `conflicts.openConflicts(gid)` содержит одну запись, `marker(cmdbKey) = [criticality]`, `marker(eamKey) = null` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java:198-219`).

Журнал (`EventJournalIT`): `PostgresEventJournal(dataSource())` создаётся в каждом тесте; события — `event(source, id, version)`; для DLQ — `dlqRows(source, id)`; для raw — `S3RawPayloadStore` на контейнере S3 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EventJournalIT.java:43-107, 243-259`) 🟢.

## Fluxos Alternativos

- **Имитации** вместо реального пути: `SourceConflictIT.reconciliationTombstoneResolvesConflictAndClearsMarkers` подменяет маркер snapshot событием `TYPE_ASSET_UPSERTED`; `rebuildKeepsNodeConflictAndMarker` — `DETACH DELETE` и переигровка с новыми `eventId`, не `RebuildService`; `GraphProjectorIT.missingServiceDependencyIsFoundByReconcilerAndItsEdgeIsClosed` не вызывает `Reconciler` (Q-15) 🟡 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java:280-338`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:748-770`).
- **Изменение PG напрямую:** `DeployedFromIT.commitChangeWhileRepositoryIsUnknownClosesOldEdge` выставляет `r.active = false` у репозитория Cypher-ом, `PlatformInvariantIT` и `DeployedFromIT` дополнительно чистят `source_conflict` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeployedFromIT.java:344-357, 92-98`) 🟡.
- **`processor.process` с тем же `eventId`**: возвращает `PROJECTED` (повторная доставка того же события в `GraphProjectorIT` вызывается только в `replayDoesNotChangeGraph`; остальные `handle` берут новый `eventId`) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:222-239`).

## Dependências

`ingestion/graph-projector` (`GraphProjector`, `EventProcessor`, `Reconciler`, `Neo4jSchema`, `TemporalRelationMigration`), `ingestion/event-journal` (`PostgresEventJournal`, `JournalMigrations`, `JournalQuery`, `S3RawPayloadStore`), `ingestion/identity-resolution` (`PostgresIdentityMapping`, `PostgresIdentityCandidates`, `PostgresSourceConflicts`), `ingestion/normalizer` (`Normalizer`, мапперы), `contracts/canonical-model`, `contracts/event-schemas`. Спецификации: [`../../graph-projector/design.md`](../../graph-projector/design.md), [`../../identity-resolution/design.md`](../../identity-resolution/design.md), [`../../event-journal/design.md`](../../event-journal/design.md), [`../../normalizer/design.md`](../../normalizer/design.md), [`../../canonical-model/design.md`](../../canonical-model/design.md) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Проектор проверяется через `EventProcessor` (журнал + нормализатор + identity + проекция), а не только `project` напрямую | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:144-147` | 🟢 |
| Идентичность в тестах — реальный PostgreSQL, а не in-memory | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:92-101` | 🟢 |
| Crosswalk выполняется строго до первого события любой из записей: иначе `CrosswalkConflictException` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SourceConflictIT.java:176-179`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:225` | 🟢 (ADR 0019) |
| Сравнение «до/после повтора» делается полным дампом графа (`dump`), а не выборочными проверками | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:171-181, 225-238` | 🟢 |
| Для ожидаемых исходов обработки допускается набор статусов при гонке (`PROJECTED`, `IGNORED_OLD_VERSION`, `DUPLICATE`) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:291-292` | 🟢 |

## Observabilidade

Нет логов и метрик; проверяются статус обработки, код ошибки и состояние хранилищ.

## Riscos e Lacunas

- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 3); ответ владельца: вопросы 14] Q-03 (транзиентные сбои внутри transaction functions), Q-09 (статус кандидата), Q-11 (`SUPERSEDED`, выход из `QUARANTINED`).
- 🟡 Q-15 (имитации), Q-16 (имена, обещающие больше), Q-27 (непокрытые пункты ADR 0031), Q-19 (upgrade-path миграций).
- 🟡 Явный `null` в payload и семантика удаления поля не закреплены: единственный тест неполной записи убирает поля, а не передаёт `null` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:303-315`).
- 🟡 `closeAssertionClosesOnlyTheOpenRelationAssertedByTheRecordAndIsIdempotent` проверяет один вызов (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphProjectorIT.java:613-629`).
