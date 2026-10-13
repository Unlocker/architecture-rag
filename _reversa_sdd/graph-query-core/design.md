# access/graph-query-core: дизайн

> Ссылки даны полными путями от корня репозитория. Требования: [`requirements.md`](requirements.md). Контракты: [`contracts.md`](contracts.md).
> Входные артефакты: [`../flowcharts/graph-query-core.md`](../flowcharts/graph-query-core.md) (блок-схемы), [`../data-dictionary.md`](../data-dictionary.md) §X.1 (типы и результат шаблонов), [`../code-analysis.md`](../code-analysis.md) §X1.
> Use case-спецификации: [`execute-template/`](execute-template/design.md), [`budget-limits/`](budget-limits/design.md), [`template-registry/`](template-registry/design.md), [`asset-search/`](asset-search/design.md), [`trace-and-footprint/`](trace-and-footprint/design.md).

## Интерфейс

Пакеты `io.github.unlocker.archrag.graphquerycore` и `...graphquerycore.templates`. Публичные символы:

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `GraphQueryExecutor(Driver, QueryTemplateRegistry)` | конструктор | в БД не ходит | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:45-48` 🟢 |
| `GraphQueryExecutor.execute` | `(String templateId, Map<String,Object> params, ResultBudget budget) -> QueryResult` | `IllegalArgumentException` (шаблон/параметр), `IllegalStateException` (не read-only, узел/связь/путь), `QueryTimeoutException`, прочие `Neo4jException` как есть | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:60-97` 🟢 |
| `GraphQueryExecutor.READ_SESSION_CONFIG` | `SessionConfig` | `AccessMode.READ` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:35-36` 🟢 |
| `QueryTemplate` | `record(id, cypher, parameters, kind)` | валидация в компактном конструкторе | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:21-58` 🟢 |
| `QueryTemplate.render` | `(int depth, int ceiling) -> String` | `IllegalArgumentException` при `depth ∉ 1..ceiling` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:133-138` 🟢 |
| `QueryTemplate.maxLiteralDepth` / `hasDepthPlaceholder` | `() -> int` / `() -> boolean` | | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:108-123` 🟢 |
| `QueryTemplateRegistry.of` | `(Collection<QueryTemplate>, QueryLimits) -> QueryTemplateRegistry` | `IllegalArgumentException` (дубликат, глубина выше потолка) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:32-45` 🟢 |
| `QueryTemplateRegistry.find` / `ids` / `limits` | `Optional<QueryTemplate>` / `Set<String>` / `QueryLimits` | | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:48-60` 🟢 |
| `ResultBudget` | `record(maxDepth, maxNodes, maxPaths, timeout, maxResponseBytes)` | `IllegalArgumentException` при непозитивных значениях | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java:17-27` 🟢 |
| `ResultBudget.clampTo` | `(QueryLimits) -> ResultBudget` | каждое поле не больше потолка | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java:30-37` 🟢 |
| `QueryLimits.asBudget` | `() -> ResultBudget` | потолки как бюджет | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java:22-25` 🟢 |
| `BudgetedCollector.collect` | `(Iterator<Map<String,Object>>) -> Collected(rows, truncated)` | `IllegalStateException` при невозможности сериализовать строку | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:41-66` 🟢 |
| `QueryResult` | `record(templateId, rows, truncated, rowCount, elapsed)` | `rows` копируется в неизменяемый список | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryResult.java:19-28` 🟢 |
| `ResultKind.maxRows` | `(ResultBudget) -> int` | `NODES` → `maxNodes`, `PATHS` → `maxPaths` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultKind.java:11-13` 🟢 |
| `QueryTimeoutException.templateId` | `() -> String` | | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTimeoutException.java:4-17` 🟢 |
| `FulltextQuery.escape` | `static (String) -> String` | `null`, если после очистки нет букв и цифр | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:25-40` 🟢 |

Публичные константы шаблонов и allowlist'ов — каталог в [`contracts.md`](contracts.md) §1–§2.

## Поток исполнения `execute`

1. Поиск шаблона; нет → `IllegalArgumentException("Unknown template: <id>")` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:61-64`) 🟢.
2. Проверка ключей `params` в обе стороны (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:65, 133-146`) 🟢.
3. `effective = (budget ?? limits.asBudget()).clampTo(limits)`; `cypher = template.render(effective.maxDepth(), limits.maxDepth())`; `maxRows = kind.maxRows(effective)` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:66-69`) 🟢.
4. `queryParams = params + {limit: maxRows+1}`, `TransactionConfig.withTimeout(effective.timeout())`, `BudgetedCollector(maxRows, effective.maxResponseBytes())` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:71-74`) 🟢.
5. `verifyReadOnly`: при первом запуске шаблона — `EXPLAIN <cypher>` с `null` во всех параметрах и `limit=1` в сессии `READ`; тип запроса `READ_ONLY` → вердикт `true`, иначе `false`; вердикт хранится в `ConcurrentHashMap` по ID (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:99-124`) 🟢.
6. `session(READ).executeRead(tx -> collector.collect(new RowIterator(tx.run(cypher, queryParams))), txConfig)`; `RowIterator.next` превращает запись в `LinkedHashMap` и проверяет значения на `Node`/`Relationship`/`Path` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:78-87, 149-188`) 🟢.
7. `QueryResult(templateId, rows, truncated, rows.size(), elapsed)`; `Neo4jException` → `translate` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:88-96`) 🟢.

## Сборка результата (`BudgetedCollector`)

Цикл `while (read <= maxRows && source.hasNext())`; на `read > maxRows` возвращается `Collected(rows, true)` без добавления лишней строки; иначе `bytes += размер JSON строки (Jackson 3)`; при `bytes > maxBytes` — `truncated=true`, строка не добавляется (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:41-58`) 🟢. Размер считается сериализацией `JsonMapper.writeValueAsBytes` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:27, 60-66`) 🟢.

## Валидация шаблона

См. [`template-registry/design.md`](template-registry/design.md). Сводка: плейсхолдер `{...}` допустим только `{maxDepth}` и не более одного; текст заканчивается на `LIMIT $limit`; множество `$param` (без `limit`) равно `parameters`; каждый `[..*..]` имеет верхнюю границу; число использований `{maxDepth}` как границы равно числу плейсхолдеров (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:60-105`) 🟢.

