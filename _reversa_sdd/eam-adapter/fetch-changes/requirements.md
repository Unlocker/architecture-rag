# fetch-changes — чтение изменений `eam-api` (`EamApiConnector.fetchChanges`)

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`. Модуль: [`../requirements.md`](../requirements.md); дизайн — [`design.md`](design.md).

## Обзор

Сценарий постраничного чтения трёх типов EAM (`platforms` → `itproducts` → `itsystems`) в двух режимах: полный snapshot (`cursor == null`, `sort=id`) и инкремент от водяного знака (`W,…`, `sort=-mtime`). Результат — `ChangePage` с `SourceChange` (UPSERT/COMPLETE) и курсором; snapshot завершается маркером `snapshotComplete`, инкремент — нет 🟢 (`EA/EamApiConnector.java:93-132`).

## Правила

- Один вызов = один HTTP-запрос списка одного типа и одной страницы 🟢 (`:108`).
- Размер страницы на проход = `min(pageSize, MAX_PAGE_SIZE=500, limit)` в момент старта прохода и далее фиксирован курсором 🟢 (`:82, 97, 126, 156`).
- Конец типа: `reachedKnown` (только инкремент) или `objects.size() < c.size()` 🟢 (`:124`).
- Конец прохода: после `itsystems`; ответ `hasMore=false`, `nextCursor=W,<watermark>`, `snapshotComplete = (режим == SNAPSHOT)` 🟢 (`:131`).
- Инкремент останавливается на первом объекте с `updatedAt ≤ from` (строго `!isAfter`) и **не включает его** 🟢 (`:115-118`).
- Водяной знак — максимум `updatedAt` среди **принятых** объектов, не меньше исходного 🟢 (`:111, 120-122`).
- `limit ≤ 0` → `IllegalArgumentException("limit must be positive")` 🟢 (`:94-96`).
- Курсор режима `W` превращается в `I` (`incrementalStart`) с `from = watermark` 🟢 (`:99-101`, `EA/EamApiCursor.java:35-37`).
- Индекс типа в курсоре `≥ 3` → `IllegalArgumentException("malformed eam-api cursor")` 🟢 (`:103-105`).
- Ответ списка — не массив → `IllegalStateException("eam answered a non-array list")` 🟢 (`:162-165`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|---|---|---|---|
| FC-01 | Snapshot отдаёт `PLATFORM`, `SOLUTION`, `IT_SYSTEM` в этом порядке с `sourceId = id`, `version = ver`, `updatedAt = datetime` | Must | `snapshotReadsThreeTypesWithIntegerIdAsSourceId` (`ET/EamApiConnectorTest.java:84`) |
| FC-02 | Страница ровно `size` не завершает тип; следующая (в т.ч. пустая) завершает | Must | `paginationWithFullLastPageContinuesToEmptyPage` (`:98`) |
| FC-03 | Неполная страница завершает тип без лишнего запроса | Must | `paginationWithPartialLastPageStopsImmediately` (`:107`) |
| FC-04 | `snapshotComplete` — ровно на одной странице, последней, `hasMore=false` | Must | `snapshotCompleteOnlyOnLastPageOfLastType` (`:115`) |
| FC-05 | Инкремент возвращает только объекты новее водяного знака, по убыванию `mtime` | Must | `incrementalReturnsOnlyObjectsChangedSinceWatermark` (`:133`) — ожидает `["PLATFORM/3102","IT_SYSTEM/5101"]`, версии `2,2` |
| FC-06 | Итоговый курсор инкремента `W,<максимальный datetime>`, `snapshotComplete=false`, повторное чтение пусто | Must | `incrementalNextCursorMovesWatermarkForward` (`:146`) |
| FC-07 | Архивный объект не попадает в snapshot (пропал из списка) | Must | `archivedObjectDisappearsFromSnapshot` (`:171`) |
| FC-08 | `attrs` ссылок отдаются как есть (число, `null`, отсутствие); разбор — задача нормализатора | Should | `referencesAcceptSingleValueAndNullInPayload` (`:199`) |
| FC-09 | Некорректный `id` (строка, не `Long`) → `IllegalStateException` без значения в сообщении | Must | `malformedIntegerIdIsRejected` (`:212`) |
| FC-10 | Битый курсор → `IllegalArgumentException` | Must | `malformedCursorIsRejected` (`:296`) |

## Критерии приёмки (Дано/Когда/Тогда)

```gherkin
Сценарий: полный snapshot, три типа
  Дано eam-api с платформами 3101,3102, решениями 7201,7202, системами 5101,5102
  Когда коннектор читает fetchChanges(null, 100) и далее по nextCursor
  Тогда ключи — PLATFORM/3101, PLATFORM/3102, SOLUTION/7201, SOLUTION/7202, IT_SYSTEM/5101, IT_SYSTEM/5102
  И последняя страница: hasMore=false, snapshotComplete=true
  # EamApiConnectorTest.snapshotReadsThreeTypesWithIntegerIdAsSourceId

Сценарий: пагинация с полной последней страницей
  Дано page_size=2 и ровно по 2 объекта на тип
  Когда читается snapshot
  Тогда выполняется 6 страниц (по 2 на тип: полная и пустая)
  # EamApiConnectorTest.paginationWithFullLastPageContinuesToEmptyPage

Сценарий: инкремент после изменений
  Дано курсор W,<w> после snapshot и затем изменены платформа 3102 и система 5101 (ver 2)
  Когда читается fetchChanges(W,…) до hasMore=false
  Тогда возвращаются PLATFORM/3102 и IT_SYSTEM/5101 с sourceVersion 2
  # EamApiConnectorTest.incrementalReturnsOnlyObjectsChangedSinceWatermark

Сценарий: инкремент без изменений
  Дано курсор W,<w>
  Когда читается инкремент
  Тогда changes пусты, hasMore=false, nextCursor=W,<w>
  # EamApiConnectorTest.incrementalReturnsOnlyObjectsChangedSinceWatermark (первый assert)

Сценарий (негатив): объект с нечисловым id
  Дано ответ [{"id":"A","ver":1,"attrs":{}}]
  Когда читается snapshot
  Тогда IllegalStateException "eam answered malformed ArchObject" без значения "A"
  # EamApiConnectorTest.malformedIntegerIdIsRejected

Сценарий (негатив): курсор не коннектора
  Когда fetchChanges("garbage", 10) или курсор "S,9,1,10,2026-01-01T00:00:00Z"
  Тогда IllegalArgumentException
  # EamApiConnectorTest.malformedCursorIsRejected

Сценарий (негатив): limit <= 0
  Когда fetchChanges(null, 0)
  Тогда IllegalArgumentException("limit must be positive")
  # теста нет 🟡
```

## MoSCoW

| Требование | MoSCoW | Обоснование |
|---|---|---|
| FC-01…FC-04, FC-07 | Must | основной snapshot и reconcile |
| FC-05, FC-06 | Must | основной путь свежести (polling) |
| FC-09, FC-10 | Must | защита от недоверенного/повреждённого входа |
| FC-08 | Should | граница ответственности коннектор/нормализатор |
| Компенсация сдвига страниц `-mtime` | Won't | решается периодическим snapshot (`docs/06-eam-integration.md` п. 6) |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `EA/EamApiConnector.java:93-132` | `fetchChanges` | 🟢 |
| `EA/EamApiConnector.java:155-157` | `next` | 🟢 |
| `EA/EamApiConnector.java:159-166` | `list` | 🟢 |
| `EA/EamApiConnector.java:168-195` | `change`, `integral` | 🟢 |
| `EA/EamApiCursor.java` | `EamApiCursor` | 🟢 |
