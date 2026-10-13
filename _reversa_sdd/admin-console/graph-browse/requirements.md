# graph-browse: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Шаблоны графа: [`../../graph-query-core/asset-search/requirements.md`](../../graph-query-core/asset-search/requirements.md), [`../../graph-query-core/trace-and-footprint/requirements.md`](../../graph-query-core/trace-and-footprint/requirements.md).

## Visão Geral

Оператор просматривает граф: сводка (число узлов по меткам, связей по типам, время обновления), поиск активов, карточка актива с записями источников, окрестность узла глубиной 1–2 для визуализации. Все четыре эндпоинта — `GET /api/graph/**`, читают граф только именованными шаблонами через `GraphReads`.

## Regras de Negócio

- **stats**: три шаблона (`graph_stats_nodes`, `graph_stats_relations`, `graph_last_update`) на потолках конфигурации; `lastUpdatedAt` — значение первой строки или `null`, если строк нет 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:56-65`).
- **search**: `q` обязателен; после `strip` не пуст и не длиннее 200 символов; `limit` по умолчанию 10, допустимо 1..50; `types` — повторяемый параметр, значения из `AssetTemplates.SEARCHABLE_TYPES`, пустой список = без фильтра 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:40-42, 68-84, 175-188`).
- **search**: в шаблон передаются все четыре параметра, включая `null` (`text` = `FulltextQuery.escape(q)`, `environment` всегда `null`); бюджет — потолки, но `maxNodes = min(limit, потолок)` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:86-100`).
- **search**: ответ `{items[AssetHit], truncated}`; `truncated` берётся из результата исполнителя 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:101`).
- **node**: `gid` нормализуется в UUID (регистр приводится к нижнему), иначе `400 gid must be a UUID` без эха ввода; выполняются `get_asset` и `explain_provenance` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:105-114, 190-197`).
- **node**: пустой результат `get_asset` → `404 asset not found` (закрытый узел находится: фильтра `isCurrent` в шаблоне нет) 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:110-112`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:106-110`).
- **node**: `properties` — свойства узла без служебных `gid, isCurrent, firstSeenAt, lastSeenAt, deletedAt`; времена `ZonedDateTime`/`OffsetDateTime` приводятся к ISO-8601 UTC (`Instant`), списки обходятся рекурсивно 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:46-47, 220-250`).
- **node**: `sources` — записи из `explain_provenance` (поле `records`), активные первыми; нет строки провенанса — пустой список 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:115-123, 233-239`).
- **neighborhood**: `depth` по умолчанию 1, допустимо 1..2 (константа `MAX_DEPTH`, независимо от потолка 6); `relTypes` из `NEIGHBORHOOD_RELATION_TYPES`, по умолчанию все 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:43-44, 127-140`).
- **neighborhood**: глубина попадает в шаблон проверенным `int` через `ResultBudget.maxDepth`; остальное бюджета — потолки 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:142-150`; `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplate.java:133-138`).
- **neighborhood**: узлы запрашиваются первыми; ноль узлов → `404 asset not found`; затем рёбра между полученными `gid` одним запросом; `truncated = nodes.truncated OR edges.truncated` 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:151-166`).
- Время в ответах — ISO-8601 строка (UTC) 🟢 (`access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:80, 120-122`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:241-250`).
- Свойства узлов (тексты источников, ловушка 11) отдаются без пометки недоверенных и без обрезки 🟡 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:221-231`; Q-09 `graph-query-core`/Q-05).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Сводка графа | Must | AC-01 |
| RF-02 | Поиск активов с валидацией параметров | Must | AC-02, AC-03 |
| RF-03 | Карточка актива | Must | AC-04, AC-05 |
| RF-04 | Окрестность узла 1–2 уровня | Must | AC-06, AC-07 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Значения `types`/`relTypes` только из allowlist, ввод не эхоится в ошибку | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:175-188` | 🟢 |
| Безопасность | Cypher не собирается из ввода; параметры идут в шаблон | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:33-35, 86-91` | 🟢 |
| Производительность | Бюджет: 500 узлов, 5 с, 512 КБ; окрестность ≤ 2 | `access/admin-console/src/main/resources/application.yml:35-41`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:44` | 🟢 |
| Надёжность | Таймаут графового запроса → 504 | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/ApiExceptionHandler.java:38-41` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01 (ConsoleApiTest.statsReturnsCountsAndLastUpdate; AdminConsoleIT.statsCountNodesByLabelRelationsByTypeAndLastUpdate)
Дано граф с метками и связями
Когда GET /api/graph/stats с токеном architecture.admin
Тогда 200, nodes[{name,count}], relations[{name,count}], lastUpdatedAt

# AC-02 (ConsoleApiTest.searchReturnsHits; AdminConsoleIT.searchFindsByFulltextAndByExactGid)
Дано актив с известным gid
Когда GET /api/graph/search?q=pay&types=Service&limit=5
Тогда 200, items[0].matchType = EXACT или FULLTEXT, truncated = false

# AC-03 (ConsoleApiTest.searchRejectsInvalidParameters)
Дано q из пробелов, отсутствующий q, limit=0, limit=51, limit=x, types с чужим значением, q длиной 201
Когда GET /api/graph/search
Тогда 400 в каждом случае, в теле ошибки нет введённого значения, исполнитель графа не вызывался

# AC-04 (ConsoleApiTest.nodeCardHasPropertiesTimesAndSourceRecords; AdminConsoleIT.cardShowsPropertiesTimesAndAssertingSourceRecords)
Дано актив со свойствами и записями источников
Когда GET /api/graph/nodes/{gid}
Тогда 200, properties без gid, времена в ISO-8601 UTC, sources[0].authority = MASTER

# AC-05 (ConsoleApiTest.nodeCardUnknownGidIs404AndBadGidIs400WithoutEcho)
Дано gid без узла
Когда GET /api/graph/nodes/{gid}
Тогда 404
Дано gid = not-a-uuid
Когда GET /api/graph/nodes/not-a-uuid
Тогда 400 без эха значения

# AC-06 (ConsoleApiTest.neighborhoodReturnsNodesAndEdges, neighborhoodOverBudgetIsTruncated; AdminConsoleIT.neighborhoodDepthOneAndTwoWithRelTypeFilter, neighborhoodAcceptsDeployedFromRelType, neighborhoodOverNodeBudgetIsTruncated)
Дано узел с соседями на расстоянии 1
Когда GET .../neighborhood?depth=2&relTypes=OWNED_BY,DEPENDS_ON
Тогда 200, nodes (центр с distance 0), edges, truncated = false
Дано бюджет узлов меньше окрестности
Когда GET .../neighborhood?depth=2
Тогда truncated = true и узлов не больше потолка

# AC-07 (ConsoleApiTest.neighborhoodRejectsDepthAboveTwoAndUnknownRelTypes; AdminConsoleIT.depthThreeIsRejectedBeforeAnyQuery)
Дано depth=3, depth=0, depth=abc или relTypes=ASSERTS
Когда GET .../neighborhood
Тогда 400, запрос к графу не выполнялся
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-04 | Must | Основной сценарий оператора (ADR 0026) |
| Поиск по окружению | Won't | `environment` всегда `null` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:91`) |
| Глубина окрестности > 2 | Won't | Константа контроллера (ADR 0026) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java` | `GraphController` | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphResponses.java` | формы ответов | 🟢 |
| `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReads.java` | доступ к графу | 🟢 |
