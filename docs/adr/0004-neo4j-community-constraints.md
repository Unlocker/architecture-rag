# 0004. Neo4j Community: составной UNIQUE вместо NODE KEY, контроль доступа в приложении вместо RBAC

- **Статус:** Принято
- **Дата:** 2026-10-03
- **Источник:** владелец 2026-10-03 (решение 4); UNLOCKER-159; UNLOCKER-176; [`00-vision.md`, «Constraints и indexes»](../00-vision.md#constraints-и-indexes)
- **Кто принял:** владелец

## Контекст

PoC работает на Neo4j Community. NODE KEY, existence constraints, RBAC/PBAC и online backup доступны только в Enterprise. Исходное видение предполагало `IS NODE KEY` для `SourceRecord`.

## Решение

Для `SourceRecord` используется `REQUIRE (source, sourceType, sourceId) IS UNIQUE`. Обязательность свойств проверяет приложение. Роли заменены разделением учётных записей и проверками в query layer. Платные возможности в коде не используются. Лицензирование Enterprise входит в production gap list.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| Community: составной UNIQUE + проверки в приложении | Нет лицензионных затрат | Существование свойств не гарантируется БД | Выбран |
| Enterprise: NODE KEY и RBAC | Гарантии на уровне БД | Лицензия, вне бюджета PoC | Отклонён для PoC, отражён в production gap list |
| `IS NODE KEY` в видении (прежний вариант) | — | Недоступен в Community | Заменён |

## Последствия

- **Положительные:** Схема применяется той же миграцией в тестах и в проде.
- **Отрицательные / ограничения:** `MERGE` допустим только по ключам под constraint; обязательность полей на совести кода.
- **Затронутый код:** `ingestion/graph-projector/.../schema/Neo4jSchema.java`

## Связанные ADR

[0003](0003-gid-and-source-record-identity.md), [0005](0005-readonly-mcp-by-application.md)
