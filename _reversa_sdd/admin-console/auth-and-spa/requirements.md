# auth-and-spa: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). ADR [0026](../../../docs/adr/0026-admin-console-readonly-loopback.md).

## Visão Geral

Вход оператора и безопасность API: консоль — OAuth2 resource server (JWT, issuer, обязательный audience, scope `architecture.admin` на `/api/**`), раздаёт статику SPA с fallback на `index.html`, отдаёт публичный `console-config.json` для OIDC-логина браузера (Authorization Code + PKCE на стороне UI), добавляет заголовки безопасности и аудитирует чтения. Всё, что не перечислено, закрыто.

## Regras de Negócio

- JWT проверяется по `issuer-uri` (`ARCHRAG_OIDC_ISSUER_URI`) и `audiences` = `archrag.console.resource-uri` (`ARCHRAG_CONSOLE_RESOURCE_URI`); токен с другим audience → 401 🟢 (`access/admin-console/src/main/resources/application.yml:10-15`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:108`).
- Без `ARCHRAG_CONSOLE_RESOURCE_URI` (пусто или `null`) приложение не стартует: `ConsoleProperties` бросает `IllegalArgumentException` с именем свойства 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleProperties.java:22-27`; тесты `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleStartupTest.java:15, 33`).
- `/api/**` → authority `SCOPE_architecture.admin` (любой HTTP-метод); без токена 401, без scope 403 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:25, 44-45`; тесты `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:94, 100`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:212`).
- Публично без токена: `GET /actuator/health/**`, `GET /console-config.json`, `GET /**` (статика и маршруты SPA) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:42-43, 49-50`).
- `/actuator/**` (кроме health) → `denyAll`; любой другой запрос (не-`GET` вне `/api/**`) → `denyAll`; `DispatcherType.ERROR` → `permitAll` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:40-41, 46-52`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:129`).
- Сессии `STATELESS`, CSRF отключён (токен в заголовке `Authorization`) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:32-33`).
- Заголовки: `X-Content-Type-Options: nosniff` и CSP `default-src 'self'; connect-src 'self' <origin issuer>; frame-ancestors 'none'; base-uri 'self'; object-src 'none'` на всех ответах (статика и API); в `connect-src` — origin (схема+authority) issuer, а не полный URI 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:34-37, 57-66`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:146`).
- `GET /console-config.json` → `{issuer, clientId, scope}` без секретов; `clientId` по умолчанию `archrag-admin-console`, `scope` — `openid architecture.admin` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:18-30`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleProperties.java:14-17`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:160`).
- Статика из `classpath:/META-INF/resources/` (бандл `admin-ui`) с `Cache-Control: no-cache`; существующий файл отдаётся как есть 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:23, 32-40, 50-54`).
- SPA fallback на `index.html`: только для путей без расширения файла и не `api/**`, не `actuator/**`; отсутствующий файл с расширением → 404, неизвестный `/api/...` с токеном → 404 (не HTML) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:56-70`; тесты `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:121, 172`).
- Бандл `index.html` не содержит inline-скриптов и стилей (CSP `default-src 'self'` их запрещает) 🟢 (тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:183`).
- Аудит чтений: перехватчик на `/api/**` пишет одну запись `console read` с `sub`, `endpoint` (шаблон маршрута), `templates`, `rows`, `truncated`, `status`, `durationMs`; SQL-чтения учитываются как `sql:<выборка>`; токен, query и значения path-переменных не пишутся 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java:25, 30-58`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditContext.java:34-45`; тесты `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:367, 391`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:221`).
- `sub` берётся из JWT; без аутентификации — `anonymous` или имя `Authentication` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java:60-68`).
- Запросы, отклонённые Spring Security (401/403), в аудит чтений не попадают 🟡 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:42-45`; Q-06).
- Любой пользователь realm получает токен консоли; проверки `azp`/роли в консоли нет 🟡 (Q-02, Q-13).
- Токен в браузере — только в памяти (реализация в `admin-ui`) 🟡 (Q-14).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | 401 без токена, 403 без scope, 401 при чужом audience | Must | AC-01, AC-02 |
| RF-02 | Отказ запуска без audience | Must | AC-03 |
| RF-03 | Раздача SPA и fallback | Must | AC-04 |
| RF-04 | Публичная конфигурация OIDC без секретов | Must | AC-05 |
| RF-05 | Заголовки CSP/nosniff на статике и API | Must | AC-06 |
| RF-06 | Аудит чтений без токена и параметров | Must | AC-07 |
| RF-07 | Закрыть всё прочее | Must | AC-08 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | fail-closed: `anyRequest().denyAll()` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:51-52` | 🟢 |
| Безопасность | Секретов в `console-config.json` нет (только issuer, публичный clientId, scope) | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:18` | 🟢 |
| Безопасность | PKCE S256, без direct grants, scope `architecture.admin` — optional у клиента | `platform/docker/keycloak/archrag-realm.json:77-90` | 🟢 |
| Эксплуатация | Health доступен без токена для healthcheck контейнера | `platform/docker/docker-compose.yml:300-305` | 🟢 |
| Наблюдаемость | Аудит — лог, не таблица; retention не определён | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java:28` | 🟡 |

## Критерии приёмки

```gherkin
# AC-01 (ConsoleApiTest.apiWithoutTokenIsUnauthorized, apiWithoutAdminScopeIsForbidden; SyncConsoleIT.withoutScopeApiIsForbiddenAndWithoutTokenUnauthorized; SyncApiTest.everyRouteRequiresTokenAndAdminScope)
Дано любой маршрут /api/**
Когда запрос без токена
Тогда 401 и ни контроллер, ни исполнитель, ни репозиторий не вызваны
Когда запрос с токеном только architecture.read
Тогда 403

# AC-02 (ConsoleApiTest.apiWithForeignAudienceIsUnauthorized)
Дано токен со scope architecture.admin и audience urn:archrag:other
Когда GET /api/graph/stats
Тогда 401

# AC-03 (ConsoleStartupTest.blankResourceUriFailsStartup, propertiesRecordRejectsMissingResourceUri)
Дано archrag.console.resource-uri пуст или не задан
Когда запускается приложение
Тогда ошибка запуска с упоминанием archrag.console.resource-uri

# AC-04 (ConsoleApiTest.staticFilesAndSpaRoutesArePublicAndFallBackToIndex, unknownApiPathWithScopeIsNotFoundAndNotHtml)
Дано собранный бандл admin-ui
Когда GET / и GET /graph/nodes/abc без токена
Тогда 200 и index.html с id="root" и подключённым бандлом
Когда GET /missing.js без токена
Тогда 404
Когда GET /api/unknown с токеном architecture.admin
Тогда 404 и тело не HTML

# AC-05 (ConsoleApiTest.consoleConfigIsPublicAndHasNoSecrets)
Дано публичный запрос
Когда GET /console-config.json
Тогда 200 и ровно поля issuer, clientId = archrag-admin-console, scope (содержит architecture.admin)

# AC-06 (ConsoleApiTest.securityHeadersArePresentOnStaticAndApi, bundleIndexIsServedWithCspAndHasNoInlineScriptsOrStyles)
Дано запрос к / и к /api/graph/stats
Когда ответ получен
Тогда X-Content-Type-Options = nosniff, CSP содержит default-src 'self', connect-src 'self' https://idp.test, frame-ancestors 'none'

# AC-07 (ConsoleApiTest.auditRecordsSubEndpointTemplatesAndNeverTheToken, auditDoesNotLogSearchQuery; SyncConsoleIT.sqlReadsAreRecordedInReadAudit)
Дано GET /api/graph/search?q=secret-search-text с валидным токеном
Когда запрос обработан
Тогда одна запись аудита, в ключах нет secret-search-text и токена
Дано GET /api/sync/events?source=eam&size=2
Когда запрос обработан
Тогда endpoint = "GET /api/sync/events", templates = ["sql:inbox_event"], rows = 2

# AC-08 (ConsoleApiTest.otherActuatorEndpointsAndWritesAreDenied, healthIsPublic)
Дано GET /actuator/info без токена
Когда запрос
Тогда 401; GET /actuator/env с admin-токеном — не 2xx; POST /api/graph/stats с admin-токеном — не 2xx
Дано GET /actuator/health без токена
Когда запрос
Тогда 200
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-07 | Must | ADR 0026, критерий готовности 6 PoC (read-only, аутентификация) |
| Проверка роли/`azp` в консоли | Won't | Принято владельцем 2026-10-08 (П-9) |
| Аудит отказов 401/403 в консоли | Won't | Нет в коде (Q-06) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java` | `ConsoleSecurityConfiguration` | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleProperties.java`, `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java` | конфигурация OIDC | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java` | статика, fallback, перехватчик | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditContext.java`, `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java` | аудит чтений | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/AdminConsoleApplication.java` | точка входа | 🟢 |
