# identity-audit: задачи

## Pré-requisitos
- [ ] Таблицы `source_conflict`, `identity_candidate`, `admin_audit` созданы миграциями ingestion; `SELECT` выдан роли `archrag_console_ro` (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:15`)
- [ ] `Where`/`page` реализованы (см. [`../tasks.md`](../tasks.md) T-05)

## Tarefas

- [ ] T-01: Выборки `conflicts` и `candidates`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:171-228`
  - Критерий готовности: AC-01, AC-02
  - Уверенность: 🟢
- [ ] T-02: Выборка `audit` с усечением больших jsonb
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:232-266`
  - Критерий готовности: AC-04, AC-05
  - Уверенность: 🟢
- [ ] T-03: `IdentityController` (токены, UUID)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:16-75`
  - Критерий готовности: AC-01..AC-03
  - Уверенность: 🟢
- [ ] T-04: `AuditController`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java:13-40`
  - Критерий готовности: AC-04; `from < to`
  - Уверенность: 🟢
- [ ] T-05: Формы `Conflict`, `Candidate`, `Audit`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:48-94`
  - Критерий готовности: поля соответствуют [`../contracts.md`](../contracts.md) §1.3
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `SyncConsoleIT`: `conflictsAndCandidatesAreListedAndFiltered`, `auditListsOperationsWithRequestParameters`, `oversizedAuditResultIsNotReturned`, `consoleRoleCannotWriteOrReadOtherTables` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:183, 240, 347, 367`)
- [ ] TT-02: `SyncApiTest.sizeAboveLimitAndNegativePageAreRejected` (охватывает `/api/identity/*` и `/api/audit`) (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/SyncApiTest.java:87`)
- [ ] TT-03: unit-тест `IdentityController.token` на границы шаблона `[A-Za-z_]{1,64}` (предложение)

## Ordem Sugerida
T-05 → T-01 → T-02 → T-03 → T-04.

## Lacunas Pendentes (🔴)
Нет собственных 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 3)]; 🟡 Q-16 (маскирование `admin_audit`). Вне модуля: Р-4, П-22 (закрытие кандидатов и конфликтов).
