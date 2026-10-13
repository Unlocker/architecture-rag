# eam-api-request — задачи

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`.

## Pre-requisites

- [ ] `Sleeper` и `Json` из `adapter-core`; `SourceUnavailableException` из `source-spi`.
- [ ] Токен — из env/secret (`ARCHRAG_EAM_API_TOKEN`), в коде и Git не хранится.

## Задачи

- [ ] T-R1 Конструкторы: публичный (`Sleeper.THREAD`, `System::nanoTime`) и пакетный; валидации `baseUri`, токена, `pageSize`; `authorization = "Token " + token`; зажим до 500.
  - Origin: `EA/EamApiConnector.java:44-45, 59-85`
  - Критерий готовности: `blankTokenIsRefused`, `pageSizeIsClampedTo500` зелёные.
  - Уверенность: 🟢
- [ ] T-R2 `HttpClient` с `connectTimeout=5 с`; запрос с `timeout=10 с`, заголовками `Accept`, `Authorization`, `GET`; базовый URL без хвостовых `/`.
  - Origin: `EA/EamApiConnector.java:64, 221-239`
  - Критерий готовности: запросы к `EamApiStub` успешны; заголовок `Authorization: Token <токен>`.
  - Уверенность: 🟢
- [ ] T-R3 `throttle` (синхронизирован, 100 мс) и `pause` (interrupt-aware).
  - Origin: `EA/EamApiConnector.java:248-269`
  - Критерий готовности: `requestsAreSpacedToTenPerSecond` зелёный.
  - Уверенность: 🟢
- [ ] T-R4 `get`: один повтор после 429 с `Retry-After` (срез 30 с); 429/5xx → `SourceUnavailableException`; `retryAfter` парсит только секунды.
  - Origin: `EA/EamApiConnector.java:205-219, 271-280`
  - Критерий готовности: три теста лимитов зелёные.
  - Уверенность: 🟢
- [ ] T-R5 IO-ошибка → `SourceUnavailableException(EAM, 0, null)`; interrupt → восстановить флаг + `IllegalStateException`.
  - Origin: `EA/EamApiConnector.java:232-238`
  - Критерий готовности: прямого теста нет (🟡).
  - Уверенность: 🟡
- [ ] T-R6 `requireOk` (только 200) и `parse` (`malformed JSON`) без значений источника в сообщениях.
  - Origin: `EA/EamApiConnector.java:197-203, 241-246`
  - Критерий готовности: `wrongTokenIsRejectedWithoutLeakingIt` зелёный.
  - Уверенность: 🟢
- [ ] T-R7 `fetchById`: тип → путь, `Long.parseLong`, 404 → пусто, 200 → `change`.
  - Origin: `EA/EamApiConnector.java:134-153`
  - Критерий готовности: `fetchByIdReadsObjectAndReturnsEmptyFor404` зелёный.
  - Уверенность: 🟢
- [ ] T-R8 `close()` → `http.close()`.
  - Origin: `EA/EamApiConnector.java:282-285`
  - Критерий готовности: вызывается в `tearDown` тестов без ошибок.
  - Уверенность: 🟢

## Тестовые задачи

Есть: `wrongTokenIsRejectedWithoutLeakingIt`, `blankTokenIsRefused`, `fetchByIdReadsObjectAndReturnsEmptyFor404`, `rateLimitWithRetryAfterWaitsOnceAndRetries`, `repeatedRateLimitSurfacesAsSourceUnavailable`, `rateLimitWithoutRetryAfterAndServerErrorAreNotRetriedInside`, `requestsAreSpacedToTenPerSecond`, `pageSizeIsClampedTo500`, `malformedIntegerIdIsRejected` (`ET/EamApiConnectorTest.java`).

Пробелы (🟡):

- [ ] TT-R1 Таймаут/обрыв соединения → `SourceUnavailableException` со `statusCode() == 0`.
- [ ] TT-R2 `Retry-After: 120` → пауза 30 с, а `retryAfter()` исключения при повторном 429 сохраняет исходное значение.
- [ ] TT-R3 `Retry-After` в формате HTTP-date и отрицательный → без повтора.
- [ ] TT-R4 Не-JSON и не-массив → `IllegalStateException` с фиксированным текстом.
- [ ] TT-R5 `pageSize = 0` и `baseUri = null` в конструкторе.
- [ ] TT-R6 `403` на списке → `IllegalStateException` без тела.
- [ ] TT-R7 `fetchById` для `sourceId = "+7"`/`"007"` → путь `/…/7/`.

## Порядок

T-R1 → T-R2 → T-R3 → T-R4 → T-R5/T-R6 → T-R7 → T-R8. Тестовые двойники (`Sleeper`, `LongSupplier`) нужны уже на T-R3.

## Открытые 🔴

В сценарии нет 🔴; Q-1 (🔴, проверка прав учётки) относится к модулю — [`../questions.md`](../questions.md).
