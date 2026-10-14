# 0035. Reconcile: tombstone через журнал, missing set по журналу, ручной reconcile — новый snapshot

- **Статус:** Принято
- **Дата:** 2026-10-13
- **Источник:** UNLOCKER-278; кандидат К-5 в `_reversa_sdd/adrs/candidates.md` (UNLOCKER-269); согласие владельца в UNLOCKER-260 (2026-10-13); реализация — UNLOCKER-169, UNLOCKER-170; доработки — UNLOCKER-279, UNLOCKER-290
- **Кто принял:** владелец, 2026-10-13

## Контекст

ADR [0020](0020-tombstone-instead-of-hard-delete.md) фиксирует «удаляем только после `snapshot-complete`», но не говорит, как удаления воспроизводятся при replay и rebuild и что означает ручной `POST /admin/reconcile/{source}` ([ADR 0013](0013-operator-ops-via-admin-endpoint.md)).

## Решение

Как в коде сейчас:

1. **Tombstone идёт через журнал.** Каждый tombstone `Reconciler` до записи в граф фиксируется событием `architecture.asset.deleted.v1` с `eventId = reconcile:<syncRunId>:<sourceType>/<sourceId>` и raw в S3, чтобы replay и rebuild воспроизводили удаления. Повтор даёт ту же строку и доводит её до конечного статуса (`ingestion/graph-projector/.../Reconciler.java:54-60`, `:261-286`).
2. **Missing set считается по журналу, а не по графу** (`Reconciler.java:189-212`): объект, по которому после начала прогона пришло любое событие, не удаляется; прогон, в котором событий меньше заявленного `objectCount`, считается неполным и не удаляет ничего (`Reconciler.java:39-41`, `:190-192`). Tombstone несёт версию применённой записи ([ADR 0033](0033-source-version-numeric-idempotency.md)): обновлённый объект не удаляется.
3. **Ручной reconcile — это новый полный snapshot.** `POST /admin/reconcile/{source}` не пересчитывает прежний missing set, а вызывает `POST /control/snapshot` в adapter-сервисе (`ingestion/ingestion-service/.../AdminController.java:163-175`, `adapters/adapter-core/.../WebhookServer.java:63`); удаления делает `Reconciler` по маркеру `snapshot-complete`.

Реализовано в UNLOCKER-279 и UNLOCKER-290:

4. **Защита маркера `snapshot-complete` и `Reconciler`** (UNLOCKER-279). Маркер без валидного `objectCount` уходит в `QUARANTINED` с кодом `SNAPSHOT_OBJECT_COUNT_MISSING` (`ingestion/graph-projector/.../EventProcessor.java:383-387`, `Reconciler.java:181-183`). Прогон с полнотой `PARTIAL` удалений не делает: missing set не считается, пропуск пишется в лог и метрику `reconcile.skipped` (`Reconciler.java:184-188`, `EventProcessor.java:751-753`). Если missing set больше доли `archrag.reconcile.max-missing-share` от активных записей источника (по умолчанию `0.5`, `ingestion/ingestion-service/.../application.yml:48`), прогон отклоняется с кодом `SNAPSHOT_MASS_CLOSE_LIMIT` до первой записи (`Reconciler.java:216-223`).
5. **Авторизация `/control/snapshot`** (UNLOCKER-290). Эндпоинт проверяет bearer-токен: подпись по JWKS, issuer, срок, audience и роль `archrag-console-admin` из `realm_access.roles`; нет или недействителен — `401`, роли нет — `403` (`adapters/adapter-core/.../ControlTokenValidator.java:24`, `:74-79`, `WebhookServer.java:77-92`). Без настроенного валидатора контекст не создаётся (`WebhookServer.java:62-66`). Админский эндпоинт передаёт адаптеру токен вызывающего (`AdminController.java:173`).
6. **`forget` при tombstone** (UNLOCKER-279). Tombstone `Reconciler` убирает признаки кандидатов до записи в граф, как `EventProcessor.delete` (`Reconciler.java:226-232`, `EventProcessor.java:365`).

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A. Tombstone через журнал, missing set по журналу, ручной reconcile = новый snapshot | Удаления воспроизводимы; нет «залипшего» missing set | Ручной reconcile дорог (полный snapshot) | Выбран |
| B. Повторять сохранённый missing set | Дешёвый повтор | Устаревший набор мог удалить живой объект; был сделан и заменён | Отклонён |
| C. Физическое удаление | Просто | Противоречит ADR [0020](0020-tombstone-instead-of-hard-delete.md) | Отклонён |

## Последствия

- **Положительные:** replay и rebuild воспроизводят удаления; частичный или устаревший прогон ничего не удаляет.
- **Отрицательные / ограничения:** маркер `snapshot-complete` и `Reconciler` защищены (пп. 4, 6), `/control/snapshot` требует роль `archrag-console-admin` (п. 5); ручной reconcile ждёт завершения полного snapshot (таймаут 10 минут, `docs/03-interfaces.md:404`).
- **Затронутый код:** `ingestion/graph-projector` (`Reconciler`), `ingestion/ingestion-service` (`AdminController`), `adapters/adapter-core` (`WebhookServer`).

## Связанные ADR

[0013](0013-operator-ops-via-admin-endpoint.md) — админский эндпоинт. [0020](0020-tombstone-instead-of-hard-delete.md) — tombstone и `snapshot-complete`. [0033](0033-source-version-numeric-idempotency.md) — версия tombstone.
