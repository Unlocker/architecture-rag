# 0039. Webhook — только триггер: данные берутся у источника через `fetchById`

- **Статус:** Принято
- **Дата:** 2026-10-13
- **Источник:** UNLOCKER-278; кандидат К-9 в `_reversa_sdd/adrs/candidates.md` (UNLOCKER-269); согласие владельца в UNLOCKER-260 (2026-10-13); реализация — UNLOCKER-165, UNLOCKER-166; упомянуто в [`00-vision.md`](../00-vision.md)
- **Кто принял:** владелец, 2026-10-13

## Контекст

Webhook приходит из внешней по отношению к графу системы. Если бы данные брались из тела запроса, подмена или повтор уведомления подсунули бы произвольные данные в граф. Граница доверия источников в реестре не была зафиксирована ([ADR 0014](0014-sla-webhook-to-neo4j.md) задаёт только SLA).

## Решение

1. **Тело webhook несёт только идентификатор объекта.** Данные в граф не берутся из тела (`adapters/adapter-core/.../WebhookHandler.java:17-22`).
2. **Подпись и окно.** Заголовки `X-Archrag-Signature: sha256=<hex>`, `X-Archrag-Timestamp` (unix-секунды), `X-Archrag-Event-Id`; подпись — `HMAC-SHA256(secret, timestamp + "." + body)`, сравнение за постоянное время; запрос вне replay window (отклонение timestamp в обе стороны) отвергается (`contracts/source-spi/.../WebhookSignature.java:12-19`, `:56-64`, `:85`). Секрет не попадает в исключения и сообщения.
3. **`fetchById`.** После проверки адаптер запрашивает актуальное состояние у источника (`WebhookHandler.java:86`) и кладёт в inbox то, что вернул источник.
4. **Ответы.** Подпись отсутствует, неверна или вне окна — `401` без деталей; тело не разбирается или идентификатор не совпал — `400` (`WebhookHandler.java:56-66`, `:72`, `:82`, `:97`).

Единый путь для webhook и polling: оба дают в inbox состояние, полученное у источника.

## Рассмотренные варианты

| Вариант | Плюсы | Минусы | Итог |
|---|---|---|---|
| A. Webhook — триггер, данные через `fetchById` | Подмена тела не попадает в граф; один путь для polling и webhook | Лишний запрос к источнику на событие | Выбран |
| B. Принимать данные из тела | Быстрее | Нет проверки целостности данных источника, два пути ингеста | Отклонён |

## Последствия

- **Положительные:** граница доверия — ответ источника, а не тело запроса; повтор уведомления даёт `DUPLICATE` ([ADR 0033](0033-source-version-numeric-idempotency.md)).
- **Отрицательные / ограничения:** источник должен поддерживать `fetchById`; `/control/snapshot` на том же сервере защищён отдельно от HMAC webhook: проверяются JWT и роль `archrag-console-admin`, без настроенного валидатора контекст не создаётся (UNLOCKER-290; `adapters/adapter-core/.../WebhookServer.java:61-66`, `:77-92`, [ADR 0035](0035-reconcile-tombstone-via-journal.md)).
- **Затронутый код:** `contracts/source-spi` (`WebhookSignature`), `adapters/adapter-core` (`WebhookHandler`, `WebhookServer`).

## Связанные ADR

[0010](0010-master-systems-are-stubs.md) — источники-заглушки. [0014](0014-sla-webhook-to-neo4j.md) — SLA webhook. [0033](0033-source-version-numeric-idempotency.md) — дубли. [0035](0035-reconcile-tombstone-via-journal.md) — `/control/snapshot`.
