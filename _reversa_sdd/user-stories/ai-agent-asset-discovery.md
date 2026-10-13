# User stories: ИИ-агент находит и объясняет архитектурные активы

> Основание: [`../mcp-server/requirements.md`](../mcp-server/requirements.md), [`../graph-query-core/requirements.md`](../graph-query-core/requirements.md), приёмка [`../integration-tests/acceptance-mcp-f1-f4/requirements.md`](../integration-tests/acceptance-mcp-f1-f4/requirements.md). OpenAPI: [`../openapi/mcp-server.yaml`](../openapi/mcp-server.yaml).

## US-A1. Найти актив по имени или идентификатору
Как ИИ-агент, я хочу вызвать `search_assets` по тексту, `gid` или `sourceId`, чтобы получить кандидатов с типом, источниками и признаком совпадения.
- Дано граф загружен, токен со scope `architecture.read`. Когда `search_assets(query="Payments Core")`. Тогда первым идёт `ITSystem` с `matchType=FULLTEXT`; по `sourceId` — `EXACT` 🟢.
- Отказ: пустой/длинный `query` или `limit` вне 1..50 — ошибка tool без стектрейса и без эха ввода 🟢.
- Спеки: [`../mcp-server/search-get-asset/`](../mcp-server/search-get-asset/requirements.md).

## US-A2. Получить карточку актива со связями
Как агент, я хочу `get_asset(gid, includeRelations)`, чтобы увидеть свойства, источники с авторитетом и соседей.
- Тогда карточка содержит источники с `authority` (MASTER/SUPPLEMENTARY) и связи с направлением; неизвестный `gid` — `asset not found` 🟢.

## US-A3. Узнать, где система реально работает
Как агент, я хочу `find_runtime_footprint(systemGid, environment?)`, чтобы увидеть сервисы → развёртывания → вычислительные узлы с источником каждого факта.
- Смена deploy map видна после очередного опроса; замещённый хост исчезает; закрытое ребро `DEPLOYED_FROM` не отдаётся 🟢.
- Спеки: [`../mcp-server/find-runtime-footprint/`](../mcp-server/find-runtime-footprint/requirements.md).

## US-A4. Оценить влияние и зависимости
Как агент, я хочу `trace_dependencies` в режимах `trace` и `impact`, чтобы получить объяснимые пути (какой источник утверждает каждое ребро) в пределах глубины ≤ 6.
- Невалидный ввод (`maxDepth=7`, `mode=foo`, `environment` в режиме trace) — отказ tool 🟢. Холодный impact ≤ 15 с, p95 ≤ 5 с проверяются приёмкой 🟢.
- Спеки: [`../mcp-server/trace-dependencies/`](../mcp-server/trace-dependencies/requirements.md).

## US-A5. Понять, чему верить при расхождении источников
Как агент, я хочу `explain_provenance(gid, property?)`, чтобы увидеть записи источников, мастера и состояние конфликта (`OPEN`/`NONE`), не получая спорных значений.
- Спеки: [`../mcp-server/explain-provenance/`](../mcp-server/explain-provenance/requirements.md).

## US-A6. Доступ только на чтение и только по токену
Как владелец платформы, я хочу, чтобы каждый tool был закрыт scope `architecture.read`, а граф читался только `executeRead`.
- Без токена 401; чужой scope или неизвестный tool — 403 с `insufficient_scope` 🟢. Не проверено тестами: неверный audience/issuer, просроченный токен (🔴 `../integration-tests/questions.md`).
- Спеки: [`../mcp-server/authorize-tool-call/`](../mcp-server/authorize-tool-call/requirements.md).
