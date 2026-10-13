# load-crosswalk: задачи

## Pré-requisitos
- [ ] `IdentityMapping.approve`, `Crosswalk`, `CrosswalkConflictException` (identity-resolution); `AdminAudit` + миграция `V3` (см. [`../tasks.md`](../tasks.md))

## Tarefas

- [ ] T-01: DTO `CrosswalkItem`, `CrosswalkResult`
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkItem.java:7-10`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkResult.java:12-19`
  - Критерий готовности: `approvedBy`/`approvedAt` отсутствуют в DTO запроса
  - Уверенность: 🟢
- [ ] T-02: `CrosswalkService.load/loadOne` (исходы, аудит на элемент, усечение `reason` до 200)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:35-79`
  - Критерий готовности: AC-01, AC-02, AC-04
  - Уверенность: 🟢
- [ ] T-03: эндпоинт `POST /admin/crosswalks` (1..1000, `operations.locked`)
  - Origin: `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:117-123`
  - Критерий готовности: AC-03
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `AdminControllerTest.crosswalksPassSubjectAsApprover`
- [ ] TT-02: `AdminEndpointIT.crosswalkAppliesValidItemAndReportsConflictAndInvalidPerItem`
- [ ] TT-03: 🔴 нет юнит-теста `CrosswalkService` (сбой посреди пакета, `null`-элемент)

## Ordem Sugerida
T-01 → T-02 → T-03.

## Lacunas Pendentes (🔴)
Q-10 — [`../questions.md`](../questions.md).
