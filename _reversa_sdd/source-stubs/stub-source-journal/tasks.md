# stub-source-journal: задачи

## Pré-requisitos
- [ ] `contracts/source-spi`: `SourceConnector`, `ChangePage` (инвариант `snapshotComplete ⇒ !hasMore`), `SourceChange`, `WebhookEvent`, `SourceUnavailableException` ([`../../source-spi/tasks.md`](../../source-spi/tasks.md))

## Tarefas

- [ ] T-01: журнал и запись (`Entry`, `versions`, `append`, `upsert`/`upsertPartial`/`delete`)
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:31-70, 182-190`
  - Критерий готовности: AC-01, AC-02 (запись и версия); `DELETE` с пустым payload; `PARTIAL` сохраняет `null`
  - Уверенность: 🟢
- [ ] T-02: `fetchChanges` — инкремент `i<seq>` и перестановка страницы
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:108-131`
  - Критерий готовности: AC-04, AC-06; `limit ≤ 0` отвергается до учёта сбоя
  - Уверенность: 🟢
- [ ] T-03: `fetchChanges` — snapshot по `bound`, курсоры `s…`, `snapshotComplete` на последней странице
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:132-168`
  - Критерий готовности: AC-02, AC-03; 🔴 курсор `s…` без `:` должен давать `IllegalArgumentException` (решение по Q-01)
  - Уверенность: 🟢 (поведение), 🔴 (обработка битого курсора)
- [ ] T-04: `fetchById`, `webhookEvents`, `suppressWebhooks`, `failNext`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSource.java:72-105, 170-180, 192-209`
  - Критерий готовности: AC-05, AC-07; `eventId = "<код>-evt-<seq>"`
  - Уверенность: 🟢
- [ ] T-05: seed четырёх источников, константы, негативные хелперы, `fields`
  - Origin: `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSources.java:16-221`, `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java:24-47`
  - Критерий готовности: AC-08, AC-09; данные seed совпадают с таблицей [`../contracts.md`](../contracts.md) §4.1 побайтно
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: `StubSourceTest` — 17 тестов (`seededSources…`, `seededDeployment…`, `negativeDeployMapScenarios…`, `redeploy…`, `updateBumps…`, `deleteIsVisible…`, `snapshotPagesAreStable…`, `incrementalPolling…`, `partialRecord…`, `brokenReference…`, `suppressedWebhook…`, `staleWebhookVersion…`, `rateLimitThenRecovery`, `serverErrorAffects…`, `reorderedPage…`, `invalidLimit…`, `unknownObject…`)
  - Origin: `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceTest.java:27-252`
  - Критерий готовности: surefire зелёный без Docker
  - Уверенность: 🟢
- [ ] TT-02: 🔴 тесты на `s5`, `i<не число>`, snapshot пустого журнала, snapshot с `PARTIAL` последней записью, `fields` с нечётным числом аргументов
  - Origin: пробелы Y1.7 ([`../../code-analysis.md`](../../code-analysis.md)); [`../questions.md`](../questions.md) Q-01, Q-07, Q-09
  - Критерий готовности: поведение каждого случая закреплено тестом
  - Уверенность: 🔴
- [ ] TT-03: 🟡 усилить слабые утверждения (`doesNotContain("missed")`, `isLessThan(current + 1)`)
  - Origin: `platform/source-stubs/src/test/java/io/github/unlocker/archrag/sourcestubs/StubSourceTest.java:183, 198-199`
  - Критерий готовности: утверждение падает при регрессии (Q-12)
  - Уверенность: 🟡

## Ordem Sugerida
T-01 → T-02 → T-03 → T-04 → T-05; TT-01 с каждой задачей.

## Lacunas Pendentes (🔴)
Q-01, Q-07, Q-09, Q-12 — [`../questions.md`](../questions.md).
