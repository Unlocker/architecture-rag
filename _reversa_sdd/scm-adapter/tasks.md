# adapters/scm-adapter, задачи реимплементации

## Pré-requisitos
- [ ] Доступны `adapter-core` (`SourceAdapter`, `AdapterConfig`, `HttpSourceConnector`), `source-spi` (`SourceSystem`), `event-schemas` (`EventJournal`, `RawPayloadStore`).
- [ ] Родительский `adapters/pom.xml` перечисляет модуль (`adapters/pom.xml:21`); версия — в BOM корневого `pom.xml:111`.
- [ ] Конфигурация (из env, значения не в Git): `ARCHRAG_WEBHOOK_SECRET`, `ARCHRAG_SOURCE_URL` (`platform/docker/docker-compose.yml:345`).

## Tarefas
- [ ] T-01 `pom.xml` с зависимостями `adapter-core`, `source-spi`, `event-schemas`, `source-stubs` (test)
  - Origin: `adapters/scm-adapter/pom.xml:17-35`
  - Критерий готовности: модуль собирается, версий в модуле нет
  - Уверенность: 🟢
- [ ] T-02 `ScmAdapter`: `SYSTEM`, `config`, `create` с проверкой источника
  - Origin: `ScmAdapter.java:18,23-25,32-38`
  - Критерий готовности: TT-01, TT-02
  - Уверенность: 🟢
- [ ] T-03 `ScmAdapterModule`, `package-info`
  - Origin: `ScmAdapterModule.java:3-6`, `package-info.java:1-2`
  - Критерий готовности: TT-05
  - Уверенность: 🟢
- [ ] T-04 (опция) `null`-проверки аргументов `create`
  - Origin: пробел `ScmAdapter.java:34`
  - Критерий готовности: решение по Q-1
  - Уверенность: 🔴

## Tarefas de Teste
- [ ] TT-01 `adapterIsBoundToItsSource`: `system()`, `sourceUrn() == urn:corp:scm`
- [ ] TT-02 то же: чужой конфиг → `IllegalArgumentException`
- [ ] TT-03 `snapshotWalksAllObjectsInPagesAndCompletes`, `incrementalPollingReturnsOnlyChangesAfterCursor`, `fetchByIdReturnsStateAndEmptyForUnknownObject`, `deleteIsVisibleAsTombstone`, `rateLimitCarriesRetryAfterAndLeavesStateIntact`
- [ ] TT-04 (нет в коде) успешный `create` с настоящим журналом: покрыт только в `platform/integration-tests` (IT)
- [ ] TT-05 `ScmAdapterSmokeTest.moduleMarkerIsOnClasspath`

## Ordem Sugerida
T-01 → T-02 → TT-01/02 → T-03 → TT-03 (требует `source-stubs`).

## Lacunas Pendentes (🔴)
Q-1 в [questions.md](questions.md).
