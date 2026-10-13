# verify-webhook-signature — требования

> Модульный контекст: [`../requirements.md`](../requirements.md). `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`.

## Visão Geral
Подписание (`sign`) и проверка (`verify`) webhook-уведомления мастер-системы по HMAC-SHA256 с защитой от replay по timestamp 🟢 (`SPI/WebhookSignature.java:12-20`).

## Regras de Negócio
- Подпись = `"sha256=" + hex(HMAC-SHA256(secret, timestamp + "." + body))`, UTF-8 для секрета и данных 🟢 (`:47-49, 88-95`).
- Порядок проверок в `verify`: (1) `timestampHeader == null` или `signatureHeader == null` или нет префикса `sha256=` → `MALFORMED`; (2) timestamp не парсится `Long.parseLong` → `MALFORMED`; (3) hex-часть не парсится `HexFormat.parseHex` → `MALFORMED`; (4) `!MessageDigest.isEqual` → `BAD_SIGNATURE`; (5) `|now − ts| > window.toSeconds()` → `OUT_OF_WINDOW`; иначе `VALID` 🟢 (`:65-85`).
- Подпись проверяется раньше окна: `OUT_OF_WINDOW` означает «подпись верна, время неверно» 🟢 (`:81-85`).
- Окно симметрично (прошлое и будущее); граница включительна (`skew == window` → `VALID`) 🟢 (`:84-85`, оператор `>`); тестом граница не покрыта 🟡.
- `window` усекается до целых секунд (`toSeconds()`) 🟢.
- Вызывающая сторона сводит три отказа в `401` без тела 🟢 (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java:57-66`).
- Пустой/некорректный `secret`: `SecretKeySpec` бросает IAE (`:91`), `verify` её не ловит 🟡; `null` у `secret/now/window` → NPE 🟡.
- `verify` сам по себе не отслеживает повторную доставку внутри окна; дедупликация — по `eventId` в журнале 🟡 (`docs/03-interfaces.md:347-349`).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `sign` возвращает `sha256=<lowercase hex>` | Must | AC-01 |
| RF-02 | `verify` возвращает `VALID` для верной подписи в окне | Must | AC-01 |
| RF-03 | неверный секрет или изменённое тело → `BAD_SIGNATURE` | Must | AC-02 |
| RF-04 | timestamp вне окна (и в прошлом, и в будущем) → `OUT_OF_WINDOW` при верной подписи | Must | AC-03 |
| RF-05 | отсутствующие/некорректные заголовки → `MALFORMED` | Must | AC-04 |
| RF-06 | имена заголовков — публичные константы | Must | AC-05 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | сравнение за постоянное время | `SPI/WebhookSignature.java:81` | 🟢 |
| Безопасность | секрет не попадает в исключения | `SPI/WebhookSignature.java:93-94` | 🟢 |

## Critérios de Aceitação

```gherkin
AC-01
Дано secret "s3cret", body {"eventId":"e1"}, now = 2026-10-04T12:00:00Z, окно 5 мин
Когда подпись = sign("s3cret", now, body) и вызван verify с timestamp = now
Тогда VALID                                  # WebhookSignatureTest.validSignatureIsAccepted

AC-02
Дано подпись, вычисленная секретом "other", либо тело изменено добавлением пробела
Когда вызван verify
Тогда BAD_SIGNATURE                          # WebhookSignatureTest.wrongSecretOrTamperedBodyIsRejected

AC-03
Дано верная подпись для timestamp на 10 минут раньше (и на 10 минут позже) now, окно 5 мин
Когда вызван verify
Тогда OUT_OF_WINDOW                          # WebhookSignatureTest.timestampOutsideReplayWindowIsRejectedEvenWithValidSignature

AC-04
Дано timestamp = null, либо подпись = null, либо timestamp "abc", либо "sha256=zz", либо префикс "md5="
Когда вызван verify
Тогда MALFORMED                              # WebhookSignatureTest.missingOrMalformedHeadersAreRejected

AC-05
Дано обращение к константам
Когда читаются SIGNATURE_HEADER, TIMESTAMP_HEADER, EVENT_ID_HEADER
Тогда значения "X-Archrag-Signature", "X-Archrag-Timestamp", "X-Archrag-Event-Id"   # тест на сами константы отсутствует 🟡
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Обоснование |
|---|---|---|
| RF-01…RF-05 | Must | единственная аутентификация webhook-входа |
| Граница окна, регистр hex, пустой секрет | Could | поведение есть, тестов нет |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|---|---|---|
| `SPI/WebhookSignature.java` | `WebhookSignature` | 🟢 |
| `contracts/source-spi/src/test/java/io/github/unlocker/archrag/sourcespi/WebhookSignatureTest.java` | 4 теста | 🟢 |
