# verify-webhook-signature — дизайн

> Модульный дизайн: [`../design.md`](../design.md). Блок-схема: `_reversa_sdd/flowcharts/source-spi.md` §1.

## Interface
См. таблицу `WebhookSignature.sign/verify/Result` в [`../design.md`](../design.md) и [`../contracts.md`](../contracts.md) §1.

## Fluxo Principal
`verify(secret, ts, sig, body, now, window)`:
1. `ts == null || sig == null || !sig.startsWith("sha256=")` → `MALFORMED` (`SPI/WebhookSignature.java:65-67`).
2. `timestamp = Long.parseLong(ts)`; `NumberFormatException` → `MALFORMED` (`:68-73`).
3. `provided = HexFormat.of().parseHex(sig.substring(7))`; `IllegalArgumentException` (нечётная длина, не-hex) → `MALFORMED` (`:74-79`).
4. `expected = HMAC-SHA256(secret.getBytes(UTF_8), (timestamp + "." + body).getBytes(UTF_8))` — строка собирается из распарсенного `long`, не из исходного заголовка (`:80, 88-95`).
5. `MessageDigest.isEqual(expected, provided)` ложь → `BAD_SIGNATURE` (`:81-83`).
6. `skew = Math.abs(now.getEpochSecond() - timestamp)`; `skew > window.toSeconds()` → `OUT_OF_WINDOW`, иначе `VALID` (`:84-85`).

`sign` = `"sha256=" + HexFormat.of().formatHex(hmac(secret, ts + "." + body))` (`:47-49`).

## Fluxos Alternativos
- Пустой hex (`"sha256="`): `parseHex("")` даёт пустой массив → шаг 5 → `BAD_SIGNATURE` (не `MALFORMED`) 🟡 (вывод из `:76, 81`, тест не проверяет).
- Заголовок `"+5"` / `"-5"` парсится `parseLong`, в подпись идёт `5` / `-5` 🟡.
- `secret` пуст → IAE из `SecretKeySpec` (`:91`), наружу 🟡.
- Потребитель `WebhookHandler`: любой результат ≠ `VALID` → `401` (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java:57-66`) 🟢. Источники подписи в проекте: `StubWebhookSender` (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSender.java:47, 55, 76-78`) 🟢.

## Dependências
JDK `javax.crypto.Mac("HmacSHA256")`, `MessageDigest`, `HexFormat`. `GeneralSecurityException` → `IllegalStateException("HmacSHA256 unavailable", e)` без секрета (`:93-94`) 🟢.

## Decisões de Design Identificadas
См. [`../decisions.md`](../decisions.md). Replay window приходит параметром (`AdapterConfig.replayWindow`), не константой SPI 🟡.

## Estado Interno
Нет.

## Observabilidade
Нет логов.

## Riscos e Lacunas
- 🟡 Повторная доставка в окне с тем же timestamp проходит `verify`; защита от replay — по `eventId` вне модуля.
- 🔴 Нет тестов на границу окна, регистр hex, пустой hex, пустой секрет ([`../questions.md`](../questions.md) Q-03).
- 🟡 Приём верхнего регистра hex мягче, чем `docs/03-interfaces.md:331` («нижний регистр»).
