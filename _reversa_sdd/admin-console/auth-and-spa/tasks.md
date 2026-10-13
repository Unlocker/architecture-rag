# auth-and-spa: задачи

## Pré-requisitos
- [ ] Бандл `admin-ui` собран и лежит в `META-INF/resources` зависимости (`access/admin-console/pom.xml:20-23`)
- [ ] Keycloak: публичный клиент `archrag-admin-console` (PKCE S256), scope `architecture.admin`, audience `urn:archrag:console` (`platform/docker/keycloak/archrag-realm.json:77-90`)

## Tarefas

- [ ] T-01: `ConsoleProperties` (обязательный `resource-uri`, значения по умолчанию)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleProperties.java:13-28`
  - Критерий готовности: AC-03
  - Уверенность: 🟢
- [ ] T-02: Цепочка безопасности (JWT, scope, fail-closed, STATELESS, CSRF off, CSP, nosniff)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:21-67`
  - Критерий готовности: AC-01, AC-02, AC-06, AC-08
  - Уверенность: 🟢
- [ ] T-03: `ConsoleConfigController` (`/console-config.json`)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:8-31`
  - Критерий готовности: AC-05
  - Уверенность: 🟢
- [ ] T-04: `SpaWebConfiguration` (статика, fallback, перехватчик на `/api/**`)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:21-71`
  - Критерий готовности: AC-04
  - Уверенность: 🟢
- [ ] T-05: Аудит чтений (`ReadAuditContext`, `ReadAuditInterceptor`)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditContext.java:13-58`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java:22-69`
  - Критерий готовности: AC-07
  - Уверенность: 🟢
- [ ] T-06: Конфигурация `application.yml` для OIDC (`issuer-uri`, `audiences`, `add-mappings=false`)
  - Origin: `access/admin-console/src/main/resources/application.yml:6-22`
  - Критерий готовности: AC-01..AC-03
  - Уверенность: 🟢
- [ ] T-07: Аудит отказов 401/403 — предложение, закрывает часть Q-06
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:42-45` (сегодня только `/api/**` после фильтров)
  - Критерий готовности: решение архитектора
  - Уверенность: 🟡

## Tarefas de Teste

- [ ] TT-01: `ConsoleApiTest` (безопасность, статика, конфигурация, аудит) (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:94-203, 367-391`)
- [ ] TT-02: `ConsoleStartupTest` (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleStartupTest.java:15-40`)
- [ ] TT-03: `TestJwt` — тестовый JWKS и выпуск токенов (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/TestJwt.java:33-53`)
- [ ] TT-04: `SyncConsoleIT.withoutScopeApiIsForbiddenAndWithoutTokenUnauthorized`, `sqlReadsAreRecordedInReadAudit` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:212, 221`)
- [ ] TT-05: `platform/docker/console-smoke.sh` — Authorization Code + PKCE на curl (проверка на стенде; не запускалось при составлении спецификации)

## Ordem Sugerida
T-01 → T-06 → T-02 → T-04 → T-03 → T-05 (T-07 по решению Q-06).

## Lacunas Pendentes (🔴)
Q-06 (аудит отказов и retention), Q-01 (loopback) — решение за архитектором/владельцем.
