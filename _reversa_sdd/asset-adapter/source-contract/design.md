# source-contract: дизайн

Модульный дизайн — [`../design.md`](../design.md). Реализацию контракта (`HttpSourceConnector`, `Poller`) см. спецификацию `adapter-core` и [`../../flowcharts/adapter-core.md`](../../flowcharts/adapter-core.md).

## Устройство тестов 🟢
- `@BeforeEach`: `StubSources.cmdb(Clock.systemUTC())` → `StubSourceServer(source)` → `new HttpSourceConnector(AssetAdapter.SYSTEM, server.baseUri())` (`AT/AssetAdapterContractTest.java:26-31`).
- `@AfterEach`: закрыть коннектор и сервер (`:33-37`).
- Управление заглушкой: `source.upsert(type, id, fields)`, `source.delete(type, id)`, `source.failNext(times, status, retryAfter)` (`:71,94,105`).
- Тест `adapterIsBoundToItsSource` проверяет `connector.system()`, `config(...).sourceUrn()`, отказ `create` на чужом `config` (`:39-51`).

## Зависимость от внешних модулей
`platform/source-stubs` (test-scope, `adapters/asset-adapter/pom.xml:30-35`); `adapter-core.HttpSourceConnector`; `source-spi` (`ChangePage`, `ChangeOperation`, `SourceUnavailableException`). Связь заглушки с форматом реального CMDB не проверяется 🔴 (см. [`../questions.md`](../questions.md) № 1).

## Риски
- 🟡 Тест RF-01 жёстко предполагает ровно 1 объект (`assertThat(seen).isEqualTo(1)`, `:63`) и ломается при расширении seed `StubSources.cmdb`.
- 🟡 Утверждение `isEqualTo(2)` для `sourceVersion` (`:77`) завязано на нумерацию версий заглушки (стартовая версия 1).
