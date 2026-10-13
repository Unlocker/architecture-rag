# access/admin-console: задачи

> Задачи use case: [`graph-browse/`](graph-browse/tasks.md), [`sync-journal/`](sync-journal/tasks.md), [`identity-audit/`](identity-audit/tasks.md), [`auth-and-spa/`](auth-and-spa/tasks.md). Здесь — сквозные части.

## Pré-requisitos
- [ ] Модули `graph-query-core` и `admin-ui` собираются (`access/admin-console/pom.xml:20-28`)
- [ ] PostgreSQL с миграциями ingestion (включая `V6`), Neo4j с учёткой reader, OIDC issuer доступны
- [ ] Переменные окружения из [`contracts.md`](contracts.md) §4 заданы (секреты — Docker secrets)

## Tarefas

- [ ] T-01: `pom.xml`: зависимости `admin-ui`, `graph-query-core`, driver, webmvc, jdbc, postgresql (runtime), actuator, resource-server; `repackage` с классификатором `exec`
  - Origin: `access/admin-console/pom.xml:19-82`
  - Критерий готовности: обычный jar остаётся основным артефактом (на него зависит `platform/integration-tests`); exec-jar собирается для образа
  - Уверенность: 🟢
- [ ] T-02: `application.yml` (issuer, audience, reader, console-pg, лимиты, graceful shutdown, health-only)
  - Origin: `access/admin-console/src/main/resources/application.yml:1-58`
  - Критерий готовности: AC-10 (`requirements.md`); `ConsoleStartupTest.configurationHasReadOnlyCredentialsOnly`
  - Уверенность: 🟢
- [ ] T-03: `GraphReaderProperties`, `GraphReaderConfiguration`, `GraphReads`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReaderProperties.java:14-35`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReaderConfiguration.java:24-57`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReads.java:19-46`
  - Критерий готовности: драйвер один на приложение, закрывается при остановке; реестр содержит 9 шаблонов; каждый вызов графа попадает в аудит запроса
  - Уверенность: 🟢
- [ ] T-04: `ConsolePgProperties`, `ConsolePgConfiguration` (read-only пул, `Clock`, `JdbcClient`)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsolePgProperties.java:12-19`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsolePgConfiguration.java:17-48`
  - Критерий готовности: `SyncConsoleIT.connectionsDefaultToReadOnlyTransactions`; старт без PostgreSQL
  - Уверенность: 🟢
- [ ] T-05: `ConsoleReadRepository`, `Rows`, `Page`, `PageRequest`, `SyncSources`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:31-366`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:10-117`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/PageRequest.java:8-29`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:13-63`
  - Критерий готовности: AC `sync-journal`, `identity-audit`; в запросах только константные фрагменты и именованные параметры
  - Уверенность: 🟢
- [ ] T-06: `ReadAuditContext`, `ReadAuditInterceptor`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditContext.java:13-58`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java:22-69`
  - Критерий готовности: AC-08 (`requirements.md`); `ConsoleApiTest.auditDoesNotLogSearchQuery`
  - Уверенность: 🟢
- [ ] T-07: `ApiExceptionHandler` и исключения `InvalidRequestException`, `NotFoundException`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/ApiExceptionHandler.java:12-42`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/InvalidRequestException.java:4-9`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/NotFoundException.java:4-9`
  - Критерий готовности: AC-09; ввод не эхоится в тексте ошибки
  - Уверенность: 🟢
- [ ] T-08: Миграция роли `V6` (владелец — `ingestion-service`)
  - Origin: `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:6-16`
  - Критерий готовности: `SyncConsoleIT.consoleRoleCannotWriteOrReadOtherTables`, `migrationIsIdempotentForExistingRole`
  - Уверенность: 🟢
- [ ] T-09: Compose-сервис `admin-console` (loopback, сети, секреты, healthcheck)
  - Origin: `platform/docker/docker-compose.yml:274-305`
  - Критерий готовности: `platform/docker/console-smoke.sh` (401 без токена, 403 без scope, 200 на `/api/graph/stats`) 🟡
  - Уверенность: 🟢
- [ ] T-10: Привязка к loopback на уровне приложения (`server.address`) — предложение, закрывает Q-01
  - Origin: `access/admin-console/src/main/resources/application.yml:43-44`
  - Критерий готовности: решение архитектора; сейчас не реализовано
  - Уверенность: 🟡

## Tarefas de Teste

- [ ] TT-01: `ConsoleApiTest` — 20 тестов безопасности, статики, графового API и аудита (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:94-391`)
- [ ] TT-02: `SyncApiTest` — 8 тестов API журнала (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java:77-176`)
- [ ] TT-03: `ConsoleStartupTest` — 3 теста (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleStartupTest.java:15-40`)
- [ ] TT-04: `AdminConsoleIT` — граф против Neo4j (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:180-280`); `SyncConsoleIT` — журнал против PostgreSQL и роли (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:183-367`)
- [ ] TT-05: Тест на 504 `query_timeout` и на 500 при `Neo4jException` (предложение, Q-05, Q-15)

## Ordem Sugerida
T-01 → T-02 → T-04 → T-03 → T-05 → T-06 → T-07 → T-08 → T-09 (T-10 по решению Q-01).

## Lacunas Pendentes (🔴)
Q-01 (loopback только в Compose), Q-03 (`replayed_at`), Q-04 (`eventId` с `/`), Q-05 (500 без контракта), Q-06 (аудит чтений — только лог), Q-12 (SLO и лимиты) — решение за архитектором/владельцем.
