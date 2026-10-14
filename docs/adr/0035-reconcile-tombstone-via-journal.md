# 0035. Reconcile: tombstone через журнал, missing set по журналу, ручной reconcile — новый snapshot

- **Статус:** Принято
- **Дата:** 2026-10-13
- **Источник:** UNLOCKER-278; кандидат К-5 в `_reversa_sdd/adrs/candidates.md` (UNLOCKER-269); согласие владельца в UNLOCKER-260 (2026-10-13); реализация — UNLOCKER-169, UNLOCKER-170; целевые доработки — UNLOCKER-276
- **Кто принял:** владелец, 2026-10-13

## Контекст

ADR [0020](0020-tombstone-instead-of-hard-delete.md) фиксирует «удаляем только после `snapshot-complete`», но не говорит, как удаления воспроизводятся при replay и rebuild и что означает ручной `POST /admin/reconcile/{source}` ([ADR 0013](0013-operator-ops-via-admin-endpoint.md)).

## Решение

Как в коде сейчас:

1. **Tombstone идёт через журнал.** Каждый tombstone `Reconciler` до записи в граф фиксируется событием `architecture.asset.deleted.v1` с `eventId = reconcile:<syncRunId>:<sourceType>/<sourceId>` и raw в S3, чтобы replay и rebuild воспроизводили удаления. Повтор даёт ту же строку и доводит её до конечного статуса (`ingestion/graph-projector/.../Reconciler.java:44-47`, `:61`, `:157-171`).
2. **Missing set считается по журналу, а не по графу** (`Reconciler.java:105-119`): объект, по которому после начала прогона пришло любое событие, не удаляется; прогон, в котором событий меньше заявленного `objectCount`, считается неполным и не удаляет ничего (`Reconciler.java:37-42`, `:106`). Tombstone несёт версию применённой записи ([ADR 0033](0033-source-version-numeric-idempotency.md)): обновлённый объект не удаляется.
3. **Ручной reconcile — это новый полный snapshot.** `POST /admin/reconcile/{source}` не пересчитывает прежний missing set, а вызывает `POST /control/snapshot` в adapter-сервисе (`ingestion/ingestion-service/.../AdminController.java:95-102`, `adapters/adapter-core/.../WebhookServer.java:18`, `:56`); удаления делает `Reconciler` по маркеру `snapshot-complete`.

Целевое решение (в коде этого нет, реализация — UNLOCKER-276 пп. 6, 11, 15):

4. Защита маркера `snapshot-complete` и `Reconciler`.
5. Авторизация `/control/snapshot`: роль `admin` для методов управления и контроля. Сегодня эндпоинт без аутентификации и рассчитан на внутреннюю сеть (`docs/03-interfaces.md:33`, `:368`).
6. Исправление дефекта: `forget` при tombstone.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A. Tombstone через журнал, missing set по журналу, ручной reconcile = новый snapshot | Удаления воспроизводимы; нет «залипшего» missing set | Ручной reconcile дорог (полный snapshot) | Выбран |
| B. Повторять сохранённый missing set | Дешёвый повтор | Устаревший набор мог удалить живой объект; был сделан и заменён | Отклонён |
| C. Физическое удаление | Просто | Противоречит ADR [0020](0020-tombstone-instead-of-hard-delete.md) | Отклонён |

## Последствия

- **Положительные:** replay и rebuild воспроизводят удаления; частичный или устаревший прогон ничего не удаляет.
- **Отрицательные / ограничения:** пока не выполнены пп. 4–6, `/control/snapshot` доступен без аутентификации любому, кто достаёт порт адаптера, а маркер `snapshot-complete` не защищён; ручной reconcile ждёт завершения полного snapshot (таймаут 10 минут, `docs/03-interfaces.md:400`).
- **Затронутый код:** `ingestion/graph-projector` (`Reconciler`), `ingestion/ingestion-service` (`AdminController`), `adapters/adapter-core` (`WebhookServer`).

## Связанные ADR

[0013](0013-operator-ops-via-admin-endpoint.md) — админский эндпоинт. [0020](0020-tombstone-instead-of-hard-delete.md) — tombstone и `snapshot-complete`. [0033](0033-source-version-numeric-idempotency.md) — версия tombstone.
