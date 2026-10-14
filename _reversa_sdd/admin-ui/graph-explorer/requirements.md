# graph-explorer: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §1 (№ 2..5). Серверная сторона: [`../../flowcharts/admin-console.md`](../../flowcharts/admin-console.md), [`../../flowcharts/graph-query-core.md`](../../flowcharts/graph-query-core.md).

## Visão Geral

Раздел «Граф» (`#/graph`): сводка графа, поиск активов, карточка выбранного актива (свойства, жизненный цикл, происхождение) и интерактивная окрестность узла. Всё — чтение графа через `GET /api/graph/**` под reader-учёткой на стороне консоли; UI изменений не делает 🟢 (`access/admin-ui/src/pages/GraphPage.tsx:8-23`).

## Regras de Negócio

- Сводка показывает число узлов по меткам, связей по типам и «последнее обновление»; пустые обе таблицы → «Нет данных»; кнопка «Обновить» перезагружает с индикатором загрузки 🟢 (`access/admin-ui/src/pages/graph/GraphSummary.tsx:9-20`).
- Поиск: запрос обрезается `trim()`, пустой не отправляется; фильтр типов — чекбоксы `ASSET_TYPES`; `types` не отправляется, если ничего не выбрано; в строке запроса `q` идёт перед `types` 🟢 (`access/admin-ui/src/pages/graph/AssetSearch.tsx:17-27, 41, 48-52`; `access/admin-ui/src/api/endpoints.ts:50-51`; `access/admin-ui/src/pages/GraphPage.test.tsx:102`).
- Если сервер вернул `truncated = true`, над таблицей показывается «Результат обрезан лимитом.» (`role="status"`); пустой список → «Ничего не найдено» 🟢 (`access/admin-ui/src/pages/graph/AssetSearch.tsx:56-60`).
- Клик по имени результата выбирает `gid`; ниже показываются карточка и окрестность этого актива 🟢 (`access/admin-ui/src/pages/graph/AssetSearch.tsx:74-76`; `access/admin-ui/src/pages/GraphPage.tsx:15-20`).
- Карточка: тип и `gid`; таблица свойств (значение через `text()`, объекты — `JSON.stringify`, пустое — «—»); блок «Жизненный цикл» (`isCurrent` с бейджем «закрыт», если `false`, и бейджем «устарел», если `isStale`; `firstSeenAt`, `lastSeenAt`, `deletedAt`); таблица «Происхождение» 🟢 (`access/admin-ui/src/pages/graph/AssetCard.tsx:40-115`; `access/admin-ui/src/components/common.tsx:65-69`).
- `isStale` (как в MCP, `TraceDependenciesTool.java:187-190,228`): актив текущий (`isCurrent`) и (`lastSeenAt` отсутствует или не разбирается, либо старше `STALE_AFTER_MS` (7 суток) на момент `now`, либо ни у одного источника нет `active === true`; `active: null` и пустой `sources` считаются неактивными); закрытый узел не «устарел» 🟢 [UNLOCKER-286, вопрос 19] (`access/admin-ui/src/pages/graph/AssetCard.tsx:8-17`; `access/admin-ui/src/api/constants.ts:56`). Равенство порога `archrag.query.stale-after` проверяет `access/admin-ui/src/api/constants.test.ts` (формат `PnD`).
- Блок «Исходный код» показывается только у `Deployment` с непустой строкой `properties.sourceCommit`: короткий (7 символов) и полный commit и `repositoryUrl` текстом, без ссылки 🟢 (`access/admin-ui/src/pages/graph/AssetCard.tsx:15-28`).
- Запись источника в «Происхождении»: «источник / тип / id», версия, `fetchedAt`, `authority`, статус «tombstone» (если `active === false` или есть `deletedAt`) либо «активна», бейдж «расхождения: …» при непустых `conflicts` 🟢 (`access/admin-ui/src/pages/graph/AssetCard.tsx:90-110`).
- Окрестность: глубина 1 (по умолчанию) или 2; чекбоксы типов связей (`RELATION_TYPES`); пустой выбор = все типы (параметр не отправляется); `truncated` → баннер «Окрестность показана не полностью…» (`role="status"`) 🟢 (`access/admin-ui/src/pages/graph/NeighborhoodView.tsx:12-18, 23-37, 41-45`).
- Граф рисует Cytoscape (раскладка `cose`): цвет узла по метке (`LABEL_COLORS`, иначе `DEFAULT_LABEL_COLOR`), центр — рамка, закрытые узлы (`isCurrent = false`) — `opacity 0.5`, подписи узлов и рёбер — текст на canvas; клик по узлу выбирает его `gid` 🟢 (`access/admin-ui/src/components/GraphCanvas.tsx:23-65`).
- Поиск защищён от гонки: ответ устаревшего запроса отбрасывается 🟢 (`access/admin-ui/src/pages/graph/AssetSearch.tsx:21-26`).
- Ограничения сервера: `q` не пуст и ≤ 200 символов; `limit` 1..50 (UI не передаёт, по умолчанию 10); глубина 1..2; типы и связи только из allowlist, иначе 400; нет окрестности (узел не найден) → 404 🟢 (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:40-44, 74-82`; `docs/adr/0026-admin-console-readonly-loopback.md:19`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Сводка графа и ручное обновление | Should | AC-01 |
| RF-02 | Поиск с фильтром типов, защита от гонки, `truncated` | Must | AC-02, AC-03 |
| RF-03 | Карточка актива: свойства как текст, жизненный цикл, происхождение | Must | AC-04 |
| RF-04 | Блок «Исходный код» у `Deployment` | Should | AC-05 |
| RF-05 | Окрестность: глубина, фильтр связей, баннер `truncated` | Must | AC-06, AC-07 |
| RF-06 | Переход по клику на узел графа | Must | AC-08 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Свойства и URL из источников не интерпретируются как HTML; URL не становится ссылкой | `access/admin-ui/src/pages/graph/AssetCard.tsx:15, 25, 50`; `access/admin-ui/src/pages/GraphPage.test.tsx:121-153` | 🟢 |
| Безопасность | Подписи узлов — текст на canvas (HTML не интерпретируется) | `access/admin-ui/src/components/GraphCanvas.tsx:6-9, 26-34` | 🟢 |
| Надёжность | Размонтирование уничтожает экземпляр Cytoscape | `access/admin-ui/src/components/GraphCanvas.tsx:65` | 🟢 |
| Производительность | Граф ограничен бюджетом сервера (`max-nodes 500`, `timeout 5s`) и баннером `truncated` | `access/admin-console/src/main/resources/application.yml:35-41`; `access/admin-ui/src/pages/graph/NeighborhoodView.tsx:41-45` | 🟢 |
| Совместимость | Cytoscape под CSP `default-src 'self'` проверен только в jsdom | `access/admin-ui/src/pages/GraphPage.test.tsx:198-215` | 🟡 (Q-10) |

## Критерии приёмки

```gherkin
# AC-01 (GraphPage.test.tsx «summary shows counters…», «summary shows empty and error states»)
Дано /api/graph/stats вернул nodes [{Service,7}], relations [{OWNED_BY,5}], lastUpdatedAt
Когда открыт раздел «Граф»
Тогда таблицы «Узлы по меткам» и «Связи по типам» показывают 7 и 5, время — в <time datetime=…>
Дано /api/graph/stats вернул пустые массивы
Тогда «Нет данных»
Дано сервер ответил 500 с телом «boom secret»
Тогда показано «Ошибка запроса (HTTP 500)», текст тела не выводится

# AC-02 (GraphPage.test.tsx «search sends query and type filter…», «blank query is not sent», «search with no hits…»)
Дано введено «billing», отмечен тип Service
Когда нажато «Найти»
Тогда GET /api/graph/search?q=billing&types=Service, в таблице видны gid и score
Дано строка запроса пуста
Когда нажато «Найти»
Тогда запрос к /search не отправляется
Дано сервер вернул items = []
Тогда «Ничего не найдено»

# AC-03 (GraphPage.test.tsx «stale search response does not overwrite a newer one»)
Дано отправлены запросы «a» и «b», ответ на «a» пришёл позже
Когда поздний ответ разрешён
Тогда в таблице остаётся результат запроса «b»

# AC-04 (GraphPage.test.tsx «asset card shows properties as text…», «asset card shows error when asset is not found», «card marks closed and stale assets»)
Дано свойство со значением «<img src=x onerror=alert(1)>»
Когда открыта карточка
Тогда это текст в ячейке, элемента img в документе нет
Дано записи источников: одна с deletedAt, одна активная с conflicts [owner]
Тогда видны «tombstone», «активна», «расхождения: owner»
Дано GET /api/graph/nodes/{gid} вернул 404
Тогда в карточке «Ошибка запроса (HTTP 404)»
Дано isCurrent=true и (lastSeenAt старше 7 суток, или отсутствует/не дата, или все источники active=false/null, или sources пуст)
Тогда isStale = true; если есть хотя бы один активный источник и lastSeenAt свежий — false; для isCurrent=false — false

# AC-05 (GraphPage.test.tsx «deployment card shows short and full commit…», «…short commit shows it whole», «card without commit has no source code block»)
Дано Deployment с sourceCommit из 40 символов и repositoryUrl
Когда открыта карточка
Тогда видны первые 7 символов (title = полный commit), полный commit и URL текстом, элемента <a> нет
Дано Deployment без sourceCommit
Тогда блока «Исходный код» нет

# AC-06 (GraphPage.test.tsx «neighborhood depth and relation type filters are sent to the API»)
Дано открыта карточка, окрестность загружена с depth=1
Когда выбрана глубина 2, затем тип OWNED_BY
Тогда GET …/neighborhood?depth=2, затем ?depth=2&relTypes=OWNED_BY

# AC-07 (GraphPage.test.tsx «truncated neighborhood shows a banner…», «complete neighborhood has no truncated banner»)
Дано truncated = true
Тогда баннер «Окрестность показана не полностью…» с role=status
Дано truncated = false
Тогда баннера нет

# AC-08 (GraphPage.test.tsx «neighborhood draws the graph, colors by label and opens a clicked node card»)
Дано окрестность нарисована, узлы разных меток
Когда произошёл tap по узлу OTHER
Тогда запрошена карточка GET /api/graph/nodes/OTHER; цвета разных меток различаются
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| Поиск, карточка, окрестность (RF-02, 03, 05, 06) | Must | Основной сценарий оператора |
| Сводка, блок «Исходный код» (RF-01, RF-04) | Should | Обзор; ADR 0029 (commit развёртывания) |
| Фильтр `limit`, фильтр по среде | Won't | UI не передаёт `limit` (`access/admin-ui/src/api/endpoints.ts:50-51`); `environment` в поиске консоли всегда `null` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:91`) 🟢 |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `access/admin-ui/src/pages/GraphPage.tsx` | `GraphPage` | 🟢 |
| `access/admin-ui/src/pages/graph/GraphSummary.tsx` | `GraphSummary` | 🟢 |
| `access/admin-ui/src/pages/graph/AssetSearch.tsx` | `AssetSearch` | 🟢 |
| `access/admin-ui/src/pages/graph/AssetCard.tsx` | `AssetCard`, `isStale`, `SourceCode` | 🟢 |
| `access/admin-ui/src/pages/graph/NeighborhoodView.tsx`, `access/admin-ui/src/components/GraphCanvas.tsx` | окрестность | 🟢 (Cytoscape замокан) |
| `access/admin-ui/src/pages/GraphPage.test.tsx` | 17 тестов | 🟢 |
