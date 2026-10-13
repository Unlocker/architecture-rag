# contracts/source-spi — внешние контракты

> Пути: `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`. Подробности webhook — `docs/03-interfaces.md` §3, §5.1 (только ссылка).

## 1. Заголовки webhook (`SPI/WebhookSignature.java:25-33`) 🟢

| Заголовок | Значение |
|---|---|
| `X-Archrag-Signature` | `sha256=` + hex(HMAC-SHA256(secret, `<timestamp>` + `.` + `<body>`)), строки в UTF-8; `sign` выдаёт нижний регистр (`HexFormat.of()`), `verify` принимает и верхний (`HexFormat.parseHex`) 🟡 — `docs/03-interfaces.md:331` требует нижний |
| `X-Archrag-Timestamp` | unix-секунды отправки, десятичное `long` (`Long.parseLong`, допускает знак `+`/`-`) |
| `X-Archrag-Event-Id` | идентификатор события; сверку с `eventId` тела делает `WebhookHandler` (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java:81`), не SPI |

## 2. Уведомление `WebhookEvent` (JSON-тело webhook) 🟢

`eventId: String` (уникален в источнике, при повторной доставке сохраняется), `source: String` (код: `eam|scm|cmdb|deploymap`), `sourceType: String`, `sourceId: String`, `sourceVersion: long`, `operation: UPSERT|DELETE`, `occurredAt: Instant` (UTC) (`SPI/WebhookEvent.java:18-25`). Состояния объекта не несёт.

## 3. Формат изменения `SourceChange` (ответ источника, JSON в `HttpSourceConnector`) 🟢

`sourceType`, `sourceId`, `sourceVersion: long`, `operation`, `completeness: COMPLETE|PARTIAL`, `updatedAt: Instant`, `payload: object` (поля источника; `null`-значение = «поле не передано») (`SPI/SourceChange.java:24-31`; JSON-разбор — `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/HttpSourceConnector.java`, вне модуля).

## 4. Страница `ChangePage` 🟢

`changes[]`, `nextCursor` (непрозрачная строка; на последней странице — курсор инкрементального polling), `hasMore`, `snapshotComplete`; запрещено `snapshotComplete && hasMore` (`SPI/ChangePage.java:17-25`). В `HttpSourceConnector` поля читаются как `Boolean.TRUE.equals(doc.get(...))` (`HttpSourceConnector.java:66-67`).

## 5. Коды источников (`SPI/SourceSystem.java:5-9`) 🟢

| Константа | `code()` |
|---|---|
| `EAM` | `eam` |
| `SCM` | `scm` |
| `CMDB` | `cmdb` |
| `DEPLOY_MAP` | `deploymap` |

## 6. Ошибки

| Тип | Условие | Код возврата вызывающей стороны |
|---|---|---|
| `SourceUnavailableException` | 429, 5xx, timeout (`statusCode=0`) | `Poller` — повтор с backoff; `WebhookHandler` — `503` (`WebhookHandler.java:86-88`) 🟢 |
| `IllegalArgumentException` | `ChangePage(snapshotComplete=true, hasMore=true)` | — |
| `NullPointerException` | `null` в обязательных полях `SourceChange`; `null changes` у `ChangePage` | — |

## 7. Результаты `WebhookSignature.Result` и их отображение 🟢

`VALID` → продолжить; `MALFORMED`, `BAD_SIGNATURE`, `OUT_OF_WINDOW` → `401` без тела (`WebhookHandler.java:65-66`).
