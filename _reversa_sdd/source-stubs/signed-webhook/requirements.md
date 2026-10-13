# signed-webhook: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Wire-формат: [`../contracts.md`](../contracts.md) §3. Приём на стороне адаптера: [`../../adapter-core/receive-webhook/requirements.md`](../../adapter-core/receive-webhook/requirements.md). Контракт подписи: [`../../source-spi/requirements.md`](../../source-spi/requirements.md).

## Visão Geral

`StubWebhookSender` — HTTP-клиент (не endpoint), которым тесты имитируют источник, присылающий уведомления об изменениях. Он формирует тело-уведомление из `WebhookEvent`, подписывает его по `WebhookSignature` (`HMAC-SHA256(secret, timestamp + "." + body)`) и отправляет `POST` на endpoint адаптера. Умеет два негативных варианта: подпись другим секретом и корректную подпись со старым timestamp. Обслуживает критерий PoC 2 (p95 ≤ 30 с по webhook) и критерий 3 (повтор и перестановка уведомлений) (`docs/00-vision.md:799-800`; ADR [0014](../../../docs/adr/0014-sla-webhook-to-neo4j.md)).

## Regras de Negócio

- Тело содержит только уведомление: `eventId`, `source`, `sourceType`, `sourceId`, `sourceVersion`, `operation`, `occurredAt`; состояние объекта (payload) не передаётся — адаптер читает его через `fetchById` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:58-69`; `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookEvent.java:6-8`).
- Заголовки: `Content-Type: application/json`, `X-Archrag-Event-Id`, `X-Archrag-Timestamp` (epoch-секунды), `X-Archrag-Signature` (`sha256=<hex>`) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:71-80`).
- `send` берёт timestamp из `Clock` отправителя; `sendAt(event, ts)` подписывает корректно, но с заданным `ts` (проверка окна replay); `sendWithBadSignature` использует `Clock` и секрет `secret + "-wrong"` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:39-56`).
- Повторная отправка того же события сохраняет `eventId` и тело байт в байт (дедупликация по `source + eventId` — на стороне ingestion) 🟢 (`platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSenderTest.java:84-90`).
- Метод возвращает HTTP-статус ответа; тело ответа отбрасывается 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:82`).
- Ошибка ввода-вывода → `IllegalStateException("webhook delivery failed: <простое имя класса>")` без текста исключения клиента (секрет не утекает); прерывание → восстановление флага `interrupt` и `IllegalStateException("webhook delivery interrupted")` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:83-89`).
- Таймауты: подключение 5 с (клиент), запрос 10 с (на запрос); класс `AutoCloseable` закрывает `HttpClient` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:26-27, 74, 92-95`).
- Секрет не логируется и не попадает в исключения (в классе нет логирования) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:29-37`).
- Идентификатор события формирует `StubSource.webhookEvents()`: `"<код источника>-evt-<seq>"` 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:192-202`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Корректная подпись и отправка уведомления без состояния | Must | AC-01 |
| RF-02 | Устаревший timestamp (`sendAt`) отклоняется окном replay | Must | AC-02 |
| RF-03 | Неверная подпись отклоняется | Must | AC-03 |
| RF-04 | Повторная доставка сохраняет `eventId` и тело | Must | AC-04 |
| RF-05 | Ошибка доставки не раскрывает секрет | Must | AC-05 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Segurança | Подпись сравнивается на стороне адаптера в постоянное время; окно replay 5 минут | `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java:19-20, 81-85`; `docs/03-interfaces.md:557` | 🟢 |
| Segurança | Причина отказа наружу не раскрывается (адаптер возвращает одинаковый `401`) | `docs/03-interfaces.md:557` | 🟡 (поведение адаптера, не заглушки) |
| Desempenho | Таймауты 5 с / 10 с | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:26-27, 74` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01: корректный webhook (StubWebhookSenderTest.signedWebhookIsAcceptedAndCarriesOnlyNotification; AdapterInboxIT.webhookIsStoredAsReceivedRowWithRawObjectAndDuplicateAddsNothing)
Дано HTTP-приёмник, проверяющий WebhookSignature.verify с окном 5 минут, и событие eam-evt-4 из StubSources.eam(clock)
Когда sender.send(event)
Тогда статус 202, вердикт VALID; тело содержит "eventId":"eam-evt-4", "sourceId":"dep-payments-api-ledger-api", "sourceVersion":1, "operation":"UPSERT" и не содержит состояния объекта (svc-ledger-api)
Дано реальный адаптер EAM
Тогда после send в inbox_event появляется строка со статусом RECEIVED и raw-объект в S3, читаемый по ссылке; повторный send оставляет одну строку

# AC-02: устаревший timestamp (StubWebhookSenderTest.staleTimestampIsRejectedByReplayWindow; AdapterInboxIT.badSignatureAndStaleTimestampAreRejectedAndNothingIsWritten)
Дано событие
Когда sender.sendAt(event, now − 1 час)
Тогда статус 401, вердикт OUT_OF_WINDOW; в inbox_event записей нет

# AC-03: неверная подпись (StubWebhookSenderTest.wrongSignatureIsRejected)
Когда sender.sendWithBadSignature(event)
Тогда статус 401, вердикт BAD_SIGNATURE; в inbox_event записей нет

# AC-04: повторная доставка (StubWebhookSenderTest.duplicateDeliveryKeepsEventId; SyncAcceptanceIT.k3a_repeatedEventChangesNothing)
Когда sender.send(e) дважды
Тогда оба тела идентичны; в inbox_event строка с этим event_id одна, граф не меняется

# AC-05: отказ доставки (код; тест отсутствует) 🟡
Дано target недоступен
Когда sender.send(event)
Тогда IllegalStateException("webhook delivery failed: <класс>") без секрета и без URL запроса в сообщении

# AC-06: перестановка уведомлений (SyncAcceptanceIT.k3b_reversedWebhooks)
Дано два уведомления версий 1 и 2 одного объекта
Когда отправлены в обратном порядке (v2, затем v1)
Тогда в графе только актуальная V2, есть событие DUPLICATE, карантина нет
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-04 | Must | Проверка подписи и идемпотентности входа |
| RF-05 | Should | Защита секрета от утечки в сообщениях |
| Повторы доставки и backoff на стороне отправителя | Won't | Отправитель однократный: решение о повторе принимает тест |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java` | `StubWebhookSender` | 🟢 |
| `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSenderTest.java` | 4 теста | 🟢 |
| `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java` | подпись и проверка (контракт) | 🟢 |
