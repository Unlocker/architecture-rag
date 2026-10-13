# receive-webhook, Задачи

> `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`. Каркас — [`../tasks.md`](../tasks.md).

## Пререквизиты

- [ ] `AdapterConfig` (секрет, `replayWindow`), `InboxWriter`, `Json`, `SourceConnector` ([`../tasks.md`](../tasks.md) T-01, T-02, T-04).
- [ ] `WebhookSignature` из `contracts/source-spi` (заголовки `X-Archrag-Signature`, `X-Archrag-Timestamp`, `X-Archrag-Event-Id`).

## Задачи

- [ ] T-01 `WebhookHandler.handle`: проверка подписи → JSON → поля → `fetchById` → ветка tombstone → запись → статус.
  - Origin: `AC/WebhookHandler.java:56-111`
  - Критерий готовности: 12 тестов `WebhookHandlerTest` зелёные; порядок проверок и коды по [`../contracts.md`](../contracts.md) §1.1
  - Уверенность: 🟢
- [ ] T-02 Tombstone по подписанному телу: `Number` ≥ 0, `COMPLETE`, `clock.instant()`, `Map.of()`.
  - Origin: `AC/WebhookHandler.java:93-100`
  - Критерий готовности: `WebhookHandlerTest.deleteNotificationForForgottenObjectIsRecordedFromSignedBody`; ветка `400` — новый тест TT-02
  - Уверенность: 🟢
- [ ] T-03 `WebhookServer` `/webhook`: `POST` only, `readNBytes(MAX_BODY_BYTES + 1)`, `413`, `500` с логом класса, ответы без тела.
  - Origin: `AC/WebhookServer.java:30, 54, 96-123`
  - Критерий готовности: TT-03
  - Уверенность: 🟢
- [ ] T-04 `SourceAdapter.startWebhook(InetSocketAddress)` передаёт `poller::snapshotOnce`.
  - Origin: `AC/SourceAdapter.java:61-66`
  - Критерий готовности: `endpoint()` отдаёт `http://host:port/webhook`
  - Уверенность: 🟢

## Тестовые задачи

- [ ] TT-01 Перенос `WebhookHandlerTest` (12 тестов, RF-01…RF-12). 🟢
- [ ] TT-02 Новый: `DELETE` для забытого объекта с `sourceVersion` отсутствующей / `-1` / строкой → `400` (RF-13). 🟡
- [ ] TT-03 Новый (HTTP): `GET /webhook` → 405; тело 64 КБ проходит, 64 КБ + 1 → 413; сбой журнала → 500 без тела; `/webhook/x` — зафиксировать U-05. 🟡
- [ ] TT-04 Новый: `fetchById` вернул объект с другим `sourceId` — зафиксировать текущее поведение (U-02). 🟡

## Порядок

T-01 → T-02 → T-03 → T-04; TT-01 параллельно T-01.

## Открытые 🔴

R-03 (`500` на отравленном объекте → бесконечные повторы доставки) — [`../questions.md`](../questions.md).
