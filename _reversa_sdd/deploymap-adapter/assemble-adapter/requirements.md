# Use case: assemble-adapter (сборка адаптера deploymap)

Модульный контекст: [../requirements.md](../requirements.md), [../design.md](../design.md).

## Visão Geral
Получить рабочий `SourceAdapter` для источника deploy map: собрать `AdapterConfig` и вызвать `DeploymapAdapter.create`. Вход — `config`, `EventJournal`, `RawPayloadStore`; выход — `SourceAdapter` либо `IllegalArgumentException` (`adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:32-38`) 🟢.

## Regras de Negócio
- `config(sourceBaseUri, webhookSecret)` = `AdapterConfig.of(DEPLOY_MAP, ...)`: `replayWindow` 5 мин (`AdapterConfig.java:32`), `pageSize` 100, `pollInterval` 30 с, `reconcileInterval` 6 ч (`:57`), `RetryPolicy` 5 попыток, 500 мс–30 с (`RetryPolicy.java:30-33`) 🟢.
- Пустой/blank `webhookSecret` → `IllegalArgumentException("webhookSecret is required")` (`AdapterConfig.java:39-41`) 🟢.
- `create`: `config.system() != DEPLOY_MAP` → `IllegalArgumentException("config is for " + config.system() + ", expected DEPLOY_MAP")` (`adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:34-36`) 🟢. Проверка выполняется до использования `journal`/`rawStore`.
- Прод-вызов не использует `config(...)`, а собирает `AdapterConfig` в `DemoLauncher` (`DemoLauncher.java:114-116`) 🟡.

## Requisitos Funcionais
| ID | Требование | Приоритет | Критерий |
|----|-----------|-----------|----------|
| RF-01 | `config` привязывает конфигурацию к `DEPLOY_MAP`; `sourceUrn()` = `urn:corp:deploymap` | Must | `adapterIsBoundToItsSource` |
| RF-02 | `create` отвергает чужой источник | Must | `adapterIsBoundToItsSource` (foreign = EAM) |
| RF-03 | `create` для `DEPLOY_MAP` возвращает `SourceAdapter` | Must | 🔴 теста нет |

## Critérios de Aceitação
```gherkin
Дано URI источника и секрет "secret"
Когда вызывается DeploymapAdapter.config(uri, "secret")
Тогда sourceUrn() равен "urn:corp:deploymap"

Дано AdapterConfig для EAM
Когда вызывается DeploymapAdapter.create(config, null, null)
Тогда IllegalArgumentException

Дано AdapterConfig для DEPLOY_MAP, journal и rawStore
Когда вызывается DeploymapAdapter.create
Тогда возвращается SourceAdapter (🔴 тест отсутствует)

Дано пустой секрет
Когда вызывается DeploymapAdapter.config(uri, "")
Тогда IllegalArgumentException("webhookSecret is required") (поведение AdapterConfig; тест в adapter-core)
```

## Rastreabilidade
`adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java`; тест `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterContractTest.java:40-51` (`adapterIsBoundToItsSource`) 🟢.
