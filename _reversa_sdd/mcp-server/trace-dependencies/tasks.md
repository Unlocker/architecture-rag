# trace-dependencies: задачи

## Pré-requisitos
- [ ] Шаблоны `trace_downstream`, `trace_upstream`, `trace_impact`, `get_asset` зарегистрированы (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:51-84)
- [ ] `archrag.query.limits.*` и `archrag.query.stale-after` заданы (access/mcp-server/src/main/resources/application.yml:43-50)

## Tarefas

- [ ] T-01: Валидация аргументов (gid, mode, direction, environment, maxDepth, relationTypes) до запроса
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:111-138, 165-185
  - Критерий готовности: AC-06; `TraceDependenciesToolTest.rejectsBadArgumentsBeforeTouchingGraphWithoutEchoingInput`, `rejectsBadArgumentsForImpact`
  - Уверенность: 🟢
- [ ] T-02: Выбор шаблона, параметры и бюджет глубины
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:140-152
  - Критерий готовности: AC-01, AC-02
  - Уверенность: 🟢
- [ ] T-03: Проверка существования для пустого обхода
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:154-163
  - Критерий готовности: пустой обход существующего узла — не ошибка; неизвестный gid — `asset not found`
  - Уверенность: 🟢
- [ ] T-04: Сборка ответа: сортировка, дедупликация, `stale`, конфликты, `affected`
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesTool.java:37-50, 187-254
  - Критерий готовности: AC-03, AC-05
  - Уверенность: 🟢
- [ ] T-05: `TraceDependenciesResult` и вложенные record
  - Origin: access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesResult.java:25-94
  - Критерий готовности: структура из [`../contracts.md`](../contracts.md) §1.4
  - Уверенность: 🟢
- [ ] T-06: Шаблоны обхода и подстановка глубины
  - Origin: access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:17-123; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:125-138
  - Критерий готовности: `TraceDependenciesIT` на Neo4j; обход без верхней границы отвергается регистром
  - Уверенность: 🟢
- [ ] T-07: Scope-запись
  - Origin: access/mcp-server/src/main/resources/application.yml:34
  - Критерий готовности: AC-08
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `TraceDependenciesToolTest` (15 тестов) (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/TraceDependenciesToolTest.java:76-306)
- [ ] TT-02: `TraceDependenciesMcpTest` — список tool, вызов, отказ, ошибки, impact (access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/TraceDependenciesMcpTest.java:61-153)
- [ ] TT-03: `TraceDependenciesIT` и `F3AcceptanceIT` (S1-S8) (platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:137-393; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:254-400)
- [ ] TT-04: 🔴 нет теста на недетерминизм состава путей при `truncated` (документировано, не проверяется)

## Ordem Sugerida
1. T-06 → 2. T-05 → 3. T-01 → 4. T-02, T-03 → 5. T-04 → 6. T-07.

## Lacunas Pendentes (🔴 и 🟡)
- Q-07, Q-11 — [`../questions.md`](../questions.md); ограничения «связь без узла всегда stale» — в [`design.md`](design.md).
