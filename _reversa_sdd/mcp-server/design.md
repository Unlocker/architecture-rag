# access/mcp-server: дизайн

> Ссылки даны полными путями от корня репозитория. Требования: [`requirements.md`](requirements.md). Контракты: [`contracts.md`](contracts.md). Блок-схемы: [`../flowcharts/mcp-server.md`](../flowcharts/mcp-server.md).
> Use case-спецификации: [`authorize-tool-call/`](authorize-tool-call/design.md), [`search-get-asset/`](search-get-asset/design.md), [`explain-provenance/`](explain-provenance/design.md), [`trace-dependencies/`](trace-dependencies/design.md), [`find-runtime-footprint/`](find-runtime-footprint/design.md).
> Сквозные части (безопасность, аудит, сборка бинов графа, конфигурация) описаны здесь и в use case не дублируются.

## Интерфейс

Пакет `io.github.unlocker.archrag.mcpserver` (подпакеты `security`, `tools`, `audit`, `graph`). MCP/HTTP-контракт — в [`contracts.md`](contracts.md) §1-2.

| Символ | Сигнатура | Результат / исключения | Трассировка |
|--------|-----------|------------------------|-------------|
| `McpServerApplication.main` | `(String[]) -> void` | `SpringApplication.run`; порт — умолчание Spring (8080), `server.port` не задан | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/McpServerApplication.java:6-13` 🟢 |
| `McpSecurityConfiguration.mcpSecurityFilterChain` | `(HttpSecurity, McpSecurityProperties, ToolCallAuditor, OAuth2ResourceServerProperties) -> SecurityFilterChain` | цепочка: JWT, PRM, `ToolScopeFilter` после `AuthorizationFilter` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:25-63` 🟢 |
| `ToolScopeFilter.doFilterInternal` | `(request, response, chain) -> void` | `400` кодировка/JSON и `413` (оба с аудитом `rejected`); `403` + `WWW-Authenticate`; иначе `chain.doFilter(BufferedRequest)` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:56-98` 🟢 |
| `McpSecurityProperties` | `record(String resourceUri, Map<String,String> toolScopes, DataSize maxRequestBytes)` | `toolScopes` по умолчанию пусто, `maxRequestBytes` — 1 МБ | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityProperties.java:15-23` 🟢 |
| `@McpTool` методы | см. [`contracts.md`](contracts.md) §1.3 | результат — `record`, ошибки — `IllegalArgumentException` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:39-93` (и остальные tools) 🟢 |
| `ToolCallAuditAspect.audit` | `@Around("@annotation(mcpTool)")` | открывает `ToolCallContext`, наблюдение, пишет итог в `finally`; исключение пробрасывается | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:29-60` 🟢 |
| `ToolCallAuditor.denied` | `(String tool, ToolDecision) -> void` | запись об отказе до выполнения tool (вызывается фильтром) | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:91-97` 🟢 |
| `ToolCallAuditor.completed` | `(Observation, tool, args, ToolCallContext, Duration, ToolDecision, Throwable) -> void` | не бросает: сбой аудита логируется `WARN` с классом исключения | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:54-83` 🟢 |
| `GraphQueries.execute` | `(String templateId, Map<String,Object> params, ResultBudget budget) -> QueryResult` | делегирует `GraphQueryExecutor`, пишет шаблон/строки/усечение в `ToolCallContext` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphQueries.java:26-31` 🟢 |
| `GraphReaderConfiguration` | бины `Driver`, `QueryLimits`, 7 `QueryTemplate`, `QueryTemplateRegistry`, `GraphQueryExecutor`, `Clock` | драйвер закрывается `destroyMethod = "close"` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:35-104` 🟢 |

## Fluxo Principal

