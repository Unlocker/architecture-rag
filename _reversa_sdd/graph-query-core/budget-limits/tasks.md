# budget-limits: задачи

## Pré-requisitos
- [ ] Jackson 3 в зависимостях модуля (`access/graph-query-core/pom.xml:26-30`)

## Tarefas

- [ ] T-01: `ResultBudget` с инвариантом и `clampTo`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java:17-38`
  - Критерий готовности: AC-01, AC-05; `ResultBudgetTest` зелёный
  - Уверенность: 🟢
- [ ] T-02: `QueryLimits` с валидацией через бюджет и `asBudget`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java:14-26`
  - Критерий готовности: непозитивный потолок отвергается при создании
  - Уверенность: 🟢
- [ ] T-03: `ResultKind` с выбором потолка строк
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultKind.java:4-14`
  - Критерий готовности: `NODES`→`maxNodes`, `PATHS`→`maxPaths`
  - Уверенность: 🟢
- [ ] T-04: `BudgetedCollector` (ленивое чтение, `maxRows+1`, порог байт)
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:17-67`
  - Критерий готовности: AC-02, AC-03; `BudgetedCollectorTest` (6 тестов)
  - Уверенность: 🟢
- [ ] T-05: Подстановка глубины `render(depth, ceiling)`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:125-138`
  - Критерий готовности: AC-04
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `ResultBudgetTest` — 3 теста (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/ResultBudgetTest.java:14-26`)
- [ ] TT-02: `BudgetedCollectorTest` — 6 тестов (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollectorTest.java:35-73`)
- [ ] TT-03: `GraphQueryCoreIT.rowBudgetTruncates`, `byteBudgetTruncates`, `requestedBudgetIsClampedToConfiguredCeiling`, `traversalDepthIsBoundedByMaxDepth` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:161-214`)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05.

## Lacunas Pendentes (🔴)
Q-03 (усечение по глубине не отражается в `truncated`) — решение за архитектором.
