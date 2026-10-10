# 0025. Индекс `deployment_env` удалён

- **Статус:** Принято
- **Дата:** 2026-10-10
- **Источник:** UNLOCKER-247; UNLOCKER-162; [`00-vision.md`, «Constraints и indexes»](../00-vision.md#constraints-и-indexes)
- **Кто принял:** владелец

## Контекст

Индекс `deployment_env` был построен по `Deployment.environmentKey`, а в модели такого свойства нет: окружение задаётся связью `IN_ENVIRONMENT`.

## Решение

Индекс `deployment_env` удалён из модели и не создаётся. Фильтр по окружению идёт через `IN_ENVIRONMENT` → `Environment.code`; `Environment.code` защищён UNIQUE-constraint `environment_code`. Свойство `environmentKey` в `Deployment` не добавляется.

Сверено с кодом: в [`Neo4jSchema`](../../ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java) индекса нет, состав схемы проверяет [`Neo4jSchemaTest`](../../ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchemaTest.java) (на живом Neo4j — [`Neo4jSchemaIT`](../../platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java)).

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| Добавить `environmentKey` в `Deployment` | Быстрый фильтр по окружению | Денормализация, риск расхождения со связью | Отклонён |
| Перестроить индекс | Модель не меняется | Фильтр идёт через связь | Отклонён |
| Удалить индекс, фильтровать через `IN_ENVIRONMENT` → `Environment.code` | Нет денормализации, `code` уже под UNIQUE | Фильтр идёт через связь | Выбран |

## Последствия

- **Положительные:** нет дублирования окружения в свойстве и связи.
- **Отрицательные / ограничения:** запросы по окружению обходят связь `IN_ENVIRONMENT`.
- **Затронутый код:** код уже соответствует решению (`Neo4jSchema`); правится только документация.

## Связанные ADR

[0004](0004-neo4j-community-constraints.md)
