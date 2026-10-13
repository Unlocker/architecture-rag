# asset-adapter: дизайн

> Пути: `AA` = `adapters/asset-adapter/src/main/java/io/github/unlocker/archrag/assetadapter`, `AC` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore`. Общий поток данных (webhook → inbox, poll, snapshot, tombstone) — [`../flowcharts/adapter-core.md`](../flowcharts/adapter-core.md); здесь не дублируется.

## Интерфейс

| Символ | Сигнатура | Возврат | Замечание |
|---|---|---|---|
| `AssetAdapter.SYSTEM` | `public static final SourceSystem` | `SourceSystem.CMDB` | `AA/AssetAdapter.java:18` 🟢 |
| `AssetAdapter.config` | `(URI sourceBaseUri, String webhookSecret)` | `AdapterConfig` | делегирует `AdapterConfig.of(SYSTEM, ...)`; валидация секрета и прочего — в record (`AA/AssetAdapter.java:23-25`; `AC/AdapterConfig.java:34-54`) 🟢 |
| `AssetAdapter.create` | `(AdapterConfig config, EventJournal journal, RawPayloadStore rawStore)` | `SourceAdapter` | `IllegalArgumentException`, если `config.system() != CMDB` (`AA/AssetAdapter.java:32-38`) 🟢 |
| `AssetAdapterModule` | `final class`, приватный конструктор | — | маркер (`AA/AssetAdapterModule.java:4-7`) 🟢 |

Конструктор класса `AssetAdapter` приватный: класс — фабрика статических методов (`AA/AssetAdapter.java:20`) 🟢.

## Основной поток (`create`)
1. Сравнить `config.system()` с `SYSTEM` по ссылке (`!=`) (`AA/AssetAdapter.java:34`) 🟢. `config == null` даст `NullPointerException` (явной проверки нет) 🟡.
2. При несовпадении — `IllegalArgumentException("config is for " + config.system() + ", expected " + SYSTEM)` (`:35`) 🟢. Сообщение содержит только имена enum, без секретов.
3. Иначе `new SourceAdapter(config, journal, rawStore)` (`:37`) 🟢. Трёхаргументный конструктор сам создаёт `new HttpSourceConnector(config.system(), config.sourceBaseUri())` (`AC/SourceAdapter.java:29-31`) 🟢.
4. `SourceAdapter` строит `WebhookHandler` и `Poller` (`AC/SourceAdapter.java:44-45`); запуск (`startWebhook`, `startPolling`, `startReconciliation`) делает вызывающий код 🟢.

## Альтернативные потоки
- Чужой источник → исключение (шаг 2). Тест: `AssetAdapterContractTest.adapterIsBoundToItsSource` 🟢.
- Источник с нестандартным API в этом модуле не нужен: используется стандартный `HttpSourceConnector`; другой коннектор можно передать только прямым вызовом четырёхаргументного конструктора `SourceAdapter` (`AC/SourceAdapter.java:37-41`), `AssetAdapter` такой перегрузки не имеет 🟢.

## Зависимости
- Maven: `adapter-core`, `source-spi`, `event-schemas`; тестовая `source-stubs` (`adapters/asset-adapter/pom.xml:17-35`) 🟢. Модуль включён в `adapters/pom.xml` и в `dependencyManagement` корня (`pom.xml:116`) 🟢.
- Потребители: `platform/demo-launcher` (`DemoLauncher.java:142`, ветка `case CMDB`), `platform/integration-tests` (`F2AcceptanceIT.java:169`, `AdminEndpointIT.java:155`) 🟢.

## Зависит от / Связанные спецификации
(`traceability/spec-impact-matrix.md` §1 и §2)

| Спецификация | Связь | Тип |
|---|---|---|
| `adapters/adapter-core` | весь поток данных; изменение `adapter-core` затрагивает asset-adapter (§1) | прямая (pom) 🟢 |
| `contracts/source-spi` | `SourceSystem.CMDB`, `SourceConnector` (§1) | прямая 🟢 |
| `contracts/event-schemas` | `EventJournal`, `RawPayloadStore` (§1) | прямая 🟢 |
| `platform/source-stubs` | `StubSources.cmdb` в контрактных тестах (§1; тест-scope) | прямая, test 🟢 |
| `platform/demo-launcher` | сборка адаптера в стенде (§1) | потребитель 🟢 |
| `ingestion/normalizer` (`Normalizer.sourceCode`, `CmdbMapper`) | §2 № 2 и № 15: `urn:corp:cmdb` → `SourceSystemCode.CMDB`; `asset` ≠ `cmdb`, защиты нет | через данные 🔴 |
| `ingestion/graph-projector`, `access/admin-console` (`SyncSources.CODES`), `access/admin-ui` | §2 № 2, № 3: код `cmdb` продублирован в 6 местах без теста полноты; регистр (URN / `CMDB` / `cmdb`) | через данные 🔴 |
| `adapter-core` ↔ `ingestion-service` | §2 № 1, № 6: типы событий и формат raw; защищён `StoredEventReaderContractTest` (только формат) | через данные 🟢/🔴 |
| `docs/03-interfaces.md:322,658` | таблица адаптеров и запись «имя модуля ≠ источник» | документация 🟡 |

## Решения дизайна

| Решение | Evidence | Уверенность |
|---|---|---|
| Тонкая привязка вместо подкласса: фабрика над `final SourceAdapter` | `AA/AssetAdapter.java:15,32-38`; `AC/SourceAdapter.java:17` | 🟢 |
| Один шаблон на `scm`, `asset`, `deploymap` (копии, различается `SYSTEM`) | `_reversa_sdd/code-analysis.md:203` | 🟢 |
| Имя модуля `asset` вместо `cmdb` | `docs/00-vision.md:466`; обоснования нет | 🔴 |

## Состояние
Модуль состояния не хранит (только статические конструкции). Состояние (checkpoint, inbox) — в `adapter-core`/PostgreSQL 🟢.

## Наблюдаемость
Собственного логирования нет. `SourceAdapter` пишет `<code> poll ...` / `reconcile ...` с кодом `cmdb` и только именем типа исключения (`AC/SourceAdapter.java:81-84,106-109`) 🟢.

## Риски и пробелы
- 🔴 Реальный API CMDB (аутентификация, пагинация, лимиты) не определён; адаптер работает только с форматом заглушки (`docs/05-production-gap-list.md:43`; `_reversa_sdd/integrations.md:110`).
- 🔴 Причина расхождения имени `asset` и кода `cmdb` не записана (`docs/03-interfaces.md:658`).
- 🟡 `create` не проверяет `config`, `journal`, `rawStore` на `null`; `NullPointerException` возникнет позже, в `adapter-core`.
- 🟡 Тест `adapterIsBoundToItsSource` выбирает «чужой» источник тернарным выражением (`AT/AssetAdapterContractTest.java:44-48`); для `CMDB` оно всегда даёт `EAM`, и сравнение `SYSTEM == EAM` избыточно.
