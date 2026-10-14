# query-core-read: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Требования: [`requirements.md`](requirements.md). Контракты (лимиты): [`../contracts.md`](../contracts.md) §6.

## Interface

| Символ | Сигнатура | Назначение | Трассировка |
|--------|-----------|------------|-------------|
| `GraphQueryCoreIT.connect` | `@BeforeAll static void` | админ-драйвер, `CREATE USER mcp-reader` (пароль — константа теста), драйвер читателя; схема **не** применяется | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:48-55` 🟢 |
| `GraphQueryCoreIT.template(id, cypher, params...)` | `-> QueryTemplate` | шаблон `ResultKind.NODES` из произвольного Cypher | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:62-64` 🟢 |
| `GraphQueryCoreIT.executor(templates...)` | `-> GraphQueryExecutor` | исполнитель над драйвером читателя и `QueryTemplateRegistry.of(templates, LIMITS)` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:66-68` 🟢 |
| `GraphQueryCoreIT.budget(maxDepth, maxNodes, timeout, bytes)` | `-> ResultBudget` | бюджет с `maxPaths = 50` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:74-76` 🟢 |
| `GetAssetIT.connect` / `seed` / `run(template, gid, maxNodes)` | | `Neo4jSchema.apply`, пользователь читателя, реестр `GET_ASSET` + `ASSET_RELATIONS`; засев через `AssetFixtures`; вызов с бюджетом `(1, maxNodes, 1, …)` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:59-102` 🟢 |
| `SearchAssetsIT.seed` / `search(q, types, env, limit)` / `gids(result)` | | засев и вызов `search_assets`; `FulltextQuery.escape` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:65-137, 238-243` 🟢 |
| `TraceDependenciesIT.seed` / `trace(template, gid, depth, maxPaths, relationTypes)` / `endGids(result)` | | засев, цикл `billing ↔ auth`, `HOSTED_ON` и `PhysicalServer` Cypher-ом, ручной `fetchedAt`; вызов шаблонов `TRACE_DOWNSTREAM`, `TRACE_UPSTREAM`, `IMPACT_UPSTREAM` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:62-133` 🟢 |
| `DeploymentCommitReadIT`, `RuntimeFootprintIT` | | `@BeforeAll`/`@BeforeEach` с засевом; `warmLimits` с таймаутом 120 с для прогрева | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeploymentCommitReadIT.java:50-112`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:36-106` 🟢 |
| `ExplainProvenanceIT` | | `AnnotationConfigApplicationContext` с `GraphQueries` и `ExplainProvenanceTool`, executor над `AssetTemplates.EXPLAIN_PROVENANCE`; факты через `EventProcessor` и `Reconciler` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java:97-138` 🟢 |

## Fluxo Principal

1. `@BeforeAll`: Neo4j-контейнер; админ-драйвер (`neo4j`), `CREATE USER mcp-reader` в БД `system`; для классов, использующих проектор — `Neo4jSchema.apply`; драйвер читателя (`mcp-reader`) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:59-71`).
2. `@BeforeEach seed()`: `MATCH (n) DETACH DELETE n`, затем `AssetFixtures`: `node(gid, sourceType, sourceId, data, at)`, `nodeFrom(...)`, `tombstone(...)`, `relation(type, from, fromGid, to, toGid, validity, at)`. Мастер узла — первый из `AuthorityMatrix.nodeAuthorities(label)`; исход проекции обязан быть `APPLIED` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:73-97`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AssetFixtures.java:42-94`).
3. Запрос: `executor.execute(templateId, params, ResultBudget)` → `QueryResult(rows, rowCount, truncated, templateId)`; строки — `Map<String,Object>` (проекции `n {.gid}`, `nodes`, `relations`, `properties`, `sources`).
4. Проверки строк и флагов `truncated`; отказы — исключения (`IllegalArgumentException`, `IllegalStateException`, `QueryTimeoutException`).

Закрытый узел в `GetAssetIT`: единственная мастер-запись `tombstone` (версия `2`), узел остаётся с `isCurrent = false` и неактивной записью, карточка его возвращает (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:87-89, 123-135`) 🟢. Закрытая связь: `Validity(from, to)` в прошлом, `validTo` не `null` → связь исключается (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:95-96, 158-167`) 🟢.

Плотный граф `TraceDependenciesIT.seedDenseGraph` (30 × 30 `DEPENDS_ON`): вставка в обход `GraphProjector` и constraint-схемы, gid-строки не UUID (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:358-393`) 🟡.

