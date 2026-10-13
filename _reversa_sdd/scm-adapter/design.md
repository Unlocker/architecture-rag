# adapters/scm-adapter, дизайн

## Interface

| Символ | Сигнатура | Возврат | Замечание |
|--------|-----------|---------|-----------|
| `ScmAdapter.SYSTEM` | `public static final SourceSystem` | `SourceSystem.SCM` | `ScmAdapter.java:18` 🟢 |
| `ScmAdapter.config` | `(URI sourceBaseUri, String webhookSecret)` | `AdapterConfig` | делегирует `AdapterConfig.of(SYSTEM, ...)`, `ScmAdapter.java:23-25` 🟢 |
| `ScmAdapter.create` | `(AdapterConfig, EventJournal, RawPayloadStore)` | `SourceAdapter` | `IllegalArgumentException` при `config.system() != SCM`; `ScmAdapter.java:32-38` 🟢 |
| `ScmAdapterModule` | приватный конструктор | — | маркер, `ScmAdapterModule.java:3-6` 🟢 |

`ScmAdapter` — `final`, приватный конструктор (`ScmAdapter.java:15,20`) 🟢.

## Fluxo Principal (`create`, детали — [create-adapter](create-adapter/design.md))
1. Проверка `config.system() == SYSTEM` (`ScmAdapter.java:34`).
2. `new SourceAdapter(config, journal, rawStore)` (`:37`) → внутри `new HttpSourceConnector(config.system(), config.sourceBaseUri())`, `WebhookHandler`, `Poller.create` (`adapters/adapter-core/.../SourceAdapter.java:29-46`).
3. Вызывающий сам запускает `startWebhook`/`startPolling`/`startReconciliation` (`SourceAdapter.java:61,72,97`), как в `platform/demo-launcher/.../DemoLauncher.java:125-126,141`.

## Fluxos Alternativos
- Чужой источник → `IllegalArgumentException("config is for " + config.system() + ", expected SCM")` (`ScmAdapter.java:35`) 🟢.
- `config == null` → `NullPointerException` 🟡 (выведено из `:34`).

## Зависимости / Связанные спецификации
- `pom.xml`: `adapter-core`, `source-spi`, `event-schemas` (compile), `source-stubs` (test) — `adapters/scm-adapter/pom.xml:17-35`; родитель `adapters` (`adapters/pom.xml:21`) 🟢.
- Прямые потребители: `platform/demo-launcher` (`DemoLauncher.java:12,141`, `pom.xml:32`), `platform/integration-tests` (`pom.xml:50`) 🟢.
- Spec-impact-matrix §1: изменение `adapters/adapter-core` затрагивает `scm-adapter` (строка `adapters/adapter-core`, `traceability/spec-impact-matrix.md:15`); изменение `contracts/source-spi` — все `*-adapter`.
- Spec-impact-matrix §2, затрагивающие модуль (не через pom):
  - #2 таблица кодов источников `eam/scm/cmdb/deploymap`: `EventMapper.java:46-48` ↔ `Normalizer.java:97-100`, `EventProcessor.java:642`, `SyncSources.java:19`, `admin-ui/src/api/constants.ts:27` — нет теста «покрыты все коды» 🔴;
  - #3 регистр источника (URN в журнале, верхний регистр в графе) 🔴;
  - #1, #6, #7 литералы типов событий и формат raw JSON — на стороне `adapter-core` (`StoredEventReaderContractTest` 🟢 покрывает формат);
  - #11 два нормализатора URL репозитория (`ScmMapper` ↔ `FeatureNormalizer`) касается данных SCM `REPOSITORY.url` 🟡;
  - #15 имя модуля ≠ код источника — для SCM совпадает (`scm`), путаницы нет 🟢.
- Общая логика: `_reversa_sdd/flowcharts/adapter-core.md`; сопоставление типов SCM в normalizer: `ingestion/normalizer/.../ScmMapper.java:22-23` (`SERVICE`, `REPOSITORY`) 🟢.
- ADR: [0010](../../docs/adr/0010-master-systems-are-stubs.md) (заглушки), [0024](../../docs/adr/0024-open-source-id-format.md) (sourceId без префикса системы; `ScmMapper.java:68-71` по `adrs/retroactive-map.md:33`). Интерфейсы: `docs/03-interfaces.md:322`.

## Decisões de Design

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Статическая фабрика вместо Spring-бина/подкласса | `ScmAdapter.java:15-38` | 🟢 |
| Источник — константа, защита от подмены конфигом | `ScmAdapter.java:18,34-36` | 🟢 |
| Стандартный `HttpSourceConnector` (REST `/changes`, `/objects`) | `SourceAdapter.java:29-31` | 🟢 |
| Модуль — копия шаблона `asset`/`deploymap` (39 строк, отличается константой) | `code-analysis.md:203` (проверено: `ScmAdapter.java` 39 строк) | 🟢 |

## Estado Interno
Нет. Состояние (checkpoint, замок snapshot) — в `adapter-core`/PostgreSQL.

## Observabilidade
Собственных логов нет. Логи `SourceAdapter` пишут `config.system().code()` и класс исключения, без значений (`SourceAdapter.java:81-84,106-110`) 🟢.

## Riscos e Lacunas
- 🔴 `create` не валидирует `null` аргументов (Q-1).
- 🟡 Тест `adapterIsBoundToItsSource` строит «чужой» конфиг тернарником, который для SCM всегда даёт `EAM` (`ScmAdapterContractTest.java:44-48`) — это шаблон, общий с другими адаптерами; для SCM корректно, но избыточно.
- 🟡 Ни один тест модуля не вызывает успешный `ScmAdapter.create` (только отказ с `null`-журналом, `ScmAdapterContractTest.java:49`).
- 🟡 Документ `_reversa_sdd/flowcharts/scm-adapter.md` рисует `new SourceAdapter(config, HttpSourceConnector, journal, rawStore)`; в коде вызывается 3-арг. конструктор, коннектор создаётся внутри (`ScmAdapter.java:37`, `SourceAdapter.java:29-31`) — функционально эквивалентно. Блок-схема исправлена в R.6c (UNLOCKER-275).
