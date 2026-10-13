# adapters/eam-adapter — задачи реализации

> `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`. Задачи сценариев — в `fetch-changes/tasks.md`, `composite-sync/tasks.md`, `eam-api-request/tasks.md`; здесь — сборка модуля и сквозные.

## Pre-requisites

- [ ] Доступны `contracts/source-spi`, `contracts/event-schemas`, `adapters/adapter-core` (`adapters/eam-adapter/pom.xml`); `platform/source-stubs` — только `test`-scope.
- [ ] `pom.xml` модуля без версий (наследуется от `adapters`), артефакт `eam-adapter`.
- [ ] Для запуска интеграции — переменные `ARCHRAG_EAM_MODE`, `ARCHRAG_EAM_API_URL`, `ARCHRAG_EAM_API_TOKEN` (значения не хранить в Git).

## Задачи

- [ ] T-01 Модуль-маркер `EamAdapterModule` и `package-info`.
  - Origin: `EA/EamAdapterModule.java:3-7`, `EA/package-info.java:1-2`
  - Критерий готовности: `EamAdapterSmokeTest.moduleMarkerIsOnClasspath` зелёный.
  - Уверенность: 🟢
- [ ] T-02 `EamAdapter.SYSTEM = SourceSystem.EAM` и `config(URI, String)` через `AdapterConfig.of`.
  - Origin: `EA/EamAdapter.java:19, 24-26`
  - Критерий готовности: `config(...).sourceUrn()` равен `urn:corp:eam` (`EamAdapterContractTest.adapterIsBoundToItsSource`).
  - Уверенность: 🟢
- [ ] T-03 `EamAdapter.create(config, journal, rawStore)`: проверка источника → `new SourceAdapter(config, journal, rawStore)` (legacy-режим).
  - Origin: `EA/EamAdapter.java:33-39`
  - Критерий готовности: конфигурация `SCM` → `IllegalArgumentException("config is for SCM, expected EAM")`.
  - Уверенность: 🟢
- [ ] T-04 Запись `EamApi(URI baseUri, String token)` с `toString` без токена.
  - Origin: `EA/EamAdapter.java:47-52`
  - Критерий готовности: `toString()` не содержит токена и имеет вид `EamApi[baseUri=<uri>, token=***]`.
  - Уверенность: 🟢
- [ ] T-05 `EamAdapter.create(config, EamApi, journal, rawStore)`: та же проверка источника → `CompositeEamConnector(EamApiConnector(baseUri, token, config.pageSize()), HttpSourceConnector(EAM, config.sourceBaseUri()))` → `new SourceAdapter(config, connector, journal, rawStore)`; закрытие коннектора делает `SourceAdapter.close()` (`AC/SourceAdapter.java:130`).
  - Origin: `EA/EamAdapter.java:62-71`
  - Критерий готовности: IT-адаптер читает snapshot двух половин (`platform/integration-tests/.../EamApiProjectionIT`, `EamKeyMigrationIT`).
  - Уверенность: 🟢
- [ ] T-06 Реализовать `EamApiConnector` — см. [`eam-api-request/tasks.md`](eam-api-request/tasks.md) (HTTP) и [`fetch-changes/tasks.md`](fetch-changes/tasks.md) (проход).
  - Origin: `EA/EamApiConnector.java`
  - Критерий готовности: все тесты `EamApiConnectorTest` зелёные.
  - Уверенность: 🟢
- [ ] T-07 Реализовать `EamApiCursor` — см. [`fetch-changes/tasks.md`](fetch-changes/tasks.md) T-F2.
  - Origin: `EA/EamApiCursor.java`
  - Критерий готовности: round-trip `parse(encode(c)) == c` для трёх режимов; тест `malformedCursorIsRejected`.
  - Уверенность: 🟢
- [ ] T-08 Реализовать `CompositeEamConnector` — см. [`composite-sync/tasks.md`](composite-sync/tasks.md).
  - Origin: `EA/CompositeEamConnector.java`
  - Критерий готовности: все тесты `CompositeEamConnectorTest` зелёные.
  - Уверенность: 🟢
- [ ] T-09 Fixtures `eam-api/**` и `EamApiFixturesContractTest` (формат `ArchObject`, пагинация, tombstone-кандидат, same-ver, ref-change, deferred-reference, platform-mismatch, same-name).
  - Origin: `adapters/eam-adapter/src/test/resources/eam-api/README.md`, `ET/EamApiFixturesContractTest.java:75-215`
  - Критерий готовности: 14 тестов класса зелёные, `baselineFixturesMatchDemoStubSeed` сверяет fixtures с `EamApiSeed`.
  - Уверенность: 🟢

## Тестовые задачи

Уже существуют (имена — методы):

- [ ] TT-01 `EamApiConnectorTest` — 18 тестов (snapshot, пагинация, инкремент, токен, fetchById, лимиты, курсор) — `ET/EamApiConnectorTest.java:84-296`.
- [ ] TT-02 `CompositeEamConnectorTest` — 9 тестов — `ET/CompositeEamConnectorTest.java:73-197`.
- [ ] TT-03 `EamApiFixturesContractTest` — 14 тестов — `ET/EamApiFixturesContractTest.java:75-215`.
- [ ] TT-04 `EamAdapterContractTest` — 6 тестов: 5 проверяют **legacy-путь** (`HttpSourceConnector`), `adapterIsBoundToItsSource` — фабрику legacy-режима — `ET/EamAdapterContractTest.java:40-104`.
- [ ] TT-05 `EamAdapterSmokeTest` — `ET/EamAdapterSmokeTest.java:10`.

Пробелы покрытия (тестов нет; ожидаемое поведение выведено из кода, уверенность 🟡):

- [ ] TT-13 `EamAdapter.create(config, EamApi, journal, rawStore)` с конфигурацией чужого источника → `IllegalArgumentException` (`EA/EamAdapter.java:64-66`).
- [ ] TT-14 `EamApi.toString()` не содержит токена (`EA/EamAdapter.java:49-52`).
- [ ] TT-15 Повреждённая `eam-api`-часть составного курсора — зафиксировать фактическое поведение (`IllegalArgumentException`, вопрос Q-4).

Сквозные интеграционные тесты, лежащие вне модуля: `EamApiProjectionIT`, `EamKeyMigrationIT`, `AdapterInboxIT` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/`).

## Порядок

1. `EamApiCursor` → `EamApiConnector` (HTTP-слой → `fetchChanges` → `fetchById`) → `CompositeEamConnector` → `EamAdapter`.
2. Тесты коннектора идут первыми: они используют `EamApiStub` (тест-scope `source-stubs`) и не требуют Docker.
3. Сквозные IT — после появления рабочего `adapter-core`.

## Открытые 🔴

См. [`questions.md`](questions.md): Q-1 (🔴 проверка прав учётки при подключении) и 🟡 Q-2…Q-9.
