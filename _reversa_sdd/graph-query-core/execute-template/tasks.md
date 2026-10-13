# execute-template: задачи

## Pré-requisitos
- [ ] `QueryTemplateRegistry`, `ResultBudget`, `BudgetedCollector` реализованы (см. [`../tasks.md`](../tasks.md) T-02..T-04)
- [ ] Neo4j с учёткой reader доступен приложению (драйвер создаёт приложение)

## Tarefas

- [ ] T-01: Проверка ID и набора параметров (`Unknown template`, `Unknown parameter`, `Missing parameter`)
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:61-65, 133-146`
  - Критерий готовности: AC-02; значения параметров не попадают в текст ошибки
  - Уверенность: 🟢
- [ ] T-02: Вердикт `EXPLAIN` с кэшем по ID и необратимым закрытием
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:42-43, 76, 99-124`
  - Критерий готовности: AC-03; сбой планирования не закрывает шаблон
  - Уверенность: 🟢
- [ ] T-03: Рабочая транзакция `executeRead` с таймаутом и `limit = maxRows+1`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:69-87`
  - Критерий готовности: AC-01; `GraphQueryCoreIT.readSessionConfigRejectsWritesAtDatabaseLevel`
  - Уверенность: 🟢
- [ ] T-04: Запрет `Node`/`Relationship`/`Path` в значениях строки
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:149-188`
  - Критерий готовности: AC-04 на пяти формах возврата
  - Уверенность: 🟢
- [ ] T-05: Перевод таймаута в `QueryTimeoutException`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:38, 94-96, 126-131`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTimeoutException.java:4-17`
  - Критерий готовности: AC-05
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `GraphQueryCoreIT`: `writingTemplatesFailExplainCheckOnFirstExecuteAndStayClosed`, `executorConstructionDoesNotTouchDatabase`, `readSessionConfigRejectsWritesAtDatabaseLevel`, `nodesRelationshipsAndPathsInResultAreForbidden`, `publicApiHasNoMethodAcceptingCypher`, `unknownTemplateAndBadParametersFailBeforeDatabase`, `timeoutGivesQueryTimeoutException` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:79-212`)
- [ ] TT-02: Unit-тест исполнителя с заглушкой драйвера (предложение, Q-07)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05.

## Lacunas Pendentes (🔴)
Q-01 (ADR 0005 ↔ `EXPLAIN`) — решение за архитектором.