1. Запрос `POST /mcp` проходит цепочку Spring Security: валидация JWT (issuer из `spring.security.oauth2.resourceserver.jwt.issuer-uri`, audience из `archrag.mcp.security.resource-uri`); без токена — `401` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:37-50`; `access/mcp-server/src/main/resources/application.yml:15-20`) 🟢.
2. `ToolScopeFilter` срабатывает только на `POST` и ровно `/mcp` (`shouldNotFilter`), читает тело и решает судьбу `tools/call` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:51-54`) 🟢. Детали — [`authorize-tool-call/design.md`](authorize-tool-call/design.md).
3. Spring AI MCP (stateless, webmvc) разбирает JSON-RPC и вызывает `@McpTool`-метод, найденный по имени 🟡 (поведение библиотеки; подтверждено интероп-тестом `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpServerInteropTest.java:43-66`).
4. Аспект оборачивает метод: `ToolCallContext.open` (ThreadLocal) → `auditor.start(tool)` → `proceed()` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:34-42`) 🟢.
5. Tool валидирует аргументы (до обращения к графу), собирает `ResultBudget` и вызывает `GraphQueries.execute(templateId, params, budget)` 🟢.
6. `GraphQueryExecutor.execute`: поиск шаблона, проверка набора параметров, `budget.clampTo(limits)`, подстановка глубины, `LIMIT $limit = maxRows + 1`, `EXPLAIN`-проверка (один раз на шаблон), `session.executeRead` с таймаутом транзакции, сбор строк под бюджетом (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:60-96`) 🟢.
7. Tool собирает ответ-`record`; `finally` аспекта закрывает контекст и вызывает `completed` (решение `ALLOWED`, `TIMEOUT` для `QueryTimeoutException`, иначе `ERROR`) (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:43-59`) 🟢.
8. Spring AI сериализует `record` в `result.content[0].text` (JSON-строка); исключение превращается в `result.isError=true` и текст исключения 🟡 (подтверждено тестами: `access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/TraceDependenciesMcpTest.java:98-104`).

## Fluxos Alternativos

- **Токен без нужного scope** — `403` с `WWW-Authenticate: Bearer error="insufficient_scope", scope="architecture.read", resource_metadata=...`, тело пустое; до аспекта дело не доходит, пишется `DENIED_SCOPE` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:91-95, 124-132`) 🟢.
- **Неизвестный tool** — `403` без параметра `scope` в challenge, `DENIED_UNKNOWN_TOOL`, метрика с тегом `tool=unknown` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:84-90`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:91-97`) 🟢.
- **Таймаут Neo4j** — `Neo4jException` с кодом `TransactionTimedOut` превращается в `QueryTimeoutException`, аспект ставит `TIMEOUT` (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:126-131`) 🟢.
- **Сбой записи аудита** — логируется `WARN` с классом исключения, ответ и исключение tool не подменяются (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:62-82`) 🟢.
- **Вызов вне аспекта** (tool в другом потоке) — `ToolCallContext.record` ничего не делает, `templates`/`rows` в аудите пусты 🟡 (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallContext.java:8-13, 34-42`).

## Dependências

- `access/graph-query-core` (шаблоны, исполнитель, бюджеты) — единственная проектная зависимость модуля `access/mcp-server/pom.xml:19-21` 🟢; `canonical-model` (`AuthorityMatrix`, `NodeLabel`, `SourceSystemCode`) используется в `ExplainProvenanceTool` только транзитивно (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/ExplainProvenanceTool.java:3-5`) 🟡 (Н-15, Q-07).
- Библиотеки: `neo4j-java-driver`, `spring-ai-starter-mcp-server-webmvc`, `spring-boot-starter-actuator`, `micrometer-registry-otlp`, `spring-boot-opentelemetry`, `spring-boot-starter-aspectj`, `spring-boot-starter-security-oauth2-resource-server` (`access/mcp-server/pom.xml:23-50`) 🟢.
- Не зависит от `ingestion/*` и `adapters/*`; writer-учётных данных нет (направление зависимостей CLAUDE.md) 🟢.
- Сборка: `spring-boot-maven-plugin` с `repackage` и классификатором `exec`; основной артефакт — обычный jar, на него зависит `platform/integration-tests` (`access/mcp-server/pom.xml:60-73`) 🟢.
- Развёртывание: образ из общего `Dockerfile`, в compose без `ports`, healthcheck `curl /actuator/health`, за nginx `location = /mcp` (`platform/docker/docker-compose.yml:222-250`; `platform/docker/proxy/nginx.conf:18-33`) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Режим `STATELESS` вместо сессионного `STREAMABLE` | `access/mcp-server/src/main/resources/application.yml:11`; `docs/adr/0017-mcp-streamable-http.md:14, 34` | 🟢 |
| Scope проверяется фильтром по телу JSON-RPC, а не правилом URL | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:30-36` | 🟢 |
| `ToolScopeFilter` создаётся через `new`, а не бином (иначе регистрируется ещё и как servlet-фильтр) | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:34-36` | 🟢 |
| Единая точка доступа к графу `GraphQueries` ради аудита | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphQueries.java:10-16` | 🟢 |
| Свой драйвер reader вместо автоконфигурации `spring.neo4j.*` | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:20-26` | 🟢 |
| Tool-ответы — `record`, ошибки — `IllegalArgumentException` с фиксированным текстом | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:94-101` | 🟢 |
| Пути трассировки не сортируются в Cypher (иначе Neo4j не остановится на лимите), порядок задаёт tool | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:72-76` | 🟢 |
| Глубина обхода подставляется в Cypher числом после проверки диапазона (единственная конкатенация) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:125-138` | 🟢 |

## Estado Interno

Состояния между запросами нет: сервис stateless. Единственное изменяемое состояние — `ThreadLocal<ToolCallContext>` на время вызова tool (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallContext.java:16, 24-32`) и кэш вердиктов `EXPLAIN` в `GraphQueryExecutor` (по ID шаблона; провал необратим) (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:99-108`) 🟢.

## Observabilidade

Лог `archrag.audit.mcp` (INFO, структурированные key-value): `principal`, `tool`, `arguments`, `templates`, `durationMs`, `rows`, `truncated`, `decision`, `errorClass` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:99-119`) 🟢. Метрики — [`contracts.md`](contracts.md) §4. `WARN`: `tools/call отклонён: для tool нет записи в archrag.mcp.security.tool-scopes`; `Сбой записи аудита tool-вызова: <класс>` 🟢.

