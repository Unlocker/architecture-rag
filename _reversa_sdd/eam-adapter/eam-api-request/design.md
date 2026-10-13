# eam-api-request — дизайн

> Ссылается на [`../design.md`](../design.md) и [`../contracts.md`](../contracts.md) §1. `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`. Блок-схема — [`../../flowcharts/eam-adapter.md`](../../flowcharts/eam-adapter.md) §2.

## Интерфейс

| Символ | Сигнатура | Где |
|---|---|---|
| `EamApiConnector(URI, String, int)` | публичный; `Sleeper.THREAD`, `System::nanoTime` | `EA/EamApiConnector.java:68-70` |
| `EamApiConnector(URI, String, int, Sleeper, LongSupplier)` | пакетный, для тестов | `:73-85` |
| `Optional<SourceChange> fetchById(String sourceType, String sourceId)` | публичный | `:135-153` |
| `private HttpResponse<String> get(String pathAndQuery)` | 429-логика | `:205-219` |
| `private HttpResponse<String> send(String pathAndQuery)` | один запрос | `:221-239` |
| `private void requireOk(HttpResponse<String>)` | только 200 | `:241-246` |
| `private synchronized void throttle()` | интервал 100 мс | `:249-260` |
| `private void pause(Duration)` | `Sleeper` + interrupt | `:262-269` |
| `private static Duration retryAfter(HttpResponse<String>)` | заголовок → `Duration`/`null` | `:271-280` |

`Sleeper` — `void sleep(Duration) throws InterruptedException`; `Sleeper.THREAD = d -> Thread.sleep(d.toMillis())` (`AC/Sleeper.java:7-10`) 🟢.

## Алгоритм `get`

```
r = send(path)
if r.status == 429:
   wait = retryAfter(r)                              # целое сек ≥ 0, иначе null
   if wait != null:
      pause(min(wait, 30 с)); r = send(path)         # ровно один повтор
if r.status == 429 or r.status >= 500:
   throw SourceUnavailableException(EAM, r.status, retryAfter(r))   # retryAfter последнего ответа, без среза до 30 с
return r                                             # 200, 404, прочее — решает вызывающий (requireOk)
```

`send`: `throttle()` → запрос `GET <base без хвостовых />><pathAndQuery>` (`Accept`, `Authorization`, timeout 10 с) → `HttpClient.send(..., BodyHandlers.ofString(UTF_8))`; `IOException` → `SourceUnavailableException(EAM, 0, null)`; `InterruptedException` → `interrupt()` + `IllegalStateException("interrupted while reading eam")` (`:221-239`) 🟢.

`throttle`: при первом запросе (`requested == false`) пауз нет. Далее `wait = lastRequestNanos + 100 мс − now`; если `wait > 0` — `pause(wait)` и `now += wait`; `lastRequestNanos = now`. Часы — `LongSupplier nanoClock`, паузы — `Sleeper` (в тестах подменяются; паузы там = 100 мс, потому что часы идут только за счёт пауз) (`:249-260`, `ET/EamApiConnectorTest.java:60, 275-279`) 🟢.

`retryAfter`: `Long.parseLong(trim)`, `< 0` или нечисло → `null` (`:271-280`).

## `fetchById` (`:135-153`)

1. Путь по `TYPES` для `sourceType` (может быть `null`).
2. `Long.parseLong(sourceId)`; `NumberFormatException` → пусто. Значения вида `007`, `+7` разбираются в `7` и уходят в путь как число — внешний ввод в URL не подставляется (защита от `../types`).
3. Тип неизвестен → пусто (проверка **после** разбора `id`).
4. `GET /api/<path>/<id>/`; `404` → пусто; иначе `requireOk` (только 200) → `change(sourceType, parse(body))`.

## Решения проектирования

| Решение | Evidence | Уверенность |
|---|---|---|
| Повтор после 429 ровно один; дальше — на `RetryPolicy` Poller'а | `EA/EamApiConnector.java:205-219`; `AC/Poller.java:190-205` | 🟢 |
| Срез ожидания до 30 с (чтобы не блокировать поток poller'а надолго) | `:57, 210` | 🟡 (мотив выведен) |
| Статусы 401/403/прочие — ошибка без повторов (повтор не поможет) | `:243` (комментарий) | 🟢 |
| `HttpClient` без следования redirect'ам (значение по умолчанию) и без прокси-настроек | `:64` | 🟡 |

## Зависимости

`java.net.http`, `Sleeper`, `Json` (`adapter-core`), `SourceUnavailableException` (`source-spi`: сообщение `eam unavailable, status=<n>`; `isRateLimited()` ⇔ 429; `contracts/source-spi/.../SourceUnavailableException.java:18-22`) 🟢.

## Связанные спецификации

[`../design.md`](../design.md) §«Зависит от / Связанные спецификации»: `contracts/source-spi` (исключение и `SourceConnector.fetchById`), `adapter-core` (`Poller.fetch` и `RetryPolicy.delay(attempt, e, random)`, использующий `retryAfter`; `WebhookHandler` → 503 при `SourceUnavailableException`, `AC/WebhookHandler.java:86-89`), `platform/source-stubs` (`EamApiStub`: проверка токена, `429`, `404` для архивных).

## Наблюдаемость

Логов нет; все сбои — исключения с кодом статуса (`SourceUnavailableException.statusCode`) или фиксированным текстом без значений источника (`:171, 186, 201, 237, 244, 267`) 🟢.

## Риски и пробелы

- 🟡 `fetchById` на 404 возвращает пусто, а не `DELETE` (расхождение с Javadoc `SourceConnector.fetchById`; для EAM webhook'ов нет).
- 🟡 `Retry-After` в формате HTTP-date не поддерживается (трактуется как отсутствие).
- 🟡 Q-1: нет проверки прав сервисной учётки при подключении (200 на списки, 403/404 на `buffers`).
- 🟡 Нет повторов для IO-ошибок внутри коннектора; повтор выполняет `Poller` (`SourceUnavailableException` со status 0).
