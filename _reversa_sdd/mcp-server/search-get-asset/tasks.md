# search-get-asset: задачи

## Pré-requisitos
- [ ] Шаблоны `search_assets`, `get_asset`, `asset_relations` зарегистрированы бинами (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:51-64)
- [ ] В Neo4j есть FULLTEXT-индекс `asset_text` и constraints (создаёт ingestion при старте)

## Tarefas

- [ ] T-01: `SearchAssetsTool.searchAssets` с валидацией, бюджетом `min(limit, max-nodes)` и маппингом `AssetHit`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:21-119
  - Критерий готовности: AC-01..AC-04; `SearchAssetsToolTest` (4 теста)
  - Уверенность: 🟢
- [ ] T-02: `SearchAssetsResult` / `AssetHit`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsResult.java:11-31
  - Критерий готовности: поля `gid,type,name,score,matchType,sources,lastSeenAt`, `truncated`
  - Уверенность: 🟢
- [ ] T-03: `GetAssetTool.getAsset` (карточка + опциональные связи, нормализация gid, ISO-время)
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:24-153
  - Критерий готовности: AC-05..AC-07; `GetAssetToolTest` (6 тестов)
  - Уверенность: 🟢
- [ ] T-04: `GetAssetResult` / `SourceRef` / `RelationRef`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetResult.java:21-64
  - Критерий готовности: структура из [`../contracts.md`](../contracts.md) §1.4
  - Уверенность: 🟢
- [ ] T-05: Шаблоны `search_assets`, `get_asset`, `asset_relations` и `FulltextQuery.escape`
  - Origin: access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:38-168; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:11-28
  - Критерий готовности: `SearchAssetsIT`, `GetAssetIT` зелёные на Neo4j
  - Уверенность: 🟢
- [ ] T-06: Scope-запись для обоих tools
  - Origin: access/mcp-server/src/main/resources/application.yml:31-32
  - Критерий готовности: без `architecture.read` — `403` (AC-08)
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `SearchAssetsToolTest` — параметры, маппинг, ошибки, потолок `max-nodes` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsToolTest.java:41-111)
- [ ] TT-02: `GetAssetToolTest` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetToolTest.java:71-147)
- [ ] TT-03: `SearchAssetsMcpTest`, `GetAssetMcpTest` — сквозной вызов, аудит шаблонов, отказ без scope, ошибки tool (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/SearchAssetsMcpTest.java:76-128; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/GetAssetMcpTest.java:78-156)
- [ ] TT-04: `SearchAssetsIT`, `GetAssetIT`, `F1AcceptanceIT` (S1-S8) на Testcontainers (platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:139-253; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:106-175; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:242-360)

## Ordem Sugerida
1. T-05 → 2. T-02, T-04 → 3. T-01, T-03 → 4. T-06 → 5. тесты.

## Lacunas Pendentes (🔴 и 🟡)
- Q-06 (дубликаты MCP ↔ консоль), Q-04 (сверка списка tools) — [`../questions.md`](../questions.md).
