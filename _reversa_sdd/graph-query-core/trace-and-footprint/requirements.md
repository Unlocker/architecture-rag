# trace-and-footprint: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Бюджет и глубина: [`../budget-limits/requirements.md`](../budget-limits/requirements.md).

## Visão Geral

Шаблоны обхода и обзора графа: `trace_downstream` / `trace_upstream` (пути зависимостей), `trace_impact` (кто пострадает), `runtime_footprint` (где развёрнута система) и консольные `graph_stats_*`, `graph_last_update`, `console_neighborhood_*`, `console_latest_sync_runs`. Все читают только действующие факты: открытые связи (`validTo IS NULL`) и текущие узлы (`isCurrent`).

## Regras de Negócio

- `trace_downstream`: пути `(s {gid})-[*1..{maxDepth}]->(t)` по направлению стрелок; `trace_upstream` — `<-[*1..{maxDepth}]-`; стартовая метка из `TRACE_LABELS`; `ResultKind.PATHS` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:82-95`).
- Фильтр пути: все связи имеют тип из `$relationTypes` и `validTo IS NULL`; все узлы `isCurrent = true` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:29-33`).
- Проекция строки-пути: `nodes[]` (`gid, label, name, lastSeenAt, conflicts[]`) и `relations[]` (`type, from, to, validFrom, assertedBySource/Type/Id, sourceFetchedAt, sourceActive`) в порядке обхода; свежесть берётся из `SourceRecord` по ключу `(source, sourceType, sourceId)` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:40-70`).
- Порядка строк нет: `ORDER BY` до `LIMIT` отсутствует, сортировка — на вызывающем; состав путей при усечении по `LIMIT` может меняться между вызовами 🟡 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:35-38, 79-80`; Q-04; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplatesTest.java:57`).
- Циклы не делают обход бесконечным: Cypher не повторяет связь внутри пути 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:79`).
- `trace_impact`: пути против стрелок, оканчивающиеся на `Service|ITSystem`; при непустом `$environment` каждый `Deployment` на пути обязан иметь открытую связь `IN_ENVIRONMENT` на `Environment` с этим `code`; путь без `Deployment` фильтр проходит 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:104-123`).
- Allowlist'ы типов связей: trace — `TRACE_RELATION_TYPES` (7), impact — `IMPACT_RELATION_TYPES` (5, подмножество trace) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:18-19, 98-99`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplatesTest.java:81`). Проверка принадлежности значений — на вызывающем 🟡 (Q-02).
- `runtime_footprint`: одна строка на `(service, deployment, compute)`; система без текущей `ITSystem` с таким `gid` даёт ноль строк; сервис без подходящих deployments — строка с `null` в deployment; цели `RUNS_ON` ограничены `ComputeInstance` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:14-28, 33-41`).
- Темпоральность в footprint: `validTo IS NULL` у `DECOMPOSED_INTO`, `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `RUNS_ON`; `HOSTED_ON` не темпоральна и не фильтруется по `validTo` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:34-41`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplatesTest.java:49`).
- Источник кода развёртывания читается подзапросом `COLLECT ... LIMIT 1` (не `OPTIONAL MATCH`): при нескольких открытых `DEPLOYED_FROM` выбирается связь с наибольшим `validFrom`, при равенстве — репозиторий с меньшим `gid` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:61-67`).
- Консоль: `graph_stats_nodes` / `graph_stats_relations` считают по `UNION` одного запроса на метку/тип, нули присутствуют; `graph_last_update` — `max(SourceRecord.fetchedAt)` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:47-97`; тест `access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplatesTest.java:56`).
- Окрестность: `console_neighborhood_nodes` — центр (`distance 0`) и узлы до `{maxDepth}` по связям из `$relTypes`, закрытые связи и закрытые узлы отсекаются; `console_neighborhood_edges` — открытые связи между узлами из `$gids` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:105-152`).
- `console_latest_sync_runs`: последний `SyncRun` каждого адаптера из `$adapters` по `startedAt`; свойства, которые проектор не пишет, приходят `null` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:159-179`; Р-5).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Пути зависимостей в обе стороны с ограниченной глубиной и проекцией происхождения | Must | AC-01 |
| RF-02 | Impact-обход до `Service|ITSystem` с фильтром окружения | Must | AC-02 |
| RF-03 | Runtime footprint системы | Must | AC-03 |
| RF-04 | Сводка графа, окрестность, последние прогоны для консоли | Must | AC-04 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Глубина подставляется числом из `1..ceiling`, без `*` и `*1..` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:85, 93, 115`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:133-138` | 🟢 |
| Производительность | Neo4j останавливает перечисление путей на первом `LIMIT` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:35-38` | 🟡 |
| Производительность | Типы связей фильтруются после раскрытия пути; стоимость на плотном графе ограничивают `LIMIT` и таймаут | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:29-33` | 🟡 |
| Корректность | Усечение по `maxDepth` не отражается в `truncated` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:41-58` | 🔴 |

