# fetch-changes — дизайн

> Ссылается на модульный [`../design.md`](../design.md) (интерфейс, зависимости, связанные спецификации) и [`../contracts.md`](../contracts.md) §1, §3. `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`. Блок-схема — [`../../flowcharts/eam-adapter.md`](../../flowcharts/eam-adapter.md) §1, §5.

## Интерфейс

`ChangePage EamApiConnector.fetchChanges(String cursor, int limit)` (`EA/EamApiConnector.java:93`) → `ChangePage(changes, nextCursor, hasMore, snapshotComplete)`.

Таблица `TYPES` (индекс = `c.type`): `0: PLATFORM→platforms`, `1: SOLUTION→itproducts`, `2: IT_SYSTEM→itsystems` (`:51-54`) 🟢.

## Алгоритм (псевдокод, порядок шагов важен)

```
fetchChanges(cursor, limit):
  require limit > 0                                  # IAE "limit must be positive"      :94-96
  size = min(pageSize, limit)                        # pageSize уже ≤ 500                :97
  c = cursor == null ? snapshotStart(size) : parse(cursor)   # snapshotStart = S,0,1,size,EPOCH   :98
  if c.mode == WATERMARK: c = c.incrementalStart(size)       # I,0,1,size,from=w,w      :99-101
  snapshot = (c.mode == SNAPSHOT)                    #                                    :102
  require c.type < 3                                 # IAE "malformed eam-api cursor"    :103-105
  (srcType, apiType) = TYPES[c.type]
  sort = snapshot ? "id" : "-mtime"                  #                                    :107
  objects = list(apiType, c.page, c.size, sort)      # ровно 1 HTTP GET                  :108
  changes = []; watermark = c.watermark; reachedKnown = false
  for o in objects:                                  #                                    :113-123
     ch = change(srcType, o)
     if !snapshot and !ch.updatedAt.isAfter(c.from): reachedKnown = true; break
     changes += ch
     if ch.updatedAt.isAfter(watermark): watermark = ch.updatedAt
  typeDone = reachedKnown or objects.size < c.size   #                                    :124
  if !typeDone:                       return Page(changes, next(c, c.type,   c.page+1, watermark), hasMore=true,  snapshotComplete=false)
  if c.type + 1 < 3:                  return Page(changes, next(c, c.type+1, 1,        watermark), hasMore=true,  snapshotComplete=false)
  return Page(changes, W,<watermark>, hasMore=false, snapshotComplete=snapshot)
```

`next(c, type, page, wm)` сохраняет `mode`, `size`, `from` курсора и подставляет новые `type/page/watermark` (`:155-157`) 🟢.

### Кодировки и точная форма курсора

- `S,<type>,<page>,<size>,<watermark>`; `I,<type>,<page>,<size>,<from>,<watermark>`; `W,<watermark>` — `EA/EamApiCursor.java:39-45` 🟢.
- В режиме `S` поле `from` всегда `EPOCH` и в строку не пишется (`:26-28, 48-52`).
- `W` разбирается в `watermark(w)`: `from = watermark = w`, `page=1`, `size=0` (`:30-32`); размер подставляется при `incrementalStart(size)`.
- Валидация `check`: число полей (5/6/2) и `type ≥ 0`, `page ≥ 1`, `size ≥ 1` (не для `W`) (`:64-69`).

### Преобразование объекта (`change`, `:169-188`)

1. `o` не `Map` → `IllegalStateException("eam answered malformed ArchObject")`.
2. `id = integral(map.id)`, `ver = integral(map.ver)` (`integral`: только `Long`; иначе `IllegalStateException("integer expected")`) (`:190-195`).
3. `attrs` обязан быть `Map`, иначе `IllegalStateException("attrs")`.
4. `updatedAt = datetime == null ? EPOCH : Instant.parse((String) datetime)`.
5. Любое `RuntimeException` внутри шагов 2–4 оборачивается в `IllegalStateException("eam answered malformed ArchObject", cause)` (`:184-186`).
6. Результат: `SourceChange(srcType, Long.toString(id), ver, UPSERT, COMPLETE, updatedAt, archObject)` — `payload` содержит весь объект.

## Контрольный пример (из `EamApiConnectorTest`)

`page_size=2`, по 2 объекта на тип (`paginationWithFullLastPageContinuesToEmptyPage`): вызовы с курсорами `null` → `S,0,2,2,…` (стр. 2 платформ, пустая) → `S,1,1,2,…` → `S,1,2,2,…` → `S,2,1,2,…` → `S,2,2,2,…` → последняя страница с `W,<max datetime>`: всего 6 страниц, 6 изменений 🟢.

## Альтернативные потоки

- Пустой тип: `objects.size() == 0 < size` → тип завершён сразу, переход к следующему (`:124-130`).
- Инкремент пересёк известный объект в середине страницы: изменения до него отдаются, остальная страница и тип отбрасываются (`:115-118`).
- Любая ошибка HTTP/разбора — исключение до формирования страницы; курсор не сдвигается, повтор безопасен (чтение без побочных эффектов) 🟢.

## Зависимости

`Json.parse` (`adapter-core`) — отдаёт `Long` для целых, `Double` для дробных (`AC/Json.java:27, 169-171`); HTTP-слой — [`../eam-api-request/design.md`](../eam-api-request/design.md).

## Связанные спецификации

[`../design.md`](../design.md) §«Зависит от / Связанные спецификации» целиком; особенно `ingestion/normalizer` (`EamMapper`) как потребитель `payload` и `adapter-core` (`Poller.snapshot`/`incremental`: сохранение курсора `AC/Poller.java:136, 171`, защита от «hasMore без нового курсора» `:130-133, 157-160`) 🟢.

## Риски и пробелы

См. [`../questions.md`](../questions.md): Q-2 (строгое сравнение водяного знака), Q-3 (`EPOCH` для `datetime == null`: в инкременте такой объект всегда прерывает тип), Q-5 (`cause` с текстом даты), Q-9 (допущение «`datetime` ≈ `mtime`»). 🟡: `datetime` в инкременте сравнивается с `from` в пределах одной сортировки `-mtime`; если порядок `-mtime` и значения `datetime` разойдутся, чтение остановится раньше срока.
