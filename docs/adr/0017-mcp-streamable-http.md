# 0017. MCP-транспорт — Streamable HTTP, без legacy SSE и WebSocket

- **Статус:** Принято
- **Дата:** 2026-10-03
- **Источник:** [`00-vision.md`, «Выбор транспорта»](../00-vision.md#выбор-транспорта)
- **Кто принял:** архитектор, принято владельцем

## Контекст

Для удалённого MCP API нужен стандартный binding, работающий за gateway и без sessions.

## Решение

Сервер на `spring-ai-starter-mcp-server-webmvc` с `spring.ai.mcp.server.protocol=STREAMABLE`. Legacy HTTP+SSE и WebSocket не используются. `stdio` допустим для разработки.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| Streamable HTTP | Стандарт для remote, stateless scale-out | — | Выбран |
| Legacy HTTP+SSE | Старые клиенты | Deprecated | Отклонён |
| WebSocket | Realtime | Не стандартный binding MCP | Отклонён |

## Последствия

- **Положительные:** Совместимость с готовыми MCP-клиентами.
- **Отрицательные / ограничения:** Аутентификация starter не включена, её добавляет Spring Security (scope на каждый tool).
- **Затронутый код:** `access/mcp-server`

## Связанные ADR

[0005](0005-readonly-mcp-by-application.md), [0018](0018-domain-tools-not-arbitrary-cypher.md)
