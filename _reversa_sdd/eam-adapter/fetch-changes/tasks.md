# fetch-changes — задачи

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`.

## Pre-requisites

- [ ] HTTP-слой `EamApiConnector.get` готов — [`../eam-api-request/tasks.md`](../eam-api-request/tasks.md).
- [ ] `adapter-core.Json` отдаёт `Long` для целых.

## Задачи

- [ ] T-F1 Таблица типов `TYPES` и константа `SOLUTION_TYPE`.
  - Origin: `EA/EamApiConnector.java:47-54`
  - Критерий готовности: порядок `platforms, itproducts, itsystems`; `PLATFORM/SOLUTION/IT_SYSTEM`.
  - Уверенность: 🟢
- [ ] T-F2 `EamApiCursor`: record, режимы, `snapshotStart`, `watermark`, `incrementalStart`, `encode`, `parse`, `check`.
  - Origin: `EA/EamApiCursor.java:18-69`
  - Критерий готовности: все три формата round-trip; ошибки → `IllegalArgumentException("malformed eam-api cursor")` (в т.ч. неверное число полей, `page < 1`, `size < 1`, `type < 0`, неизвестный режим).
  - Уверенность: 🟢
- [ ] T-F3 `fetchChanges`: проверка `limit`, выбор `size`, разбор курсора, `WATERMARK → INCREMENTAL`, проверка `type < 3`, выбор `sort`.
  - Origin: `EA/EamApiConnector.java:93-107`
  - Критерий готовности: `EamApiConnectorTest.pageSizeIsClampedTo500`, `malformedCursorIsRejected` зелёные.
  - Уверенность: 🟢
- [ ] T-F4 Цикл по объектам: остановка по известному объекту (только инкремент), накопление водяного знака, `typeDone`, переход страница/тип/конец.
  - Origin: `EA/EamApiConnector.java:108-132`
  - Критерий готовности: `snapshotCompleteOnlyOnLastPageOfLastType`, `incrementalReturnsOnlyObjectsChangedSinceWatermark`, `incrementalNextCursorMovesWatermarkForward` зелёные.
  - Уверенность: 🟢
- [ ] T-F5 `change`/`integral`: преобразование `ArchObject` → `SourceChange`; ошибки без значений источника.
  - Origin: `EA/EamApiConnector.java:168-195`
  - Критерий готовности: `snapshotReadsThreeTypesWithIntegerIdAsSourceId`, `malformedIntegerIdIsRejected` зелёные.
  - Уверенность: 🟢
- [ ] T-F6 `list`: `GET /api/<type>/?page=<p>&page_size=<s>&sort=<sort>`, ответ — массив, иначе `IllegalStateException`.
  - Origin: `EA/EamApiConnector.java:159-166`
  - Критерий готовности: `pageSizeIsClampedTo500` проверяет `page_size=500` и `sort=id` в строке запроса.
  - Уверенность: 🟢

## Тестовые задачи

Есть: TT-F1 `snapshotReadsThreeTypesWithIntegerIdAsSourceId`, TT-F2 `paginationWithFullLastPageContinuesToEmptyPage`, TT-F3 `paginationWithPartialLastPageStopsImmediately`, TT-F4 `snapshotCompleteOnlyOnLastPageOfLastType`, TT-F5 `incrementalReturnsOnlyObjectsChangedSinceWatermark`, TT-F6 `incrementalNextCursorMovesWatermarkForward`, TT-F7 `archivedObjectDisappearsFromSnapshot`, TT-F8 `referencesAcceptSingleValueAndNullInPayload`, TT-F9 `malformedIntegerIdIsRejected`, TT-F10 `malformedCursorIsRejected` — все в `ET/EamApiConnectorTest.java`.

Пробелы (🟡, ожидание выведено из кода):

- [ ] TT-F11 `fetchChanges(null, 0)` и `fetchChanges(null, -1)` → `IllegalArgumentException("limit must be positive")` (`EA/EamApiConnector.java:94-96`).
- [ ] TT-F12 Round-trip `EamApiCursor.parse(encode(c))` для `SNAPSHOT`, `INCREMENTAL`, `WATERMARK` и отказ на неполных строках (`EA/EamApiCursor.java:39-69`).
- [ ] TT-F13 `datetime == null` → `updatedAt == Instant.EPOCH`; нестроковый/непарсимый `datetime` → `IllegalStateException` (`EA/EamApiConnector.java:180-186`).
- [ ] TT-F14 Инкремент прерывает чтение на известном объекте в середине страницы и не включает его (`:115-118`).
- [ ] TT-F15 `ver`/`attrs` отсутствуют или неверного типа → `IllegalStateException`.

## Порядок

T-F1 → T-F2 → T-F6 → T-F5 → T-F3/T-F4. `fetchChanges` собирается последним: он использует все остальные.

## Открытые 🔴

Нет 🔴 в сценарии; 🟡 — Q-2, Q-3, Q-5, Q-9 в [`../questions.md`](../questions.md).