## Каталог шаблонов (14)

| ID | Класс | Параметры | `ResultKind` | Трассировка |
|----|-------|-----------|--------------|-------------|
| `search_assets` | `AssetTemplates` | `query, text, types, environment` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:37-96` 🟢 |
| `get_asset` | `AssetTemplates` | `gid` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:112-141` 🟢 |
| `asset_relations` | `AssetTemplates` | `gid` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:150-168` 🟢 |
| `explain_provenance` | `AssetTemplates` | `gid` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:180-210` 🟢 |
| `trace_downstream` | `DependencyTemplates` | `gid, relationTypes` | PATHS | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:82-87` 🟢 |
| `trace_upstream` | `DependencyTemplates` | `gid, relationTypes` | PATHS | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:90-95` 🟢 |
| `trace_impact` | `DependencyTemplates` | `gid, relationTypes, environment` | PATHS | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:112-123` 🟢 |
| `runtime_footprint` | `RuntimeTemplates` | `systemGid, environment` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:29-90` 🟢 |
| `graph_stats_nodes` | `ConsoleTemplates` | — | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:47-63` 🟢 |
| `graph_stats_relations` | `ConsoleTemplates` | — | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:66-82` 🟢 |
| `graph_last_update` | `ConsoleTemplates` | — | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:88-97` 🟢 |
| `console_neighborhood_nodes` | `ConsoleTemplates` | `gid, relTypes` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:105-133` 🟢 |
| `console_neighborhood_edges` | `ConsoleTemplates` | `gids, relTypes` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:139-152` 🟢 |
| `console_latest_sync_runs` | `ConsoleTemplates` | `adapters` | NODES | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:159-179` 🟢 |

Реестр собирают приложения: консоль регистрирует `ConsoleTemplates.ALL` + `search_assets`, `get_asset`, `explain_provenance` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReaderConfiguration.java:44-52`) 🟢. Состав реестра MCP — в спецификации `mcp-server`.

## Зависит от / Связанные спецификации

| Что | Связь | Трассировка |
|-----|-------|-------------|
| `contracts/canonical-model` | зависимость модуля (метки и свойства графа, на которые опираются шаблоны) 🟡 | `access/graph-query-core/pom.xml:17-31` |
| Neo4j Java Driver | `Driver`, `executeRead`, `EXPLAIN` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:11-21` 🟢 |
| `access/admin-console` | потребитель: [`../admin-console/design.md`](../admin-console/design.md) | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReads.java:36-45` 🟢 |
| `ingestion/graph-projector` (`Neo4jSchema`) | схема графа (имя индекса `asset_text`, свойства `isCurrent`, `validTo`, `assertedBy*`), которую читают шаблоны; общего контракта-проверки нет | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:52`; Q-08 🟡 |
| `access/mcp-server` | потребитель: tools `search_assets`, `get_asset`, `explain_provenance`, `find_runtime_footprint`, `trace_dependencies`; состав реестра MCP — в спецификации `mcp-server` (в этом наборе не описана) | `access/graph-query-core/pom.xml:17-31` (модуль не зависит от потребителей) 🟡 |

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Три слоя защиты чтения: статическая валидация шаблона, `EXPLAIN READ_ONLY`, сессия `AccessMode.READ` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:26-30, 99-124` | 🟢 |
| Лимит строк задаёт исполнитель (`maxRows+1`), вызывающий `limit` передать не может | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:24-25, 53-56`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:71-72` | 🟢 |
| Результат — только проекции (скаляры, map, списки) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:172-188` | 🟢 |
| Трассировки: два шаблона (направление не параметризуется), без `ORDER BY` до `LIMIT`, сортировка на стороне вызывающего | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:11-13, 35-38, 79-80` | 🟢 |
| Свежесть шага пути берётся из `SourceRecord` по проиндексированному ключу `(source, sourceType, sourceId)` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:36-38, 46` | 🟡 |
| Единственная конкатенация — глубина `{maxDepth}`; значение — проверенный `int` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:125-138` | 🟢 |

## Estado Interno

- `GraphQueryExecutor.readOnlyVerdicts` — `ConcurrentHashMap<String,Boolean>`; живёт до перезапуска процесса, сбросить нельзя (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:42-43`) 🟢.
- `QueryTemplateRegistry.templates` — `Map.copyOf`, неизменяем после `of` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:23`) 🟢.
- Остального состояния нет: сессия создаётся на вызов и закрывается try-with-resources (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:78, 115`) 🟢.

## Riscos e Lacunas

- 🔴 Членство значений `types`/`relationTypes`/`relTypes` в allowlist ядро не проверяет (Q-02).
- 🔴 `EXPLAIN` как слой защиты расходится с таблицей вариантов ADR 0005 (Q-01).
- 🔴 `truncated` не отражает обрезку глубиной (Q-03).
- 🟡 Исполнитель в самом модуле не тестируется unit-тестами (Q-07).
- 🟡 Статический разбор регулярными выражениями не ловит экзотические формы (комментарии/переносы внутри скобок) (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:34-36`).
