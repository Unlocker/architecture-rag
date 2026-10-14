# find-runtime-footprint: задачи

## Pré-requisitos
- [ ] Шаблон `runtime_footprint` зарегистрирован (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:96-99)
- [ ] `archrag.query.stale-after` задан (access/mcp-server/src/main/resources/application.yml:43-44)

## Tarefas

- [ ] T-01: Валидация `systemGid`, подготовка параметров
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:62-72
  - Критерий готовности: AC-05; невалидный UUID не вызывает граф
  - Уверенность: 🟢
- [ ] T-02: Сборка дерева `service → deployment → compute → host`, `stale`, `warnings`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:79-150
  - Критерий готовности: AC-01, AC-02, AC-04; `FindRuntimeFootprintToolTest` (6 тестов)
  - Уверенность: 🟢
- [ ] T-03: Вспомогательные проекции (`sources`, `edge`, `repository`, `deployedFrom`, `seen`)
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintTool.java:194-256
  - Критерий готовности: AC-03
  - Уверенность: 🟢
- [ ] T-04: `FindRuntimeFootprintResult` и вложенные record
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintResult.java:24-98
  - Критерий готовности: структура из [`../contracts.md`](../contracts.md) §1.4
  - Уверенность: 🟢
- [ ] T-05: Шаблон `runtime_footprint`
  - Origin: access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:11-90
  - Критерий готовности: `RuntimeFootprintIT` зелёный на Neo4j
  - Уверенность: 🟢
- [ ] T-06: Scope-запись
  - Origin: access/mcp-server/src/main/resources/application.yml:30
  - Критерий готовности: AC-06
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `FindRuntimeFootprintToolTest` (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/FindRuntimeFootprintToolTest.java:101-229)
- [ ] TT-02: `FindRuntimeFootprintMcpTest` — схема входа, ошибка без эха, отказ без scope (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/FindRuntimeFootprintMcpTest.java:43-87)
- [ ] TT-03: `RuntimeFootprintIT`, `F2AcceptanceIT` (S1-S7), `DeployedFromIT`, `DeploymentCommitReadIT` (platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:107-277; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:247-440)
- [x] TT-04: тест `systemGid` с пробелами/верхним регистром (`FindRuntimeFootprintToolTest.upperCaseAndPaddedGidIsNormalizedLikeLowerCase`, Q-06)

## Ordem Sugerida
1. T-05 → 2. T-04 → 3. T-03 → 4. T-01, T-02 → 5. T-06.

## Lacunas Pendentes (🔴 и 🟡)
- Q-06 — [`../questions.md`](../questions.md).
