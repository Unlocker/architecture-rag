# search-get-asset: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §1.3-1.5.

## Visão Geral

Два tools для навигации агента по витрине. `search_assets` находит кандидатов: точное совпадение по `gid`, `sourceId` (и `ITSystem.eamCode`), затем полнотекстовый поиск по имени и описанию только для `ITSystem` и `Service`. `get_asset` по `gid` возвращает карточку актива: свойства, времена наблюдения, записи источников и действующие связи 1 уровня. `gid` соседей можно передавать в следующие вызовы (`get_asset`, `trace_dependencies`, `find_runtime_footprint`, `explain_provenance`).

## Regras de Negócio

### search_assets

- Ввод `query` обрезается `strip`; пустой → ошибка; длина > 200 символов → ошибка 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:21-23, 63-69).
- `limit` по умолчанию 10, допустим 1..50; вне диапазона → ошибка `limit must be in 1..50` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:21-22, 70-73).
- Реальный потолок строк = `min(limit, archrag.query.limits.max-nodes)`; остальные поля бюджета — потолки конфигурации 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:84-90; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsToolTest.java:101-111).
- Фильтр `types` допускает только `ITSystem, Service, Repository, Team, Environment, Deployment, ComputeInstance`; другая метка → `Unknown asset type; allowed: ...` (метки подставляются из allowlist, не из ввода) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:95-107; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:16-17).
- Пустой/`null` `types` = без фильтра; пустой/пробельный `environment` = без фильтра 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:74-75, 95-98).
- В шаблон передаются все четыре параметра (`query`, `text`, `types`, `environment`), включая `null`: исполнитель требует полный набор ключей 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:77-82; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:95).
- `text` = `FulltextQuery.escape(query)`: спецсимволы Lucene экранируются, регистр понижается; если текста не осталось, `text = null` и выполняются только точные ветки 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:11-28; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:231-246).
- Возвращаются только актуальные активы (`isCurrent`); точные совпадения идут первыми (score `1000`, `matchType=EXACT`), затем FULLTEXT по убыванию score; узел, найденный обоими способами, приходит один раз с типом `EXACT` 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:26-37, 19-20; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:139-179, 225-229).
- Полнотекстовый поиск — только для `ITSystem` и `Service`; остальные типы находятся лишь точным совпадением 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:47-50; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:186-190).
- `truncated=true`, если кандидатов больше `limit` либо результат обрезан бюджетом 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsResult.java:5-11; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SearchAssetsIT.java:215-223).

### get_asset

- `gid` обязателен и должен быть UUID; нормализуется `UUID.fromString(strip).toString()`; ошибка не содержит значения 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:94-101).
- Закрытый актив возвращается (`isCurrent=false`, `deletedAt`), фильтра `isCurrent` в шаблоне карточки нет 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:105-111; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:124-135).
- Нет строки → `asset not found` (gid в тексте отсутствует) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:68-71; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:137-145).
- `properties` — свойства узла без служебных (`gid`, `isCurrent`, `firstSeenAt`, `lastSeenAt`, `deletedAt`); временные значения приводятся к ISO-8601 UTC, списки обходятся рекурсивно 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:24-26, 103-125; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetToolTest.java:125-141).
- `sources` включает неактивные записи (активные первыми); `authority` — `MASTER` или `SUPPLEMENTARY` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetResult.java:17, 33-46).
- `includeRelations` по умолчанию `true`; при `false` второй шаблон не выполняется и `relations=[]` 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:74-80; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetToolTest.java:114-124).
- Связи 1 уровня — в обе стороны (`OUT`/`IN`) по allowlist `DECOMPOSED_INTO, IMPLEMENTED_IN, HAS_DEPLOYMENT, IN_ENVIRONMENT, RUNS_ON, DEPENDS_ON, OWNED_BY, DEPLOYED_FROM`, соседи только искомых меток, закрытые связи (`validTo` задан) не отдаются 🟢 (access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:98-103, 142-150; platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/GetAssetIT.java:146-168).
- `truncated` относится только к связям (берётся из второго запроса) 🟢 (access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:75-80; access/mcp-server/src/test/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetToolTest.java:141-147).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `search_assets`: точное совпадение, затем FULLTEXT; только актуальные | Must | AC-01 |
| RF-02 | Валидация `query`, `limit`, `types` до обращения к графу, без эха ввода | Must | AC-02 |
| RF-03 | Фильтры `types` и `environment` | Must | AC-03 |
| RF-04 | `limit` и `truncated` | Must | AC-04 |
| RF-05 | `get_asset`: карточка, источники, связи 1 уровня | Must | AC-05 |
| RF-06 | `get_asset`: закрытый актив возвращается с `isCurrent=false` | Should | AC-06 |
| RF-07 | `get_asset`: невалидный gid и неизвестный gid — ошибки tool без эха | Must | AC-07 |
| RF-08 | Оба tools доступны только со scope `architecture.read` | Must | AC-08 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Cypher только с параметрами; метки из allowlist; Lucene-ввод экранируется | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:89-95; access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java:11-28 | 🟢 |
| Безопасность | Тексты ошибок не содержат ввода | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java:98-99 | 🟢 |
| Производительность | Фильтр `environment` — один `EXISTS` с `UNION` (холодное планирование четырёх `OR EXISTS` не укладывалось в таймаут, UNLOCKER-216) | access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:34-36 | 🟢 |
| Производительность | Таймаут 5 с, ответ ≤ 512 КБ, ≤ 500 строк | access/mcp-server/src/main/resources/application.yml:45-50 | 🟢 |
| Disponibilidade | Нет сессий; повтор вызова идемпотентен (`idempotentHint=true`) | access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java:42-46 | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (SearchAssetsMcpTest.callWithScopeReturnsHitsAndAuditsTemplateId; SearchAssetsIT.exactGidMatchComesFirstAboveFulltextHits; F1AcceptanceIT.s2_searchByNameIsFulltext, s3_searchBySourceIdIsExact)
Дано в графе ITSystem "Payments Core" и Service, найденный по тексту
Когда search_assets query="<gid ITSystem>"
Тогда первым идёт EXACT (score 1000), FULLTEXT-кандидаты ниже, дублей нет, ответ помечен truncated=false

