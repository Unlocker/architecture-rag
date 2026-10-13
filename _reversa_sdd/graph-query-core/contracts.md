# access/graph-query-core: внешние контракты

> Модуль — библиотека: HTTP, MCP-tools, SQL и метрик у него нет. Контракты: публичный Java API, каталог шаблонов (Cypher-параметры и колонки ответа), allowlist'ы, потолки конфигурации, ошибки, зависимость от схемы Neo4j. Секреты не приводятся; драйвер приходит готовым от приложения.

## 1. Java API для приложений

Сигнатуры и инварианты — [`design.md`](design.md) §«Интерфейс». Потребители сегодня: `access/admin-console` (через `GraphReads`) и `access/mcp-server` (через `GraphQueries`) 🟡.

Ошибки `GraphQueryExecutor.execute` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:56-58, 126-131`) 🟢:

| Исключение | Условие | Сообщение содержит |
|------------|---------|--------------------|
| `IllegalArgumentException` | неизвестный ID; неизвестный/недостающий параметр | ID шаблона и имя параметра, значений нет |
| `IllegalStateException` | вердикт `EXPLAIN` ≠ `READ_ONLY`; в строке `Node`/`Relationship`/`Path` | ID шаблона / тип значения |
| `QueryTimeoutException` | код ошибки Neo4j содержит `TransactionTimedOut` | ID шаблона |
| `Neo4jException` | прочие сбои драйвера (в том числе сбой `EXPLAIN`-планирования) | как у драйвера |

## 2. Потолки бюджета (`QueryLimits`)

Модуль не читает конфигурацию: потолки создаёт приложение (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java:14-26`). Источник значений в консоли — `archrag.query.limits.*` (`access/admin-console/src/main/resources/application.yml:35-41`) 🟢.

| Поле | Смысл | Значение по умолчанию в консоли |
|------|-------|-------------------------------|
| `maxDepth` | потолок глубины обхода, подставляемой в `{maxDepth}` | 6 |
| `maxNodes` | потолок строк для `ResultKind.NODES` | 500 |
| `maxPaths` | потолок строк для `ResultKind.PATHS` | 50 |
| `timeout` | таймаут транзакции Neo4j | 5 с |
| `maxResponseBytes` | порог сериализованного размера всех строк | 512 КБ |

Для MCP значения те же (`docs/03-interfaces.md:94-99`) 🟡.

## 3. Allowlist'ы (публичные константы)

| Константа | Состав | Назначение | Трассировка |
|-----------|--------|------------|-------------|
| `AssetTemplates.SEARCHABLE_TYPES` | `ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance` | метки `search_assets`, `get_asset`, `asset_relations`, `explain_provenance`; параметр `types` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:16-17` 🟢 |
| `AssetTemplates.RELATION_TYPES` | `DECOMPOSED_INTO, IMPLEMENTED_IN, HAS_DEPLOYMENT, IN_ENVIRONMENT, RUNS_ON, DEPENDS_ON, OWNED_BY, DEPLOYED_FROM` | связи 1 уровня; служебные (`ASSERTS`, `OWNS_RECORD`, `PROCESSED`, `PART_OF`, `HOSTED_ON`) исключены | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:102-103` 🟢 |
| `DependencyTemplates.TRACE_LABELS` | 11 меток: `SEARCHABLE_TYPES` + `VirtualMachine, PhysicalServer, Namespace, KubernetesCluster` | старт и проекция обхода | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:22-25` 🟢 |
| `DependencyTemplates.TRACE_RELATION_TYPES` | `DEPENDS_ON, DECOMPOSED_INTO, HAS_DEPLOYMENT, RUNS_ON, HOSTED_ON, OWNED_BY, DEPLOYED_FROM` | параметр `relationTypes` trace | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:18-19` 🟢 |
| `DependencyTemplates.IMPACT_RELATION_TYPES` | `RUNS_ON, HOSTED_ON, HAS_DEPLOYMENT, DECOMPOSED_INTO, DEPENDS_ON` | `relationTypes` impact | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:98-99` 🟢 |
| `DependencyTemplates.IMPACT_TARGET_LABELS` | `Service, ITSystem` | конец impact-пути | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:102` 🟢 |
| `ConsoleTemplates.STATS_LABELS` | `TRACE_LABELS` + `SourceRecord` | счётчики узлов | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:19-25` 🟢 |
| `ConsoleTemplates.STATS_RELATION_TYPES` | `RELATION_TYPES` + `HOSTED_ON`, `ASSERTS` | счётчики связей | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:28-35` 🟢 |
| `ConsoleTemplates.NEIGHBORHOOD_RELATION_TYPES` | `RELATION_TYPES` + `HOSTED_ON` | параметр `relTypes` окрестности | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:38` 🟢 |
| `AssetTemplates.EXACT_SCORE` | `1000.0` | score точного совпадения (выше любого FULLTEXT) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:19-20` 🟢 |
| `RuntimeTemplates.RUNTIME_FOOTPRINT_ID` | `runtime_footprint` | ID шаблона | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:10-11` 🟢 |

Проверка членства значений от вызывающего в этих списках — на стороне вызывающего (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:11-13`; Q-02) 🟡.

