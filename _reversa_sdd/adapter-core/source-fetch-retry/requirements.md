# source-fetch-retry, Чтение источника по REST и повторы

> Часть [`adapter-core`](../requirements.md). `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`, `AT/` = `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/`. Реализация — [`design.md`](design.md); контракт источника — [`../contracts.md`](../contracts.md) §4.

## Визуальная картина

`HttpSourceConnector` — реализация `SourceConnector` поверх REST (`GET /changes`, `GET /objects/{type}/{id}`). Недоступность источника выражается `SourceUnavailableException`. `Poller.fetch` повторяет `fetchChanges` по `RetryPolicy` (exponential backoff + equal jitter + `Retry-After`) 🟢. Webhook-путь повторов не делает (один `fetchById`) 🟢.

## Регулярные правила

- Только `GET`, `Accept: application/json`, таймаут запроса 10 с, connect 5 с 🟢 (`AC/HttpSourceConnector.java:37-38, 80-86`).
- `429`, любой `5xx` → `SourceUnavailableException(system, status, retryAfter)`; `IOException` (timeout, обрыв) → статус `0`, `retryAfter = null` 🟢 (`:90-92, 97-100`).
- `Retry-After`: только целое число секунд ≥ 0; отрицательное, нечисловое и HTTP-date → `null` (откат на backoff) 🟢 (`:110-119`).
- `404` на `GET /objects/…` → `Optional.empty()` («источник объект не знает»); `404` на `/changes` → `IllegalStateException` (неожиданный статус) 🟢 (`:71-76, 104-108`).
- Любой другой не-`200` → `IllegalStateException("<code> answered unexpected status N")` без тела ответа (ловушка 11) 🟢 (`:104-108`).
- Ответ не JSON-объект / ломаный JSON → `IllegalStateException("<code> answered malformed JSON")`; элемент не объект, отсутствует/неверного типа обязательное поле (`sourceType`, `sourceId`, `sourceVersion`, `operation`, `completeness`, `updatedAt`) → `IllegalStateException("… malformed change")` 🟢 (`:121-147`).
- `limit <= 0` → `IllegalArgumentException("limit must be positive")` 🟢 (`:52-54`).
- Задержка повтора `n`-й неудачной попытки (с 1): `ceiling = min(maxDelay, baseDelay · 2^(n−1))` (сдвиг ≤ 30), затем `ceiling/2 + random[0, ceiling − ceiling/2]` (включительно); если `Retry-After` > `maxDelay` — берётся `maxDelay`; итог = `max(backoff, retryAfter)` 🟢 (`AC/RetryPolicy.java:40-50`).
- Число обращений за вызов `fetch` ≤ `maxAttempts` (по умолчанию 5); пауза только между попытками (после последней нет); исчерпание → `GaveUp(status последней ошибки, appended, syncRunId)` → `GAVE_UP` 🟢 (`AC/Poller.java:191-204`).
- `InterruptedException` при паузе или запросе → флаг прерывания восстановлен, `IllegalStateException` 🟢 (`AC/Poller.java:206-213`, `AC/HttpSourceConnector.java:93-96`).

## Требования функциональные

| ID | Требование | MoSCoW | Критерий приёмки |
|---|---|---|---|
| RF-01 | Страницы snapshot/инкремента несут `nextCursor`, последняя — `snapshotComplete` | Must | `HttpSourceConnectorTest.snapshotPagesCarryCursorAndFinishWithSnapshotComplete` |
| RF-02 | `fetchById`: объект или `Optional.empty()` для неизвестного | Must | `HttpSourceConnectorTest.fetchByIdReturnsStateAndEmptyForUnknown` |
| RF-03 | `DELETE` виден через оба эндпоинта | Must | `HttpSourceConnectorTest.deleteIsVisibleThroughBothEndpoints` |
| RF-04 | `null` в payload `PARTIAL`-записи сохраняется коннектором | Must | `HttpSourceConnectorTest.partialRecordKeepsNullValues` |
| RF-05 | `429` + `Retry-After` → `SourceUnavailableException(isRateLimited, retryAfter)` | Must | `HttpSourceConnectorTest.rateLimitIsReportedWithRetryAfter` |
| RF-06 | `5xx` и недостижимый источник → `SourceUnavailableException` (статус / `0`) | Must | `HttpSourceConnectorTest.serverErrorAndUnreachableSourceAreUnavailable` |
| RF-07 | Backoff растёт экспоненциально с jitter внутри `[ceiling/2, ceiling]` | Must | `RetryPolicyTest.backoffGrowsExponentiallyWithJitterInsideBounds` |
| RF-08 | Backoff ограничен `maxDelay` | Must | `RetryPolicyTest.backoffIsCappedByMaxDelay` |
| RF-09 | `Retry-After` поднимает паузу, но не снижает; огромный — режется до `maxDelay` | Must | `RetryPolicyTest.retryAfterRaisesTheDelayButNeverLowersIt`, `hugeRetryAfterIsCappedByMaxDelay` |
| RF-10 | Невалидные параметры `RetryPolicy` отвергаются | Must | `RetryPolicyTest.rejectsInvalidParameters` |
| RF-11 | Повтор в рамках цикла: после временного сбоя цикл завершается успешно, пауз = числу сбоев | Must | `PollerTest.transientFailureIsRetriedWithinOneCycle` |
| RF-12 | Исчерпание попыток → `GAVE_UP`, курсор и журнал не меняются, паузы не короче `Retry-After` | Must | `PollerTest.rateLimitKeepsCursorAndResumesFromTheSamePlace` |
| RF-13 | Неожиданный статус / ломаный JSON / ломаное изменение → `IllegalStateException` без содержимого | Should | прямого теста нет 🟡 |

