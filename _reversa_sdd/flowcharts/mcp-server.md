# Блок-схемы: access/mcp-server

> Archaeologist, R.2d. Пути относительно `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/`. Шаблоны и исполнитель — `flowcharts/graph-query-core.md`. Конфигурация — `code-analysis.md` §X2.4.

## Прохождение `POST /mcp` (`security/McpSecurityConfiguration.java:37-62`, `security/ToolScopeFilter.java:56-98`) 🟢

```mermaid
flowchart TD
  R["POST /mcp"] --> J{"валидный JWT\n(issuer, audience)?"}
  J -- нет --> U401["401 + WWW-Authenticate"]
  J -- да --> CS{"кодировка поддерживается?"}
  CS -- нет --> B400["400"]
  CS -- да --> SZ{"тело ≤ max-request-bytes (1 МБ)?"}
  SZ -- нет --> B413["413"]
  SZ -- да --> OB{"JSON-объект?\n(не массив, не мусор)"}
  OB -- нет --> B400
  OB -- да --> M{"method == tools/call?"}
  M -- нет --> PASS["дальше в MCP (initialize, tools/list и т. д.)\nтолько валидный токен"]
  M -- да --> SC{"для params.name есть scope\nв tool-scopes?"}
  SC -- нет --> D1["аудит DENIED_UNKNOWN_TOOL, 403"]
  SC -- да --> HS{"у токена есть SCOPE_<scope>?"}
  HS -- нет --> D2["аудит DENIED_SCOPE,\n403 insufficient_scope + resource_metadata"]
  HS -- да --> PASS2["BufferedRequest → Spring AI MCP → @McpTool"]
```

## Вызов tool под аспектом аудита (`audit/ToolCallAuditAspect.java:29-60`) 🟢

```mermaid
flowchart TD
  A["@McpTool метод вызван"] --> O["ToolCallContext.open (ThreadLocal), Observation.start"]
  O --> P["joinPoint.proceed()"]
  P --> V["tool: валидация аргументов"]
  V -- IllegalArgumentException --> ERR["decision = ERROR"]
  V -- ok --> Q["GraphQueries.execute → GraphQueryExecutor.execute\n+ ToolCallContext.record(шаблон, rowCount, truncated)"]
  Q -- QueryTimeoutException --> TO["decision = TIMEOUT"]
  Q -- иное исключение --> ERR
  Q -- ok --> ASM["сборка ответа (record) → JSON"]
  ASM --> OK["decision = ALLOWED"]
  ERR --> FIN
  TO --> FIN
  OK --> FIN["finally: ToolCallContext.close;\nauditor.completed → Observation.stop, summary rows,\nодна запись archrag.audit.mcp"]
  FIN --> RE{"было исключение?"}
  RE -- да --> THROW["исключение пробрасывается (isError=true у клиента)"]
  RE -- нет --> RET["результат tool"]
```

## `trace_dependencies` (`tools/TraceDependenciesTool.java:101-158`) 🟢

```mermaid
flowchart TD
  S["traceDependencies(gid, direction, maxDepth, relationTypes, mode, environment)"] --> G["gid → UUID (strip, lower)"]
  G --> MD{"mode ∈ trace, impact?"}
  MD -- нет --> E["IllegalArgumentException"]
  MD -- impact --> DI{"direction пусто или upstream?"}
  DI -- нет --> E
  DI -- да --> EV
  MD -- trace --> DT{"direction ∈ upstream, downstream (по умолч. downstream)?"}
  DT -- нет --> E
  DT -- да --> EV{"environment задан?"}
  EV -- "trace или длиннее 128" --> E
  EV -- ok --> DP{"1 ≤ maxDepth ≤ limits.maxDepth?"}
  DP -- нет --> E
  DP -- да --> RT{"relationTypes ⊆ allowlist режима?"}
  RT -- нет --> E
  RT -- да --> TP["шаблон: impact → trace_impact;\ndownstream → trace_downstream; upstream → trace_upstream"]
  TP --> X["GraphQueries.execute (budget: depth, maxNodes, maxPaths…)"]
  X --> EM{"пусто?"}
  EM -- да --> EX{"get_asset находит узел?"}
  EX -- нет --> E2["IllegalArgumentException: asset not found"]
  EX -- да --> AS
  EM -- нет --> AS["assemble: сортировка путей (длина, gid),\nдедупликация узлов и связей, stale, affected (impact)"]
```

## `find_runtime_footprint` — сборка (`tools/FindRuntimeFootprintTool.java:79-150`) 🟢

```mermaid
flowchart TD
  Q["runtime_footprint: строки (service, deployment, compute, host)"] --> EMP{"строк нет?"}
  EMP -- да --> NF["IllegalArgumentException: asset not found"]
  EMP -- нет --> SYS["system из первой строки; stale → warning"]
  SYS --> L["для каждой строки"]
  L --> SV{"service == null?"}
  SV -- да --> L
  SV -- нет --> SB["service (computeIfAbsent по gid)"]
  SB --> DP{"deployment == null?"}
  DP -- да --> L
  DP -- нет --> DB["deployment (computeIfAbsent по gid)"]
  DB --> CP{"compute == null\nили уже добавлен?"}
  CP -- да --> L
  CP -- нет --> CB["compute (+ host, stale → warnings) → в deployment"]
  CB --> L
```

## `explain_provenance` (`tools/ExplainProvenanceTool.java:55-88`) 🟢

```mermaid
flowchart TD
  A["explainProvenance(gid, property?)"] --> V["gid → UUID; property ~ ^[A-Za-z][A-Za-z0-9_]{0,63}$"]
  V --> Q["explain_provenance(gid)"]
  Q --> N{"строка есть?"}
  N -- нет --> E["asset not found"]
  N -- да --> AU["authorities(type, property): AuthorityMatrix.defaults();\nнеизвестная метка → пусто"]
  AU --> R["records: authoritative = source ∈ мастера;\nпри property конфликты фильтруются по имени"]
  R --> CS["conflictState = OPEN, если у какой-либо записи есть конфликт, иначе NONE"]
```
