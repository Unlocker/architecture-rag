# access/mcp-server: внешние контракты

> MCP (tools и схемы), HTTP-ручки, конфигурация, метрики и аудит, коды отказов. Секреты не приводятся, только имена переменных. Все ссылки на код даны полными путями от корня репозитория.

## 1. MCP (Streamable HTTP, stateless)

### 1.1 Транспорт 🟢

| Параметр | Значение | Трассировка |
|----------|----------|-------------|
| Протокол | `spring.ai.mcp.server.protocol=STATELESS` (Streamable HTTP без сессий; все ответы `application/json`, заголовок `Mcp-Session-Id` не выдаётся) | `access/mcp-server/src/main/resources/application.yml:6-13`; `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpServerInteropTest.java:77-101` |
| Endpoint | `POST /mcp` (`spring.ai.mcp.server.streamable-http.mcp-endpoint`) | `access/mcp-server/src/main/resources/application.yml:12-13`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:40` |
| Имя / версия сервера | `arch-rag` / `0.1.0` | `access/mcp-server/src/main/resources/application.yml:9-10` |
| Legacy SSE (`GET /sse`) | не обслуживается, `404` | `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpServerInteropTest.java:103-116` |
| `GET /mcp` | `405` по документации; тестом не покрыто | `docs/03-interfaces.md:53` 🟡 |
| Порт | умолчание Spring (8080); `server.port` не задан; наружу — через nginx `location = /mcp` | `docs/03-interfaces.md:29`; `platform/docker/proxy/nginx.conf:18-26` |
| Версия MCP-протокола | не фиксируется кодом сервера, согласует MCP SDK Spring AI | `docs/03-interfaces.md:55` 🟡 |

Методы JSON-RPC: `initialize`, `tools/list`, `tools/call` (и прочие, предоставляемые SDK); scope проверяется только для `tools/call`. Ответ `tools/call`: `result.content[0].text` — JSON-строка `record`-результата; ошибка валидации/выполнения — `result.isError=true`, HTTP `200` 🟢 (`access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/SearchAssetsMcpTest.java:121-128`).

### 1.2 Матрица tool → scope → шаблон 🟢

| Tool | Scope (Spring Security authority) | Шаблон(ы) графа | Результат | Трассировка |
|------|-----------------------------------|-----------------|-----------|-------------|
| `ping` | `architecture.read` (`SCOPE_architecture.read`) | нет | `{"status":"ok"}` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/PingTool.java:11-22`; `access/mcp-server/src/main/resources/application.yml:29` |
| `search_assets` | `architecture.read` | `search_assets` | `SearchAssetsResult` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:39-93`; `access/mcp-server/src/main/resources/application.yml:31` |
| `get_asset` | `architecture.read` | `get_asset`, `asset_relations` | `GetAssetResult` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:42-92`; `access/mcp-server/src/main/resources/application.yml:32` |
| `explain_provenance` | `architecture.read` | `explain_provenance` | `ExplainProvenanceResult` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:41-88`; `access/mcp-server/src/main/resources/application.yml:33` |
| `trace_dependencies` | `architecture.read` | `trace_downstream` / `trace_upstream` / `trace_impact` (+ `get_asset` для проверки существования) | `TraceDependenciesResult` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:82-158`; `access/mcp-server/src/main/resources/application.yml:34` |
| `find_runtime_footprint` | `architecture.read` | `runtime_footprint` | `FindRuntimeFootprintResult` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:46-77`; `access/mcp-server/src/main/resources/application.yml:30` |

Все tools имеют аннотации `readOnlyHint=true`, `destructiveHint=false`, `idempotentHint=true`, `openWorldHint=false` (`access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:110-124`). ID шаблонов: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:39, 114, 152, 182`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:84, 92, 114`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:11` 🟢. Регистрация шаблонов бинами — `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:51-99` 🟢.

### 1.3 Входные схемы (аргументы `tools/call`)

Все параметры помечены `@McpToolParam`; `required` по умолчанию `true`. Типы — JSON Schema, выводятся SDK из сигнатуры (`inputSchema.type=object`, `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpServerInteropTest.java:51-58`) 🟢.

