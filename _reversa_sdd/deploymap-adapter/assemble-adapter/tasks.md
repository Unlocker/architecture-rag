# assemble-adapter, Tarefas

- [ ] T-01, `SYSTEM` и `config` — Origin: `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:18, 22-25`; готово: `sourceUrn()` = `urn:corp:deploymap`; Уверенность 🟢
- [ ] T-02, `create` с проверкой источника — Origin: `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:27-38`; готово: `IllegalArgumentException` на чужом `system`, `SourceAdapter` на своём; Уверенность 🟢
- [ ] T-03, Вызов из лаунчера — Origin: `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:143`; Уверенность 🟢

## Tarefas de Teste
- [ ] TT-01, `adapterIsBoundToItsSource` (🟢 есть, `DeploymapAdapterContractTest.java:40-51`)
- [ ] TT-02, Положительный `create(DEPLOY_MAP, journal, rawStore)` возвращает не-null `SourceAdapter` (🔴 нет)

## Ordem
T-01 → T-02 → T-03.

## Lacunas (🔴)
Q-03 (нет положительного теста `create`), Q-02 (`config` не используется в проде) — [../questions.md](../questions.md).
