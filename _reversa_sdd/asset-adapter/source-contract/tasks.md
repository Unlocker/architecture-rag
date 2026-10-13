# source-contract: задачи

- [ ] T-01 Подготовить фикстуру: `StubSources.cmdb` + `StubSourceServer` + `HttpSourceConnector(CMDB, baseUri)`, закрывать в `@AfterEach`.
  - Origin: `adapters/asset-adapter/src/test/java/io/github/unlocker/archrag/assetadapter/AssetAdapterContractTest.java:22-37`
  - Критерий готовности: порты и клиенты освобождаются после каждого теста.
  - Уверенность: 🟢
- [ ] TT-01 Snapshot по страницам до `snapshotComplete` (`:53-66`).
- [ ] TT-02 Инкремент по курсору, `sourceVersion=2` (`:68-80`).
- [ ] TT-03 `fetchById` найден / не найден (`:82-89`).
- [ ] TT-04 Tombstone `DELETE` в `fetchById` и в инкременте (`:91-101`).
- [ ] TT-05 `429` с `Retry-After`, состояние источника цело (`:103-113`).
- [ ] TT-06 (нет в коде 🟡) `fetchChanges(null, 0)` → `IllegalArgumentException`; `5xx` → `SourceUnavailableException`.

Порядок: T-01 → TT-01..TT-05. Открытые 🔴: контракт реального CMDB не проверен (см. [`../questions.md`](../questions.md) № 1).
