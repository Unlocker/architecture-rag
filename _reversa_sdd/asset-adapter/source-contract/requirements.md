# source-contract: контракт коннектора на заглушке CMDB

> Сценарий модуля [`asset-adapter`](../requirements.md). `AT` = `adapters/asset-adapter/src/test/java/io/github/unlocker/archrag/assetadapter`. Production-кода в сценарии нет: он выражен тестами `AssetAdapterContractTest`, которые проверяют стандартный `HttpSourceConnector` (`adapter-core`) против `StubSources.cmdb` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:73-81`).

## Обзор
Для источника CMDB должен выполняться контракт `SourceConnector`: постраничный snapshot с `snapshotComplete`, инкремент по курсору, `GET by id`, видимость удаления как tombstone, обработка `429` с `Retry-After` 🟢.

## Исходные данные заглушки 🟢
Один объект: `COMPUTE_INSTANCE` / `vm-pay-01`, поля `hostname=vm-pay-01.prod.example.org`, `kind=VIRTUAL_MACHINE`, `state=RUNNING`, `environment=prod` (`StubSources.java:73-81`). Формат HTTP заглушки — `docs/03-interfaces.md:530-538` (`GET /changes?cursor&limit`, `GET /objects/{type}/{id}`).

## Требования

| ID | Требование | Приоритет | Тест |
|---|---|---|---|
| RF-01 | Snapshot (`cursor == null`) страницами по `limit`; пока `hasMore`, `snapshotComplete == false`; на последней `true`; после — пустой инкремент | Must | `snapshotWalksAllObjectsInPagesAndCompletes` (`AT/AssetAdapterContractTest.java:53-66`) |
| RF-02 | Инкремент по курсору возвращает только изменения после него: `sourceId`, `sourceVersion` (=2 после апдейта), `payload` | Must | `incrementalPollingReturnsOnlyChangesAfterCursor` (`:68-80`) |
| RF-03 | `fetchById` возвращает состояние; для неизвестного объекта `Optional.empty()` | Must | `fetchByIdReturnsStateAndEmptyForUnknownObject` (`:82-89`) |
| RF-04 | Удаление видно и в `fetchById`, и в инкременте как `ChangeOperation.DELETE` | Must | `deleteIsVisibleAsTombstone` (`:91-101`) |
| RF-05 | `429` с `Retry-After: 3` → `SourceUnavailableException`, `isRateLimited()`, `retryAfter() == 3 с`; состояние источника не теряется (повтор отдаёт 1 объект) | Must | `rateLimitCarriesRetryAfterAndLeavesStateIntact` (`:103-113`) |

## Правила коннектора (реализует `adapter-core`) 🟢
- `limit <= 0` → `IllegalArgumentException("limit must be positive")` (`adapters/adapter-core/.../HttpSourceConnector.java:52-54`); тестом asset-adapter не покрыто 🟡.
- `429`, `5xx` и сбой сети → `SourceUnavailableException` (статус 0 для сети), `Retry-After` только в секундах, HTTP-date игнорируется (`:97-100,110-119`).
- Прочий статус ≠ 200 → `IllegalStateException` без тела ответа (тело недоверенное) (`:104-108`).
- `404` на объект → пусто (`:73-75`).

## Критерии приёмки

```gherkin
Дано заглушка CMDB с одним объектом vm-pay-01
Когда fetchChanges(null, 1) и затем по nextCursor, пока hasMore
Тогда всего получен 1 объект, на последней странице snapshotComplete=true, а последующий fetchChanges по nextCursor пуст

Дано снятый курсор после snapshot и upsert vm-pay-01 (state=changed)
Когда fetchChanges(cursor, 100)
Тогда одна запись: sourceVersion=2, payload.state="changed"

Дано объект vm-pay-01 удалён в источнике
Когда fetchById и fetchChanges(cursor)
Тогда операция DELETE

Дано источник, отвечающий 429 с Retry-After 3
Когда fetchChanges(null, 10)
Тогда SourceUnavailableException(rateLimited, retryAfter=3с); следующий вызов успешен и возвращает 1 объект

Дано fetchById("COMPUTE_INSTANCE", "no-such-object")
Тогда Optional.empty()
```

## Трассировка
`AT/AssetAdapterContractTest.java:20-113`. Дизайн: [`design.md`](design.md).
