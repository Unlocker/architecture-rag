# create-adapter, задачи

- [ ] T-01 `config` через `AdapterConfig.of` — Origin: `ScmAdapter.java:23-25`; готово: TT-01; 🟢
- [ ] T-02 `create` с guard и сборкой `SourceAdapter` — Origin: `ScmAdapter.java:32-38`; готово: TT-02; 🟢
- [ ] TT-01 `ScmAdapterContractTest.adapterIsBoundToItsSource` (позитив `sourceUrn`)
- [ ] TT-02 то же (негатив: чужой источник)
- [ ] TT-03 (нет в коде) пустой секрет — 🟡
- [ ] TT-04 (нет в модуле) успешный `create` — только `platform/integration-tests` 🟡

Порядок: T-01 → T-02. Открытые 🔴: Q-1 ([../questions.md](../questions.md)).
