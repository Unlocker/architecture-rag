# adapters/eam-adapter — дизайн

> Пути: `EA` = `adapters/eam-adapter/src/main/java/io/github/unlocker/archrag/eamadapter`, `ET` = `adapters/eam-adapter/src/test/java/io/github/unlocker/archrag/eamadapter`, `AC` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore`. Требования — [`requirements.md`](requirements.md). Схемы потоков уже есть в [`../flowcharts/eam-adapter.md`](../flowcharts/eam-adapter.md) и здесь не повторяются.

## Интерфейс

### Публичные символы

| Символ | Сигнатура | Возврат | Наблюдение |
|---|---|---|---|
| `EamAdapter.SYSTEM` | `SourceSystem` | `SourceSystem.EAM` | `EA/EamAdapter.java:19` 🟢 |
| `EamAdapter.config` | `(URI sourceBaseUri, String webhookSecret)` | `AdapterConfig` | `AdapterConfig.of(SYSTEM, …)`: `pageSize` 100, poll 30 с, reconcile 6 ч, `RetryPolicy.defaults()` (`AC/AdapterConfig.java:59-62`) 🟢 |
| `EamAdapter.create` | `(AdapterConfig, EventJournal, RawPayloadStore)` | `SourceAdapter` | режим **legacy**: стандартный `HttpSourceConnector` внутри `SourceAdapter`; `IllegalArgumentException`, если `config.system() != EAM` (`EA/EamAdapter.java:33-39`) 🟢 |
| `EamAdapter.create` | `(AdapterConfig, EamApi, EventJournal, RawPayloadStore)` | `SourceAdapter` | режим **eam-api**: `CompositeEamConnector(EamApiConnector(api.baseUri, api.token, config.pageSize), HttpSourceConnector(EAM, config.sourceBaseUri))` (`EA/EamAdapter.java:62-71`) 🟢 |
| `EamAdapter.EamApi` | `record(URI baseUri, String token)` | — | `toString` = `EamApi[baseUri=…, token=***]` (`:47-52`); валидации в записи нет, её делает `EamApiConnector` 🟢 |
| `EamApiConnector` | `implements SourceConnector, AutoCloseable` | — | публичный конструктор `(URI baseUri, String token, int pageSize)`; второй, пакетный, для тестов `(…, Sleeper, LongSupplier nanoClock)` (`EA/EamApiConnector.java:68-85`) 🟢 |
| `EamApiConnector.MAX_PAGE_SIZE` | `int` = 500 | — | `:45` 🟢 |
| `EamApiConnector.SOLUTION_TYPE` | `String` = `"itproducts"` | — | `:48` 🟢 |
| `CompositeEamConnector` | `implements SourceConnector, AutoCloseable`; ctor `(SourceConnector api, SourceConnector legacy)` | — | `:27, 39-42` 🟢 |
| `EamApiCursor` | пакетный `record(Mode, int type, int page, int size, Instant from, Instant watermark)` | — | `EA/EamApiCursor.java:18` 🟢 |
| `EamAdapterModule` | пустой `final class` | — | маркер, чтобы JAR не был пустым (`EA/EamAdapterModule.java:3`) 🟢 |

`SourceConnector` (контракт `contracts/source-spi`): `system()`, `fetchChanges(String cursor, int limit)`, `fetchById(String sourceType, String sourceId)` — `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceConnector.java:81-103`. Оба коннектора возвращают `system() == EAM` (`EA/EamApiConnector.java:88-90`, `EA/CompositeEamConnector.java:45-47`) 🟢.

### Конфигурация (снаружи модуля)

Модуль сам конфигурации не читает. Режим выбирает `platform/demo-launcher`: `ARCHRAG_EAM_MODE` = `legacy` (по умолчанию, `null`/пусто тоже legacy) | `eam-api`; для второго обязательны `ARCHRAG_EAM_API_URL` и `ARCHRAG_EAM_API_TOKEN` (токен — Docker secret); иное значение режима — `IllegalArgumentException` (`platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:127-139`, `DemoLauncher.java:138-140`) 🟢. В compose по умолчанию стоит `eam-api` (`platform/docker/docker-compose.yml:324`), то есть умолчание кода (`legacy`) и стенда (`eam-api`) различаются — это сознательно (откат `ARCHRAG_EAM_MODE=legacy`) 🟢.

## Основной поток

Один цикл poller'а (`adapter-core`) вызывает `connector.fetchChanges(cursor, config.pageSize())` (`AC/Poller.java:195`); для EAM в режиме `eam-api` это `CompositeEamConnector.fetchChanges`:

1. `cursor == null` → `State(snapshot=true, legacyPhase=false, null, null)`; иначе `State.parse(cursor)`, при `IllegalArgumentException` → `State(false,false,null,null)` и WARN (`EA/CompositeEamConnector.java:51, 78-85`).
2. Фаза `A` (eam-api): `api.fetchChanges(s.api, limit)`; страница без `nextCursor` → `IllegalStateException("eam-api answered a page without a cursor")`; ответ — те же изменения, новый составной курсор, `hasMore=true`, `snapshotComplete=false`; в курсоре фаза переключается на `L`, когда `api` вернул `hasMore=false` (`:52-59`).
3. Фаза `L` (legacy): `legacy.fetchChanges(s.legacy, limit)`; оставляются только `TEAM`/`SERVICE_DEPENDENCY`; если `legacy.hasMore` — страница с `hasMore=true` и тем же `api`-курсором; иначе (после проверки snapshot-complete, BR-5) итоговая страница `hasMore=false`, `snapshotComplete = s.snapshot`, курсор `I;A;…` (`:60-69`).
4. Внутри `api`-половины работает `EamApiConnector.fetchChanges` — подробно в [`fetch-changes/design.md`](fetch-changes/design.md); HTTP-слой — в [`eam-api-request/design.md`](eam-api-request/design.md); склейка и курсор — в [`composite-sync/design.md`](composite-sync/design.md).

## Альтернативные потоки

- **Источник недоступен** (429 после повтора, `5xx`, IO-ошибка) — `SourceUnavailableException(EAM, status, retryAfter)`; повторы и `GaveUp` — на `Poller.fetch` (`AC/Poller.java:190-205`) 🟢.
- **Любая иная ошибка** (401/403/3xx, не-JSON, не-массив, битый `ArchObject`, битый курсор) — `IllegalStateException`/`IllegalArgumentException` без повторов внутри цикла; Poller их не ловит 🟢 (`EA/EamApiConnector.java:171, 186, 201, 244`).
- **Webhook** (`fetchById`): для EAM `eam-api` вебхуков не имеет (`docs/06-eam-integration.md` §«Схема синхронизации»); путь остаётся рабочим для legacy `TEAM` (`ET/CompositeEamConnectorTest.java:151`) 🟢.

## Зависимости

- `adapters/adapter-core` — `SourceAdapter`, `HttpSourceConnector`, `AdapterConfig`, `Json`, `Sleeper` (`adapters/eam-adapter/pom.xml`, `EA/EamAdapter.java:3-5`, `EA/EamApiConnector.java:3-4`) 🟢.
- `contracts/source-spi` — `SourceConnector`, `SourceChange`, `ChangePage`, `SourceSystem`, `SourceUnavailableException`, `ChangeOperation`, `Completeness` 🟢.
- `contracts/event-schemas` — `EventJournal`, `RawPayloadStore` (только как типы параметров `create`) 🟢.
- `platform/source-stubs` — **только тест-scope** (`EamApiStub`, `EamApiSeed`, `StubSources`, `StubSourceServer`) (`adapters/eam-adapter/pom.xml`) 🟢.
- JDK `java.net.http.HttpClient`, `java.util.Base64`; внешних библиотек нет 🟢.

## Зависит от / Связанные спецификации

Из `traceability/spec-impact-matrix.md` §1 (строка `adapters/eam-adapter`) и §2:

| Связь | Что общего | Спецификация / защита |
|---|---|---|
| `adapters/adapter-core` → eam-adapter (§1) | формат raw JSON и типы событий, `snapshot-complete`, `Reconciler`; `SourceAdapter`, `Poller`, `WebhookHandler` | `_reversa_sdd/adapter-core/*` (R.6a соседнего модуля); защита — `StoredEventReaderContractTest` (§2 № 6) |
| `contracts/source-spi` (§1) | интерфейс `SourceConnector`, инварианты `ChangePage`/`SourceChange` | `_reversa_sdd/source-spi/*` |
| eam-adapter → `demo-launcher` (§1) | выбор режима `ARCHRAG_EAM_MODE`, токен/URL | `_reversa_sdd/demo-launcher/*` |
| eam-adapter ↔ `ingestion/normalizer`, `EamMapper` (§1, «через данные») | формат `ArchObject` и `attrs` (`id`, `ver`, `datetime`, `attrs.{name,status,criticality,eam_id,product_id,platform_id,ownerTeam,…}`); `sourceType` ∈ {`PLATFORM`,`SOLUTION`,`IT_SYSTEM`}; `sourceId = id`. Тест на стороне нормализатора — `EamArchObjectMapperTest`; общего теста «коннектор → мапер» в модулях нет 🟡 | `_reversa_sdd/normalizer/*`; контракт — [`contracts.md`](contracts.md) §2 |
| eam-adapter ↔ `platform/source-stubs` `EamApiStub`/`EamApiSeed` (§1) | формат ответа, `SOLUTION_TYPE` дублируется (`EA/EamApiConnector.java:48` и `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiSeed.java:27`) без теста, связывающего две константы 🟡; тест-scope зависимость | `_reversa_sdd/source-stubs/*` |
| eam-adapter ↔ `docs/eam-api.yaml` (§1) | `EamApiFixturesContractTest` читает спеку по относительному пути `../../docs/eam-api.yaml` (`ET/EamApiFixturesContractTest.java:34`) — тест зависит от рабочего каталога модуля 🟡 | `docs/06-eam-integration.md` |
| §2 № 1, 2, 3 | литералы типов событий, таблица кодов источников `eam/scm/cmdb/deploymap`, регистр источника: код `eam` задаёт `SourceSystem.EAM.code()`, URN `urn:corp:eam` строит `AdapterConfig.sourceUrn()` | `_reversa_sdd/adapter-core/*`, `_reversa_sdd/normalizer/*`, `_reversa_sdd/graph-projector/*` |
| §2 № 7 | tombstone `Reconciler` собирает raw-JSON вручную в формате `InboxWriter`; для EAM tombstone-ит **все** активные записи источника `EAM`, не попавшие в snapshot — основание существования `CompositeEamConnector` | `_reversa_sdd/graph-projector/*`, [`decisions.md`](decisions.md) D-1 |
| §2 № 15 | имя модуля = код источника (`eam-adapter` ↔ `EAM`/`eam`): здесь путаницы нет, в отличие от `asset`/`cmdb` | — |
| ADR | 0010 (мастера — заглушки), 0020 (tombstone вместо hard delete), 0024 (формат `sourceId`), 0031 (инвариант системы и платформы — на стороне нормализатора/projector) | `docs/adr/`, [`decisions.md`](decisions.md) |

## Решения проектирования

| Решение | Evidence | Уверенность |
|---|---|---|
| Один составной коннектор вместо двух адаптеров под `EAM` | `EA/CompositeEamConnector.java:19-22`; `docs/06-eam-integration.md` §«Заглушки и fixtures» | 🟢 |
| Курсор кодируется строкой в самом коннекторе, формат закрыт пакетом | `EA/EamApiCursor.java:7-14`, `EA/CompositeEamConnector.java:24-26` | 🟢 |
| Размер страницы фиксируется в курсоре, а не берётся из очередного `limit` | `EA/EamApiCursor.java:18`; `EA/EamApiConnector.java:97-98, 126, 156` | 🟢 |
| Инкремент = сортировка `-mtime` + стоп по водяному знаку, т.к. в API нет фильтра «изменено после T» | `EA/EamApiConnector.java:31-35, 107, 115-118`; `docs/06-eam-integration.md` §«Чтение» | 🟢 |
| Водяной знак = `datetime` объекта, а не `mtime` (предполагается, что это одно и то же поле) | `EA/EamApiConnector.java:120-122, 180-181`; в спеке `datetime` ≠ явный `mtime` | 🟡 |
| Тип «ИТ-решение» — `itproducts` одной константой | `EA/EamApiConnector.java:47-48` | 🟢 |
| Лимиты и троттлинг внутри коннектора, повторы по `RetryPolicy` — снаружи | `EA/EamApiConnector.java:205-219`; `AC/Poller.java:190-205` | 🟢 |
| Исключения без значений источника | `EA/EamApiConnector.java:185-186, 243-245` | 🟢 |

## Внутреннее состояние

- `EamApiConnector`: `lastRequestNanos`, `requested` (состояние троттлинга; защищено `synchronized throttle`, `:65-66, 249-260`), один `HttpClient` (`:64`). Между вызовами `fetchChanges` состояния прохода нет — вся позиция в курсоре 🟢.
- `CompositeEamConnector`: без изменяемого состояния; `State` — неизменяемый record, путешествует строкой-курсором (`:118-155`) 🟢.
- Постоянное состояние (checkpoint `<code>-poller`, `<code>-snapshot`) ведёт `adapter-core` (`Poller`), не модуль.

## Наблюдаемость

- `System.Logger` только в `CompositeEamConnector`: `WARNING` «eam cursor is not of the composite format, restarting incremental read» (`:82`) и «connector close failed: <класс исключения>» (`:113`) 🟢. Значений источника и токена в логах нет.
- Метрик и трейсов в модуле нет 🟢. Ошибки `EamApiConnector` видны только через исключения, которые обрабатывает `adapter-core`.

## Риски и пробелы

- 🟡 Водяной знак сравнивается строго (`!isAfter(from)`): объект с `datetime == watermark`, изменённый после снятия водяного знака в ту же секунду/долю, в инкременте не будет прочитан; компенсируется периодическим snapshot (reconcile, 6 ч по умолчанию) (`EA/EamApiConnector.java:115, 120`). Вопрос Q-2.
- 🟡 Объект с `datetime == null` получает `EPOCH`, поэтому в инкрементальном режиме он всегда считается «уже известным» и прерывает чтение типа (`:115-118, 180-181`); в graph-слое получает `lastSeenAt` 1970 (tech-debt Н-17). Q-3.
- 🟡 Сдвиг страниц при записи во время чтения `-mtime` может пропустить объекты; план — периодический snapshot (`docs/06-eam-integration.md` п. 6) 🟢 как допущение, 🟡 как гарантия.
- 🔴 Проверка прав сервисной учётки при подключении (200 на три списка, 403/404 на `buffers`; `docs/06-eam-integration.md` §«Решения», п. 8) в коде модуля не реализована. Q-1.
- 🟡 Повреждённая `eam-api`-половина составного курсора (корректный base64, невалидный `S|I|W`) не восстанавливается: `EamApiCursor.parse` бросает `IllegalArgumentException`, `CompositeEamConnector` её не перехватывает (`stateOf` ловит только ошибки разбора составного формата) (`EA/CompositeEamConnector.java:53, 78-85`, `EA/EamApiConnector.java:98`). Q-4.
- 🟡 `DateTimeParseException` из `Instant.parse` попадает в `cause` исключения «malformed ArchObject»; её `getMessage()` содержит разобранный текст, т.е. значение из источника может оказаться в логе стека (`EA/EamApiConnector.java:181, 184-186`). Комментарий в коде утверждает «причина без значения поля». Q-5.
- 🟡 `SourceConnector.fetchById` по Javadoc возвращает `DELETE` для удалённого объекта (`contracts/source-spi/.../SourceConnector.java:96`), а `EamApiConnector` на 404 возвращает пусто (`EA/EamApiConnector.java:148-150`); `WebhookHandler` строит tombstone только из подписанного уведомления `DELETE` (`AC/WebhookHandler.java:91-106`). Для EAM webhook'ов нет, поэтому практического влияния нет.
- 🟡 `EamAdapterContractTest` — «контрактный» тест **legacy**-пути (`HttpSourceConnector` + `StubSources.eam`), а не `EamApiConnector` (`ET/EamAdapterContractTest.java:30`); фабрика `create(config, EamApi, …)` в модуле юнит-тестом не покрыта, её проверяют только IT в `platform/integration-tests` (`EamApiSupport.java:31`, `EamApiProjectionIT`, `EamKeyMigrationIT`).
- 🟡 Данные о `ownerTeam` и поля словарей — допущения (`docs/06-eam-integration.md` п. 9); на коннектор не влияют (он отдаёт `attrs` как есть).
