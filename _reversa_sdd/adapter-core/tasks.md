# adapters/adapter-core, Задачи реализации

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`, `AT/` = `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/`. Сценарные задачи — в папках use case: [`incremental-poll`](incremental-poll/tasks.md), [`full-snapshot`](full-snapshot/tasks.md), [`receive-webhook`](receive-webhook/tasks.md), [`source-fetch-retry`](source-fetch-retry/tasks.md). Здесь — каркас модуля.

## Пререквизиты

- [ ] Maven-модуль `io.github.unlocker.archrag:adapter-core` (родитель `adapters`), зависимости только `source-spi`, `event-schemas`; `source-stubs` — scope `test` (`adapters/adapter-core/pom.xml:17-32`).
- [ ] Java 21 (records, `switch` по паттернам, виртуальные потоки); внешних библиотек нет.
- [ ] Контракты `source-spi` и `event-schemas` доступны (`SourceConnector`, `WebhookSignature`, `EventJournal`, `RawPayloadStore`, `CanonicalEvent`, `SourceVersion`).
- [ ] Пакет `io.github.unlocker.archrag.adaptercore`.

## Задачи

- [ ] T-01 Записи `AdapterConfig`, `RetryPolicy`, `PollResult` с валидацией и значениями по умолчанию.
  - Origin: `AC/AdapterConfig.java:21-76`, `AC/RetryPolicy.java:19-51`, `AC/PollResult.java:10-24`
  - Критерий готовности: `of(...)` даёт 5 мин / 100 / 30 с / 6 ч / 5×(500 мс…30 с); невалидные значения → `IllegalArgumentException`; `toString` маскирует секрет и не печатает `retry`, `pollInterval`, `reconcileInterval`
  - Уверенность: 🟢
- [ ] T-02 `Json`: парсер глубиной ≤ 64 с Long/Double, `parseObject`, детерминированный `write` с экранированием.
  - Origin: `AC/Json.java:16-277`
  - Критерий готовности: `JsonTest` (5 тестов); сообщения ошибок содержат только смещение; целое вне `long` → `bad number` (воспроизвести как есть, 🟡 U-04)
  - Уверенность: 🟢
- [ ] T-03 `EventMapper`: константы типов/схем/ключей, `sourceUrn`, `toEvent`, `snapshotComplete`, `pollEventId`, `snapshotEventId`, `subject`.
  - Origin: `AC/EventMapper.java:26-109`
  - Критерий готовности: форматы по [`contracts.md`](contracts.md) §2; `null` верхнего уровня отбрасываются; `DELETE` → пустой payload без `_completeness`
  - Уверенность: 🟢
- [ ] T-04 `InboxWriter`: `record(...)` (raw → journal), `rawJson`, `markerRawJson`.
  - Origin: `AC/InboxWriter.java:21-71`
  - Критерий готовности: порядок `rawStore.put` → `journal.append`; ключи raw в фиксированном порядке; `StoredEventReaderContractTest` зелёный
  - Уверенность: 🟢
- [ ] T-05 `Sleeper` (+ `THREAD`) как точка подмены паузы.
  - Origin: `AC/Sleeper.java`
  - Критерий готовности: `Poller` принимает `Sleeper`; тесты не зависят от реального времени
  - Уверенность: 🟢
- [ ] T-06 `SourceAdapter`: два конструктора, `webhook()/poller()/connector()`, `startWebhook`, `startPolling` (initial delay 0), `startReconciliation` (initial delay = interval), `close` (shutdownNow → server → connector).
  - Origin: `AC/SourceAdapter.java:29-136`
  - Критерий готовности: повторные `start*` — no-op/возврат существующего; `RuntimeException` цикла логируется как `<code> poll|reconcile failed: <класс>` и расписание живёт; `AutoCloseable` коннектор закрывается
  - Уверенность: 🟢
- [ ] T-07 `WebhookServer`: `POST /webhook`, опциональный `POST /control/snapshot`, лимит 64 КБ, 405/413/500, виртуальные потоки.
  - Origin: `AC/WebhookServer.java:27-129`
  - Критерий готовности: статусы по [`contracts.md`](contracts.md) §1; ответы `/webhook` без тела
  - Уверенность: 🟢
- [ ] T-08 Сценарные компоненты: `Poller` → [`incremental-poll`](incremental-poll/tasks.md) и [`full-snapshot`](full-snapshot/tasks.md); `WebhookHandler` → [`receive-webhook`](receive-webhook/tasks.md); `HttpSourceConnector` + `RetryPolicy.delay` → [`source-fetch-retry`](source-fetch-retry/tasks.md).
  - Origin: `AC/Poller.java`, `AC/WebhookHandler.java`, `AC/HttpSourceConnector.java`
  - Критерий готовности: задачи use case выполнены
  - Уверенность: 🟢

## Тестовые задачи

- [ ] TT-01 `AdapterConfig`: пустой/пробельный `webhookSecret`, нулевые/отрицательные интервалы, `pageSize = 0` → `IllegalArgumentException`; значения `of(...)` (пробел: прямого теста нет). Уверенность 🟡
- [ ] TT-02 `RetryPolicyTest.*` (6) — перенос как есть. Origin: `AT/RetryPolicyTest.java`. 🟢
- [ ] TT-03 `JsonTest.*` (5) + новые: целое вне `long`, `+5`/`01`, отрицательные числа, `Double`. 🟡
- [ ] TT-04 Формат raw/маркера и совместимость с `StoredEventReader`: `ingestion/ingestion-service/src/test/java/io/github/unlocker/archrag/adaptercore/StoredEventReaderContractTest.java`. 🟢
- [ ] TT-05 `SourceAdapterTest.*` (2): коннектор закрывается, конструктор по умолчанию строит `HttpSourceConnector`. 🟢
- [ ] TT-06 `WebhookServerControlTest.*` (4): 202 / 409 / 503 / 500 / 405 / отсутствие контекста без supplier. 🟢
- [ ] TT-07 Новый: `POST /webhook` через HTTP — 405 на GET, 413 на теле 64 КБ + 1 (граница: ровно 64 КБ проходит), 500 при сбое журнала. 🟡 (в `AT/` нет)
- [ ] TT-08 Новый: `EventMapper` — `_completeness = COMPLETE`, вложенные `null` сохраняются, `subject` для `IT_SYSTEM` = `it-system/<id>`, маркер несёт `objectCount: long`. 🟡
- [ ] TT-09 Новый: `startPolling`/`startReconciliation` — исключение цикла не останавливает расписание; `close()` останавливает; initial delay 0 и = interval соответственно. 🟡
- [ ] TT-10 Новый: контрактный тест на совпадение литералов типов с `EventProcessor`/`StoredEventReader` (закрывает 🔴 R-01). 🔴

## Порядок

1. T-01, T-05, T-02 (нет внутренних зависимостей) → T-03 (нужны `AdapterConfig`-независимые константы) → T-04 (нужны `EventMapper`, `Json`).
2. `HttpSourceConnector` + `RetryPolicy` ([`source-fetch-retry`](source-fetch-retry/tasks.md)) → `Poller` ([`incremental-poll`](incremental-poll/tasks.md), [`full-snapshot`](full-snapshot/tasks.md)).
3. `WebhookHandler` ([`receive-webhook`](receive-webhook/tasks.md)) → T-07 → T-06 (сборка).
4. Тесты: `InMemoryJournal`, `InMemoryRawStore` (`AT/`) и `StubSource` из `platform/source-stubs` — до `PollerTest`/`WebhookHandlerTest`.

## Открытые 🔴

- R-01 литералы типов (TT-10), R-02 формулировка ADR 0020, R-03 карантин «отравленного» объекта — см. [`questions.md`](questions.md). До решения реализовывать поведение «как есть»: исключение пробрасывается, checkpoint не двигается.