| Tool | Параметр | Тип | Обяз. | Ограничение / умолчание | Трассировка |
|------|----------|-----|-------|-------------------------|-------------|
| `ping` | — | — | — | аргументов нет | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/PingTool.java:20` |
| `search_assets` | `query` | string | да | `strip`, не пусто, ≤ 200 символов; `gid`, `sourceId` или текст | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:52, 63-69` |
| | `types` | string[] | нет | подмножество `ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance`; пусто = без фильтра | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:53-58, 95-107` |
| | `environment` | string | нет | `Environment.code`; пусто/пробелы = без фильтра | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:59, 75` |
| | `limit` | integer | нет | 1..50, умолчание 10; дополнительно `min(limit, max-nodes)` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:21-22, 60-62, 70-73, 87` |
| `get_asset` | `gid` | string | да | UUID (`strip`, нормализуется `UUID.toString()`) | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:55, 94-101` |
| | `includeRelations` | boolean | нет | умолчание `true` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:56-57, 76` |
| `explain_provenance` | `gid` | string | да | UUID | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:56, 90-97` |
| | `property` | string | нет | `^[A-Za-z][A-Za-z0-9_]{0,63}$`; пусто = без фильтра | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:24, 57-58, 99-108` |
| `trace_dependencies` | `gid` | string | да | UUID | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:102, 165-172` |
| | `direction` | string | нет | `upstream`/`downstream` (регистр не важен), умолчание `downstream`; для `impact` только `upstream` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:103-104, 117-128` |
| | `maxDepth` | integer | нет | 1..`max-depth` (6), умолчание = лимит | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:105-106, 134-137` |
| | `relationTypes` | string[] | нет | из allowlist режима; пусто = весь allowlist | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:107-108, 138, 174-185` |
| | `mode` | string | нет | `trace` (умолчание) или `impact`, регистр не важен | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:109, 112-116` |
| | `environment` | string | нет | только `impact`; ≤ 128 символов; пусто = без фильтра | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:35, 110, 129-133` |
| `find_runtime_footprint` | `systemGid` | string | да | UUID (не нормализуется; передаётся как есть) | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:59, 62-70` |
| | `environment` | string | нет | `strip`; пусто = все окружения | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:60-61, 67` |

Allowlist связей: `trace` — `DEPENDS_ON, DECOMPOSED_INTO, HAS_DEPLOYMENT, RUNS_ON, HOSTED_ON, OWNED_BY, DEPLOYED_FROM`; `impact` — `RUNS_ON, HOSTED_ON, HAS_DEPLOYMENT, DECOMPOSED_INTO, DEPENDS_ON` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:17-19, 97-99`) 🟢.

### 1.4 Выходные схемы (структуры `record`)

| Tool | Корневые поля | Вложенные типы | Трассировка |
|------|---------------|----------------|-------------|
| `search_assets` | `items[]`, `truncated` | `AssetHit{gid,type,name,score,matchType(EXACT|FULLTEXT),sources[],lastSeenAt}` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsResult.java:11-31` |
| `get_asset` | `gid,type,properties{},firstSeenAt,lastSeenAt,deletedAt,isCurrent,sources[],relations[],truncated` | `SourceRef{source,sourceType,sourceId,sourceVersion,fetchedAt,active,authority(MASTER|SUPPLEMENTARY)}`, `RelationRef{relationType,direction(OUT|IN),gid,type,name,isCurrent,validFrom}` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetResult.java:21-64` |
| `explain_provenance` | `gid,type,property,isCurrent,deletedAt,authorities[],conflictState(OPEN|NONE),records[]` | `RecordRef{source,sourceType,sourceId,sourceVersion,fetchedAt,contentHash,confidence,authority,authoritative,active,deletedAt,conflictProperties[]}` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java:19-51` |
| `trace_dependencies` | `startGid,mode,direction,environment,staleAfter,nodes[],relations[],paths[],affected[],truncated` | `NodeRef{gid,label,name,lastSeenAt,stale,conflicts[]}`, `Conflict{source,sourceType,sourceId,properties[]}`, `RelationRef{type,from,to,validFrom,assertedBySource,assertedByType,assertedById,sourceFetchedAt,sourceActive,stale}`, `TracePath{nodes[],relations[]}`, `RelationKey{type,from,to}` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesResult.java:25-94` |
| `find_runtime_footprint` | `systemGid,systemName,systemLastSeenAt,systemStale,systemSources[],environment,services[],staleAfter,warnings[],truncated` | `ServiceFootprint`, `DeploymentFootprint{...,sourceCommit,repositoryUrl,repository,deployedFrom,compute[]}`, `ComputeFootprint`, `HostFootprint`, `EdgeProvenance`, `RepositoryRef`, `DeployedFromRef`, `FactSource` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintResult.java:24-98` |

Времена — ISO-8601 UTC (строки). Значения свойств активов в `explain_provenance` не возвращаются (только имена спорных свойств) (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceResult.java:5-7`) 🟢.

