# stub-source-http: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Требования: [`requirements.md`](requirements.md). Wire-формат: [`../contracts.md`](../contracts.md) §1.

## Interface

| Символ | Сигнатура | Результат | Трассировка |
|--------|-----------|-----------|-------------|
| `StubSourceServer(StubSource)` | `(source)` | loopback, порт 0 | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:41-44` 🟢 |
| `StubSourceServer(StubSource, InetSocketAddress)` | `(source, address)` | запускает сервер сразу; `IllegalStateException("cannot start stub source server")`, если порт недоступен | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:46-56` 🟢 |
| `baseUri()` / `port()` | `() -> URI` / `() -> int` | `http://127.0.0.1:<port>` / фактический порт | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:58-66` 🟢 |
| `failOnCall(callNumber, status, retryAfter)` | `(int, int, Duration)` | запись в расписание | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:68-77` 🟢 |
| `close()` | `() -> void` | `server.stop(0)` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:177-180` 🟢 |

## Fluxo Principal

`respond(exchange, handler)` `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:102-127`:

1. Метод ≠ `GET` → `Reply(405, "{}")`; конец.
2. `failIfScheduled()`: `calls++`; если `scheduledFailures.remove(calls)` вернул сбой — бросить его.
3. `handler.handle()` → `Reply(status, body)`.
4. `SourceUnavailableException` → `Reply(statusCode == 0 ? 504 : statusCode, "{}")`; при `retryAfter != null` заголовок `Retry-After: <секунды>`.
5. `IllegalArgumentException` → `Reply(400, "{}")`.
6. Запись ответа: `Content-Type: application/json`, затем заголовки (`Retry-After`), `sendResponseHeaders(status, длина тела UTF-8)`, тело.

`handleChanges` `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:79-88`: параметры запроса → `cursor` (пусто → `null`), `limit` (`Integer.parseInt`, по умолчанию 100) → `source.fetchChanges` → `Json.write(pageJson(page))`.

`handleObject` `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:90-100`: `rawPath` после `/objects/` делится по `/`; не два сегмента → 404; `fetchById(decode(type), decode(id))` → 200 `changeJson` либо 404.

## Fluxos Alternativos

- **Сбой источника** (`StubSource.failNext`) срабатывает внутри `fetchChanges`/`fetchById` и обрабатывается тем же катчем, что и запланированный (`failOnCall`) 🟢. Порядок: расписание сервера проверяется раньше сбоя источника.
- **Запланированный сбой на запросе, который иначе закончился бы `400`/`404`** всё равно расходует номер вызова 🟢 (счётчик растёт до обработчика).
- **Путь с пустыми сегментами** (`/objects/a//`): `split("/")` отбрасывает хвостовые пустые, результат зависит от `rawPath` 🟡 (не покрыто тестом).
- **Параллельные клиенты**: обработка последовательна (executor не задан) 🟡.

## Dependências

`StubSource` (журнал), `Json.write`, `contracts/source-spi` (`ChangePage`, `SourceChange`, `SourceUnavailableException`). Потребители URL: `adapters/*` через `HttpSourceConnector` (`adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/HttpSourceConnectorTest.java`) 🟡 — [`../../adapter-core/requirements.md`](../../adapter-core/requirements.md).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| JDK `HttpServer`, без Spring | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:3-4, 49` | 🟢 |
| Единая точка преобразования исключений в статусы | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:102-127` | 🟢 |
| Тело ошибки — пустой JSON-объект `{}`, чтобы не эхо значений ввода | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:94, 108, 114, 119` | 🟢 |
| Расписание сбоев по номеру вызова — для сценариев retry/backoff адаптера без привязки ко времени | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:68-77, 129-134` | 🟡 |

## Observabilidade

Нет. Проверка — по статусам и заголовкам ответа.

## Riscos e Lacunas

- 🔴 Q-01: любые не-`IllegalArgumentException` из `StubSource` дают обрыв соединения.
- 🟡 Q-11: `baseUri()` не отражает привязку `0.0.0.0`.
- 🟡 Q-14: последовательная обработка, общий счётчик `calls`.
