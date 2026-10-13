# raw-payload-store: задачи

> Общие задачи: [`../tasks.md`](../tasks.md) (T-09). Сокращения: `S3S`.

- [ ] **T-S1** `sha256(byte[])` — SHA-256 в hex нижнего регистра.
  - Origin: `S3S:101-108`
  - Критерий готовности: AC-S5.
  - Уверенность: 🟢
- [ ] **T-S2** `create(...)`: клиент с path-style, `us-east-1`, `WHEN_REQUIRED`, статическими ключами, `UrlConnectionHttpClient`.
  - Origin: `S3S:39-51`
  - Критерий готовности: клиент работает с SeaweedFS (AC-S1); секреты не попадают в тексты исключений.
  - Уверенность: 🟢
- [ ] **T-S3** `put`: валидация `source`, ключ, `exists`, условный `putObject`, возврат `RawPayloadRef`.
  - Origin: `S3S:68-79`, `S3S:91-99`
  - Критерий готовности: AC-S1, AC-S2.
  - Уверенность: 🟢
- [ ] **T-S4** `get`: чтение и проверка hash.
  - Origin: `S3S:81-89`
  - Критерий готовности: AC-S3.
  - Уверенность: 🟢
- [ ] **T-S5** `ensureBucket`, `close`, публичный конструктор.
  - Origin: `S3S:54-66`, `S3S:110-112`
  - Критерий готовности: AC-S4; `close` закрывает `S3Client`.
  - Уверенность: 🟢

## Тесты

- [x] TT-S1 `S3RawPayloadStoreTest.sha256IsStableHex`.
- [x] TT-S2 `S3RawPayloadStoreTest.rejectsSourceThatCouldEscapeKeyPrefix`.
- [x] TT-S3 `EventJournalIT.rawPayloadRoundTripsAndKeyIsContentAddressed` (контейнер S3 SeaweedFS, порт 8333).
- [ ] TT-S4 🟡 Повторный `ensureBucket`; повторный `put` не вызывает `putObject` (мок `S3Client`).
- [ ] TT-S5 🟡 `get` несуществующего ключа даёт `NoSuchKeyException` (не перехватывается).

## Порядок

T-S1 → T-S3 → T-S4 → T-S2 → T-S5.

## Открытые 🔴

Нет собственных 🔴; retention raw (R-5) — [`../questions.md`](../questions.md).
