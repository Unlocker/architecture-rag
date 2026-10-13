# fixtures-contract, Tarefas

- [ ] T-01, Создать 7 fixtures по таблице [../contracts.md](../contracts.md) §3 — Origin: `adapters/deploymap-adapter/src/test/resources/deploymap/**`; готово: файлы парсятся `EamApiFormat.parse`; Уверенность 🟢
- [ ] T-02, `README.md` fixtures со сценариями и риском H.2 — Origin: `.../deploymap/README.md:1-23`; Уверенность 🟢
- [ ] T-03, Хелперы `load/payload/meta/annotations/single` и сверка с seed SCM/EAM — Origin: `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeployMapFixturesContractTest.java:32-79`; Уверенность 🟢

## Tarefas de Teste (все 🟢 существуют, `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeployMapFixturesContractTest.java`)
- [ ] TT-01, `everyPoc1FixtureHasChartMetaWithThreeAnnotations` (:81)
- [ ] TT-02, `baselineIsConsistentWithScmAndEamSeed` (:96), `baselineMatchesStubSeed` (:106)
- [ ] TT-03, `unknownRepositoryDoesNotMatchAnyScmRepository` (:113), `systemMismatchKeepsRepositoryButChangesSystem` (:120), `invalidCommitIsNot40Hex` (:128)
- [ ] TT-04, `commitChangeKeepsSourceIdAndRaisesVersion` (:134), `legacyPoc0HasNoChartMeta` (:144), `stubSystemIsDeployMap` (:152)
- [ ] TT-05, Прогон fixtures через `DeployMapMapper` (🔴 нет, Q-04)

## Ordem
T-01 → T-03 (нужен seed `StubSources`, `EamApiSeed`) → TT-01…TT-04.

## Lacunas (🔴)
Q-01 (временный формат), Q-04 — [../questions.md](../questions.md).
