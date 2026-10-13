# contracts/source-spi — задачи реализации

> `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`, `SPT/` = `contracts/source-spi/src/test/java/io/github/unlocker/archrag/sourcespi/`. Use case: `verify-webhook-signature/`, `fetch-changes/`, `fetch-by-id/`.

## Pré-requisitos

- [ ] Java 21 (records, `HexFormat` — Java 17+) 🟢
- [ ] Родительский `pom.xml` с BOM и тестовыми зависимостями JUnit 5 + AssertJ (`pom.xml:164-171`) 🟢
- [ ] Артефакт `io.github.unlocker.archrag:source-spi`, родитель `contracts` (`contracts/source-spi/pom.xml:7-15`) 🟢
- [ ] Зависимость `canonical-model` — не нужна по коду; решение Q-01 🔴

## Tarefas

- [ ] T-01 Enum'ы `ChangeOperation {UPSERT, DELETE}`, `Completeness {COMPLETE, PARTIAL}`, `SourceSystem` с `code()` (`eam`, `scm`, `cmdb`, `deploymap`)
  - Origin: `SPI/ChangeOperation.java:4-7`, `SPI/Completeness.java:7-10`, `SPI/SourceSystem.java:4-20`
  - Критерий готовности: константы и коды совпадают дословно (в т. ч. `DEPLOY_MAP` → `"deploymap"`)
  - Уверенность: 🟢
- [ ] T-02 Record `SourceChange` с `requireNonNull` на 5 полях и неизменяемой копией `payload` (`LinkedHashMap`, `null` → `Map.of()`, `null`-значения сохраняются)
  - Origin: `SPI/SourceChange.java:24-42`
  - Критерий готовности: `new SourceChange(..., null)` даёт пустую неизменяемую карту; `put` → `UnsupportedOperationException`; значение `null` под ключом сохраняется
  - Уверенность: 🟢
- [ ] T-03 Record `ChangePage` с `List.copyOf` и запретом `snapshotComplete && hasMore` (сообщение `snapshotComplete is only valid on the last page`)
  - Origin: `SPI/ChangePage.java:17-25`
  - Критерий готовности: AC-03 `fetch-changes`
  - Уверенность: 🟢
- [ ] T-04 Record `WebhookEvent` (7 полей, без проверок)
  - Origin: `SPI/WebhookEvent.java:18-25`
  - Критерий готовности: компоненты и порядок как в контракте
  - Уверенность: 🟢
- [ ] T-05 `SourceUnavailableException(SourceSystem, int, Duration)` с сообщением `"<code> unavailable, status=<n>"`, `statusCode()`, `retryAfter()`, `isRateLimited()` (429)
  - Origin: `SPI/SourceUnavailableException.java:9-36`
  - Критерий готовности: AC-04 `fetch-changes`
  - Уверенность: 🟢
- [ ] T-06 Интерфейс `SourceConnector` (`system`, `fetchChanges`, `fetchById`)
  - Origin: `SPI/SourceConnector.java:12-34`
  - Критерий готовности: `HttpSourceConnector`, `EamApiConnector`, `StubSource` компилируются без изменений
  - Уверенность: 🟢
- [ ] T-07 `WebhookSignature`: константы заголовков, `sign`, `verify`, `Result`, приватный `hmac`
  - Origin: `SPI/WebhookSignature.java:22-96`
  - Критерий готовности: AC-01…AC-05 `verify-webhook-signature`
  - Уверенность: 🟢
- [ ] T-08 `package-info.java` с описанием SPI
  - Origin: `SPI/package-info.java:1-5`
  - Критерий готовности: Javadoc пакета ссылается на `SourceConnector` и `platform/source-stubs`
  - Уверенность: 🟢
- [ ] T-09 (опционально) Принудительные инварианты `SourceChange`
  - Origin: `SPI/SourceChange.java:12-14` (только Javadoc)
  - Критерий готовности: решение Q-02; иначе не реализовывать
  - Уверенность: 🔴

## Tarefas de Teste

- [ ] TT-01 `validSignatureIsAccepted` — `sign` → `verify` = `VALID` (`SPT/WebhookSignatureTest.java:20-25`) 🟢
- [ ] TT-02 `wrongSecretOrTamperedBodyIsRejected` — чужой секрет и изменённое тело → `BAD_SIGNATURE` (`:27-35`) 🟢
- [ ] TT-03 `timestampOutsideReplayWindowIsRejectedEvenWithValidSignature` — −10 мин и +10 мин при окне 5 мин → `OUT_OF_WINDOW` (`:37-47`) 🟢
- [ ] TT-04 `missingOrMalformedHeadersAreRejected` — `null`-timestamp, `null`-подпись, нечисловой timestamp, не-hex, префикс `md5=` → `MALFORMED` (`:49-61`) 🟢
- [ ] TT-05 `ChangePage`: `snapshotComplete && hasMore` → IAE; `hasMore=false, snapshotComplete=true` допустим; `changes` неизменяем — **тестов нет** 🔴
- [ ] TT-06 `SourceChange`: `null`-поля → NPE; payload неизменяем и сохраняет `null`-значения; `null` payload → пустая карта — **тестов нет** 🔴
- [ ] TT-07 `SourceUnavailableException`: `isRateLimited()`, сообщение без секретов, `retryAfter == null` — **тестов нет** 🔴
- [ ] TT-08 Граница окна (`skew == window` → `VALID`, `window + 1 с` → `OUT_OF_WINDOW`), пустой hex `sha256=` → `BAD_SIGNATURE`, верхний регистр hex → `VALID` — **не покрыто** 🟡
- [ ] TT-09 Косвенные потребители: `WebhookHandlerTest` (`adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/WebhookHandlerTest.java:48-51`), `StubWebhookSenderTest` (`platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubWebhookSenderTest.java:37-40`) 🟢

## Ordem Sugerida

1. T-01, T-04, T-05 (независимые типы) → T-02, T-03 (используют T-01) → T-06 (использует T-01…T-03, T-05) → T-07 (самостоятельно) → T-08.
2. TT-01…TT-04 — вместе с T-07; TT-05…TT-07 — сразу после соответствующих T.

## Lacunas Pendentes (🔴)

См. [`questions.md`](questions.md): Q-01 (неиспользуемая зависимость), Q-02 (инварианты `SourceChange`), Q-03 (нет тестов DTO), Q-04 (эскиз в видении), Q-05 (`long` против `SourceVersion`).
