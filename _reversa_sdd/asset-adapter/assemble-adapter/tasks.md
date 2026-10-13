# assemble-adapter: задачи

- [ ] T-01 Реализовать `config` и `create` с проверкой источника.
  - Origin: `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter/AssetAdapter.java:22-38`
  - Критерий готовности: чужой `config` отвергается, свой даёт `SourceAdapter`.
  - Уверенность: 🟢
- [ ] TT-01 `adapterIsBoundToItsSource` (`.../AssetAdapterContractTest.java:39-51`).
- [ ] TT-02 (нет в коде 🟡) позитивный юнит на `create` для `CMDB`.
- [ ] TT-03 (нет в коде 🟡) пустой секрет в `config` → `IllegalArgumentException`.

Порядок: T-01 → TT-01..TT-03. Открытые 🔴: нет (см. [`../questions.md`](../questions.md) для модуля).