## 4. Каталог шаблонов: параметры и колонки результата

Общее: каждый шаблон заканчивается на `LIMIT $limit`; `limit` задаёт исполнитель; результат — строки из скаляров, map и списков (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:71-72, 172-188`) 🟢. Время отдаётся `toString(...)` (ISO-8601) 🟢.

| ID | Параметры (`null` допустим) | Колонки результата | Трассировка |
|----|-----------------------------|--------------------|-------------|
| `search_assets` | `query` (trim-нутый ввод), `text` (экранированный Lucene или `null`), `types`, `environment` (оба `null` допустимы) | `gid, type, name, score, matchType (EXACT|FULLTEXT), sources[], lastSeenAt`; только `isCurrent=true`; сортировка `score DESC, gid` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:37-96` 🟢 |
| `get_asset` | `gid` | `gid, type, properties, firstSeenAt, lastSeenAt, deletedAt, isCurrent, sources[]` (`source, sourceType, sourceId, sourceVersion, fetchedAt, active, authority`); без фильтра `isCurrent` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:112-141` 🟢 |
| `asset_relations` | `gid` | `relationType, direction (OUT|IN), gid, type, name, isCurrent, validFrom`; только открытые связи (`validTo IS NULL`) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:150-168` 🟢 |
| `explain_provenance` | `gid` | `gid, type, isCurrent, deletedAt, records[]` (`source, sourceType, sourceId, sourceVersion, fetchedAt, contentHash, active, deletedAt, authority, confidence, conflicts`); значений свойств нет | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:180-210` 🟢 |
| `trace_downstream` / `trace_upstream` | `gid`, `relationTypes` | `nodes[]` (`gid, label, name, lastSeenAt, conflicts[]`), `relations[]` (`type, from, to, validFrom, assertedBySource/Type/Id, sourceFetchedAt, sourceActive`); `PATHS`; порядка строк нет | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:40-95` 🟢 |
| `trace_impact` | `gid`, `relationTypes`, `environment` | как у trace; конец пути `Service|ITSystem`, обход против стрелок | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:112-123` 🟢 |
| `runtime_footprint` | `systemGid`, `environment` | `system, service, deployment, compute, host` (map или `null`); строка на `(service, deployment, compute)`; сортировка `svc.gid, d.gid, c.gid` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:29-90` 🟢 |
| `graph_stats_nodes` | — | `label, count` (нули присутствуют) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:47-63` 🟢 |
| `graph_stats_relations` | — | `type, count` (нули присутствуют) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:66-82` 🟢 |
| `graph_last_update` | — | одна строка `lastUpdatedAt` (`max(SourceRecord.fetchedAt)` или `null`) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:88-97` 🟢 |
| `console_neighborhood_nodes` | `gid`, `relTypes` | `gid, type, name, isCurrent, distance`; центр с `distance=0`; обход `[*1..{maxDepth}]` в обе стороны | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:105-133` 🟢 |
| `console_neighborhood_edges` | `gids`, `relTypes` | `type, source, target` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:139-152` 🟢 |
| `console_latest_sync_runs` | `adapters` | `adapter, runId, startedAt, endedAt, status, fetched, applied, failed`; не записанные проектором свойства — `null` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:159-179` 🟢 |

## 5. Зависимость от схемы Neo4j (чтение)

Шаблоны полагаются на: метки из allowlist'ов; свойства узла `gid, name, hostname, url, code, eamCode, isCurrent, firstSeenAt, lastSeenAt, deletedAt`; связь `ASSERTS` (`authority, confidence, conflicts`) и узлы `SourceRecord` (`source, sourceType, sourceId, sourceVersion, fetchedAt, contentHash, active, deletedAt`); темпоральные свойства связей `validFrom, validTo, assertedBySource/Type/Id, commit`; полнотекстовый индекс `asset_text`; узлы `SyncRun` (`adapter, runId, startedAt, endedAt, status, fetched, applied, failed`) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:42-82`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:40-69`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:159-179`). Индекс `asset_text` создаётся `FOR (n:ITSystem|Service)` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:57-58`) 🟢: `FULLTEXT`-ветка `search_assets` находит только эти две метки (Q-09). Проверки согласованности схемы и шаблонов в модуле нет; её покрывают IT `GraphQueryCoreIT` и `SearchAssetsIT` 🟡 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:79-229`).

## 6. Конфигурация и метрики

Свойств Spring, переменных окружения и метрик в модуле нет 🟢 (`access/graph-query-core/pom.xml:14-32`). Аудит вызовов — на стороне приложения (`QueryResult.templateId`, `rowCount`, `truncated`, `elapsed` предназначены для него, `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryResult.java:13-17`) 🟢.
