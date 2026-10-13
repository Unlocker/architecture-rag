# adapters/deploymap-adapter, Tarefas de Implementação

## Pré-requisitos
- [ ] Доступны модули `adapter-core`, `source-spi`, `event-schemas` (`pom.xml:17-29`); для тестов `canonical-model` и `source-stubs` в `scope=test` (`pom.xml:30-41`)
- [ ] Родитель `adapters` объявляет модуль (`adapters/pom.xml:23`)
- [ ] БД и env не нужны (секреты и URL приходят из `DemoLauncher`: `ARCHRAG_SOURCE_URL`, `ARCHRAG_WEBHOOK_SECRET`)

## Tarefas

- [ ] T-01, Пакет `io.github.unlocker.archrag.deploymapadapter` и маркер модуля
  - Origin: `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/package-info.java:1-2`, `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterModule.java:3-6`
  - Критерий готовности: `DeploymapAdapterSmokeTest.moduleMarkerIsOnClasspath` зелёный
  - Уверенность: 🟢
- [ ] T-02, `DeploymapAdapter`: `public static final SourceSystem SYSTEM = DEPLOY_MAP`, private-конструктор
  - Origin: `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:15-20`
  - Критерий готовности: `DeploymapAdapter.SYSTEM.code()` == `"deploymap"`; `AdapterConfig.sourceUrn()` == `urn:corp:deploymap`
  - Уверенность: 🟢
- [ ] T-03, `config(URI, String)` через `AdapterConfig.of(SYSTEM, ...)`
  - Origin: `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:22-25`
  - Критерий готовности: дефолты ядра (replay 5 мин, pageSize 100, poll 30 с, reconcile 6 ч, retry 5×500 мс–30 с)
  - Уверенность: 🟢
- [ ] T-04, `create(config, journal, rawStore)` с проверкой `config.system() == SYSTEM`
  - Origin: `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:27-38`
  - Критерий готовности: чужой `system` даёт `IllegalArgumentException` с текстом `config is for <X>, expected DEPLOY_MAP`
  - Уверенность: 🟢
- [ ] T-05, Fixtures `deploymap-poc-0/1` (7 файлов) и `README.md`
  - Origin: `adapters/deploymap-adapter/src/test/resources/deploymap/**`
  - Критерий готовности: `DeployMapFixturesContractTest` зелёный; содержимое — в [contracts.md](contracts.md)
  - Уверенность: 🟢
- [ ] T-06, Подключить модуль к `demo-launcher` (`case DEPLOY_MAP`)
  - Origin: `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:143`, `platform/demo-launcher/pom.xml:40`
  - Критерий готовности: контейнер `adapter-deploymap` стартует с командой `adapter DEPLOY_MAP` (`platform/docker/docker-compose.yml:366-379`)
  - Уверенность: 🟢

## Tarefas de Teste
- [ ] TT-01, Привязка к источнику и отказ на чужом `AdapterConfig` — `DeploymapAdapterContractTest.adapterIsBoundToItsSource` (🟢 есть)
- [ ] TT-02, Контракт коннектора на заглушке: снапшот страницами, инкремент после курсора, GET by id, tombstone, 429 — 5 тестов `DeploymapAdapterContractTest` (🟢 есть)
- [ ] TT-03, Fixtures: форма, согласованность с seed, негативы — 9 тестов `DeployMapFixturesContractTest` (🟢 есть)
- [ ] TT-04, Положительная ветка `create(DEPLOY_MAP, ...)` возвращает `SourceAdapter` (🔴 теста нет, см. Q-03)
- [ ] TT-05, Прогон fixtures через `DeployMapMapper` (🔴 теста нет, Q-04)

## Ordem Sugerida
1. T-01 → T-02 → T-03 → T-04 (последовательно в одном файле).
2. T-05 независимо от кода; зависит от формата в `DeployMapFormat` и seed `StubSources`.
3. T-06 после T-04.

## Lacunas Pendentes (🔴)
См. [questions.md](questions.md): Q-01 (реальный источник), Q-03 (тест `create`), Q-04 (fixtures ↔ нормализатор), Q-05 (расхождения в предыдущих документах).
