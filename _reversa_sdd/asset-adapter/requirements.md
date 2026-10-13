# asset-adapter: требования

> R.6a. Модуль `adapters/asset-adapter`. Пути от корня репозитория; `AA` = `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter`, `AT` = `adapters/asset-adapter/src/test/java/io/github/unlocker/archrag/assetadapter`, `AC` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore`. Общая логика (webhook, polling, snapshot, retry, запись в inbox) принадлежит `adapter-core` и здесь не повторяется.

## Визуальный обзор
Адаптер источника **CMDB** (материальные активы: `COMPUTE_INSTANCE`, виртуальные и физические хосты). Имя модуля `asset`, код источника `cmdb`. Модуль не содержит собственной логики: он фиксирует источник `SourceSystem.CMDB` и собирает типовой `SourceAdapter` из `adapter-core` 🟢 (`AA/AssetAdapter.java:15-39`).

## Ответственности
- Привязать адаптер к источнику `SourceSystem.CMDB` через константу `AssetAdapter.SYSTEM` 🟢 (`AA/AssetAdapter.java:18`).
- Дать конфигурацию по умолчанию `AssetAdapter.config(URI, String)` 🟢 (`AA/AssetAdapter.java:23-25`).
- Собрать `SourceAdapter` и отвергнуть конфигурацию другого источника 🟢 (`AA/AssetAdapter.java:32-38`).
- Служить точкой сборки для `platform/demo-launcher` (`DemoLauncher.java:142`) и интеграционных тестов (`F2AcceptanceIT.java:169`, `AdminEndpointIT.java:155`) 🟢.
- Маркер модуля `AssetAdapterModule` (приватный конструктор, нужен «ради непустого JAR») 🟢 (`AA/AssetAdapterModule.java:3-7`); `package-info.java` без содержимого 🟢.

## Не входит (границы)
- Нет Neo4j-доступа, нет записи в источник (Javadoc `AA/AssetAdapter.java:12-13`; `AC/SourceAdapter.java:12-16`) 🟢.
- Нет аутентификации к API CMDB: коннектор шлёт только `Accept: application/json` (`AC/HttpSourceConnector.java:81-86`) 🟢.
- Нет разбора полей CMDB: payload объекта идёт в событие как есть, разбирает `ingestion/normalizer` (`CmdbMapper`) 🟢.
- Собственного `main()` и чтения env нет; запуск — `platform/demo-launcher` (`DemoLauncher.java:142`, env `ARCHRAG_SOURCE_URL`, `ARCHRAG_WEBHOOK_SECRET` — `LauncherConfig.java:24-25`) 🟢.

## Бизнес-правила
- `source` событий этого адаптера — `urn:corp:cmdb` (`EventMapper.sourceUrn`, `AC/EventMapper.java:47-49`; `SourceSystem.CMDB("cmdb")`, `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceSystem.java:7`) 🟢.
- `create` принимает только `AdapterConfig` с `system() == CMDB`; иначе `IllegalArgumentException("config is for <SYSTEM>, expected CMDB")` 🟢 (`AA/AssetAdapter.java:34-36`).
- Проверка идёт до создания `SourceAdapter`, поэтому при чужом `config` журнал и хранилище не используются (в тесте передаются `null`) 🟢 (`AT/AssetAdapterContractTest.java:49`).
- Пустой или `null` секрет webhook запрещён (`IllegalArgumentException("webhookSecret is required")`) — проверка в `AdapterConfig`, не в модуле 🟢 (`AC/AdapterConfig.java:39-41`).
- Значения по умолчанию `AdapterConfig.of` (наследуются): `replayWindow` 5 мин, `pageSize` 100, `pollInterval` 30 с, `reconcileInterval` 6 ч, `RetryPolicy.defaults()` = 5 попыток, 500 мс…30 с 🟢 (`AC/AdapterConfig.java:32,57,60-63`; `AC/RetryPolicy.java:31-33`).
- Имя `asset` ≠ код `cmdb`: причина в коде не зафиксирована 🔴 (`docs/03-interfaces.md:658`).

## Требования (RF)

| ID | Требование | Приоритет | Критерий |
|---|---|---|---|
| RF-01 | `AssetAdapter.SYSTEM == SourceSystem.CMDB` | Must | `AssetAdapterContractTest.adapterIsBoundToItsSource` |
| RF-02 | `config(uri, secret)` возвращает `AdapterConfig.of(CMDB, uri, secret)`; `sourceUrn()` = `urn:corp:cmdb` | Must | тот же тест |
| RF-03 | `create` отвергает чужой источник через `IllegalArgumentException` | Must | тот же тест |
| RF-04 | `create` возвращает `SourceAdapter` со стандартным `HttpSourceConnector(CMDB, sourceBaseUri)` | Must | косвенно: `F2AcceptanceIT`, `AdminEndpointIT` (стенд); отдельного юнита нет 🟡 |
| RF-05 | Коннектор CMDB соблюдает контракт `SourceConnector` на заглушке (страницы, курсор, GET by id, tombstone, 429) | Must | см. [`source-contract`](source-contract/requirements.md) |
| RF-06 | Класс-маркер модуля находится в пакете `io.github.unlocker.archrag.assetadapter` | Could | `AssetAdapterSmokeTest.moduleMarkerIsOnClasspath` |

## Нефункциональные требования

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Безопасность | Секрет webhook не печатается: `AdapterConfig.toString` пишет `webhookSecret=***` | `AC/AdapterConfig.java:70-74` | 🟢 |
| Безопасность | Адаптер не получает Neo4j-доступ и не пишет в источник | `AC/SourceAdapter.java:12-16`; зависимости `adapters/asset-adapter/pom.xml:17-35` (только `adapter-core`, `source-spi`, `event-schemas`; `source-stubs` в `test`) | 🟢 |
| Производительность/доступность | Таймаут соединения 5 с, запроса 10 с; 429/5xx/сетевой сбой → `SourceUnavailableException` и повтор по `RetryPolicy` | `AC/HttpSourceConnector.java:37-38,83,97-100` | 🟢 |

> Остальные НФТ (лимит webhook 64 КБ, replay window и т.д.) принадлежат `adapter-core`.

## Критерии приёмки

```gherkin
Дано URI источника и секрет "secret"
Когда вызывается AssetAdapter.config(uri, "secret")
Тогда sourceUrn() равен "urn:corp:cmdb"

