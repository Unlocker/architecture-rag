# access/mcp-server: задачи

> Задачи use case: [`authorize-tool-call/`](authorize-tool-call/tasks.md), [`search-get-asset/`](search-get-asset/tasks.md), [`explain-provenance/`](explain-provenance/tasks.md), [`trace-dependencies/`](trace-dependencies/tasks.md), [`find-runtime-footprint/`](find-runtime-footprint/tasks.md). Здесь — сквозные части (сборка, конфигурация, граф, служебный tool, health).

## Pré-requisitos
- [ ] Модуль `graph-query-core` собирается (access/mcp-server/pom.xml:19-21)
- [ ] Neo4j с reader-учёткой `archrag_reader`, схема создана ingestion-сервисом; OIDC issuer доступен
- [ ] Переменные окружения из [`contracts.md`](contracts.md) §3 заданы (секреты — из env/Docker secrets)

## Tarefas

- [ ] T-01: Зависимости и сборка: `spring-ai-starter-mcp-server-webmvc`, actuator, micrometer-otlp, aspectj, oauth2-resource-server; repackage с классификатором `exec`
  - Origin: access/mcp-server/pom.xml:17-73
  - Критерий готовности: основной артефакт — обычный jar (на него зависит `platform/integration-tests`), исполняемый — `*-exec.jar`; версии берутся из BOM корня
  - Уверенность: 🟢
- [ ] T-02: `application.yml`: STATELESS Streamable HTTP на `/mcp`, имя/версия сервера, JWT, `tool-scopes`, reader, лимиты, health-пробы, OTLP
  - Origin: access/mcp-server/src/main/resources/application.yml:1-80
  - Критерий готовности: сервис не стартует без `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_MCP_RESOURCE_URI`, `ARCHRAG_NEO4J_READER_*`
  - Уверенность: 🟢
- [ ] T-03: `McpServerApplication`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/McpServerApplication.java:6-13
  - Критерий готовности: приложение стартует (`McpServerInteropTest`)
  - Уверенность: 🟢
- [ ] T-04: `GraphReaderConfiguration`: единственный `Driver` (read-only), `QueryLimits`, 7 шаблонов, реестр, исполнитель, `Clock`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:27-104
  - Критерий готовности: драйвер закрывается при остановке, создаётся лениво; шаблоны с глубиной выше потолка отвергаются реестром при старте
  - Уверенность: 🟢
- [ ] T-05: `GraphReaderProperties` (reader + `Limits`) и `StalenessProperties`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderProperties.java:14-35; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/StalenessProperties.java:12-20
  - Критерий готовности: `toString` без пароля; `stale-after` ≤ 0 → `IllegalArgumentException`; `GraphReaderConfigurationTest` (3 теста)
  - Уверенность: 🟢
- [ ] T-06: `GraphQueries` — единственная точка доступа tools к графу, пишет шаблон/строки/усечение в аудит
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphQueries.java:18-31
  - Критерий готовности: `McpAuditTest.graphToolRecordsTemplatesRowsAndMasksSecretArguments`
  - Уверенность: 🟢
- [ ] T-07: `PingTool` — служебный tool, `{"status":"ok"}`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/PingTool.java:11-22
  - Критерий готовности: `McpServerInteropTest.initializeListAndCallPing`
  - Уверенность: 🟢
- [ ] T-08: Health-пробы и закрытие остальных actuator-эндпоинтов
  - Origin: access/mcp-server/src/main/resources/application.yml:73-80; access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:43-45
  - Критерий готовности: AC-06 из `requirements.md`
  - Уверенность: 🟢
- [ ] T-09: Развёртывание: образ из общего `Dockerfile`, compose-сервис без `ports`, nginx `location = /mcp`
  - Origin: `platform/docker/docker-compose.yml:222-250`; `platform/docker/proxy/nginx.conf:18-33`
  - Критерий готовности: `--scale mcp-server=2` обслуживается; healthcheck `curl /actuator/health` проходит
  - Уверенность: 🟢
- [ ] T-10: Реализация tools и безопасности — в use case (T-идентификаторы там)
  - Origin: [`authorize-tool-call/tasks.md`](authorize-tool-call/tasks.md) и далее
  - Критерий готовности: все шесть tools имеют строку в `tool-scopes`
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `McpServerInteropTest` — MCP-клиент Spring AI, stateless, JSON-ответ, `/sse` = 404 (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpServerInteropTest.java:43-116)
- [ ] TT-02: `McpSecurityTest` — безопасность и список tools (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:59-218)
- [ ] TT-03: `ActuatorProbesTest` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/ActuatorProbesTest.java:30-50)
- [ ] TT-04: `GraphReaderConfigurationTest` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfigurationTest.java:28-58)
- [ ] TT-05: приёмочные `F1AcceptanceIT`..`F4AcceptanceIT` (Testcontainers, нужен Docker) (platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:242-360)
- [ ] TT-06: 🔴 нет теста сверки `@McpTool` ↔ `tool-scopes` при старте (Q-04)
- [ ] TT-07: 🔴 нет теста `GET /mcp → 405` (Q-18)

## Ordem Sugerida
1. T-01, T-02, T-03 → 2. T-05, T-04, T-06 → 3. use case `authorize-tool-call` → 4. T-07 → 5. остальные use case → 6. T-08, T-09.

## Lacunas Pendentes (🔴 и 🟡)
См. [`questions.md`](questions.md): Q-01..Q-19.
