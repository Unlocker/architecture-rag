# source-fetch-retry, Задачи

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`. Каркас — [`../tasks.md`](../tasks.md).

## Пререквизиты

- [ ] `Json` ([`../tasks.md`](../tasks.md) T-02), контракты `source-spi`.
- [ ] `platform/source-stubs` (HTTP-заглушка источника) в scope `test`.

## Задачи

- [ ] T-01 `RetryPolicy` (record, валидация, `defaults()`, `delay`).
  - Origin: `AC/RetryPolicy.java:19-51`
  - Критерий готовности: `RetryPolicyTest` (5 тестов про политику) зелёные; формула — [`design.md`](design.md)
  - Уверенность: 🟢
- [ ] T-02 `HttpSourceConnector.get`: GET, заголовки, таймауты, отображение статусов/IO-ошибок в `SourceUnavailableException`, `Retry-After` в секундах.
  - Origin: `AC/HttpSourceConnector.java:37-38, 80-119`
  - Критерий готовности: `HttpSourceConnectorTest.rateLimitIsReportedWithRetryAfter`, `serverErrorAndUnreachableSourceAreUnavailable`
  - Уверенность: 🟢
- [ ] T-03 `fetchChanges` / `fetchById` / `change` / `parse`: разбор страницы и изменения, `404 → Optional.empty()`, ошибки формата → `IllegalStateException`.
  - Origin: `AC/HttpSourceConnector.java:50-78, 121-151`
  - Критерий готовности: `HttpSourceConnectorTest` (4 функциональных теста)
  - Уверенность: 🟢
- [ ] T-04 `Poller.fetch` + `pause` + `Sleeper`.
  - Origin: `AC/Poller.java:190-224`, `AC/Sleeper.java`
  - Критерий готовности: `PollerTest.transientFailureIsRetriedWithinOneCycle`, `rateLimitKeepsCursorAndResumesFromTheSamePlace`
  - Уверенность: 🟢
- [ ] T-05 `close()` коннектора освобождает `HttpClient`.
  - Origin: `AC/HttpSourceConnector.java:153-156`
  - Критерий готовности: `SourceAdapterTest.adapterReadsThroughTheGivenConnectorAndClosesIt` (для подставного `AutoCloseable`)
  - Уверенность: 🟡

## Тестовые задачи

- [ ] TT-01 Перенос `HttpSourceConnectorTest` (6) и `RetryPolicyTest` (5 тестов политики). 🟢
- [ ] TT-02 Перенос `PollerTest.transientFailureIsRetriedWithinOneCycle`, `rateLimitKeepsCursorAndResumesFromTheSamePlace`. 🟢
- [ ] TT-03 Новый: `200` с телом `[]`, с ломаным JSON, с элементом без `operation`, с неизвестным значением `operation` → `IllegalStateException`, текст исключения не содержит тела ответа. 🟡
- [ ] TT-04 Новый: `404` на `/changes` и `401`/`400` → `IllegalStateException("… unexpected status N")`. 🟡
- [ ] TT-05 Новый: `Retry-After: abc`, `-1`, HTTP-date → `retryAfter() == null`. 🟡
- [ ] TT-06 Новый: `Poller.pause` при прерывании потока → флаг восстановлен, `IllegalStateException`. 🟡
- [ ] TT-07 Новый: целое вне `long` в payload (U-04). 🟡

## Порядок

T-01 → T-02 → T-03 → T-04 → T-05; `Json` (T-02 модуля) до T-03.

## Открытые 🔴

R-03 (постоянная ошибка формата блокирует polling) — [`../questions.md`](../questions.md).
