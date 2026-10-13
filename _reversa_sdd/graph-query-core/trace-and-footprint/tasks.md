# trace-and-footprint: задачи

## Pré-requisitos
- [ ] `AssetTemplates.SEARCHABLE_TYPES` (база для `TRACE_LABELS`), `QueryTemplate`, `ResultKind`
- [ ] Граф с темпоральными связями (`validFrom`, `validTo`, `assertedBy*`) — ADR 0028

## Tarefas

- [ ] T-01: Allowlist'ы `TRACE_LABELS`, `TRACE_RELATION_TYPES`, `IMPACT_RELATION_TYPES`, `IMPACT_TARGET_LABELS`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:17-27, 97-102`
  - Критерий готовности: `allowlistIsExactlyTheTraceRelations`, `impactAllowlistIsExactAndSubsetOfTraceAllowlist`
  - Уверенность: 🟢
- [ ] T-02: Шаблоны `trace_downstream` / `trace_upstream` с общим фильтром и проекцией
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:29-95`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-03: Шаблон `trace_impact`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplates.java:112-123`
  - Критерий готовности: AC-02
  - Уверенность: 🟢
- [ ] T-04: Шаблон `runtime_footprint`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplates.java:29-90`
  - Критерий готовности: AC-03; `RuntimeFootprintIT`, `DeploymentCommitReadIT`
  - Уверенность: 🟢
- [ ] T-05: Консольные шаблоны сводки и последних прогонов
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:19-97, 159-179`
  - Критерий готовности: AC-04 (`statsCoverEveryAllowlistedLabelAndRelationType`)
  - Уверенность: 🟢
- [ ] T-06: Шаблоны окрестности `console_neighborhood_nodes` / `console_neighborhood_edges` и `ConsoleTemplates.ALL`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplates.java:105-152, 181-189`
  - Критерий готовности: AC-04 (`neighborhoodNodesUsesBoundedDepthPlaceholderOnly`)
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `DependencyTemplatesTest` — 9 тестов (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/DependencyTemplatesTest.java:18-90`)
- [ ] TT-02: `RuntimeTemplatesTest` — 6 тестов (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/RuntimeTemplatesTest.java:15-54`)
- [ ] TT-03: `ConsoleTemplatesTest` — 4 теста (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/ConsoleTemplatesTest.java:18-56`)
- [ ] TT-04: `TraceDependenciesIT`, `RuntimeFootprintIT`, `DeploymentCommitReadIT`, `AdminConsoleIT` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/`)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06.

## Lacunas Pendentes (🔴)
Q-03 (усечение по глубине) — решение за архитектором; Q-04 (порядок путей) — 🟡.