## Критерии приёмки

```gherkin
# AC-01 (DependencyTemplatesTest.templatesAreBoundedPathTemplates, directionsDiffer, templatesFilterClosedRelationsAndNotCurrentNodes, noTemplateOrdersBeforeLimit, projectionCarriesProvenanceFreshnessAndConflicts; TraceDependenciesIT.depthBoundsTheTraversal, dependencyCycleTerminatesAndDoesNotRepeatRelationInPath, closedRelationIsNotTraversed)
Дано trace_downstream и trace_upstream
Когда render(3, 6)
Тогда текст содержит [*1..3], ->/<- по направлению, фильтры r.validTo IS NULL и n.isCurrent = true, "WITH p LIMIT $limit" и нет ORDER BY

# AC-02 (DependencyTemplatesTest.impactTemplateGoesUpstreamToServicesAndSystemsFilteredByEnvironment, impactAllowlistIsExactAndSubsetOfTraceAllowlist; TraceDependenciesIT.environmentFilterCutsDeploymentsOfOtherEnvironments, pathWithoutDeploymentPassesEnvironmentFilter)
Дано trace_impact
Когда проверяется шаблон
Тогда обход <-[*1..{maxDepth}]-(t:Service|ITSystem), условие IN_ENVIRONMENT с e.validTo IS NULL, IMPACT_RELATION_TYPES без OWNED_BY и DEPLOYED_FROM

# AC-03 (RuntimeTemplatesTest.*; RuntimeFootprintIT.environmentFilterNarrowsToProdAndServiceWithoutDeploymentsIsKept, closedUnknownAndNonSystemGidsGiveNoRows)
Дано runtime_footprint {systemGid, environment: null}
Когда система существует, сервис без deployments
Тогда строка с service и null в deployment/compute
Дано systemGid несуществующей или закрытой системы
Когда runtime_footprint
Тогда ноль строк
Дано шаблон runtime_footprint
Когда проверяется текст
Тогда нет CREATE/MERGE/SET/DELETE/REMOVE/CALL, нет "*" (нет variable-length), нет Namespace, нет "ho.validTo"

# AC-04 (ConsoleTemplatesTest.*; AdminConsoleIT.statsCountNodesByLabelRelationsByTypeAndLastUpdate, neighborhoodDepthOneAndTwoWithRelTypeFilter, neighborhoodOverNodeBudgetIsTruncated)
Дано ConsoleTemplates.ALL
Когда проверяется регистрация вместе с search_assets, get_asset, explain_provenance
Тогда ID не конфликтуют; нет записывающих операторов; NEIGHBORHOOD_RELATION_TYPES содержит DEPENDS_ON, HOSTED_ON, DEPLOYED_FROM, не содержит ASSERTS и OWNS_RECORD
Дано консоль с малым потолком maxNodes (tinyConsole) и окрестность глубины 2 больше потолка
Когда console_neighborhood_nodes
Тогда truncated=true и узлов не больше потолка (3)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-03 | Must | Tools `trace_dependencies`, `find_runtime_footprint`; ADR 0012 |
| RF-04 | Must | API консоли (ADR 0026) |
| Детерминированный порядок путей в ядре | Won't | Сортировка на вызывающем (Q-04) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java` | `DependencyTemplates` | 🟢 |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java` | `RuntimeTemplates` | 🟢 |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java` | `ConsoleTemplates` | 🟢 |