Дано AdapterConfig для источника EAM
Когда вызывается AssetAdapter.create(config, null, null)
Тогда выбрасывается IllegalArgumentException и SourceAdapter не создаётся

Дано AdapterConfig с пустым секретом
Когда вызывается AssetAdapter.config(uri, " ")
Тогда выбрасывается IllegalArgumentException("webhookSecret is required")   # 🟡 тестом модуля не покрыто
```

## MoSCoW

| Что | MoSCoW | Обоснование |
|---|---|---|
| Привязка к `CMDB`, `config`, `create` с проверкой источника | Must | Единственная точка сборки CMDB-адаптера для launcher и IT |
| Контрактные тесты коннектора на заглушке CMDB | Must | Единственная защита коннектора для этого источника |
| `AssetAdapterModule` | Could | Только непустой JAR |
| Аутентификация к реальному CMDB | Won't (PoC) | Контракт реального CMDB не определён 🔴 (`docs/05-production-gap-list.md:43`) |

## Трассировка

| Файл | Символ | Покрытие |
|---|---|---|
| `AA/AssetAdapter.java:15-39` | `SYSTEM`, `config`, `create` | 🟢 |
| `AA/AssetAdapterModule.java:4-7` | маркер | 🟢 |
| `AA/package-info.java:1-2` | пакет | 🟢 |
| `AT/AssetAdapterContractTest.java` | 6 тестов | 🟢 |
| `AT/AssetAdapterSmokeTest.java:9-12` | 1 тест | 🟢 |

## Связанные
Дизайн: [`design.md`](design.md). Сценарии: [`assemble-adapter/`](assemble-adapter/requirements.md), [`source-contract/`](source-contract/requirements.md). Вопросы: [`questions.md`](questions.md).
