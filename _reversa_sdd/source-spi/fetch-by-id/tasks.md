# fetch-by-id — задачи

## Pré-requisitos
- [ ] `ChangeOperation`, `Completeness`, `SourceSystem`, `SourceUnavailableException` (см. `../tasks.md` T-01, T-05) 🟢

## Tarefas
- [ ] T-01 `SourceChange` (5 `requireNonNull`, неизменяемая копия payload с `null`-значениями)
  - Origin: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceChange.java:24-42`
  - Критерий готовности: AC-01, AC-02
  - Уверенность: 🟢
- [ ] T-02 `WebhookEvent` (7 полей)
  - Origin: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookEvent.java:18-25`
  - Критерий готовности: порядок и типы как в контракте
  - Уверенность: 🟢
- [ ] T-03 `SourceConnector.fetchById` → `Optional<SourceChange>`
  - Origin: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceConnector.java:26-33`
  - Критерий готовности: AC-03 в потребителе
  - Уверенность: 🟢

## Tarefas de Teste
- [ ] TT-01 `SourceChange` payload с `null`-значением и неизменяемость (AC-01) — нет 🔴
- [ ] TT-02 `SourceChange` `null`-поля → NPE (AC-02) — нет 🔴
- [ ] TT-03 `fetchById` пусто → `404`, недоступно → `503` — `WebhookHandlerTest.unknownObjectAnswersNotFound` (:153), `sourceOutageAnswersServiceUnavailableAndStoresNothing` (:141), `deleteNotificationForForgottenObjectIsRecordedFromSignedBody` (:180) в `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/WebhookHandlerTest.java` 🟢
- [ ] TT-04 `SourceSystem.code()` (AC-04) — нет 🔴

## Ordem Sugerida
T-01 → T-02 → T-03.

## Lacunas Pendentes (🔴)
[`../questions.md`](../questions.md) Q-02, Q-03, Q-05.
