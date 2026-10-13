# trace-dependencies: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.4. Блок-схема: [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md) («`trace_dependencies`»).

## Interface

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `TraceDependenciesTool.traceDependencies` | `(String gid, String direction, Integer maxDepth, List<String> relationTypes, String mode, String environment) -> TraceDependenciesResult` | `IllegalArgumentException` (тексты — [`../contracts.md`](../contracts.md) §5) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:82-158 🟢 |
| `TraceDependenciesTool(GraphQueries, QueryLimits, StalenessProperties, Clock)` | конструктор | `Clock` — бин `systemUTC`, в тестах фиксированный | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:62-67; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:101-104 🟢 |
| `TraceDependenciesResult` и вложенные | record | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesResult.java:25-94 🟢 |
| `DependencyTemplates.TRACE_DOWNSTREAM / TRACE_UPSTREAM / IMPACT_UPSTREAM` | `QueryTemplate` (`ResultKind.PATHS`) | ID `trace_downstream`, `trace_upstream`, `trace_impact`; параметры `gid, relationTypes[, environment]` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:83-87, 91-95, 113-123 🟢 |
| `DependencyTemplates.TRACE_RELATION_TYPES / IMPACT_RELATION_TYPES / IMPACT_TARGET_LABELS` | `List<String>` | allowlist'ы | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:17-19, 97-102 🟢 |
| `QueryTemplate.render` | `(int depth, int ceiling) -> String` | подставляет `{maxDepth}`; `IllegalArgumentException` вне `1..ceiling` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:125-138 🟢 |

## Fluxo Principal

1. `normalizeGid`; `mode` → `trace`/`impact`; `direction`; `environment`; `depth`; `types` — все проверки до обращения к графу (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:111-138) 🟢.
2. Выбор шаблона по режиму и направлению (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:140-143) 🟢.
3. Параметры: `gid`, `relationTypes`, для `impact` ещё `environment` (может быть `null`) (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:144-149) 🟢.
4. Бюджет: `ResultBudget(depth, limits.maxNodes, limits.maxPaths, limits.timeout, limits.maxResponseBytes)`; `GraphQueryExecutor` зажимает его потолками и вызывает `template.render(effective.maxDepth, limits.maxDepth)` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:150-152; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:67-68) 🟢.
5. Запрос выполняется `executeRead` с `LIMIT $limit = maxPaths + 1` (для PATHS строки ограничены `maxPaths`); перебор строк останавливается по бюджету и ставит `truncated` (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultKind.java:11-13; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:69-76) 🟢.
6. Пустой результат → проверка существования `get_asset`: нет строк → `asset not found`; иначе пустой ответ (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:154-163) 🟢.
7. `assemble`: порог `now − staleAfter`; сортировка строк `PATH_ORDER`; для каждого пути — узлы (`computeIfAbsent` по `gid`, `NodeRef` со `stale` и `conflicts`), связи (по ключу `(type, from, to)`, `RelationRef` со `stale = !active || isStale(fetchedAt)`), `TracePath(nodeGids, relationKeys)`; в `impact` последний `gid` пути добавляется в `affected` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:192-239) 🟢.
8. Ответ: `startGid, mode, direction, environment, staleAfter (ISO-8601, например PT168H), nodes, relations, paths, affected, truncated` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:236-238) 🟢.

## Fluxos Alternativos

- **Узел без связей** — пустые `paths`, ошибки нет (только если `get_asset` находит узел) (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:154-156; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesToolTest.java:127-137) 🟢.
- **Таймаут Neo4j** — `QueryTimeoutException`, аудит `TIMEOUT` (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:126-131) 🟢.
- **Усечение** — `truncated=true` из `QueryResult`; состав путей недетерминирован, так как порядок в Cypher не задан (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:72-76) 🟡.
- **`maxDepth` = null** — глубина = `limits.maxDepth` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:134) 🟢.

## Dependências

- `GraphQueries`, `QueryLimits`, `StalenessProperties`, `Clock` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:57-67).
- `AssetTemplates.GET_ASSET` для проверки существования (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:162).
- Темпоральные связи (`validFrom`/`validTo`) — ADR [0028](../../../docs/adr/0028-structural-relations-temporal.md), пишет `graph-projector`.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| `analyze_impact` = режим `impact` одного tool | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:27; docs/adr/0012-trace-dependencies-impact-mode.md:27 | 🟢 |
| Глубина отклоняется, а не зажимается | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:134-137 | 🟢 |
| Сортировка путей в Java (в Cypher сортировка не даёт остановиться на лимите) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:37-50, 72-76 | 🟢 |
| Свежесть шага берётся из `SourceRecord` по проиндексированному ключу `(source, sourceType, sourceId)` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:35-40 | 🟢 |
| Конфликты узла лежат на рёбрах `ASSERTS` и отдаются без значений | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:35-40; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesResult.java:45, 49-57 | 🟢 |

## Estado Interno

Нет. `PATH_ORDER` — статический компаратор (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:37-50).

## Observabilidade

Аудит перечисляет ID шаблона (`trace_downstream`/`trace_upstream`/`trace_impact`) и, при пустом результате, `get_asset` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:152, 162).

## Riscos e Lacunas

- 🟡 Недетерминизм состава путей при `truncated=true` (документирован в описании tool).
- 🟡 Связь, утверждённая записью без узла, не несёт `sourceFetchedAt` и всегда `stale` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:98-100).
- 🟡 Allowlist связей и метки продублированы в шаблонах и `canonical-model` вручную (матрица №4, №9).