## Требования нефункциональные

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Производительность | Connect-таймаут 5 с, таймаут запроса 10 с | `AC/HttpSourceConnector.java:38, 83` | 🟢 |
| Доступность | До 5 попыток, паузы 0,5…30 с (по умолчанию) | `AC/RetryPolicy.java:31-33` | 🟢 |
| Безопасность | Недоверенный `Retry-After` не усыпит poller дольше `maxDelay` | `AC/RetryPolicy.java:45-48` | 🟢 |
| Безопасность | Тела ответов источника не попадают в исключения/логи | `AC/HttpSourceConnector.java:104-108, 121-127, 144-146` | 🟢 |
| Безопасность | JSON источника: глубина ≤ 64 | `AC/Json.java:18` | 🟢 |

## Критерии приёмки

```gherkin
# Позитив
Дано источник отвечает 503 два раза подряд, затем 200
Когда Poller выполняет цикл с RetryPolicy(5, …)
Тогда цикл завершается ADVANCED, выполнено 2 паузы                   # PollerTest.transientFailureIsRetriedWithinOneCycle

# Позитив: Retry-After
Дано источник отвечает 429 с Retry-After: 7
Когда вызывается fetchChanges
Тогда SourceUnavailableException(statusCode=429, isRateLimited=true, retryAfter=7 с)   # HttpSourceConnectorTest.rateLimitIsReportedWithRetryAfter

# Негатив: исчерпание
Дано источник отвечает 429 (Retry-After 3 с) все maxAttempts=3 раза
Когда выполняется pollOnce
Тогда GAVE_UP, 2 паузы, каждая ≥ 3 с, журнал и курсор не изменены      # PollerTest.rateLimitKeepsCursorAndResumesFromTheSamePlace

# Негатив: недоступность
Дано сервер источника остановлен
Когда fetchChanges
Тогда SourceUnavailableException со statusCode = 0                     # HttpSourceConnectorTest.serverErrorAndUnreachableSourceAreUnavailable

# Негатив: огромный Retry-After
Дано Retry-After = 999 999 999 с, maxDelay = 8 с
Когда считается пауза
Тогда пауза = 8 с                                                      # RetryPolicyTest.hugeRetryAfterIsCappedByMaxDelay

# Негатив: ломаный ответ
Дано источник ответил 200 с телом "[]" или без поля operation
Когда fetchChanges/fetchById
Тогда IllegalStateException ("answered malformed JSON"/"answered malformed change")   # 🟡 теста нет (TT-03)

# Негатив: параметры
Дано RetryPolicy(0, …) или baseDelay > maxDelay
Тогда IllegalArgumentException                                          # RetryPolicyTest.rejectsInvalidParameters
```

## Приоритет

Must — всё, кроме RF-13 (Should: защитное поведение). Формат HTTP-date в `Retry-After` — Won't (осознанно, комментарий в коде `:116`).

## Трассировка

| Файл | Символ | Тест | Покрытие |
|---|---|---|---|
| `AC/HttpSourceConnector.java:33-157` | `HttpSourceConnector` | `HttpSourceConnectorTest` (6) | 🟢 (кроме ошибок формата) |
| `AC/RetryPolicy.java:19-51` | `RetryPolicy.delay` | `RetryPolicyTest` (5 из 6) | 🟢 |
| `AC/Poller.java:190-213` | `fetch`, `pause` | `PollerTest.transientFailure…`, `rateLimit…` | 🟢 (прерывание — нет) |
| `AC/Sleeper.java` | `Sleeper` | подменяется в `PollerTest` | 🟢 |
