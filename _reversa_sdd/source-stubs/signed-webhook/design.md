# signed-webhook: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Требования: [`requirements.md`](requirements.md). Wire-формат: [`../contracts.md`](../contracts.md) §3.

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `StubWebhookSender(URI target, String secret, Clock clock)` | | секрет хранится в приватном финальном поле | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:29-37` 🟢 |
| `send` | `(WebhookEvent) -> int` | HTTP-статус; корректная подпись с `clock.instant()` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:39-42` 🟢 |
| `sendAt` | `(WebhookEvent, Instant) -> int` | корректная подпись с заданным timestamp | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:44-49` 🟢 |
| `sendWithBadSignature` | `(WebhookEvent) -> int` | подпись секретом `secret + "-wrong"` с `clock`-timestamp | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:51-56` 🟢 |
| `body` | `static (WebhookEvent) -> String` | JSON из семи ключей (package-private, виден тесту) | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:58-69` 🟢 |
| `close` | `() -> void` | закрывает `HttpClient` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:92-95` 🟢 |
| `WebhookSignature.sign` | `(secret, long ts, body) -> "sha256=<hex>"` | контракт `source-spi` | `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java:46-49` 🟢 |

## Fluxo Principal

1. Вызывающий берёт `WebhookEvent` из `StubSource.webhookEvents()` (обычно `.getLast()`).
2. `sendAt(event, ts)`: `body = Json.write(LinkedHashMap{eventId, source, sourceType, sourceId, sourceVersion, operation, occurredAt})`, `signature = WebhookSignature.sign(secret, ts.getEpochSecond(), body)` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:45-49`).
3. `post(event, body, ts, signature)`: `POST target`, таймаут запроса 10 с, заголовки (см. контракт), тело строкой UTF-8; `HttpClient.send(..., BodyHandlers.discarding()).statusCode()` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:71-82`).
4. Сторона адаптера (не часть модуля): `WebhookSignature.verify(secret, timestampHeader, signatureHeader, body, now, window)` → `VALID` → запись в журнал и `202`; `MALFORMED`/`BAD_SIGNATURE`/`OUT_OF_WINDOW` → `401` (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java:58-86`; поведение — `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSenderTest.java:35-46`) 🟢.

## Fluxos Alternativos

- **Сетевая ошибка** → `IllegalStateException` с простым именем класса исключения; исходное исключение передаётся как `cause` (оно может нести URL, но не секрет) 🟢 (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:83-85`).
- **Прерывание потока** → флаг восстанавливается, `IllegalStateException("webhook delivery interrupted")` 🟢.
- **Подавленный webhook** (`StubSource.suppressWebhooks(true)`): событие в `webhookEvents()` не попадает, отправлять нечего; обнаружение пропуска — задача reconcile ([`../stub-source-journal/requirements.md`](../stub-source-journal/requirements.md) AC-05) 🟢.
- **Устаревшее уведомление** (`staleWebhookVersionIsLowerThanCurrentState`): `WebhookEvent` с меньшей `sourceVersion` отправляется после актуального; адаптер читает состояние по id и получает текущую версию 🟢.

## Dependências

`contracts/source-spi` (`WebhookEvent`, `WebhookSignature`), `Json.write`, `java.net.http.HttpClient`. Потребители: `platform/integration-tests` — `AdapterInboxIT`, `SyncAcceptanceIT`, `F1..F4` (косвенно) — [`../../integration-tests/sync-reconcile/requirements.md`](../../integration-tests/sync-reconcile/requirements.md) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Отправитель знает только `WebhookEvent`, но не источник: тело строится из события, а не из состояния объекта | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:58-69` | 🟢 |
| Негативные варианты — отдельные методы, а не флаги конструктора | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:44-56` | 🟢 |
| В сообщение исключения идёт только имя класса, не текст | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:83-85` | 🟢 |

## Observabilidade

Нет логов; результат — статус, вердикт `WebhookSignature.Result` фиксирует только тестовый приёмник.

## Riscos e Lacunas

- 🟡 Поле `source` тела — `SourceSystem.code()` (`eam`, `scm`, `cmdb`, `deploymap`), а журнал хранит `urn:corp:<код>`: перевод делает адаптер (Q-06).
- 🟡 Тела ответа адаптера отправитель не читает; причины `401` тест различает только по вердикту собственного приёмника, не по телу.
- 🟡 Теста на сетевую ошибку (`AC-05`) нет.
- 🟡 Однократная отправка: сценарий повторной доставки строит тест, вызывая `send` дважды.
