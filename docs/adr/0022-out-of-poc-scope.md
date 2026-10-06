# 0022. Вне PoC: semantic retrieval, resources/prompts, `compare_environments`, `find_stale_assets`, `get_sync_status`

- **Статус:** Принято
- **Дата:** 2026-10-03
- **Источник:** [`00-vision.md`, «Критерии готовности PoC»](../00-vision.md#критерии-готовности-poc); [`00-vision.md`, «Tools первого релиза»](../00-vision.md#tools-первого-релиза); [`00-vision.md`, «Resources и prompts»](../00-vision.md#resources-и-prompts)
- **Кто принял:** архитектор, принято владельцем

## Контекст

Критерий 5 требует пять tools: `search_assets`, `get_asset`, `find_runtime_footprint`, `trace_dependencies`, `explain_provenance`. Остальное из видения проверять нечем и не нужно для приёмки.

## Решение

В PoC не входят: semantic retrieval (`search_architecture_context`, vector index), MCP resources и prompts, `compare_environments`, `find_stale_assets`, `get_sync_status`. Они остаются в видении как целевое состояние.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| Ограничить PoC пятью tools | Фокус на критериях | Часть видения не реализована | Выбран |
| Реализовать всё из видения | Полнота | Растёт срок | Отклонён |

## Последствия

- **Положительные:** Меньше кода и тестов.
- **Отрицательные / ограничения:** Эти пункты попадают в production gap list.
- **Затронутый код:** `access/mcp-server`

## Связанные ADR

[0018](0018-domain-tools-not-arbitrary-cypher.md)
