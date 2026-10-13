# source-fetch-retry, Design

> Модульный дизайн — [`../design.md`](../design.md). `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`.

## Зависит от / Связанные спецификации

[`../design.md`](../design.md#зависит-от--связанные-спецификации); специально: `contracts/source-spi` (`SourceConnector`, `ChangePage`, `SourceChange`, `SourceUnavailableException`, `ChangeOperation`, `Completeness`) и его заглушка `platform/source-stubs` (HTTP-сервер для `HttpSourceConnectorTest`; контракт REST — `docs/03-interfaces.md:531-534`); `eam-adapter` подменяет коннектор составным (`_reversa_sdd/eam-adapter/`); [ADR 0010](../../../docs/adr/0010-master-systems-are-stubs.md). Потребители: [`incremental-poll`](../incremental-poll/design.md), [`full-snapshot`](../full-snapshot/design.md) (через `Poller.fetch`), [`receive-webhook`](../receive-webhook/design.md) (прямой `fetchById` без повторов).

## Интерфейс

| Символ | Сигнатура | Исключения |
|---|---|---|
| `HttpSourceConnector.fetchChanges` | `(String cursor, int limit) → ChangePage` | `SourceUnavailableException`; `IllegalStateException`; `IllegalArgumentException` (`limit ≤ 0`) |
| `HttpSourceConnector.fetchById` | `(String sourceType, String sourceId) → Optional<SourceChange>` | `SourceUnavailableException`; `IllegalStateException` |
| `RetryPolicy.delay` | `(int attempt, SourceUnavailableException, Random) → Duration` | — |
| `Poller.fetch` (private) | `(String cursor, long appended, String syncRunId) → ChangePage` | `GaveUp` (внутренний `RuntimeException`) |

## Поток `fetchChanges` (`AC/HttpSourceConnector.java:50-68`)

1. `limit ≤ 0` → `IllegalArgumentException`.
2. `query = "?limit=" + limit + (cursor == null ? "" : "&cursor=" + enc(cursor))`; `enc` = `URLEncoder` UTF-8 с заменой `+` на `%20` (`:55, 149-151`).
3. `get("/changes" + query)`; `requireOk` (должен быть `200`).
4. `Json.parseObject(body)`; `changes` — список; каждый элемент → `change(o)`; `nextCursor` — строка или `null`; `hasMore`, `snapshotComplete` — `Boolean.TRUE.equals` (`:58-67`).
5. `new ChangePage(...)` проверяет инвариант `snapshotComplete ⇒ !hasMore` (`IllegalArgumentException` из контракта `ChangePage` не перехватывается коннектором — выйдет как есть) 🟡.

## Поток `get` (`:80-102`)

URL = `baseUri` без хвостовых `/` + путь; `GET`, `Accept: application/json`, таймаут 10 с; `http.send(..., ofString(UTF_8))`. `IOException` → `SourceUnavailableException(system, 0, null)`; `InterruptedException` → флаг + `IllegalStateException`. Статус `429` или `≥ 500` → `SourceUnavailableException(system, status, retryAfter(r))`; иначе ответ возвращается вызывающему.

## Маппинг изменения (`change`, `:129-147`)

`sourceType`, `sourceId` — `String`; `sourceVersion` — `Number.longValue()`; `operation`, `completeness` — `Enum.valueOf(String)`; `updatedAt` — `Instant.parse`; `payload` — `Map` или `Map.of()`. Любая `RuntimeException` внутри (в т. ч. `NullPointerException`, `ClassCastException`, `IllegalArgumentException`) → `IllegalStateException("<code> answered malformed change", cause)`. Конструктор `SourceChange` проверяет `null` обязательных полей.

## Повторы: `Poller.fetch` (`AC/Poller.java:191-204`)

```
last = null
for attempt in 1..maxAttempts:
    try: return connector.fetchChanges(cursor, pageSize)
    catch SourceUnavailableException e:
        last = e
        if attempt < maxAttempts: pause(retry.delay(attempt, e, random))
throw GaveUp(last, appended, syncRunId)     # → PollResult(GAVE_UP, appended, syncRunId)
```

`GaveUp.getMessage()` = `"source unavailable after retries, status=<код>"` (без тела). `pause` вызывает `Sleeper.sleep` (`Sleeper.THREAD` → `Thread.sleep(ms)`); прерывание → `Thread.currentThread().interrupt()` + `IllegalStateException("poller interrupted")`.

## `RetryPolicy.delay(attempt, failure, random)` (`AC/RetryPolicy.java:40-50`)

```
shift   = min(max(attempt-1, 0), 30)
ceiling = min(maxDelay_ms, baseDelay_ms << shift)
half    = ceiling / 2
backoff = half + (long)(random.nextDouble() * (ceiling - half + 1))        # [half, ceiling]
ra      = failure.retryAfter(); if ra != null && ra > maxDelay: ra = maxDelay
return (ra != null && ra > backoff) ? ra : backoff
```

Значения по умолчанию (500 мс…30 с, 5 попыток): потолки пауз после попыток 1…4 — 0,5 / 1 / 2 / 4 с; фактические паузы лежат в `[ceiling/2, ceiling]`. Пример с тестовой политикой base 1 с, max 8 с — `RetryPolicyTest` строки 22-33.

## Решения

| Решение | Evidence | Уверенность |
|---|---|---|
| Equal jitter вместо full jitter: гарантированная нижняя граница ceiling/2 | `AC/RetryPolicy.java:43-44` | 🟢 |
| Повторы только на `SourceUnavailableException`; прочие ошибки сразу наверх | `AC/Poller.java:196-197` | 🟢 |
| Источник недоверенный: `Retry-After` ограничен `maxDelay`; HTTP-date не поддерживается | `AC/RetryPolicy.java:45-48`, `AC/HttpSourceConnector.java:116` | 🟢 |
| Webhook без повторов: источник повторит доставку сам | `docs/03-interfaces.md:351` | 🟢 |
| `HttpClient` один на коннектор, закрывается в `close()` (вызывает `SourceAdapter.close`) | `AC/HttpSourceConnector.java:37-38, 153-156`, `AC/SourceAdapter.java:128-135` | 🟢 |
| Аутентификации к источнику нет (мастера — заглушки) | `AC/HttpSourceConnector.java:80-86`; `tech-debt.md:28` (ссылка там на `:56-72` устарела) | 🟢 |

## Риски

- 🟡 U-04: целое вне `long` → `bad number` → `IllegalStateException("… malformed JSON")` на всей странице (`AC/Json.java:168-173`).
- 🔴 R-03: `IllegalStateException` коннектора не повторяется и обрывает цикл на каждой итерации расписания (постоянная ошибка формата блокирует polling).
- 🟡 Любой `IOException` (не только timeout; в т. ч. обрыв TLS/DNS) считается «недоступностью» и повторяется (`AC/HttpSourceConnector.java:90-92`); ошибка построения URL (`URI.create` → `IllegalArgumentException`, `:82`) повторов не получает.
- 🟡 `HttpClient` с настройками по умолчанию: редиректы не выполняются (`Redirect.NEVER` по умолчанию в JDK), `3xx` → `IllegalStateException` (неожиданный статус).
- 🟡 Ошибка `ChangePage` (инвариант `snapshotComplete && hasMore`) выходит наружу как `IllegalArgumentException`, а не `IllegalStateException` (`contracts/source-spi/.../ChangePage.java`).
