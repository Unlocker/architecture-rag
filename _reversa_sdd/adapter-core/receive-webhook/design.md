# receive-webhook, Design

> Модульный дизайн — [`../design.md`](../design.md); внешний контракт — [`../contracts.md`](../contracts.md) §1.1. `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`.

## Зависит от / Связанные спецификации

[`../design.md`](../design.md#зависит-от--связанные-спецификации); специально: `contracts/source-spi` (`WebhookSignature`, `SourceConnector.fetchById`, `SourceUnavailableException`, `WebhookEvent`), `contracts/event-schemas` (`EventJournal.append`, `RawPayloadStore.put`), matrix §2 №6 (raw читает `StoredEventReader`), №7 (формат `DELETE`-raw, который воспроизводит `Reconciler.rawDelete`, `ingestion/graph-projector/.../Reconciler.java:197-201`), №15 (код источника ≠ имя модуля); `docs/03-interfaces.md` §3; [ADR 0014](../../../docs/adr/0014-sla-webhook-to-neo4j.md). Ожидания к отправителю — `platform/source-stubs` (`StubWebhookSender`).

## Интерфейс

| Символ | Сигнатура | Примечание |
|---|---|---|
| `WebhookHandler.handle` | `int handle(Function<String,String> header, String body)` | `header` ищет по имени без учёта регистра, `null` если нет; `body` — как получено (подпись по тем же символам) |
| `WebhookServer` (HTTP-слой) | `POST /webhook` | читает тело до 64 КБ + 1 байт; UTF-8 |

## Поток (`AC/WebhookHandler.java:56-107`)

1. `WebhookSignature.verify(webhookSecret, header(X-Archrag-Timestamp), header(X-Archrag-Signature), body, clock.instant(), replayWindow)`; не `VALID` → `401` (`:57-67`).
2. `Json.parseObject(body)`; `IllegalArgumentException` → `400` (`:68-73`).
3. `eventId`, `sourceType`, `sourceId` = непустые строки (`text`), иначе `400`; `notification["source"]` равен `system.code()`; `eventId` равен `header(X-Archrag-Event-Id)`, иначе `400` (`:74-83, 109-111`).
4. `connector.fetchById(sourceType, sourceId)`; `SourceUnavailableException` → `503` (`:84-89`).
5. Если объект есть — `change = state.get()` (`:91-92`).
6. Иначе, если `notification["operation"] == "DELETE"`: `version = notification["sourceVersion"]` как `Number`; `null` или `< 0` → `400`; `change = SourceChange(sourceType, sourceId, version, DELETE, COMPLETE, clock.instant(), Map.of())` (`:93-100`).
7. Иначе → `404` (`:101-103`).
8. `inbox.record(eventId, change, eventId, null)` → `202` (`:105-106`).

Порядок гарантирует: подпись проверяется до разбора; обращение к источнику — только после успешной проверки полей; запись — до ответа.

## Сводка статусов

См. [`../contracts.md`](../contracts.md) §1.1. Дополнительно из `WebhookServer`: `405` (не POST), `413`, `500` (`AC/WebhookServer.java:99-100, 113-122`).

## Состояние

Без состояния, потокобезопасен (поля `final`; `Clock` и `InboxWriter` неизменяемы). Дедупликация — в `EventJournal` по `(source, eventId)` 🟢.

## Решения

| Решение | Evidence | Уверенность |
|---|---|---|
| Уведомление не доверяется по содержимому: состояние берётся у источника | `AC/WebhookHandler.java:84-92` | 🟢 |
| Tombstone по подписанному телу, когда источник уже забыл объект | `:93-100` | 🟢 |
| Заголовок `X-Archrag-Event-Id` не подписан → сверяется с подписанным `eventId` | `:81-83` | 🟢 |
| Без `RetryPolicy` в webhook-пути: источник сам повторит доставку на `503`/`500` | `docs/03-interfaces.md:351`, `AC/WebhookHandler.java:87-89` | 🟢 |
| `correlationId = eventId` | `:105` | 🟢 |
| Статусы не раскрывают причину `401` | `:65-67` | 🟢 |

## Риски

- 🟡 U-02: `sourceType`/`sourceId` ответа `fetchById` не сверяются с уведомлением (`:91-92, 105`).
- 🟡 U-07: у tombstone-повторов разный `updatedAt` → разные raw-объекты в S3 для одного `eventId` (`:99-100`).
- 🟡 Длина `eventId`/`sourceType`/`sourceId` ограничена только 64 КБ тела (`isBlank` в `text`, `:109-111`); предел накладывает журнал — не проверялось.
- 🟡 U-05: prefix-соответствие `HttpContext` (`/webhook/...`) — путь обработчиком не проверяется.
- 🔴 R-03: исключения `IllegalArgumentException` из `SourceVersion` (отрицательная версия в ответе `fetchById`) дают `500`, и источник будет повторять доставку бесконечно.
- 🟡 `IllegalStateException` коннектора (неожиданный статус `4xx`, malformed JSON) → `500` (не `503`): `AC/HttpSourceConnector.java:104-108, 121-127`.
