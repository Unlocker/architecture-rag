# adapters/eam-adapter — контракты

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`. Внешних HTTP-эндпоинтов модуль **не выставляет**; он потребляет `eam-api` и выставляет Java-контракт `SourceConnector` и строковые курсоры.

## 1. Потребляемый HTTP-контракт `eam-api` (только GET)

Базовый URL — `EamApi.baseUri` (завершающие `/` обрезаются: `baseUri.toString().replaceAll("/+$", "")`, `EA/EamApiConnector.java:224`) 🟢. Заголовки каждого запроса: `Accept: application/json`, `Authorization: Token <токен>` (`:226-227`) 🟢. Таймаут запроса 10 с, соединения 5 с (`:64, 225`) 🟢. Redirect'ы не следуются (по умолчанию `HttpClient`) → 3xx превращается в `IllegalStateException` 🟡.

| Запрос | Параметры | Использует | Ожидаемый ответ |
|---|---|---|---|
| `GET /api/platforms/` | `page` (с 1), `page_size` (≤ 500), `sort` = `id` (snapshot) или `-mtime` (инкремент) | `EamApiConnector.list` (`:159-166`) | `200`, JSON-массив `ArchObject[]` (без `total`/`next`) |
| `GET /api/itproducts/` | то же; тип = «ИТ-решение» | то же | то же |
| `GET /api/itsystems/` | то же | то же | то же |
| `GET /api/<type>/<id>/` | `id` — `long` | `EamApiConnector.fetchById` (`:147`) | `200` + `ArchObject`; `404` → объект неизвестен |

Статусы (`:205-246`) 🟢: `200` — успех; `404` — только для `fetchById` (для списка `404` → `IllegalStateException`); `429`/`≥ 500` → `SourceUnavailableException(EAM, status, retryAfter)`; всё остальное (401, 403, 3xx, 4xx) → `IllegalStateException("eam answered unexpected status <n>")` без тела; IO-ошибка/timeout → `SourceUnavailableException(EAM, 0, null)`.

### `ArchObject` (вход)

```json
{ "id": 5101, "ver": 1, "datetime": "2026-10-01T09:00:00Z",
  "attrs": { "name": "Payments Core", "eam_id": "EAM-1042", "status": "ACTIVE", "criticality": "HIGH",
             "description": "…", "product_id": 7201, "platform_id": 3101, "ownerTeam": "TEAM-PAY" } }
```

(пример — `adapters/eam-adapter/src/test/resources/eam-api/baseline/itsystems.json`). Правила разбора `EamApiConnector.change` (`:169-188`) 🟢:

| Поле | Правило |
|---|---|
| корень | должен быть JSON-объектом (`Map`), иначе `IllegalStateException("eam answered malformed ArchObject")` |
| `id` | обязательное целое (`Long` после `Json.parse`); иначе та же ошибка. `sourceId = Long.toString(id)` |
| `ver` | обязательное целое → `sourceVersion` |
| `attrs` | обязательный объект (его содержимое не разбирается) |
| `datetime` | строка ISO-8601 для `Instant.parse`; `null`/отсутствует → `Instant.EPOCH` (🟡 Н-17); не-строка/не парсится → ошибка выше |
| прочие поля | попадают в `payload` без изменений |

`datetime` в EAM трактуется как время изменения (аналог `mtime`) 🟡.

## 2. Исходящий контракт: `SourceChange`

Для каждого объекта EAM (`EA/EamApiConnector.java:182-183`) 🟢:

| Поле `SourceChange` | Значение |
|---|---|
| `sourceType` | `PLATFORM` (`platforms`), `SOLUTION` (`itproducts`), `IT_SYSTEM` (`itsystems`) — `:51-54` |
| `sourceId` | `Long.toString(id)` |
| `sourceVersion` | `ver` |
| `operation` | всегда `ChangeOperation.UPSERT` |
| `completeness` | всегда `Completeness.COMPLETE` |
| `updatedAt` | `datetime` (или `EPOCH`) |
| `payload` | весь `ArchObject` (`id`, `ver`, `datetime`, `attrs`) — копия `LinkedHashMap`, неизменяемая (`contracts/source-spi/.../SourceChange.java:40-41`) |

Потребитель — `adapter-core` (`EventMapper`: событие `poll:<type>/<id>/v<ver>` / `snap:<runId>:…`, subject `platform/5101`, `solution/…`, `it-system/…`; raw в S3 — `AC/InboxWriter.java`) и далее `ingestion/normalizer` (`EamMapper`). Описание — `_reversa_sdd/data-dictionary.md` §P.4–P.5.

Страница: `ChangePage(changes, nextCursor, hasMore, snapshotComplete)`; инвариант `snapshotComplete && hasMore` запрещён (`contracts/source-spi/.../ChangePage.java:20-25`) 🟢.

## 3. Форматы курсоров

### 3.1 `EamApiCursor` (`EA/EamApiCursor.java`) 🟢

| Режим | Кодировка | Поля |
|---|---|---|
| `SNAPSHOT` | `S,<type>,<page>,<size>,<watermark>` | `type` — индекс в `TYPES` (0=platforms, 1=itproducts, 2=itsystems); `page` с 1; `size` ≥ 1; `watermark` — ISO-8601 UTC |
| `INCREMENTAL` | `I,<type>,<page>,<size>,<from>,<watermark>` | `from` — нижняя граница (исключая), `watermark` — максимум `datetime` |
| `WATERMARK` | `W,<watermark>` | состояние между проходами |

`parse`: любая ошибка → `IllegalArgumentException("malformed eam-api cursor")`; проверяется число частей (5/6/2), `type ≥ 0`, `page ≥ 1`, `size ≥ 1` (кроме `W`); дополнительно `fetchChanges` проверяет `type < 3` (`EA/EamApiConnector.java:103-105`). `Instant.toString()` не содержит запятых; наносекундная точность сохраняется. Начальные значения: snapshot `S,0,1,<size>,1970-01-01T00:00:00Z`; `W,<w>` → `I,0,1,<size>,<w>,<w>`.

### 3.2 Составной курсор (`EA/CompositeEamConnector.java:118-155`) 🟢

```
<S|I>;<A|L>;eamapi:<b64url(api-cursor)|->|legacy:<b64url(legacy-cursor)|->
```

`S`/`I` — snapshot/инкремент; `A`/`L` — читаемая половина; `-` — `null`; base64url без паддинга, UTF-8. Пример начального snapshot-курсора после первой страницы: `S;A;eamapi:<b64("S,0,2,…")>|legacy:-`. Разбор: `split(";", 3)`, затем `split("\\|", 2)`, префиксы `eamapi:` и `legacy:` обязательны; любое нарушение → `IllegalArgumentException("malformed eam cursor")`.

## 4. Внешние ограничения (допущения `docs/06-eam-integration.md`)

- `page_size` — по умолчанию в EAM 50, максимум 500; адаптер всегда передаёт явный `page_size` = `min(config.pageSize, limit, 500)` 🟢.
- ≤ 10 запросов/с, `Retry-After` при 429 — только целое число секунд ≥ 0; HTTP-date не поддерживается (`EA/EamApiConnector.java:271-280`) 🟢.
- Сортировка: допустимы `id`, `ctime`, `mtime` (в коннекторе используются `id` и `-mtime`) 🟢.
- Фильтра «изменено после T» нет; webhook'ов нет (`docs/06-eam-integration.md` §«Схема синхронизации») 🟢.
