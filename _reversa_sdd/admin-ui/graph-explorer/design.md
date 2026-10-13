# graph-explorer: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §1 (№ 2..5), §6, §7.

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `GraphPage` | `() -> JSX` | состояние `selected: string \| null`; рендерит `GraphSummary`, `AssetSearch(onSelect)`, при `selected` — `AssetCard(gid)` и `NeighborhoodView(gid, onSelect)` | `access/admin-ui/src/pages/GraphPage.tsx:8-23` 🟢 |
| `GraphSummary` | `() -> JSX` | `useAsync(api.stats)`; кнопка «Обновить» = `reload` | `access/admin-ui/src/pages/graph/GraphSummary.tsx:7-61` 🟢 |
| `AssetSearch` | `({onSelect}) -> JSX` | форма: поле `q` (`maxLength 200`), чекбоксы `ASSET_TYPES`, таблица результатов | `access/admin-ui/src/pages/graph/AssetSearch.tsx:10-92` 🟢 |
| `AssetCard` | `({gid}) -> JSX` | `useAsync(api.node(gid), [gid])` | `access/admin-ui/src/pages/graph/AssetCard.tsx:31-121` 🟢 |
| `isStale` | `(NodeCard, now = Date.now()) -> boolean` | `isCurrent && lastSeenAt && now - lastSeenAt > STALE_AFTER_MS` | `access/admin-ui/src/pages/graph/AssetCard.tsx:9-13` 🟢 |
| `NeighborhoodView` | `({gid, onSelect}) -> JSX` | состояние `depth`, `relTypes`; `useAsync(api.neighborhood(gid, depth, relTypes), [gid, depth, relTypes])` | `access/admin-ui/src/pages/graph/NeighborhoodView.tsx:10-52` 🟢 |
| `GraphCanvas` | `({data, onSelect}) -> JSX` | Cytoscape на `<div class="graph-canvas">`; пересоздаётся при смене `data` | `access/admin-ui/src/components/GraphCanvas.tsx:10-69` 🟢 |

## Fluxo Principal

1. Открытие `#/graph`: `GraphSummary` запрашивает `GET /api/graph/stats` (`access/admin-ui/src/pages/graph/GraphSummary.tsx:9`).
2. Оператор вводит запрос и отправляет форму: `submit` отменяет стандартное поведение формы, проверяет `q.trim()`, увеличивает счётчик `latest`, ставит состояние `loading` и вызывает `api.search(q.trim(), types)`; результат применяется, только если `id === latest.current` (`access/admin-ui/src/pages/graph/AssetSearch.tsx:17-27`).
3. Клик по имени актива: `onSelect(gid)` → `GraphPage.selected` (`access/admin-ui/src/pages/graph/AssetSearch.tsx:74-76`).
4. `AssetCard(gid)` запрашивает `GET /api/graph/nodes/{gid}` и рисует свойства, жизненный цикл, происхождение; для `Deployment` с commit — блок «Исходный код» (`access/admin-ui/src/pages/graph/AssetCard.tsx:33-115`).
5. `NeighborhoodView(gid)` запрашивает `GET /api/graph/nodes/{gid}/neighborhood?depth=…[&relTypes=…]`; `GraphCanvas` строит элементы: узлы `{id: gid, label: name, color, center, closed}`, рёбра `{id: 'e'+i, source, target, label: type}`; раскладка `cose` (`access/admin-ui/src/components/GraphCanvas.tsx:23-63`).
6. `cy.on('tap', 'node', …)` вызывает актуальный `onSelect` через `useRef` (чтобы не пересоздавать граф при смене колбэка), выбранный `gid` заменяет `GraphPage.selected` → карточка и окрестность перезагружаются (`access/admin-ui/src/components/GraphCanvas.tsx:18-19, 64`).

## Fluxos Alternativos

- Любой запрос завершился ошибкой → `Loadable` показывает `role="alert"` с «Ошибка запроса (HTTP N)» (для 401/403 — реакция `ApiProvider`) 🟢 (`access/admin-ui/src/components/common.tsx:28-29`; `access/admin-ui/src/hooks/useAsync.ts:36-38`).
- Узел не найден (карточка вернула 404) → в карточке «Ошибка запроса (HTTP 404)»; секция окрестности запрашивается независимо и показывает собственное состояние (в тесте окрестность отвечала успешно) 🟢 (`access/admin-ui/src/pages/GraphPage.test.tsx:185-196`).
- `lastUpdatedAt = null` → «—» (компонент `Time`) 🟢 (`access/admin-ui/src/components/common.tsx:5-7`).
- Свойство `sourceCommit` не строка или пустое → блок «Исходный код» не показывается 🟢 (`access/admin-ui/src/pages/graph/AssetCard.tsx:17-18`).
- Узел графа с неизвестной меткой → цвет `DEFAULT_LABEL_COLOR` 🟢 (`access/admin-ui/src/components/GraphCanvas.tsx:30`).
- 🟡 Повторный клик по тому же узлу, смена `gid` во время загрузки: `useAsync` отбрасывает ответы по флагу `cancelled` (`access/admin-ui/src/hooks/useAsync.ts:29-42`).
- 🟡 Карточка показывает `properties` целиком: свойства-«тексты источников» (описания) выводятся полностью и без усечения; защита — только экранирование (ловушка 11 про недоверенные данные закрыта частично: нет предела длины на экране) (`access/admin-ui/src/pages/graph/AssetCard.tsx:45-54`).

## Dependências

`cytoscape` 3.30.4 (`access/admin-ui/package.json:12`); REST консоли `GET /api/graph/*` (серверный контроллер `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:37-251`, шаблоны `ConsoleTemplates`/`AssetTemplates` в `access/graph-query-core`); константы UI [`../contracts.md`](../contracts.md) §7. Связанные спецификации и неявные связи (№ 9, № 10): [`../design.md`](../design.md).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Выбранный актив — локальное состояние страницы, не часть URL | `access/admin-ui/src/pages/GraphPage.tsx:9` | 🟢 |
| Ответ на устаревший поиск отбрасывается счётчиком, а не `AbortController` | `access/admin-ui/src/pages/graph/AssetSearch.tsx:14, 21-26` | 🟢 |
| Закрытые узлы и текущие различаются прозрачностью (`closed`), а не скрываются | `access/admin-ui/src/components/GraphCanvas.tsx:32, 50` | 🟢 |
| Глубина окрестности ограничена в UI двумя значениями (сервер: ≤ 2) | `access/admin-ui/src/pages/graph/NeighborhoodView.tsx:25-28`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:44` | 🟢 |

## Riscos e Lacunas

- 🟢 [R.8: ответ владельца, вопрос 9] Порог «устарел» продублирован с MCP без связи (Q-04).
- 🟢 [R.8: ответ владельца, вопрос 9] Allowlist'ы меток и связей продублированы без теста (Q-05).
- 🟡 Cytoscape под строгой CSP проверен только в jsdom (Q-10).
- 🟡 EAM-объект без даты источника получает `lastSeenAt = 1970` и «устарел» (Q-16).
