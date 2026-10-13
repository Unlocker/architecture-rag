# search-get-asset: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (безопасность, аудит, бюджет). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.4. Шаблоны: `access/graph-query-core`.

## Interface

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `SearchAssetsTool.searchAssets` | `(String query, List<String> types, String environment, Integer limit) -> SearchAssetsResult` | `IllegalArgumentException`: пустой/длинный `query`, `limit` вне 1..50, неизвестная метка | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:39-93 🟢 |
| `SearchAssetsTool.DEFAULT_LIMIT / MAX_LIMIT / MAX_QUERY_LENGTH` | `10 / 50 / 200` | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:21-23 🟢 |
| `SearchAssetsResult(items, truncated)` / `AssetHit` | record | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsResult.java:11-31 🟢 |
| `GetAssetTool.getAsset` | `(String gid, Boolean includeRelations) -> GetAssetResult` | `IllegalArgumentException`: `gid must be a UUID`, `asset not found` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:42-92 🟢 |
| `GetAssetResult` / `SourceRef` / `RelationRef` | record | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetResult.java:21-64 🟢 |
| `AssetTemplates.SEARCH_ASSETS`, `GET_ASSET`, `ASSET_RELATIONS` | `QueryTemplate` | ID `search_assets`, `get_asset`, `asset_relations`; `ResultKind.NODES` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:38-96, 112-141, 151-168 🟢 |
| `FulltextQuery.escape` | `(String) -> String` | экранированная нижним регистром строка или `null` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:11-28 🟢 |

## Fluxo Principal

### search_assets

1. `trimmed = query == null ? "" : query.strip()`; пусто → ошибка; длина > 200 → ошибка (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:63-69) 🟢.
2. `effectiveLimit = limit ?? 10`; вне 1..50 → ошибка (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:70-73) 🟢.
3. `typeFilter = validTypes(types)`: `null`/пусто → `null`; иначе каждая метка обязана быть в `AssetTemplates.SEARCHABLE_TYPES`, копия неизменяемого списка (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:74, 95-107) 🟢.
4. `env = environment` с `strip`, пустая строка → `null` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:75) 🟢.
5. Параметры шаблона: `query=trimmed`, `text=FulltextQuery.escape(trimmed)`, `types`, `environment` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:78-82) 🟢.
6. Бюджет: `ResultBudget(limits.maxDepth, min(effectiveLimit, limits.maxNodes), limits.maxPaths, limits.timeout, limits.maxResponseBytes)` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:84-90) 🟢.
7. `queries.execute("search_assets", params, budget)` (шаблон берёт `LIMIT $limit`, где `$limit = maxRows + 1`; лишняя строка включает `truncated`) (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:60-96) 🟢.
8. Каждая строка маппится в `AssetHit` (`gid, type, name, score, matchType, sources, lastSeenAt`); `truncated` берётся из результата (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:91-92, 109-119) 🟢.

### get_asset

1. `normalizeGid`: `UUID.fromString(strip)`; ошибка парсинга → `gid must be a UUID` (исходное исключение не пробрасывается, ввод не эхоится) (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:94-101) 🟢.
2. Бюджет — потолки конфигурации целиком (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:60-66) 🟢.
3. `queries.execute("get_asset", {gid}, budget)`; пустые строки → `asset not found` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:68-71) 🟢.
4. Если `includeRelations` не `false`: `queries.execute("asset_relations", {gid}, budget)`; `relations` = маппинг строк, `truncated` = признак второго запроса (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:74-80) 🟢.
5. `properties(row)`: копия карты свойств без `SERVICE_KEYS`, значения проходят `jsonValue` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:103-114) 🟢; `sources(row)` — список `SourceRef` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:127-142) 🟢.

## Fluxos Alternativos

- **`ZonedDateTime`/`OffsetDateTime` в свойствах** → `Instant.toString()` (ISO-8601 UTC); прочие `TemporalAccessor` → `toString()`; списки рекурсивно; `null` и остальное как есть (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:116-125) 🟢.
- **Таймаут** шаблона → `QueryTimeoutException`, аудит `TIMEOUT` (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:126-131).
- **`limit = null`** → 10; **`types = []`** → без фильтра (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:70, 96-98) 🟢.

## Dependências

- `GraphQueries`, `QueryLimits`, `ResultBudget` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:3-8); шаблоны регистрируются бинами access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:51-64.
- Индекс `asset_text` (FULLTEXT) создаёт схема Neo4j в `graph-projector` (вне модуля): [`../../graph-projector/`](../../graph-projector/) 🟡.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Валидация аргументов до обращения к графу | access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsToolTest.java:82-100 | 🟢 |
| Метки и типы связей из allowlist, не из ввода | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:16-22, 98-103 | 🟢 |
| В `get_asset` исходное исключение разбора UUID не пробрасывается (в сообщении было бы значение) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:97-99 | 🟢 |
| Закрытые активы отдаются карточкой, но не поиском | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:105-111; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:225-229 | 🟢 |
| `matchType=EXACT` включает совпадение по `ITSystem.eamCode` | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:26-28 | 🟡 |

## Estado Interno

Нет. Tools — stateless `@Component`; зависят от `GraphQueries` и `QueryLimits`.

## Observabilidade

Шаблоны попадают в аудит через `GraphQueries.execute`: для `get_asset` — `get_asset`, `asset_relations` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/GetAssetMcpTest.java:78-126).

## Riscos e Lacunas

- 🟡 Описание tool в `@McpTool` — единственное место, где агенту сообщается, что FULLTEXT только по `ITSystem`/`Service` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:47-50).
- 🟡 `AssetHit` продублирован в консоли (Н-7, Q-06).
- 🟡 Allowlist `SEARCHABLE_TYPES` и метки узлов в `canonical-model` синхронизируются вручную (матрица №9).
