# graph-browse: задачи

## Pré-requisitos
- [ ] `GraphReads`, `GraphReaderConfiguration` с реестром из 9 шаблонов (см. [`../tasks.md`](../tasks.md) T-03)
- [ ] `ApiExceptionHandler`, `InvalidRequestException`, `NotFoundException` (T-07)

## Tarefas

- [ ] T-01: Формы ответов `GraphResponses`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphResponses.java:7-69`
  - Критерий готовности: поля соответствуют [`../contracts.md`](../contracts.md) §1.3
  - Уверенность: 🟢
- [ ] T-02: `GET /api/graph/stats`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:55-65, 169-173`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-03: `GET /api/graph/search` (валидация, allowlist типов, бюджет по `limit`)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:67-102, 175-188, 199-209`
  - Критерий готовности: AC-02, AC-03
  - Уверенность: 🟢
- [ ] T-04: `GET /api/graph/nodes/{gid}` (карточка, нормализация, времена)
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:104-124, 190-197, 220-250`
  - Критерий готовности: AC-04, AC-05
  - Уверенность: 🟢
- [ ] T-05: `GET /api/graph/nodes/{gid}/neighborhood`
  - Origin: `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:126-167, 211-218`
  - Критерий готовности: AC-06, AC-07
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `ConsoleApiTest`: `statsReturnsCountsAndLastUpdate`, `searchReturnsHits`, `searchRejectsInvalidParameters`, `nodeCardHasPropertiesTimesAndSourceRecords`, `nodeCardUnknownGidIs404AndBadGidIs400WithoutEcho`, `neighborhoodReturnsNodesAndEdges`, `neighborhoodOverBudgetIsTruncated`, `neighborhoodRejectsDepthAboveTwoAndUnknownRelTypes` (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:203-353`)
- [ ] TT-02: `AdminConsoleIT` против Neo4j (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:180-280`)
- [ ] TT-03: тест 504 на `QueryTimeoutException` (предложение, Q-15)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05.

## Lacunas Pendentes (🔴)
Q-05 (500 без контракта) — решение за архитектором.
