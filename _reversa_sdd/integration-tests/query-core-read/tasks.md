# query-core-read: задачи

## Pré-requisitos
- [ ] Каркас модуля, образ Neo4j ([`../tasks.md`](../tasks.md) T-01..T-03, T-05 `AssetFixtures`); `access/graph-query-core`, `graph-projector`, `mcp-server` (tool `explain_provenance`) собираются

## Tarefas

- [ ] T-01: `GraphQueryCoreIT` — 11 сценариев защиты исполнителя (закрытые шаблоны, режим сессии, запрет сырых структур, API без Cypher, бюджеты, таймаут, глубина)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:34-229`
  - Критерий готовности: AC-01..AC-04
  - Уверенность: 🟢
- [ ] T-02: `GetAssetIT` — карточка и связи
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:40-175`
  - Критерий готовности: AC-05
  - Уверенность: 🟢
- [ ] T-03: `SearchAssetsIT` — ранжирование, фильтры, устойчивость к вводу
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:45-255`
  - Критерий готовности: AC-06
  - Уверенность: 🟢
- [ ] T-04: `TraceDependenciesIT` — trace, impact, цикл, фильтры, плотный граф
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/TraceDependenciesIT.java:40-393`
  - Критерий готовности: AC-07
  - Уверенность: 🟢
- [ ] T-05: `DeploymentCommitReadIT` и `RuntimeFootprintIT`
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/DeploymentCommitReadIT.java:34-173`, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/RuntimeFootprintIT.java:26-277`
  - Критерий готовности: AC-08
  - Уверенность: 🟢
- [ ] T-06: `ExplainProvenanceIT` — tool как бин, провенанс двух источников, tombstone
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ExplainProvenanceIT.java:62-274`
  - Критерий готовности: AC-09
  - Уверенность: 🟢
- [ ] T-07: 🔴 тесты нижней границы `maxDepth` и запрета обхода без верхней границы на уровне IT
  - Origin: [`../questions.md`](../questions.md) Q-33; матрица «ловушка 2» в [`../../code-analysis.md`](../../code-analysis.md) §Z1.4 (№ 2); `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:213-228`
  - Критерий готовности: `maxDepth = 0` и отрицательное значение отвергаются; шаблон с `*` не принимается
  - Уверенность: 🔴
- [ ] T-08: 🟡 усилить тавтологичные тесты (`fulltextFindsBySearchableTextAndExactStillWins`, `luceneSpecialCharactersDoNotFail`) и вставки в обход проектора (Q-16, Q-31)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:159, 231-236`
  - Критерий готовности: тест падает при регрессии, которую называет
  - Уверенность: 🟡

## Tarefas de Teste

- [ ] TT-01: каждый класс запускается отдельно (`-Dit.test=<Class>`), порядок методов не важен (`@BeforeEach` очищает граф)
  - Origin: `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GraphQueryCoreIT.java:57-60`
  - Критерий готовности: зелёный на машине с Docker; без Docker заявлять нельзя
  - Уверенность: 🟢

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06; T-07, T-08 после решения.

## Lacunas Pendentes (🔴)
Q-01, Q-33 — [`../questions.md`](../questions.md).
