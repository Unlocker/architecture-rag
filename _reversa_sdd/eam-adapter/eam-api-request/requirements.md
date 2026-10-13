# eam-api-request — HTTP-доступ к `eam-api`, лимиты, ошибки, `fetchById`

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`. Модуль: [`../requirements.md`](../requirements.md); дизайн — [`design.md`](design.md).

## Обзор

Нижний слой `EamApiConnector`: построение и отправка `GET`-запросов с токеном, троттлинг 10 запросов/с, единственный повтор после 429 с `Retry-After`, отображение статусов на исключения и точечное чтение объекта (`fetchById`). Токен и тела ответов не попадают в исключения и логи 🟢.

## Правила

- Конструктор: `baseUri` не `null` (`NPE "baseUri"`); токен `null`/пробельный → `IllegalArgumentException("eam-api token is required")`; `pageSize < 1` → `IllegalArgumentException("pageSize must be positive")`; `pageSize = min(pageSize, 500)` 🟢 (`EA/EamApiConnector.java:73-85`).
- Заголовок авторизации: `Authorization: Token <токен>`; `Accept: application/json`; только `GET` 🟢 (`:81, 226-228`).
- Таймаут соединения 5 с, запроса 10 с 🟢 (`:64, 225`).
- Троттлинг: минимум 100 мс между **любыми** двумя запросами (включая повтор после 429); первый запрос не ждёт 🟢 (`:56, 249-260`).
- 429 с `Retry-After` (целое секунд ≥ 0): одна пауза `min(Retry-After, 30 с)` и один повтор; остальные 429 (без заголовка, битый, отрицательный, HTTP-date, второй подряд) → `SourceUnavailableException(EAM, 429, retryAfter ответа)` 🟢 (`:57, 205-219, 271-280`).
- `≥ 500` → `SourceUnavailableException(EAM, status, retryAfter)` без повтора внутри 🟢 (`:214-217`).
- IO-ошибка (в т.ч. timeout) → `SourceUnavailableException(EAM, 0, null)`; прерывание → флаг interrupt восстанавливается + `IllegalStateException("interrupted while reading eam")` 🟢 (`:232-238, 262-269`).
- Любой статус кроме 200 (после обработки выше), включая 401/403/3xx, для списка и для `fetchById` кроме 404 → `IllegalStateException("eam answered unexpected status <n>")` без тела 🟢 (`:241-246`).
- `fetchById`: `sourceType` → путь по `TYPES`; `sourceId` → `Long.parseLong` (иначе пусто **без запроса**); 404 → `Optional.empty()`; 200 → `SourceChange` UPSERT 🟢 (`:135-153`).
- Тело, не являющееся JSON → `IllegalStateException("eam answered malformed JSON")` 🟢 (`:197-203`).
- `close()` закрывает `HttpClient` 🟢 (`:282-285`).

## Функциональные требования

| ID | Требование | Приоритет | Критерий приёмки |
|---|---|---|---|
| FR-01 | 401 от источника → `IllegalStateException` со статусом и без токена | Must | `wrongTokenIsRejectedWithoutLeakingIt` (`ET/EamApiConnectorTest.java:177`) |
| FR-02 | Пустой токен отвергается при создании | Must | `blankTokenIsRefused` (`:185`) |
| FR-03 | 429 + `Retry-After: 3` → пауза 3 с, повтор, успех | Must | `rateLimitWithRetryAfterWaitsOnceAndRetries` (`:222`) |
| FR-04 | Два 429 подряд → `SourceUnavailableException(429, retryAfter=1 с)`, ровно 2 запроса | Must | `repeatedRateLimitSurfacesAsSourceUnavailable` (`:241`) |
| FR-05 | 429 без `Retry-After` и 503 → `SourceUnavailableException` без внутреннего повтора (1 запрос на вызов) | Must | `rateLimitWithoutRetryAfterAndServerErrorAreNotRetriedInside` (`:259`) |
| FR-06 | Интервал между запросами 100 мс (проверка: все паузы равны 100 мс) | Must | `requestsAreSpacedToTenPerSecond` (`:275`) |
| FR-07 | `page_size` не более 500 в запросе | Must | `pageSizeIsClampedTo500` (`:282`) |
| FR-08 | `fetchById` читает объект; 404, неизвестный тип, нечисловой/подозрительный `id` (`../types`) → пусто | Should | `fetchByIdReadsObjectAndReturnsEmptyFor404` (`:190`) |
| FR-09 | Нечисловой `id` в ответе → ошибка без значения | Must | `malformedIntegerIdIsRejected` (`:212`) |

## Критерии приёмки (Дано/Когда/Тогда)

```gherkin
Сценарий: ограничение частоты
  Дано часы коннектора идут только за счёт пауз
  Когда читается полный snapshot
  Тогда каждая пауза равна 100 мс (запрос начинается не раньше чем через 100 мс после предыдущего)
  # EamApiConnectorTest.requestsAreSpacedToTenPerSecond