## Riscos e Lacunas

- 🟢 `initialize` и `tools/list` требуют роль `archrag-reader` (UNLOCKER-290; П-6, Q-02); scope — только на `tools/call`.
- 🟡 Согласованность `@McpTool` ↔ `tool-scopes` проверяется только в рантайме: забытый tool получит `403` (Q-04, матрица №8).
- 🟢 `find_runtime_footprint` нормализует `systemGid` (`strip` + `UUID.toString()`) как три других tools (Н-7, Q-06).
- 🟢 [R.8: ответ владельца, вопрос 12] Нет rate limiting (Q-09).
- 🟡 Аудит аргументов маскирует секреты по именам ключей, а не по содержимому (П-14, Q-05).
- 🟡 `stale-after` продублирован в UI админ-консоли без связи (матрица №10, Q-08).

## Зависит от / Связанные спецификации

- `access/graph-query-core` — шаблоны запросов, `GraphQueryExecutor`, `QueryLimits` (спецификация вне этого комплекта; flowchart [`../flowcharts/graph-query-core.md`](../flowcharts/graph-query-core.md)).
- `contracts/canonical-model` — `AuthorityMatrix`, `NodeLabel`, `SourceSystemCode` ([`../canonical-model/`](../canonical-model/)).
- Neo4j-схема и проекция данных — [`../graph-projector/`](../graph-projector/).
- Compose-стенд и TLS-proxy — `platform/docker` ([`../flowcharts/docker.md`](../flowcharts/docker.md)).
- Права и scope — [`../permissions.md`](../permissions.md) §3.
