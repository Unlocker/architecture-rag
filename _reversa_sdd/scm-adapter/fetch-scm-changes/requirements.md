# Use case: fetch-scm-changes (контракт коннектора на SCM-заглушке)

Родитель: [../requirements.md](../requirements.md), дизайн — [../design.md](../design.md). Реализация — `HttpSourceConnector` в `adapter-core`; модуль проверяет её на `StubSources.scm`.

## Сценарий
Адаптер читает `GET /changes?limit=<n>[&cursor=<c>]` и `GET /objects/{type}/{id}` (`HttpSourceConnector.java:51-78`). Seed SCM: `REPOSITORY/repo-payments-api`, `SERVICE/svc-payments-api`, `SERVICE/svc-ledger-api` (3 объекта; `platform/source-stubs/.../StubSources.java:55-72`) 🟢.

## Правила
- `limit <= 0` → `IllegalArgumentException("limit must be positive")` (`HttpSourceConnector.java:52-54`) 🟢; теста в модуле нет 🟡.
- Страница: `changes`, `nextCursor` (String или null), `hasMore`, `snapshotComplete` (`:60-67`) 🟢.
- `404` на объект → `Optional.empty()` (`:73-75`); иной не-200 → `IllegalStateException` без тела (`:104-108`) 🟢.
- 429/5xx → `SourceUnavailableException(system, status, retryAfter)`; `Retry-After` в секундах, неразбираемый → `null` (`:98-99,110-119`) 🟢.
- DELETE виден как tombstone (`operation == DELETE`) (`:140`; тест) 🟢.

## Критерии приёмки
```gherkin
Дано SCM-заглушка с 3 объектами
Когда читаем /changes по 1 объекту до hasMore=false
Тогда суммарно 3 изменения, последняя страница snapshotComplete=true, следующий вызов пуст
  (snapshotWalksAllObjectsInPagesAndCompletes)

Дано курсор после snapshot и upsert svc-payments-api
Когда fetchChanges(cursor, 100)
Тогда одно изменение, sourceVersion=2, payload.name="changed"
  (incrementalPollingReturnsOnlyChangesAfterCursor)

Дано неизвестный объект
Когда fetchById("SERVICE", "no-such-object")
Тогда Optional.empty  (fetchByIdReturnsStateAndEmptyForUnknownObject)

Дано delete svc-payments-api
Когда fetchById / fetchChanges(cursor)
Тогда operation=DELETE  (deleteIsVisibleAsTombstone)

Дано failNext(1, 429, 3 с)
Когда fetchChanges(null, 10)
Тогда SourceUnavailableException, isRateLimited, retryAfter=3 с; повтор возвращает 3 объекта
  (rateLimitCarriesRetryAfterAndLeavesStateIntact)
```
