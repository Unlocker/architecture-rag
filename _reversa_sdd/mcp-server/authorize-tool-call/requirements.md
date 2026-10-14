# authorize-tool-call: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §2, §4.

## Visão Geral

Сценарий защиты и аудита каждого запроса к `POST /mcp`: проверка JWT (issuer + audience), разбор тела JSON-RPC, проверка scope на `tools/call` по таблице `tool-scopes` (fail-closed), публикация Protected Resource Metadata, аудит и метрики каждого вызова, включая отказы. Это реализация правила проекта «каждый новый tool закрыт scope'ом».

## Regras de Negócio

- Все пути, кроме `/actuator/health/**` и `/.well-known/oauth-protected-resource/**`, требуют валидный JWT; диспетчеризация ошибок (`DispatcherType.ERROR`) разрешена 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:39-47).
- JWT валидируется стандартным `oauth2ResourceServer.jwt` (issuer и audience из `application.yml`); токен без нужного audience или чужого issuer — `401` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:48-50; access/mcp-server/src/main/resources/application.yml:19-20; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:66-93).
- `ToolScopeFilter` стоит после `AuthorizationFilter`, поэтому до фильтра доходят только аутентифицированные запросы 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:61).
- Фильтр работает только на `POST /mcp`; остальные методы и пути пропускает 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:51-54).
- Порядок проверок в фильтре: кодировка запроса → размер тела → JSON → объект ли → метод `tools/call` → есть ли `params.name` в `tool-scopes` → есть ли `SCOPE_<scope>` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:60-96).
- Тело читается не более `max-request-bytes + 1` байт; превышение — `413` без дальнейшей обработки 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:64-70).
- JSON-массив (batch), мусор и не-объект — `400` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:71-81; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:188-193).
- Имя tool отсутствует либо отсутствует в `tool-scopes` → `403`, challenge без `scope`, аудит `DENIED_UNKNOWN_TOOL` (fail-closed даже при валидном scope) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:84-90; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:177-186).
- Нет authority `SCOPE_<scope>` → `403`, challenge с `error="insufficient_scope"`, `scope="<scope>"`, `resource_metadata="<url>"`, тело пустое, аудит `DENIED_SCOPE` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:91-95, 124-132; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:126-154).
- Для методов, кроме `tools/call` (`initialize`, `tools/list`), scope не проверяется 🟡 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:82; Q-02).
- Тело передаётся дальше буферизованным (`BufferedRequest`), поддерживаются `getInputStream()` и `getReader()` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:97, 141-189).
- Каждый допущенный `tools/call` оборачивается аспектом: одна аудит-запись и одно наблюдение; исключение tool пробрасывается 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:29-60).
- В аудит попадают `principal` (`sub`, `azp` либо `client_id`), tool, нормализованные аргументы, шаблоны, строки, `truncated`, `decision`, класс ошибки; токен не читается и не логируется 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:99-144; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpAuditTest.java:120-143).
- Имя tool в логе санитизируется (управляющие символы → `?`, ≤ 64 символов + `…`); в метрике неизвестный tool = `unknown` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:91-97, 122-129; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpAuditTest.java:145-150).
- Сбой записи аудита не подменяет ответ и исключение tool 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:62-82; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditorTest.java:86).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | JWT (issuer + audience) на `/mcp`; `401` с `WWW-Authenticate` и `resource_metadata` | Must | AC-01 |
| RF-02 | `tools/call` разрешён только при `SCOPE_<scope>` из `tool-scopes` | Must | AC-02 |
| RF-03 | Неизвестный tool отклоняется (fail-closed) | Must | AC-03 |
| RF-04 | Защита от больших и невалидных тел (`413`/`400`) | Must | AC-05, AC-06 |
| RF-05 | PRM по RFC 9728 публичен; `scopes_supported` собирается из значений `tool-scopes` | Must | AC-04 |
| RF-06 | Аудит и метрики на каждый `tools/call`, включая отказы; токен в аудит не попадает | Must | AC-07, AC-08 |
| RF-07 | Неподдерживаемая кодировка `Content-Type` → `400` | Should | AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Отказ по scope не раскрывает тело; ввод пользователя не эхоится | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:124-132 | 🟢 |
| Безопасность | Тело читается ограниченно, DoS большим телом исключён | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:64-70 | 🟢 |
| Безопасность | Кардинальность метрик ограничена: `tool=unknown` для неизвестных | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:91-97 | 🟢 |
| Производительность | Тело разбирается один раз; дальше отдаётся буфер | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:65-97 | 🟢 |
| Observabilidade | Одно наблюдение и одна запись лога на вызов | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:19-25 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (McpSecurityTest.requestWithoutTokenIsUnauthorizedWithMetadataPointer; foreignAudienceIssuerAndExpiredTokensAreUnauthorized)
Дано запрос POST /mcp без токена (либо токен с чужим audience, чужим issuer или просроченный)
Когда фильтры обрабатывают запрос
Тогда 401 и WWW-Authenticate содержит Bearer и resource_metadata=

