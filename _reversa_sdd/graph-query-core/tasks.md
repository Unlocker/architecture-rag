# access/graph-query-core: задачи

> Задачи use case: [`execute-template/`](execute-template/tasks.md), [`budget-limits/`](budget-limits/tasks.md), [`template-registry/`](template-registry/tasks.md), [`asset-search/`](asset-search/tasks.md), [`trace-and-footprint/`](trace-and-footprint/tasks.md). Здесь — сквозные части модуля.

## Pré-requisitos
- [ ] `contracts/canonical-model` собирается (`access/graph-query-core/pom.xml:17-31`)
- [ ] Neo4j Java Driver и Jackson 3 (`tools.jackson`) в BOM корневого `pom.xml` (версий в модуле нет)
- [ ] Схема Neo4j применена кодом `Neo4jSchema` (индекс `asset_text`, свойства `isCurrent`, `validTo`) — для IT

## Tarefas

- [ ] T-01: `pom.xml` модуля: три зависимости (`canonical-model`, `neo4j-java-driver`, `jackson-databind`), родитель `access`
  - Origin: `access/graph-query-core/pom.xml:7-31`
  - Критерий готовности: модуль собирается без Spring и без зависимости на `ingestion/*`, `adapters/*`
  - Уверенность: 🟢
- [ ] T-02: Типы бюджета и результата: `ResultKind`, `ResultBudget`, `QueryLimits`, `QueryResult`, `QueryTimeoutException`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultKind.java:4-14`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/ResultBudget.java:17-38`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryLimits.java:14-26`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryResult.java:19-29`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTimeoutException.java:4-17`
  - Критерий готовности: `ResultBudgetTest` (3 теста) зелёный
  - Уверенность: 🟢
- [ ] T-03: `BudgetedCollector`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollector.java:17-67`
  - Критерий готовности: `BudgetedCollectorTest` (6 тестов) зелёный; из итератора читается не больше `maxRows+1`
  - Уверенность: 🟢
- [ ] T-04: `QueryTemplate` и `QueryTemplateRegistry`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:21-139`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:17-61`
  - Критерий готовности: `QueryTemplateRegistryTest` (13 тестов) зелёный
  - Уверенность: 🟢
- [ ] T-05: `GraphQueryExecutor`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:32-189`
  - Критерий готовности: AC-05, AC-06 (`requirements.md`); `GraphQueryCoreIT` зелёный (запускается в `platform/integration-tests`)
  - Уверенность: 🟢
- [ ] T-06: Классы шаблонов `AssetTemplates`, `DependencyTemplates`, `RuntimeTemplates`, `ConsoleTemplates` и `FulltextQuery`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:13-218`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:15-131`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:8-93`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:16-203`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:12-41`
  - Критерий готовности: все 14 шаблонов проходят `QueryTemplateRegistry.of` вместе; тесты `*TemplatesTest`, `FulltextQueryTest` зелёные
  - Уверенность: 🟢
- [ ] T-07: Unit-тест `GraphQueryExecutor` без Neo4j (заглушка драйвера) — закрывает Q-07
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:79-229` (сегодня только IT)
  - Критерий готовности: `mvn -pl access/graph-query-core verify` проверяет clamp → render → вердикт → усечение
  - Уверенность: 🟡 (задача-предложение, не реализована)

## Tarefas de Teste

- [ ] TT-01: `QueryTemplateRegistryTest` — 13 тестов (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistryTest.java:20-112`)
- [ ] TT-02: `ResultBudgetTest` — 3 теста (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/ResultBudgetTest.java:14-26`)
- [ ] TT-03: `BudgetedCollectorTest` — 6 тестов (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/BudgetedCollectorTest.java:35-73`)
- [ ] TT-04: `AssetTemplatesTest` — 3, `DependencyTemplatesTest` — 9, `RuntimeTemplatesTest` — 6, `ConsoleTemplatesTest` — 4, `FulltextQueryTest` — 3 (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/`)
- [ ] TT-05: `GraphQueryCoreIT` — 11 тестов против Neo4j в Testcontainers (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:79-214`); профиль `integration-tests`
- [ ] TT-06: `SearchAssetsIT`, `GetAssetIT`, `ExplainProvenanceIT`, `TraceDependenciesIT`, `RuntimeFootprintIT`, `DeploymentCommitReadIT` — шаблоны против реального графа (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/`)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-06 → T-05 → T-07.

## Lacunas Pendentes (🔴)
Q-01 (ADR 0005 ↔ `EXPLAIN`), Q-02 (проверка allowlist в ядре), Q-03 (усечение по глубине), Q-10 (SLO) — решение за архитектором.
