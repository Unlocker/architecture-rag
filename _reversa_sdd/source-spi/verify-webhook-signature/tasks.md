# verify-webhook-signature — задачи

## Pré-requisitos
- [ ] Java 21 `HexFormat`, `javax.crypto` 🟢

## Tarefas
- [ ] T-01 Константы `SIGNATURE_HEADER`, `TIMESTAMP_HEADER`, `EVENT_ID_HEADER`, приватный `PREFIX="sha256="`
  - Origin: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java:25-33`
  - Критерий готовности: значения побуквенно совпадают
  - Уверенность: 🟢
- [ ] T-02 `hmac(secret, data)` — `HmacSHA256`, UTF-8, `GeneralSecurityException` → `IllegalStateException("HmacSHA256 unavailable", e)`
  - Origin: `WebhookSignature.java:88-96`
  - Критерий готовности: `sign` детерминирован; сообщения не содержат секрета
  - Уверенность: 🟢
- [ ] T-03 `sign`
  - Origin: `WebhookSignature.java:47-49`
  - Критерий готовности: AC-01
  - Уверенность: 🟢
- [ ] T-04 `verify` в порядке MALFORMED → BAD_SIGNATURE → OUT_OF_WINDOW → VALID
  - Origin: `WebhookSignature.java:58-86`
  - Критерий готовности: AC-01…AC-04
  - Уверенность: 🟢

## Tarefas de Teste
- [ ] TT-01 AC-01 `validSignatureIsAccepted` 🟢
- [ ] TT-02 AC-02 `wrongSecretOrTamperedBodyIsRejected` 🟢
- [ ] TT-03 AC-03 `timestampOutsideReplayWindowIsRejectedEvenWithValidSignature` 🟢
- [ ] TT-04 AC-04 `missingOrMalformedHeadersAreRejected` 🟢
- [ ] TT-05 граница окна, пустой hex, регистр hex, пустой секрет — нет теста 🟡

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04; TT-01…TT-04 с T-04.

## Lacunas Pendentes (🔴)
[`../questions.md`](../questions.md) Q-03.
