# stub-source-http: задачи

## Pré-requisitos
- [ ] `StubSource` с `fetchChanges`/`fetchById` ([`../stub-source-journal/tasks.md`](../stub-source-journal/tasks.md)); `Json.write` ([`../tasks.md`](../tasks.md) T-02)

## Tarefas

- [ ] T-01: конструкторы, `baseUri`, `port`, `close`, контексты `/changes` и `/objects/`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:41-66, 177-180`
  - Критерий готовности: AC-06; при занятом порту `IllegalStateException`
  - Уверенность: 🟢
- [ ] T-02: `handleChanges` и `handleObject` с декодированием URL
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:79-100, 159-175`
  - Критерий готовности: AC-01, AC-02, AC-03
  - Уверенность: 🟢
- [ ] T-03: `respond` — 405, расписание сбоев, преобразование исключений в статусы и `Retry-After`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:68-77, 102-134`
  - Критерий готовности: AC-04, AC-05
  - Уверенность: 🟢
- [ ] T-04: JSON-представление `ChangePage` и `SourceChange` с фиксированным порядком ключей
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:136-157`
  - Критерий готовности: тело совпадает с примером [`../contracts.md`](../contracts.md) §1.1
  - Уверенность: 🟢
- [ ] T-05: 🔴 перехват ошибок разбора курсора как `400`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:118-120`; [`../questions.md`](../questions.md) Q-01
  - Критерий готовности: `GET /changes?cursor=s5` → `400 {}`, тест добавлен
  - Уверенность: 🔴

## Tarefas de Teste

- [ ] TT-01: `StubSourceServerTest` — 8 тестов (`explicitAddressOverloadServesOnChosenPort`, `pagesAreWalkedByCursor`, `objectSnapshotAndNotFound`, `invalidLimitIsBadRequest`, `rateLimitCarriesRetryAfterOnScheduledCall`, `serverErrorFromSourceFailureIsPropagated`, `timeoutIsReportedAsGatewayTimeout`, `writesAreRejected`)
  - Origin: `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceServerTest.java:54-120`
  - Критерий готовности: surefire зелёный без Docker
  - Уверенность: 🟢
- [ ] TT-02: 🔴 нет тестов на `/objects/a/b/c`, URL-кодированные id, битый курсор, `DELETE`-запись по `/objects`
  - Origin: [`../questions.md`](../questions.md) Q-01
  - Критерий готовности: тесты добавлены
  - Уверенность: 🔴

## Ordem Sugerida
T-01 → T-04 → T-02 → T-03 → T-05.

## Lacunas Pendentes (🔴)
Q-01 — [`../questions.md`](../questions.md).