Сценарий: 429 с Retry-After
  Дано источник отвечает 429 с Retry-After: 3, затем 200 []
  Когда читается первая страница
  Тогда есть пауза 3 с и ровно 2 запроса, результат — пустая страница
  # EamApiConnectorTest.rateLimitWithRetryAfterWaitsOnceAndRetries

Сценарий (негатив): повторный 429
  Дано источник всегда отвечает 429 с Retry-After: 1
  Когда читается страница
  Тогда SourceUnavailableException, isRateLimited()=true, retryAfter=1 с, запросов ровно 2
  # EamApiConnectorTest.repeatedRateLimitSurfacesAsSourceUnavailable

Сценарий (негатив): 503 и 429 без заголовка
  Дано 429 без Retry-After, затем 503
  Когда два вызова fetchChanges
  Тогда оба — SourceUnavailableException (второй со statusCode 503), по одному запросу на вызов
  # EamApiConnectorTest.rateLimitWithoutRetryAfterAndServerErrorAreNotRetriedInside

Сценарий (негатив): неверный токен
  Дано токен "wrong-token"
  Когда fetchChanges(null, 100)
  Тогда IllegalStateException с "401", без "wrong-token"
  # EamApiConnectorTest.wrongTokenIsRejectedWithoutLeakingIt

Сценарий: чтение по id
  Дано объект IT_SYSTEM 5101 в eam-api
  Когда fetchById("IT_SYSTEM","5101")
  Тогда SourceChange с sourceId=5101
  И fetchById("IT_SYSTEM","999999"), ("TEAM","TEAM-PAY"), ("IT_SYSTEM","../types") → Optional.empty()
  # EamApiConnectorTest.fetchByIdReadsObjectAndReturnsEmptyFor404
```

Без тестов (🟡, выведено из кода): таймаут/обрыв → `SourceUnavailableException(status 0)`; `Retry-After` > 30 с урезается до 30 с на ожидание, но в исключении — исходное значение; `Retry-After` в формате HTTP-date игнорируется.

## MoSCoW

| Требование | MoSCoW | Обоснование |
|---|---|---|
| FR-01…FR-07 | Must | обязательства перед владельцем EAM (допущение 6) и безопасность |
| FR-08 | Should | нужен только webhook-пути |
| Следование redirect'ам, HTTP-date в `Retry-After` | Won't | код не поддерживает |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `EA/EamApiConnector.java:68-85` | конструкторы | 🟢 |
| `EA/EamApiConnector.java:134-153` | `fetchById` | 🟢 |
| `EA/EamApiConnector.java:197-285` | `parse`, `get`, `send`, `requireOk`, `throttle`, `pause`, `retryAfter`, `close` | 🟢 (ветви timeout/interrupt/HTTP-date — 🟡) |
