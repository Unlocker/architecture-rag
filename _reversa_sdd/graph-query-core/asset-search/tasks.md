# asset-search: задачи

## Pré-requisitos
- [ ] `QueryTemplate`, `ResultKind` (см. [`../tasks.md`](../tasks.md) T-04)
- [ ] Neo4j-схема: индекс `asset_text`, индекс `it_system_eam_code`, ключи `gid`/`SourceRecord` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:55-58`)

## Tarefas

- [ ] T-01: `FulltextQuery.escape`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:12-41`
  - Критерий готовности: AC-02; `FulltextQueryTest` (3 теста)
  - Уверенность: 🟢
- [ ] T-02: Allowlist'ы `SEARCHABLE_TYPES`, `RELATION_TYPES`, `EXACT_SCORE`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:16-22, 102-103`
  - Критерий готовности: AC-05
  - Уверенность: 🟢
- [ ] T-03: Шаблон `search_assets` (EXACT по `gid`/`sourceId`/`eamCode` + FULLTEXT; фильтры)
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:37-96`
  - Критерий готовности: AC-01, AC-03; `AssetTemplatesTest.searchTemplateIsValidAndRegistrable`
  - Уверенность: 🟢
- [ ] T-04: Шаблон `get_asset`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:112-141`
  - Критерий готовности: AC-04
  - Уверенность: 🟢
- [ ] T-05: Шаблон `asset_relations`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:150-168`
  - Критерий готовности: AC-05
  - Уверенность: 🟢
- [ ] T-06: Шаблон `explain_provenance`
  - Origin: `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:180-210`
  - Критерий готовности: AC-06
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `AssetTemplatesTest` — 3 теста (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplatesTest.java:14-40`)
- [ ] TT-02: `FulltextQueryTest` — 3 теста (`access/graph-query-core/src/test/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQueryTest.java:10-26`)
- [ ] TT-03: `SearchAssetsIT`, `GetAssetIT`, `ExplainProvenanceIT` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/`)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05 → T-06.

## Lacunas Pendentes (🔴)
Нет собственных 🔴; 🟡 Q-09 (FULLTEXT только для `ITSystem|Service`) — зафиксировать в Javadoc/ADR.
