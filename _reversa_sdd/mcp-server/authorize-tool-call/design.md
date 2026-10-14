# authorize-tool-call: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §2, §4. Блок-схема: [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md) («Прохождение `POST /mcp`»).

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `McpSecurityConfiguration.mcpSecurityFilterChain` | `(HttpSecurity, McpSecurityProperties, ToolCallAuditor, OAuth2ResourceServerProperties) -> SecurityFilterChain` | JWT + PRM + фильтр после `AuthorizationFilter` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:25-63 🟢 |
| `ToolScopeFilter(McpSecurityProperties, ToolCallAuditor)` | package-private `OncePerRequestFilter` | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:37-49 🟢 |
| `ToolScopeFilter.shouldNotFilter` | `(request) -> boolean` | `true`, если не `POST` или `getRequestURI() != "/mcp"` | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:51-54 🟢 |
| `ToolScopeFilter.doFilterInternal` | `(request, response, chain) -> void` | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:56-98 🟢 |
| `McpSecurityProperties` | `(resourceUri, toolScopes, maxRequestBytes)` | копия `Map` (иммутабельная), умолчание 1 МБ | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityProperties.java:15-23 🟢 |
| `ToolCallAuditAspect.audit` | `@Around("@annotation(mcpTool)") (ProceedingJoinPoint, McpTool) -> Object` | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:29-60 🟢 |
| `ToolCallAuditor.start/completed/denied/sanitize` | см. [`../design.md`](../design.md) | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:47-129 🟢 |
| `ToolArguments.normalize` | `(Map<String,?>) -> Map<String,Object>` | детерминированная копия аргументов | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolArguments.java:31-33 🟢 |
| `ToolDecision` | enum `ALLOWED, DENIED_SCOPE, DENIED_UNKNOWN_TOOL, TIMEOUT, ERROR` | | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolDecision.java:4-15 🟢 |

## Fluxo Principal

1. Spring Security проверяет JWT; публичные пути (`/actuator/health/**`, `/.well-known/oauth-protected-resource/**`) и `DispatcherType.ERROR` пропускаются без токена, остальное `authenticated()` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:39-47) 🟢.
2. `ToolScopeFilter`: пропускает всё, кроме `POST /mcp` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:51-54) 🟢.
3. Кодировка из `request.getCharacterEncoding()` проверяется `Charset.isSupported`; `null` допустим, неизвестное имя и недопустимый синтаксис имени → `400` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:60-63, 100-109) 🟢.
4. `body = readNBytes(min(limit, Integer.MAX_VALUE-1) + 1)`; `body.length > limit` → `413` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:64-70) 🟢.
5. `JsonMapper.readTree(body)`: `JacksonException` или не-объект (`null`, массив, скаляр) → `400` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:71-81) 🟢.
6. Если `method == "tools/call"`: `tool = params.name`, `scope = toolScopes.get(tool)`; `scope == null` (в том числе `tool == null`) → предупреждение в лог (без значения), `auditor.denied(tool, DENIED_UNKNOWN_TOOL)`, `403` без `scope` в challenge (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:82-90) 🟢.
7. `hasAuthority("SCOPE_" + scope)` перебирает authorities `SecurityContextHolder`; нет → `auditor.denied(tool, DENIED_SCOPE)`, `403` со `scope` в challenge (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:91-95, 111-122) 🟢.
8. Иначе `chain.doFilter(new BufferedRequest(request, body), response)`; дальше Spring AI вызывает `@McpTool`, и аспект пишет итог (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:97) 🟢.
9. `forbid`: статус `403`, заголовок `WWW-Authenticate: Bearer error="insufficient_scope"[, scope="<scope>"], resource_metadata="<origin>/.well-known/oauth-protected-resource"`; URL метаданных строится из URI запроса с заменой пути (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:124-139) 🟢.
10. PRM (`protectedResourceMetadata`): `resource = resourceUri`, `authorizationServer = issuer`, `scopes` = отсортированные уникальные значения `tool-scopes`, `resourceName = "arch-rag"`, `bearer_methods_supported = ["header"]` по умолчанию (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:33, 48-60) 🟢.

## Fluxos Alternativos

- **Разрешённый tool, но исключение внутри tool** — аспект: `decision = ERROR` (`TIMEOUT` для `QueryTimeoutException`), исключение пробрасывается, `completed` вызывается в `finally` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:43-59) 🟢.
- **Ошибка при открытии контекста/наблюдения** — `observation == null` → `completed` не вызывается, но исключение не подавляется 🟡 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:34-41, 49).
- **Аудит бросил `RuntimeException`** — `LOG.warn` с именем класса, поток продолжается (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:79-82) 🟢.
- **`principal` без JWT** — `anonymous` либо имя аутентификации (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:132-144) 🟢.

## Dependências

- `spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-aspectj`, `micrometer`, `slf4j` (access/mcp-server/pom.xml:44-50).
- `QueryTimeoutException` из `graph-query-core` для различения `TIMEOUT` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditAspect.java:3, 45).
- Хелпер тестов JWT: access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/TestJwt.java:33-60 (issuer `https://idp.test/realms/archrag`, audience `https://mcp.test/mcp`) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Fail-closed для tool без записи в таблице scope | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:30-36, 84-90 | 🟢 |
| Фильтр после `AuthorizationFilter` (аутентификация раньше scope) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:61 | 🟢 |
| Фильтр создан через `new`, не бином | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:34-36 | 🟢 |
| Отказ — без тела, только challenge-заголовок | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:124-132; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/McpSecurityTest.java:134 | 🟢 |
| Метаданные ресурса публичны (клиенты находят authorization server) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:43-45 | 🟢 |

## Estado Interno

Фильтр без состояния; `JsonMapper` — поле экземпляра (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:44). `ThreadLocal<ToolCallContext>` живёт на время одного вызова tool (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallContext.java:16).

## Observabilidade

См. [`../contracts.md`](../contracts.md) §4. Предупреждение фильтра: `tools/call отклонён: для tool нет записи в archrag.mcp.security.tool-scopes` (имя tool в нём не пишется) (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:86) 🟢.

## Riscos e Lacunas

- 🟡 Проверка пути — точное совпадение `getRequestURI()` с `/mcp` (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:40, 53): Q-15.
- 🟡 Отказ `401` (цепочка Spring Security до фильтра) не аудируется: Q-16. Отказы `400`/`413` аудируются: `ToolCallAuditor.rejected`, `decision` = `REJECTED_INVALID_REQUEST`/`REJECTED_TOO_LARGE`, `tool` в метриках `unknown`, в логе `null`; тело не пишется; `chain.doFilter` не вызывается, поэтому аспект второй записи не добавляет 🟢.
- 🟡 `initialize`/`tools/list` без scope: Q-02.
- 🟡 Нет сверки списка `@McpTool` и `tool-scopes` при старте: Q-04.
- 🟡 Маскирование аргументов эвристическое: Q-05.
