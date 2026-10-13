# adapters/deploymap-adapter

> Reversa Writer, R.6a. Уровень модуля. Поведение общего ядра (webhook, polling, snapshot, reconcile) описано в спецификации `adapters/adapter-core`, здесь не дублируется. Use case: [`assemble-adapter`](assemble-adapter/requirements.md), [`fixtures-contract`](fixtures-contract/requirements.md).

## Визуальная сводка

Модуль из 3 файлов main (`DeploymapAdapter`, `DeploymapAdapterModule`, `package-info`), 3 тестовых классов (16 тестов) и 7 JSON-fixtures. Единственное содержательное поведение: привязать `adapter-core` к источнику `SourceSystem.DEPLOY_MAP`. Формата deploy map модуль не знает и не разбирает 🟢.

## Visão Geral

Тонкая привязка адаптера к источнику «deploy map и Helm charts» (мастер `Deployment`, ADR 0009). Фабрика `DeploymapAdapter.create` проверяет, что конфигурация относится к `DEPLOY_MAP`, и возвращает `adapter-core.SourceAdapter` (`adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java:32-38`) 🟢. Дополнительно модуль владеет тестовыми fixtures временного формата `deploymap-poc-1` и их контрактными тестами 🟢.

Реального источника deploy map нет; формат собственный и временный: «после решения владельца формат и адаптер заменяются, а не расширяются» (`platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/DeployMapFormat.java:3-8`; `adapters/deploymap-adapter/src/test/resources/deploymap/README.md:4`) 🟢.

## Responsabilidades

- Зафиксировать привязку «модуль ↔ источник»: `SYSTEM = SourceSystem.DEPLOY_MAP`, код `deploymap`, `source` события `urn:corp:deploymap` (`DeploymapAdapter.java:18`; `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceSystem.java:9`; `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/EventMapper.java:47-49`) 🟢.
- Дать конфигурацию по умолчанию `config(URI, String)` и фабрику `create(config, journal, rawStore)` (`DeploymapAdapter.java:23-25, 32-38`) 🟢.
- Отвергнуть конфигурацию чужого источника (`DeploymapAdapter.java:34-36`) 🟢.
- Хранить и охранять fixtures `deploymap-poc-1`/`poc-0` контрактными тестами (`DeployMapFixturesContractTest`) 🟢.
- Подтвердить на заглушке `StubSources.deployMap`, что `HttpSourceConnector` обслуживает контракт источника (`DeploymapAdapterContractTest`) 🟢.

## Regras de Negócio