## 2. HTTP

| Метод и путь | Аутентификация | Ответы | Трассировка |
|--------------|----------------|--------|-------------|
| `POST /mcp` | JWT (issuer + audience); scope проверяется на `tools/call` | `200` JSON-RPC; `400` неподдерживаемая кодировка / невалидный JSON / не объект / batch; `401` нет или неверный токен; `403` нет scope или неизвестный tool; `413` тело > `max-request-bytes` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:56-98`; `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:59-65, 126-199` |
| `GET /.well-known/oauth-protected-resource` (и `/**`) | нет (публично) | `200` JSON RFC 9728: `resource`, `authorization_servers`, `scopes_supported`, `bearer_methods_supported=["header"]`, `resource_name=arch-rag` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:43-45, 48-60`; `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:201-218` |
| `GET /actuator/health/**` (liveness, readiness) | нет (публично) | `200 {"status":"UP"}` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:43-45`; `access/mcp-server/src/main/resources/application.yml:73-80`; `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/ActuatorProbesTest.java:30-38` |
| прочие `/actuator/*` (например, `/actuator/env`) | токен | `404` (не экспонируются: `management.endpoints.web.exposure.include=health`) | `access/mcp-server/src/main/resources/application.yml:73-76`; `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/ActuatorProbesTest.java:40-50` |
| любой другой путь | токен (`authenticated()`) | зависит от DispatcherServlet (`404`) | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:46-47` 🟡 |

Challenge `403`: `Bearer error="insufficient_scope"[, scope="<scope>"], resource_metadata="<url>"`, тело пустое; для неизвестного tool параметра `scope` нет (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:124-139`; `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:126-135, 177-186`) 🟢.

## 3. Конфигурация (`application.yml`)

| Ключ | Env | Умолчание | Прим. | Трассировка |
|------|-----|-----------|-------|-------------|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `ARCHRAG_OIDC_ISSUER_URI` | нет (обязателен) | | `access/mcp-server/src/main/resources/application.yml:19` |
| `spring.security.oauth2.resourceserver.jwt.audiences` | = `archrag.mcp.security.resource-uri` | нет | audience токена | `access/mcp-server/src/main/resources/application.yml:20` |
| `archrag.mcp.security.resource-uri` | `ARCHRAG_MCP_RESOURCE_URI` | нет (обязателен) | в compose `https://localhost:${ARCHRAG_PUBLIC_PORT:-8443}/mcp` | `access/mcp-server/src/main/resources/application.yml:25`; `platform/docker/docker-compose.yml:232` |
| `archrag.mcp.security.max-request-bytes` | нет | `1MB` | | `access/mcp-server/src/main/resources/application.yml:26`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityProperties.java:19-22` |
| `archrag.mcp.security.tool-scopes.<tool>` | нет | 6 строк → `architecture.read` | tool без строки отклоняется | `access/mcp-server/src/main/resources/application.yml:27-34` |
| `archrag.neo4j.reader.uri/username/password` | `ARCHRAG_NEO4J_READER_URI`, `ARCHRAG_NEO4J_READER_USERNAME`, `ARCHRAG_NEO4J_READER_PASSWORD` | нет | пароль — Docker secret `neo4j_reader_password` | `access/mcp-server/src/main/resources/application.yml:36-41`; `platform/docker/docker-compose.yml:233-239` |
| `archrag.query.stale-after` | нет | `P7D` | > 0, иначе `IllegalArgumentException` | `access/mcp-server/src/main/resources/application.yml:43-44`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/StalenessProperties.java:12-20` |
| `archrag.query.limits.max-depth` | нет | `6` | потолок глубины | `access/mcp-server/src/main/resources/application.yml:46` |
| `archrag.query.limits.max-nodes` | нет | `500` | потолок строк | `access/mcp-server/src/main/resources/application.yml:47` |
| `archrag.query.limits.max-paths` | нет | `50` | потолок путей | `access/mcp-server/src/main/resources/application.yml:48` |
| `archrag.query.limits.timeout` | нет | `5s` | таймаут транзакции | `access/mcp-server/src/main/resources/application.yml:49` |
| `archrag.query.limits.max-response-bytes` | нет | `512KB` | | `access/mcp-server/src/main/resources/application.yml:50` |
| `management.otlp.metrics.export.enabled` / `.step` | `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` (compose: `${ARCHRAG_OBSERVABILITY:-false}`) | `false` / `10s` | URL — `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | `access/mcp-server/src/main/resources/application.yml:55-62`; `platform/docker/docker-compose.yml:235-236` |
| `spring.lifecycle.timeout-per-shutdown-phase`, `server.shutdown` | нет | `20s`, `graceful` | | `access/mcp-server/src/main/resources/application.yml:4-5, 52-53` |

Тестовый профиль подставляет фиктивные reader-свойства (`bolt://localhost:7687`, `mcp-reader`): драйвер ленивый (`access/mcp-server/src/test/resources/config/application.yml:1-7`) 🟢. Переменные окружения для тестов JWT — `TestJwt.register` (`access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/TestJwt.java:42-50`) 🟢.

## 4. Метрики и аудит

| Имя | Тип | Теги | Семантика | Трассировка |
|-----|-----|------|-----------|-------------|
| `archrag.mcp.tool.call` | Observation → timer (+ гистограмма) | `tool` (имя tool; `unknown` для неизвестного), `decision` ∈ `ALLOWED, DENIED_SCOPE, DENIED_UNKNOWN_TOOL, TIMEOUT, ERROR` | одно наблюдение на `tools/call`, включая отказы | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:30, 47-51, 91-97`; `access/mcp-server/src/main/resources/application.yml:68-72` 🟢 |
| `archrag.mcp.tool.rows` | DistributionSummary | `tool` | число строк, прочитанных вызовом из графа (сумма по шаблонам) | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:31, 66-69` 🟢 |

Аудит-лог — логгер `archrag.audit.mcp`, сообщение `mcp tool call`, поля: `principal` (`sub=<sub> client=<azp|client_id>` или `anonymous`), `tool`, `arguments`, `templates`, `durationMs`, `rows`, `truncated`, `decision`, `errorClass` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:99-144`) 🟢. Отказ до tool пишет `arguments={}`, `templates=[]`, `durationMs=0` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:96`) 🟢. Экспорт OTLP: `service.name=mcp-server`, `service.instance.id=${HOSTNAME:local}` (`access/mcp-server/src/main/resources/application.yml:63-67`).

## 5. Тексты ошибок tools (стабильны, ввод не эхоится)

| Условие | Текст (`IllegalArgumentException`) | Tool | Трассировка |
|---------|------------------------------------|------|-------------|
| `gid` не UUID | `gid must be a UUID` | get_asset, explain_provenance, trace_dependencies | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:99` |
| `systemGid` не UUID | `systemGid must be a UUID` | find_runtime_footprint | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:65` |
| актив не найден (нет строки) | `asset not found` | get_asset, explain_provenance, trace_dependencies, find_runtime_footprint | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:70`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:72`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:155`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:74` |
| `query` пуст / длиннее 200 | `query must not be blank` / `query is longer than 200 characters` | search_assets | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:65, 68` |
| `limit` вне 1..50 | `limit must be in 1..50` | search_assets | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:72` |
| неизвестный тип | `Unknown asset type; allowed: ...` | search_assets | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:102-103` |
| `property` не проходит regexp | `property is invalid` | explain_provenance | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:105` |
| неизвестный `mode` | `mode must be trace or impact` | trace_dependencies | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:115` |
| неверный `direction` | `direction must be upstream or downstream` / `direction must be upstream for mode impact` | trace_dependencies | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:120, 126` |
| `environment` вне impact / слишком длинный | `environment is supported only for mode impact` / `environment is too long` | trace_dependencies | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:131-132` |
| `maxDepth` вне диапазона | `maxDepth must be in 1..6` | trace_dependencies | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:136` |
| неизвестный тип связи | `Unknown relation type; allowed: ...` | trace_dependencies | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:181` |
| таймаут Neo4j | `QueryTimeoutException` (аудит `TIMEOUT`) | любой с графом | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:126-131` |

Сообщение `maxDepth must be in 1..6` содержит значение лимита, не ввод 🟢. Исключение библиотеки (`Neo4jException`, не таймаут) пробрасывается как есть и попадает клиенту текстом исключения 🟡 (Q-11).