## Fluxos Alternativos

- **Узел вне поисковых типов** (`Namespace`) не находится ни по `sourceId`, ни по `gid` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:246-254`) 🟢.
- **Ввод без текстовых символов** (`()`): `FulltextQuery.escape` возвращает `null`, fulltext-ветка не вызывает процедуру, точные ветки работают (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:238-244`) 🟢.
- **Таймаут**: тяжёлый шаблон (`UNWIND range(1, 100000000)…`) с таймаутом 100 мс → `QueryTimeoutException`; время ожидания не более 20 с (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:200-211`) 🟢.
- **Холодный вызов footprint**: `RuntimeFootprintIT.coldFirstCallFitsProductionTimeout` не гарантирует холодность (порядок методов не зафиксирован) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:267-277`) 🟡.
- **`ExplainProvenanceIT.tombstonedSourceIsInactiveAndConflictIsCleared`**: маркер snapshot добавляется в журнал и передаётся `reconciler.reconcile` напрямую (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java:237-267`) 🟡.

## Dependências

`access/graph-query-core` (`GraphQueryExecutor`, `QueryTemplateRegistry`, `QueryTemplate`, `ResultBudget`, `QueryLimits`, `QueryResult`, `QueryTimeoutException`, `AssetTemplates`, `DependencyTemplates`, `FulltextQuery`), `ingestion/graph-projector` (`GraphProjector`, `Neo4jSchema`), `contracts/canonical-model`, `access/mcp-server` (`ExplainProvenanceTool`, `GraphQueries`); для `ExplainProvenanceIT` — ещё `event-journal`, `identity-resolution`, `normalizer`. Спецификации: [`../../flowcharts/graph-query-core.md`](../../flowcharts/graph-query-core.md), [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md), [`../../graph-projector/design.md`](../../graph-projector/design.md) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Читатель под отдельным пользователем `mcp-reader`; read-only обеспечивают режим сессии и проверка EXPLAIN, а не роли БД | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:48-55, 111-120` | 🟢 (ADR 0005) |
| Фикстуры графа строятся настоящим проектором, чтобы формы `lastSeenAt`, `isCurrent`, `active`, `ASSERTS.authority` совпадали с продовыми | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AssetFixtures.java:28-30` | 🟢 |
| Таймаут запроса в IT настраивается `-Darchrag.it.queryTimeoutSeconds` (5 с по умолчанию) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:46` | 🟡 (флаг не документирован) |
| Прогрев холодного Neo4j таймаутом 120 с, затем продовый бюджет | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeploymentCommitReadIT.java:63` | 🟡 |

## Observabilidade

Нет. Исход — `QueryResult` и исключения; текст ошибок не содержит введённых значений (`asset not found`).

## Riscos e Lacunas

- 🟡 [R.8: решение принято, реализация — UNLOCKER-290; закрыто частично: роли admin/reader и отказы по audience/issuer/exp в `McpSecurityTest`/`AdminEndpointIT`; для консоли и приёмочных IT негативы не добавлены] Q-01/Q-12: scope и 401/403 здесь не участвуют (HTTP нет) — отказы проверяет `acceptance-mcp-f1-f4` и только для вызовов `tools/call`.
- 🟡 Q-31: `HOSTED_ON`, `PhysicalServer` и `fetchedAt` вставляются Cypher-ом.
- 🟡 Q-17: время плотного графа и холодного вызова не измеряется явно.
- 🟡 `GraphQueryCoreIT` и `RuntimeFootprintIT` без схемы; `getAsset` и поиск зависят от индекса `asset_text`, который создаёт `Neo4jSchema` (spec-impact § 2 № 4) 🟢 (`SearchAssetsIT` применяет схему и ждёт индексы: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:116-120`).
