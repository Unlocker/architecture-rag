# adapters/deploymap-adapter, Design Técnico

## Interface

| Символ | Сигнатура | Возврат | Наблюдение |
|--------|-----------|---------|------------|
| `DeploymapAdapter.SYSTEM` | `public static final SourceSystem` | `SourceSystem.DEPLOY_MAP` | `DeploymapAdapter.java:18` 🟢 |
| `DeploymapAdapter.config` | `(URI sourceBaseUri, String webhookSecret)` | `AdapterConfig` | делегирует `AdapterConfig.of(SYSTEM, uri, secret)`, `DeploymapAdapter.java:23-25` 🟢 |
| `DeploymapAdapter.create` | `(AdapterConfig config, EventJournal journal, RawPayloadStore rawStore)` | `SourceAdapter` | `IllegalArgumentException("config is for " + config.system() + ", expected " + SYSTEM)` при чужом источнике, `:32-38` 🟢 |
| `DeploymapAdapter()` | `private` | - | утилитный класс, `final`, `:15, 20` 🟢 |
| `DeploymapAdapterModule` | `final`, `private` конструктор | - | маркер модуля, `DeploymapAdapterModule.java:3-6` 🟢 |

Пакет: `io.github.unlocker.archrag.deploymapadapter`. Публичный API двух методов повторяет `ScmAdapter`/`AssetAdapter`: файлы отличаются только пакетом, именем класса, `SourceSystem.X` и javadoc (проверено `diff` с `ScmAdapter.java`) 🟢.

## Fluxo Principal

1. Вызывающий (стенд) получает `AdapterConfig`. В проде `DemoLauncher.runAdapter` собирает его конструктором `new AdapterConfig(system, sourceUrl, webhookSecret, DEFAULT_REPLAY_WINDOW, 100, pollInterval, reconcileInterval, RetryPolicy.defaults())`, а не через `DeploymapAdapter.config` (`platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:114-116`) 🟢.
2. `DemoLauncher.create` выбирает ветку `case DEPLOY_MAP -> DeploymapAdapter.create(config, journal, store)` (`DemoLauncher.java:137-144`) 🟢.
3. `create` сверяет `config.system()` с `SYSTEM` (`DeploymapAdapter.java:34`); при совпадении `new SourceAdapter(config, journal, rawStore)` (`:37`), который внутри создаёт `HttpSourceConnector(config.system(), config.sourceBaseUri())` и `WebhookHandler` + `Poller` (`SourceAdapter.java:29-31, 37-46`) 🟢.
4. Далее запускаются `startWebhook`, `startPolling`, `startReconciliation` (`DemoLauncher.java:124-126`) — это уже ядро, см. спецификацию `adapter-core` 🟢.

## Fluxos Alternativos

- **Чужой источник:** `IllegalArgumentException`, `journal` и `rawStore` не затрагиваются (тест передаёт `null, null`, `DeploymapAdapterContractTest.java:49`) 🟢.
- **Пустой секрет в `config`:** `AdapterConfig` бросает `IllegalArgumentException("webhookSecret is required")` (`AdapterConfig.java:39-41`) 🟢; в `DeploymapAdapter` проверки нет.
- **`journal`/`rawStore` = null для DEPLOY_MAP:** в `create` не проверяется; поведение определяет `SourceAdapter`/`Poller` 🟡 (не прочитано в рамках модуля).

## Dependências

- `adapters/adapter-core` — `AdapterConfig`, `SourceAdapter`, `HttpSourceConnector` (`DeploymapAdapter.java:3-4`) 🟢.
- `contracts/source-spi` — `SourceSystem` (`:7`); `contracts/event-schemas` — `EventJournal`, `RawPayloadStore` (`:5-6`) 🟢.
- test-scope: `canonical-model` (`GitUrls.normalize` в `DeployMapFixturesContractTest`), `source-stubs` (`StubSources`, `StubSourceServer`, `DeployMapFormat`, `EamApiSeed`, `EamApiFormat`) (`pom.xml:30-41`) 🟢.
- Родитель `adapters` включает модуль в сборку (`adapters/pom.xml:23`); потребитель — `platform/demo-launcher` (`platform/demo-launcher/pom.xml:40`) 🟢.