# AC-02 (SearchAssetsToolTest.rejectsBadArgumentsBeforeTouchingGraphWithoutEchoingInput; SearchAssetsMcpTest.invalidArgumentIsReportedAsToolErrorWithoutEchoingInput)
Дано query пустой, или длиннее 200 символов, или limit = 0 / 51, или types содержит неизвестную метку
Когда вызывается search_assets
Тогда result.isError = true, HTTP 200, текст ошибки без значения ввода, граф не опрашивался

# AC-03 (SearchAssetsIT.typeFilterRestrictsResults; environmentFilterFollowsRelationsAndExcludesOthers; F1AcceptanceIT.s4_typeAndEnvironmentFilter)
Дано types=["Service"] и environment="PROD"
Когда search_assets
Тогда в ответе только Service, связанные с окружением PROD через открытые связи; закрытые связи фильтр не проходят

# AC-04 (SearchAssetsToolTest.limitIsCappedByConfiguredNodeCeiling; SearchAssetsIT.limitAndTruncated; F1AcceptanceIT.s5_limitTruncates)
Дано limit=1 и подходящих кандидатов больше одного
Когда search_assets
Тогда один элемент и truncated=true
Дано limit=50 при max-nodes=5 (unit-тест)
Тогда бюджет шаблона ограничен 5 строками

# AC-05 (GetAssetMcpTest.callWithScopeReturnsCardAndAuditsBothTemplates; GetAssetIT.cardHasPropertiesTimesAndSourcesWithAuthority; relationsAreCurrentOneLevelBothDirectionsFromAllowlist; F1AcceptanceIT.s6_getAssetCardWithRelations)
Дано актив с двумя источниками и связями в обе стороны
Когда get_asset gid=<UUID>
Тогда карточка содержит properties без служебных ключей, sources с authority, relations с direction OUT/IN и gid соседей; аудит перечисляет шаблоны get_asset и asset_relations

# AC-06 (GetAssetIT.closedNodeIsReturnedNotCurrentWithInactiveRecord)
Дано закрытый (tombstone) актив
Когда get_asset
Тогда ответ с isCurrent=false, deletedAt заполнен, запись источника active=false

# AC-07 (GetAssetToolTest.rejectsNonUuidWithoutEchoingInputOrTouchingGraph, emptyResultIsAssetNotFoundWithoutGid; GetAssetMcpTest.invalidGidIsReportedAsToolErrorWithoutEchoingInput, unknownGidIsReportedAsToolErrorWithoutGid; F1AcceptanceIT.s7_unknownGidIsToolErrorWithoutStacktrace)
Дано gid = "SECRET-not-uuid" либо корректный UUID несуществующего актива
Когда get_asset
Тогда isError=true: "gid must be a UUID" либо "asset not found", значение gid и стек в ответе отсутствуют

# AC-08 (SearchAssetsMcpTest.callWithoutRequiredScopeIsDenied; GetAssetMcpTest.callWithoutRequiredScopeIsDenied; F1AcceptanceIT.s8_accessWithoutScopeOrTokenIsDenied)
Дано токен без architecture.read или запрос без токена
Когда tools/call search_assets либо get_asset
Тогда 403 либо 401
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05, RF-07, RF-08 | Must | Базовый сценарий F1 |
| RF-06 | Should | Диагностика закрытых активов |
| Полнотекстовый поиск по остальным типам | Won't | Только `ITSystem` и `Service` (индекс `asset_text`) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsTool.java, access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/SearchAssetsResult.java | `search_assets` | 🟢 |
| access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetTool.java, access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/tools/GetAssetResult.java | `get_asset` | 🟢 |
| access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java, access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/FulltextQuery.java | шаблоны `search_assets`, `get_asset`, `asset_relations` | 🟢 |
