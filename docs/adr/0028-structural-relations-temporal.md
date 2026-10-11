# 0028. Структурные связи `HAS_DEPLOYMENT`/`IN_ENVIRONMENT`/`DECOMPOSED_INTO`/`IMPLEMENTED_IN` — темпоральные

- **Статус:** Принято
- **Дата:** 2026-10-10
- **Источник:** решение владельца 2026-10-10 в UNLOCKER-218 (вариант A); UNLOCKER-217; UNLOCKER-219
- **Кто принял:** владелец, по постановке архитектора

## Контекст

Четыре связи (`HAS_DEPLOYMENT`, `IN_ENVIRONMENT` из deploy map, `DECOMPOSED_INTO`, `IMPLEMENTED_IN` из SCM) были нетемпоральными: без `validFrom`/`validTo`. Когда новая версия записи переставала их утверждать (у `DEPLOYMENT` сменились `service` или `environment`), старые рёбра оставались в графе, а tombstone их не снимал. `HAS_DEPLOYMENT` из DEPLOYMAP отдавался с `validFrom = null` (UNLOCKER-219).

## Решение

Все четыре типа становятся темпоральными (`RelationType.temporal() = true`). Физического удаления нет, ADR [0020](0020-tombstone-instead-of-hard-delete.md) остаётся в силе.

- Мапперы передают `validity` так же, как для остальных темпоральных связей: `validFrom` берётся из источника, а без него проектор открывает связь на время события.
- Новая версия записи замещает свои связи по механизму UNLOCKER-217: открытая связь, не подтверждённая версией, закрывается (`validTo = eventTime`). Tombstone закрывает рёбра через `validTo`.
- Шаблоны `graph-query-core` (MCP tools и admin-console) отдают текущее состояние только по связям с `validTo IS NULL`.
- Миграция живого графа — `TemporalRelationMigration`, вызывается при старте ingestion-сервиса: для связей четырёх типов без `validFrom` ставится `coalesce(validFrom, firstSeenAt записи-источника)`. `firstSeenAt` берётся минимумом по узлам, которые утверждает запись (`assertedBy*`); если его нет (запись без узла), берётся **время миграции**. Повторный запуск ничего не меняет. Rebuild из журнала даёт `validFrom` от времени события и миграции не требует.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A. Сделать связи темпоральными | Единая модель, история смены сервиса/окружения, не нарушает 0020 | Нужны фильтры `validTo IS NULL` в шаблонах и миграция | Выбран |
| B. Физически удалять устаревшие рёбра | Проще шаблоны | Противоречит 0020, теряется история | Отклонён |

## Последствия

- **Положительные:** устаревшие рёбра не попадают в ответы; `hasDeployment.validFrom` заполнен; закрывается UNLOCKER-219.
- **Отрицательные / ограничения:** при отсутствии `firstSeenAt` `validFrom` мигрированных связей равен времени миграции, а не реальному началу связи. Запросы по типам связей без индекса, как и для существующих темпоральных связей.
- **Затронутый код:** `contracts/canonical-model` (`RelationType`), `ingestion/normalizer` (`DeployMapMapper`, `ScmMapper`), `ingestion/graph-projector` (`TemporalRelationMigration`), `ingestion/ingestion-service`, `access/graph-query-core` (`RuntimeTemplates`, `AssetTemplates`).

## Связанные ADR

[0020](0020-tombstone-instead-of-hard-delete.md) — уточняет: удаления по-прежнему нет, структурные связи закрываются через `validTo`.
