# adapters/scm-adapter

## Визуальный обзор
Тонкая привязка адаптера к источнику `SourceSystem.SCM` (код `scm`, `source` событий `urn:corp:scm`). Собственной логики приёма данных нет: webhook, polling, snapshot, retry, запись в журнал и raw-хранилище реализует `adapters/adapter-core` (поток — `_reversa_sdd/flowcharts/adapter-core.md`). Модуль не знает Neo4j и ничего не пишет в источник (`adapters/adapter-core/.../SourceAdapter.java:12-15`) 🟢.

## Responsabilidades
- Зафиксировать константу источника `ScmAdapter.SYSTEM = SourceSystem.SCM` (`adapters/scm-adapter/src/main/java/io/github/unlocker/archrag/scmadapter/ScmAdapter.java:18`) 🟢.
- Выдать конфигурацию по умолчанию `config(URI, String)` (`ScmAdapter.java:23-25`) 🟢.
- Собрать `SourceAdapter` через `create(config, journal, rawStore)` с защитой от чужого источника (`ScmAdapter.java:32-38`) 🟢.
- Маркер модуля `ScmAdapterModule` — пустой утилитарный класс «ради непустого JAR» (`ScmAdapterModule.java:3-6`) 🟢.

## Regras de Negócio
- `config.system()` обязан быть `SCM`, иначе `IllegalArgumentException("config is for <X>, expected SCM")` (`ScmAdapter.java:34-36`) 🟢.
- Коннектор всегда стандартный `HttpSourceConnector(config.system(), config.sourceBaseUri())`: трёхаргументный конструктор `SourceAdapter` (`adapters/adapter-core/.../SourceAdapter.java:29-31`) 🟢. Нестандартного API у SCM нет (в отличие от `eam`) 🟢.
- `source` события для SCM — `urn:corp:scm` (`AdapterConfig.sourceUrn()` → `EventMapper.sourceUrn`, `adapters/adapter-core/.../EventMapper.java:46-48`) 🟢.
- Реальный API SCM не зафиксирован: адаптер работает с заглушкой `StubSources.scm` (ADR [0010](../../docs/adr/0010-master-systems-are-stubs.md)) 🟢; формат `/changes`, `/objects/{type}/{id}` — `docs/03-interfaces.md` §5 🟡.
- Метод `create` не проверяет `config`, `journal`, `rawStore` на `null`: при `config == null` будет `NullPointerException` на `config.system()` (`ScmAdapter.java:34`); `journal`/`rawStore` не проверяются здесь, а в `WebhookHandler`/`Poller` — не прочитано в рамках модуля 🔴 (см. `questions.md` Q-1).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | `ScmAdapter.SYSTEM == SourceSystem.SCM` | Must | `ScmAdapterContractTest.adapterIsBoundToItsSource` |
| RF-02 | `config(uri, secret)` возвращает `AdapterConfig.of(SCM, uri, secret)` с дефолтами (replayWindow 5 мин, pageSize 100, pollInterval 30 с, reconcileInterval 6 ч, retry 5 попыток 500 мс..30 с) | Must | `adapterIsBoundToItsSource` (проверяет `sourceUrn() == "urn:corp:scm"`); дефолты `AdapterConfig.java:32,57,60-63`, `RetryPolicy.java:31-33` — отдельным тестом модуля не покрыты 🟡 |
| RF-03 | `create` с `config.system() != SCM` бросает `IllegalArgumentException` | Must | `adapterIsBoundToItsSource` (негативная часть) |
| RF-04 | `create` с корректным конфигом возвращает `SourceAdapter` | Must | В модуле прямого теста нет; используется в `platform/integration-tests` (`F1AcceptanceIT:158`, `F2AcceptanceIT:168`, `F3AcceptanceIT:163`, `F4AcceptanceIT:207`, `SyncAcceptanceIT:196`, `AdminEndpointIT:154`, `EamKeyMigrationIT:154`) и `DemoLauncher.java:141` 🟢 |
| RF-05 | Стандартный `HttpSourceConnector` против SCM-заглушки: страницы snapshot, курсор, GET by id, DELETE-tombstone, 429 | Must | `ScmAdapterContractTest` (см. use case `fetch-scm-changes`) |
| RF-06 | Webhook (`X-Archrag-*`, HMAC), polling, reconcile для SCM | Must | Реализует `adapter-core`; в модуле не тестируется 🟢 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|-----|-----------|----------|-------------|
| Безопасность | `webhookSecret` обязателен (не null/blank), в `toString` маскируется `***` | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/AdapterConfig.java:39-41` (проверка), `:70-73` (`toString`) | 🟢 |
| Безопасность | Адаптер не получает Neo4j-доступ, в источник не пишет | `SourceAdapter.java:12-15`; зависимости `adapters/scm-adapter/pom.xml:17-35` (нет neo4j) | 🟢 |
| Устойчивость | 429/5xx/сеть → `SourceUnavailableException` с `Retry-After`; HTTP connect timeout 5 с, запрос 10 с | `HttpSourceConnector.java:38,83,92,98-99` | 🟢 |
| Зависимости | Только `adapter-core`, `source-spi`, `event-schemas`; `source-stubs` в `test` scope; версий в модуле нет | `adapters/scm-adapter/pom.xml:17-35` | 🟢 |

## Критерии приёмки

```gherkin
Дано ScmAdapter.config(uri, "secret")
Когда читаем sourceUrn()
Тогда это "urn:corp:scm"

Дано AdapterConfig другого источника (EAM)
Когда вызываем ScmAdapter.create(config, journal, rawStore)
Тогда IllegalArgumentException
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Обоснование |
|-----------|--------|-------------|
| RF-01..RF-04 | Must | Единственная публичная поверхность модуля, используется `DemoLauncher` и ITs |
| RF-05 | Must | Контракт коннектора против заглушки |
| `ScmAdapterModule`, `ScmAdapterSmokeTest` | Could | Технический маркер |

## Rastreabilidade de Código

| Файл | Класс | Уверенность |
|------|-------|-------------|
| `adapters/scm-adapter/src/main/java/io/github/unlocker/archrag/scmadapter/ScmAdapter.java` | `ScmAdapter` | 🟢 |
| `.../scmadapter/ScmAdapterModule.java` | `ScmAdapterModule` | 🟢 |
| `.../scmadapter/package-info.java` | пакет | 🟢 |
| `adapters/scm-adapter/src/test/java/io/github/unlocker/archrag/scmadapter/ScmAdapterContractTest.java` | 6 тестов | 🟢 |
| `.../ScmAdapterSmokeTest.java` | 1 тест | 🟢 |
