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

## Дополнение 2026-10-13

Исходный текст выше не менялся. Дополнение уточняет границы механизма замещения связей; источник — UNLOCKER-278, кандидат К-4 в `_reversa_sdd/adrs/candidates.md` (UNLOCKER-269), согласие владельца в UNLOCKER-260 (2026-10-13). Кто принял: владелец, 2026-10-13.

1. **Замещаются только типы, которые версия утверждает или откладывает.** Для каждого такого типа (`UpsertRelation` или `DeferRelation`) открытые связи этой записи (все три поля `assertedBy*`) вне набора текущих связей закрываются (`ingestion/graph-projector/.../GraphProjector.java:604-648`). Тип, которого в версии нет вообще, не трогается: отсутствие типа означает «не передано», а не «удалено». Повтор версии ничего не замещает (решение `SAME`, [ADR 0033](0033-source-version-numeric-idempotency.md)). Замещают только темпоральные связи, по которым источник авторитетен (`GraphProjector.java:662-666`). Связь, переутверждённая другой записью, имеет другой `assertedBy*` и не закрывается.
2. **Идентичность связи — тройка `(from, to, identityProps)`**, а не пара концов. Свойства идентичности объявляет `RelationType.identityProperties()` (сегодня только `DEPLOYED_FROM {commit}`, `contracts/canonical-model/.../RelationType.java:20`, `:48-55`), их имена берутся из enum, а не из payload (`GraphProjector.java:69-71`, `:650-659`).
3. **`PART_OF` и `HOSTED_ON` нетемпоральны намеренно** (`RelationType.java:21-22`): `validTo` на `HOSTED_ON` был добавлен и затем убран (коммиты `0707d21`, `95594a6`). Исходный ADR говорит только о четырёх структурных типах; эти два остаются без `validTo`.
4. **Дефект закрыт (UNLOCKER-279).** Запись со статусом `PARTIAL` (поле `completeness` payload, `EventProcessor.java:289`) не замещает связи: проектор пропускает `closeReplacedRelations`, потому что список связей неполон и отсутствие связи не означает закрытие (`ingestion/graph-projector/.../GraphProjector.java:353-356`, флаг `ProjectionRequest.partial`). Запись без `PARTIAL` замещает связи по правилу п. 1.
