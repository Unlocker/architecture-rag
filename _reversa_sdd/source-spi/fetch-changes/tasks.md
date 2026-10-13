# fetch-changes — задачи

## Pré-requisitos
- [ ] `SourceChange`, `SourceSystem` готовы (см. `../tasks.md` T-01, T-02) 🟢

## Tarefas
- [ ] T-01 `ChangePage` с `List.copyOf` и инвариантом `snapshotComplete && hasMore`
  - Origin: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/ChangePage.java:17-25`
  - Критерий готовности: AC-02, AC-03
  - Уверенность: 🟢
- [ ] T-02 `SourceUnavailableException` (поля, `isRateLimited`, сообщение)
  - Origin: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceUnavailableException.java:9-36`
  - Критерий готовности: AC-04
  - Уверенность: 🟢
- [ ] T-03 `SourceConnector.fetchChanges` с Javadoc-контрактом (cursor null = snapshot, limit > 0, исключение)
  - Origin: `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceConnector.java:17-24`
  - Критерий готовности: реализации компилируются
  - Уверенность: 🟢

## Tarefas de Teste
- [ ] TT-01 `ChangePage` happy path (AC-01), неизменяемость (AC-02) — нет 🔴
- [ ] TT-02 `ChangePage` негатив (AC-03) — нет 🔴
- [ ] TT-03 `SourceUnavailableException` (AC-04) — нет в SPI 🔴; косвенно `RetryPolicyTest`, `PollerTest`
- [ ] TT-04 snapshot без `snapshotComplete` у потребителя (AC-05) — `PollerTest.incompleteSnapshotDoesNotWriteMarkerOrMoveCursor` (`adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/PollerTest.java:63`) 🟢

## Ordem Sugerida
T-02 → T-01 → T-03.

## Lacunas Pendentes (🔴)
[`../questions.md`](../questions.md) Q-03.
