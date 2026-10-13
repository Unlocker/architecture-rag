# authorize-tool-call: задачи

## Pré-requisitos
- [ ] OIDC issuer доступен (`ARCHRAG_OIDC_ISSUER_URI`), `ARCHRAG_MCP_RESOURCE_URI` задан (access/mcp-server/src/main/resources/application.yml:19-20, 25)
- [ ] `graph-query-core` собирается (access/mcp-server/pom.xml:19-21)

## Tarefas

- [ ] T-01: `McpSecurityProperties` (`resourceUri`, `toolScopes`, `maxRequestBytes`) с копией карты и умолчанием 1 МБ
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityProperties.java:15-23
  - Критерий готовности: `toolScopes == null` → пустая карта; `maxRequestBytes == null` → 1 МБ; карта иммутабельна
  - Уверенность: 🟢
- [ ] T-02: Конфигурация `archrag.mcp.security.tool-scopes` — строка на каждый tool (6 строк → `architecture.read`)
  - Origin: access/mcp-server/src/main/resources/application.yml:27-34
  - Критерий готовности: каждый `@McpTool` имеет строку; tool без строки получает `403` (AC-03)
  - Уверенность: 🟢
- [ ] T-03: `McpSecurityConfiguration`: JWT, публичные пути, STATELESS, CSRF off, PRM, регистрация фильтра после `AuthorizationFilter`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:25-63
  - Критерий готовности: AC-01, AC-04 из `requirements.md`
  - Уверенность: 🟢
- [ ] T-04: `ToolScopeFilter`: кодировка → размер → JSON → объект → `tools/call` → scope; `BufferedRequest`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:37-190
  - Критерий готовности: AC-02, AC-03, AC-05, AC-06
  - Уверенность: 🟢
- [ ] T-05: `ToolCallAuditAspect` + `ToolCallContext`: контекст, наблюдение, итог в `finally`, исключение пробрасывается
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:29-69; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallContext.java:14-55
  - Критерий готовности: AC-07, AC-08
  - Уверенность: 🟢
- [ ] T-06: `ToolCallAuditor`: запись `archrag.audit.mcp`, наблюдение `archrag.mcp.tool.call`, summary `archrag.mcp.tool.rows`, `denied`, `sanitize`, `principal`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:27-145
  - Критерий готовности: `ToolCallAuditorTest` (5 тестов) и `McpAuditTest` (8 тестов) зелёные
  - Уверенность: 🟢
- [ ] T-07: `ToolArguments.normalize`: сортировка ключей, усечение 256, коллекции 20, глубина 5, маска `***`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolArguments.java:18-80
  - Критерий готовности: `ToolArgumentsTest` (7 тестов)
  - Уверенность: 🟢
- [ ] T-08: Метрики: гистограмма для `archrag.mcp.tool.call`
  - Origin: access/mcp-server/src/main/resources/application.yml:68-72
  - Критерий готовности: гистограмма включена (нужна для p95 по tool)
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `McpSecurityTest` — 401, чужой audience/issuer/просрочка (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:59-93), 403 с challenge (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:126-154), unknown tool (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:177-186), 400/413 (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:156-199), PRM (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:201-218)
- [ ] TT-02: `McpAuditTest` — запись при успехе, отказах, ошибке, таймауте, маске секретов (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpAuditTest.java:120-204)
- [ ] TT-03: `ToolCallAuditorTest`, `ToolArgumentsTest` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditorTest.java:27-94; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/audit/ToolArgumentsTest.java:13-66)
- [ ] TT-04: 🔴 нет теста на варианты пути `/mcp/`, `;`-параметры (Q-15)
- [ ] TT-05: 🔴 нет теста на сверку `@McpTool` ↔ `tool-scopes` (Q-04)

## Ordem Sugerida
1. T-01, T-02 → 2. T-06, T-07, T-05, T-08 → 3. T-03, T-04 → 4. тесты TT-01..TT-03.

## Lacunas Pendentes (🔴 и 🟡)
- Q-02, Q-04, Q-05, Q-15, Q-16, Q-17 — [`../questions.md`](../questions.md).
