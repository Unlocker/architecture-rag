# assemble-adapter: сборка CMDB-адаптера

> Сценарий модуля [`asset-adapter`](../requirements.md). Пути: `AA` = `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter`, `AT` = `adapters/asset-adapter/src/test/java/io/github/unlocker/archrag/assetadapter`.

## Обзор
Вызывающий код (launcher, IT) получает `AdapterConfig` и собирает `SourceAdapter` для источника `CMDB` 🟢 (`AA/AssetAdapter.java:23-38`).

## Правила
- `config(uri, secret)` = `AdapterConfig.of(CMDB, uri, secret)` с дефолтами `adapter-core` 🟢.
- `create` отвергает `config` другого источника, `IllegalArgumentException` 🟢 (`AA/AssetAdapter.java:34-36`).
- Пустой секрет отвергается ещё в `AdapterConfig` 🟢 (`adapters/adapter-core/.../AdapterConfig.java:39-41`).

## Требования

| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-01 | `sourceUrn()` конфигурации = `urn:corp:cmdb` | Must | `adapterIsBoundToItsSource` (`AT/AssetAdapterContractTest.java:40-43`) |
| RF-02 | Чужой источник → `IllegalArgumentException` | Must | `AT/AssetAdapterContractTest.java:44-50` |
| RF-03 | Свой источник → `SourceAdapter` | Must | `F2AcceptanceIT` (`:169`), `AdminEndpointIT` (`:155`); юнита нет 🟡 |

## Критерии приёмки

```gherkin
Дано URI заглушки CMDB и секрет "secret"
Когда AssetAdapter.config(uri, "secret")
Тогда config.system() == CMDB и sourceUrn() == "urn:corp:cmdb"

Дано AdapterConfig.of(EAM, uri, "secret")
Когда AssetAdapter.create(config, null, null)
Тогда IllegalArgumentException, сообщение "config is for EAM, expected CMDB"   # текст проверяется только по типу исключения в тесте 🟡

Дано AdapterConfig.of(CMDB, uri, "secret") и рабочие journal/rawStore
Когда AssetAdapter.create(config, journal, rawStore)
Тогда возвращается SourceAdapter; webhook/polling не запущены до вызова startWebhook/startPolling
```

## Трассировка
`AA/AssetAdapter.java:23-38`; `AT/AssetAdapterContractTest.java:39-51`. Дизайн: [`design.md`](design.md).