## Зависит от / Связанные спецификации

Источник: `_reversa_sdd/traceability/spec-impact-matrix.md` §1, §2.

- `adapters/adapter-core` — вся логика приёма/polling/snapshot; изменение ядра затрагивает модуль (§1 строка `adapters/adapter-core`; формат raw JSON и типы событий `deleted`/`snapshot-complete`; §2 № 1, 6) 🟢.
- `contracts/source-spi` (`SourceSystem`, `SourceConnector`) и `contracts/event-schemas` (`EventJournal`, `RawPayloadStore`) — прямые зависимости (§1) 🟢.
- `platform/source-stubs` — тест-зависимость; `DeployMapFormat`, `StubSources.deployMap`, `EamApiSeed` задают формат и seed (§1; §2 № 16) 🟢.
- `ingestion/normalizer` (`DeployMapMapper`, `Normalizer.sourceCode`) — потребляет события `urn:corp:deploymap` и разбирает payload; §2 № 2, 15, 16 🟢. Таблица кодов `eam/scm/cmdb/deploymap` дублируется в шести местах без теста полноты (§2 № 2) 🔴 — вне модуля.
- `ingestion/graph-projector` — регистр источника (`DEPLOYMAP` в графе, `deploymap`/`urn:corp:deploymap` в журнале), §2 № 3 🔴 — вне модуля.
- `platform/demo-launcher` — единственный прод-вызыватель `create` (§1: «все *-adapter» ← `adapter-core`).
- `platform/docker` — контейнер `adapter-deploymap`, прокси `/webhooks/deploymap` (`docker-compose.yml:366-379`, `proxy/nginx.conf:58-62`) 🟢.
- ADR: [0009](../../docs/adr/0009-deployment-from-deploymap.md) (мастер Deployment), [0010](../../docs/adr/0010-master-systems-are-stubs.md) (мастер-системы — заглушки), [0029](../../docs/adr/0029-helm-chart-system-repo-commit.md) (chart: система, репозиторий, commit), [0024](../../docs/adr/0024-open-source-id-format.md) и [0030](../../docs/adr/0030-deployment-system-conflict.md) (сравнение системы) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Адаптер = статическая фабрика над общим ядром, без наследования и DI | `DeploymapAdapter.java:15-38` | 🟢 |
| Формат источника живёт вне модуля (в заглушке), адаптер payload не разбирает | `DeployMapFormat.java:3-8`; `EventMapper.java:52-63` (перенос payload без null-значений + ключ `_completeness`) | 🟢 |
| Модуль владеет fixtures формата как «живым» документом | `deploymap/README.md:3-5`; spec-impact-matrix §2 № 16 | 🟢 |
| Ключи аннотаций `archrag.io/*` — решение архитектора фичи UNLOCKER-248 | `deploymap/README.md:4-5` | 🟢 |
| Тестовый `canonical-model` только ради `GitUrls.normalize` | `pom.xml:30-35` (комментарий) | 🟢 |

## Estado Interno

Нет: у класса только `static final SYSTEM`. Состояние (checkpoint `deploymap-poller`/`deploymap-snapshot`, inbox, raw в S3) принадлежит `adapter-core`/`event-journal` (`Poller.java:78-86`) 🟢.

## Observabilidade

В модуле логов, метрик и трассировки нет 🟢. Логирование запуска `adapter DEPLOY_MAP started` — в `DemoLauncher.java:128` 🟢 (вне модуля).

## Riscos e Lacunas

- 🔴 Реального источника deploy map нет; контракт `poc-0/poc-1` временный и подлежит замене (см. [questions.md](questions.md) Q-01).
- 🟡 `DeploymapAdapter.config` не используется в прод-коде (Q-02).
- 🔴 Положительный путь `create` не покрыт тестом модуля (Q-03).
- 🟡 Контрактные тесты fixtures проверяют форму и согласованность с seed, но не прогоняют fixtures через `DeployMapMapper`; нормализатор тестируется отдельно (`ingestion/normalizer/src/test/.../DeployMapMapperTest.java`), общих файлов нет (grep по `deploymap/` в `ingestion`, `platform` пуст) (Q-04).
