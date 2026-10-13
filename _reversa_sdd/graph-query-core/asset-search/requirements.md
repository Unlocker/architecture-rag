# asset-search: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Потребитель в консоли: [`../../admin-console/graph-browse/requirements.md`](../../admin-console/graph-browse/requirements.md).

## Visão Geral

Четыре шаблона чтения активов: `search_assets` (точное совпадение и FULLTEXT), `get_asset` (карточка), `asset_relations` (связи 1 уровня), `explain_provenance` (записи источников). `FulltextQuery.escape` делает ввод пользователя безопасным для Lucene. Все четыре читают только метки из `SEARCHABLE_TYPES`.

## Regras de Negócio

- `search_assets`: точное совпадение по `gid` (score 1000), по `SourceRecord.sourceId` активной записи (score 1000), по `ITSystem.eamCode` (score 1000); затем FULLTEXT-индекс `asset_text` по `$text`, если он не `null` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:41-54, 20`).
- Совпадения одного узла объединяются: `max(score)`, `matchType = EXACT`, если был хотя бы один точный; сортировка `score DESC, gid`, `LIMIT $limit` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:55, 75, 81-82`).
- Возвращаются только узлы с `isCurrent = true` и меткой из `SEARCHABLE_TYPES` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:56-57`).
- Фильтры `$types` (метки узла) и `$environment` (код окружения) необязательны: `null` отключает фильтр; окружение проверяется через открытые связи `IN_ENVIRONMENT` для `Deployment`, `Service`, `ITSystem` (через `DECOMPOSED_INTO`/`HAS_DEPLOYMENT`) и `ComputeInstance` (через `RUNS_ON`) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:58-70`).
- `sources` в ответе — отсортированные уникальные коды источников активных записей 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:76-79`).
- `FulltextQuery.escape`: приводит к нижнему регистру (нейтрализует `AND/OR/NOT`), экранирует 19 спецсимволов Lucene обратной косой чертой, `strip`; без букв и цифр → `null` (FULLTEXT-ветка тогда не выполняется) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:14-15, 25-40`).
- `get_asset` не фильтрует по `isCurrent`: закрытый узел отдаётся с `isCurrent=false`; `sources` включает неактивные записи, активные первыми 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:106-110, 124-136`).
- `asset_relations`: связи в обе стороны, тип из `RELATION_TYPES`, сосед с меткой из `SEARCHABLE_TYPES`, только `rel.validTo IS NULL`; `direction` = `OUT`, если `startNode(rel) = n` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:154-163`).
- `explain_provenance`: все записи `SourceRecord`, утверждающие узел, с параметрами ребра `ASSERTS` (`authority`, `confidence`, `conflicts`); значений свойств в ответе нет; закрытый узел и неактивные записи отдаются 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:171-178, 184-205`).
- Время возвращается `toString(...)` 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:80, 120-122, 131`).
- Индекс `asset_text` создан только для `ITSystem|Service`; остальные метки (например `Repository`) находятся только точным совпадением; поведение подтверждено тестом, но в Javadoc шаблона и ADR не описано 🟡 (Q-09; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:57-58`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:186`).
- `get_asset` отдаёт `properties(n)` целиком, без пометки недоверенных текстов 🟡 (Q-05; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:119`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Поиск по точному ключу и по тексту с ранжированием EXACT выше FULLTEXT | Must | AC-01 |
| RF-02 | Нейтрализация операторов Lucene во вводе | Must | AC-02 |
| RF-03 | Фильтры `types`, `environment` | Should | AC-03 |
| RF-04 | Карточка актива, в том числе закрытого | Must | AC-04 |
| RF-05 | Связи 1 уровня только открытые и только из allowlist | Must | AC-05 |
| RF-06 | Происхождение без значений свойств | Must | AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Метки подставляются из `SEARCHABLE_TYPES`, значения — параметрами | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:22, 84-94, 214-217` | 🟢 |
| Безопасность | `explain_provenance` не раскрывает значений свойств | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:175-177` | 🟢 |
| Производительность | Фильтр окружения одним `EXISTS`+`UNION` (холодное планирование иначе 8–26 с) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:34-35, 59-70` | 🟢 |
| Безопасность | Недоверенный текст свойств отдаётся как есть (обработка у вызывающего) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:119` | 🟡 |

## Критерии приёмки

```gherkin
# AC-01 (SearchAssetsIT.exactGidMatchComesFirstAboveFulltextHits, nonCurrentNodeIsNotReturned, repositoryIsFoundOnlyByExactMatch; AssetTemplatesTest.searchTemplateIsValidAndRegistrable)
Дано активный ITSystem с gid G и именем "Payment"
Когда search_assets {query: G, text: "g", types: null, environment: null}
Тогда строка с matchType=EXACT и score=1000, узел с isCurrent=false не возвращается

# AC-02 (FulltextQueryTest.escapesLuceneOperatorsAndLowercases, returnsNullWhenNothingSearchableRemains, keepsPlainTerms; SearchAssetsIT.luceneSpecialCharactersDoNotFail, nonTextInputRunsOnlyExactBranches)
Дано ввод 'a&&b||c!d~e*f?g/h'
Когда FulltextQuery.escape
Тогда результат 'a\&\&b\|\|c\!d\~e\*f\?g\/h'
Дано ввод null, '   ' или '()*~'
Когда FulltextQuery.escape
Тогда null (FULLTEXT-ветка не выполняется)

# AC-03 (SearchAssetsIT.typeFilterRestrictsResults, environmentFilterFollowsRelationsAndExcludesOthers, closedStructuralEdgesDoNotMatchEnvironmentFilter)
Дано types=["Service"], environment="prod"
Когда search_assets
Тогда только Service с открытой цепочкой до Environment code=prod

# AC-04 (GetAssetIT.closedNodeIsReturnedNotCurrentWithInactiveRecord; AssetTemplatesTest.getAssetTemplatesAreValidAndRegistrable — шаблон не содержит "isCurrent = true")
Дано закрытый узел (isCurrent=false, deletedAt задан)
Когда get_asset {gid}
Тогда строка присутствует, isCurrent=false, sources включает неактивные записи

# AC-05 (AssetTemplatesTest.relationAllowlistExcludesServiceRelations)
Дано RELATION_TYPES
Когда проверяется состав
Тогда нет ASSERTS, OWNS_RECORD, PROCESSED, PART_OF, HOSTED_ON, и текст asset_relations не содержит ASSERTS

# AC-06 (ExplainProvenanceIT.twoSourcesShowMasterAndOpenCriticalityConflict, tombstonedSourceIsInactiveAndConflictIsCleared)
Дано узел с двумя записями источников и конфликтом
Когда explain_provenance {gid}
Тогда records содержит authority, confidence, conflicts (имена свойств), значений свойств нет
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01, RF-04..RF-06 | Must | Tools `search_assets`, `get_asset`, `explain_provenance` MCP и API консоли |
| RF-03 | Should | Необязательные фильтры |
| FULLTEXT по всем `SEARCHABLE_TYPES` | Won't | Индекс на двух метках (🟡 Q-09) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java` | `AssetTemplates` | 🟢 |
| `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java` | `FulltextQuery` | 🟢 |