# AC-02 (toolCallWithoutScopeIsForbiddenWithScopeChallenge; toolCallWithOtherScopeIsForbidden; toolCallWithScopeSucceeds)
Дано токен без scope architecture.read (или со scope other.scope)
Когда tools/call ping
Тогда 403, WWW-Authenticate: Bearer error="insufficient_scope", scope="architecture.read", resource_metadata=..., тело пустое
Дано токен со scope architecture.read
Когда tools/call ping
Тогда 200 и {"status":"ok"}

# AC-03 (unmappedToolIsForbiddenEvenWithScope; McpAuditTest.unknownToolIsAuditedAsDeniedUnknownTool)
Дано токен со scope architecture.read и tools/call для tool "nope" без записи в tool-scopes
Когда фильтр проверяет scope
Тогда 403, challenge без scope="...", аудит decision=DENIED_UNKNOWN_TOOL, метрика tool=unknown

# AC-04 (protectedResourceMetadataIsPublic)
Дано клиент без токена
Когда GET /.well-known/oauth-protected-resource
Тогда 200, resource = ARCHRAG_MCP_RESOURCE_URI, authorization_servers содержит issuer, scopes_supported содержит architecture.read, bearer_methods_supported = ["header"]

# AC-05 (oversizedBodyIsRejected)
Дано max-request-bytes = 2KB и тело 4 КБ при валидном токене
Когда POST /mcp
Тогда 413

# AC-06 (batchAndMalformedBodiesAreBadRequest; unknownCharsetIsBadRequest)
Дано валидный токен и тело "[...]" (batch), "{not json" либо Content-Type с charset=bogus
Когда POST /mcp
Тогда 400

# AC-07 (McpAuditTest.pingProducesOneAllowedRecordWithPrincipalAndNoToken; insufficientScopeIsAuditedAsDeniedScope)
Дано успешный tools/call ping
Когда вызов завершён
Тогда ровно одна запись archrag.audit.mcp: tool=ping, decision=ALLOWED, principal содержит sub, строка токена нигде отсутствует
Дано токен с other.scope
Тогда запись decision=DENIED_SCOPE, токена в записи нет

# AC-08 (toolFailureIsAuditedAsError; queryTimeoutIsAuditedAsTimeout; graphToolRecordsTemplatesRowsAndMasksSecretArguments)
Дано tool бросил исключение / Neo4j превысил таймаут / tool вернул строки по двум шаблонам
Когда вызов завершён
Тогда decision = ERROR / TIMEOUT / ALLOWED, errorClass заканчивается именем класса, templates перечисляет ID шаблонов, rows = сумма, ключ apiToken замаскирован ***
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-06 | Must | Правило проекта: tool закрыт scope'ом, токен не утекает в лог |
| RF-07 | Should | Защита от падения фильтра на неизвестной кодировке |
| Scope на `tools/list`/`initialize` | Won't | Не реализовано (🟡 Q-02) |
| Аудит отказов `400`/`413` | Must | Реализован: `REJECTED_INVALID_REQUEST`, `REJECTED_TOO_LARGE` 🟢 (Q-16) |
| Аудит отказа `401` | Won't | Не реализован (🟡 Q-16) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java | цепочка безопасности | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java | фильтр scope | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityProperties.java | свойства | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java | аспект | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java | аудитор | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallContext.java | контекст вызова | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolArguments.java | нормализация аргументов | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolDecision.java | решения | 🟢 |