- `create` бросает `IllegalArgumentException("config is for <X>, expected DEPLOY_MAP")`, если `config.system() != DEPLOY_MAP` (`DeploymapAdapter.java:34-36`) 🟢.
- Модуль не содержит разбора payload, валидации `chartMeta`, аннотаций и коммитов: это делает `ingestion/normalizer/.../DeployMapMapper.java:142-190` 🟢.
- Адаптер получает коннектор `HttpSourceConnector` внутри 3-аргументного конструктора `SourceAdapter` (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/SourceAdapter.java:29-31`) 🟢; собственного коннектора, в отличие от EAM, у модуля нет 🟢.
- Neo4j-доступа нет (javadoc `DeploymapAdapter.java:12-13`; main-зависимости pom: `adapter-core`, `source-spi`, `event-schemas`, `adapters/deploymap-adapter/pom.xml:17-29`) 🟢.
- Имя модуля, enum и код не совпадают: модуль `deploymap-adapter`, enum `DEPLOY_MAP`, код `deploymap`, в ingestion `SourceSystemCode.DEPLOYMAP` (`ingestion/normalizer/.../Normalizer.java:97-100`) 🟢 (риск путаницы: `traceability/spec-impact-matrix.md` §2 № 15).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `DeploymapAdapter.SYSTEM` равен `SourceSystem.DEPLOY_MAP` | Must | `DeploymapAdapterContractTest.adapterIsBoundToItsSource` (`sourceUrn` == `urn:corp:deploymap`) |
| RF-02 | `config(baseUri, secret)` возвращает `AdapterConfig.of(DEPLOY_MAP, ...)` с дефолтами ядра | Must | `adapterIsBoundToItsSource`; см. [`assemble-adapter`](assemble-adapter/requirements.md) |
| RF-03 | `create` отвергает чужой `AdapterConfig` с `IllegalArgumentException` | Must | `adapterIsBoundToItsSource` (foreign = `EAM`) |
| RF-04 | `create` для `DEPLOY_MAP` возвращает `SourceAdapter(config, journal, rawStore)` | Must | 🔴 нет теста положительной ветки в модуле (см. questions Q-03); косвенно исполняется `DemoLauncher.java:143` |
| RF-05 | Fixtures `deploymap-poc-1` содержат `chartMeta` с ровно тремя аннотациями `archrag.io/*` | Must | `DeployMapFixturesContractTest.everyPoc1FixtureHasChartMetaWithThreeAnnotations` |
| RF-06 | Fixtures согласованы с seed заглушек SCM/EAM/deploy map | Should | `baselineIsConsistentWithScmAndEamSeed`, `baselineMatchesStubSeed` |
| RF-07 | Негативные fixtures (`unknown-repository`, `system-mismatch`, `invalid-commit`) и `commit-change` описывают сценарии нормализатора | Should | одноимённые тесты, см. [`fixtures-contract`](fixtures-contract/requirements.md) |
| RF-08 | Контракт `SourceConnector` на заглушке deploy map: страницы, курсор, GET by id, tombstone DELETE, 429 с `Retry-After` | Should | 5 тестов `DeploymapAdapterContractTest` |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|------------|----------|-------------|
| Безопасность | Секрет webhook не печатается: `AdapterConfig.toString()` выводит `webhookSecret=***`; пустой/blank секрет запрещён (`IllegalArgumentException("webhookSecret is required")`) | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/AdapterConfig.java:39-41, 70-74` | 🟢 |
| Безопасность | Модуль не читает Neo4j и не зависит от `canonical-model`/`ingestion` в main (`canonical-model` и `source-stubs` — `scope=test`) | `adapters/deploymap-adapter/pom.xml:33-34, 39-40` | 🟢 |
| Надёжность | Повторы при недоступности источника: 5 попыток, задержка 500 мс — 30 с (выставляется через `AdapterConfig.of`) | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/RetryPolicy.java:30-33`; `AdapterConfig.java:60-63` | 🟢 |
| Эксплуатация | Прод-запуск: один экземпляр (`replicas: 1`), команда `adapter DEPLOY_MAP`, источник `ARCHRAG_SOURCE_URL=http://stub-deploymap:8080` | `platform/docker/docker-compose.yml:366-379` | 🟢 |

> Производительность и масштабирование в модуле не выражены; строки не добавлены (нет evidence).

## Critérios de Aceitação

```gherkin
Дано AdapterConfig.of(DEPLOY_MAP, baseUri, "secret")
Когда вызывается DeploymapAdapter.create(config, journal, rawStore)
Тогда возвращается SourceAdapter, привязанный к источнику deploymap

Дано AdapterConfig для источника EAM
Когда вызывается DeploymapAdapter.create(config, null, null)
Тогда выбрасывается IllegalArgumentException до обращения к journal и rawStore

Дано fixtures deploymap-poc-1
Когда читаются все записи baseline, unknown-repository, system-mismatch, invalid-commit, commit-change
Тогда у каждой payload.format = "deploymap-poc-1", chartMeta содержит name/version/appVersion
 и annotations содержит ровно ключи archrag.io/eam-system-id, archrag.io/git-repo-url, archrag.io/git-commit
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| Привязка `SYSTEM`/`create` с проверкой источника | Must | Единственный код, который стенд запускает для `deploymap` (`DemoLauncher.java:143`) |
| Контракт fixtures `poc-1` | Must | Единственный «живой» документ временного формата на стороне адаптеров (spec-impact-matrix §2 № 16) |
| Контракт `SourceConnector` на заглушке | Should | Покрывает ядро, а не код модуля |
| `config(URI,String)` | Could | В прод-коде не вызывается (`DemoLauncher.java:114-116` строит `AdapterConfig` напрямую) 🟡 |
| `DeploymapAdapterModule` | Won't | Маркерный класс для пустого JAR (`DeploymapAdapterModule.java:3-4`) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapter.java` | `DeploymapAdapter` | 🟢 |
| `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterModule.java` | `DeploymapAdapterModule` | 🟢 |
| `adapters/deploymap-adapter/src/main/java/io/github/unlocker/archrag/deploymapadapter/package-info.java` | пакет | 🟢 |
| `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterContractTest.java` | 6 тестов | 🟢 |
| `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeployMapFixturesContractTest.java` | 9 тестов | 🟢 |
| `adapters/deploymap-adapter/src/test/java/io/github/unlocker/archrag/deploymapadapter/DeploymapAdapterSmokeTest.java` | 1 тест | 🟢 |
| `adapters/deploymap-adapter/src/test/resources/deploymap/**` | 7 JSON + `README.md` | 🟢 |

Подробности формата и fixtures — [`contracts.md`](contracts.md); открытые вопросы — [`questions.md`](questions.md).
