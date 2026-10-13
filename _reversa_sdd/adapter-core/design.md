# adapters/adapter-core, Design

> Сокращения: `AC/` = `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/`, `AT/` = `adapters/adapter-core/src/test/java/io/github/unlocker/archrag/adaptercore/`, `SPI/` = `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/`, `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Что делает модуль — [`requirements.md`](requirements.md); форматы — [`contracts.md`](contracts.md).

## Зависит от / Связанные спецификации

Источник: `_reversa_sdd/traceability/spec-impact-matrix.md` §1 (строка `adapters/adapter-core`) и §2.

**Прямые зависимости (pom)** 🟢 — `adapters/adapter-core/pom.xml:17-32`:

| Модуль | Scope | Что используется |
|---|---|---|
| `contracts/source-spi` | compile | `SourceConnector`, `SourceChange`, `ChangePage`, `SourceSystem`, `SourceUnavailableException`, `WebhookSignature`, `ChangeOperation`, `Completeness` |
| `contracts/event-schemas` | compile | `CanonicalEvent`, `AssetEventData`, `SourceVersion`, `EventJournal`, `JournalEntry`, `Checkpoint`, `RawPayloadStore`, `RawPayloadRef` |
| `platform/source-stubs` | test | заглушка источника в `PollerTest`, `HttpSourceConnectorTest` (`StubSources`, `StubSource`) |

**Кто зависит от `adapter-core`** 🟢 (grep `adapter-core` по `pom.xml`): `adapters/eam-adapter`, `adapters/scm-adapter`, `adapters/asset-adapter`, `adapters/deploymap-adapter` (compile), `platform/demo-launcher` (compile), `platform/integration-tests` (test), `ingestion/ingestion-service` (**test**: `StoredEventReaderContractTest` лежит в пакете `io.github.unlocker.archrag.adaptercore` внутри `ingestion-service`, чтобы достать package-private `InboxWriter.rawJson`). Направление зависимостей из `CLAUDE.md` не нарушено: runtime `ingestion/*` не зависит от `adapters/*`.

**Связи вне `pom.xml` (matrix §2)** — при изменении модуля пересмотреть:

| № | Связь | Эта сторона | Противоположная сторона | Защита |
|---|---|---|---|---|
| 1 | Литералы типов `deleted`, `snapshot-complete` | `AC/EventMapper.java:29-32` (+ схемы `:40-42`) | `ingestion/graph-projector/.../EventProcessor.java:92-94`; `ingestion/ingestion-service/.../StoredEventReader.java:34`; `ingestion/ingestion-service/.../ReplayService.java:37` | теста на совпадение имён нет 🔴 (R-01) |
| 2 | Таблица кодов источников `eam/scm/cmdb/deploymap` | `AC/EventMapper.java:47-49` лишь подставляет `system.code()`; сама таблица — `SPI/SourceSystem.java:5-9` (🟡 уточнение к matrix) | `Normalizer.java:97-100`, `EventProcessor.java:642`, `access/admin-console/.../SyncSources.java:19`, `access/admin-ui/src/api/constants.ts:27` | теста «покрыты все `SourceSystem`» нет 🔴 |
| 3 | Регистр источника: URN в журнале, верхний регистр в графе, нижний в консоли | `AC/EventMapper.java:47-49` | `GraphProjector.java:697-698` | соглашение 🔴 |
| 6 | Формат raw JSON: запись ↔ чтение | `AC/InboxWriter.java:51-70` (**в matrix указан `EventMapper.java:52-95` — это ошибка, там маппинг события, а не raw**) | `ingestion/ingestion-service/.../StoredEventReader.java:44-86` | `StoredEventReaderContractTest` 🟢 |
| 7 | Tombstone `Reconciler` собирается вручную как raw JSON | `AC/InboxWriter.java:51-70` (raw `DELETE`) | `ingestion/graph-projector/.../Reconciler.java:197-201` (`rawDelete`) | совпадение только по комментарию 🟡 |
| 15 | Имя модуля ≠ код источника (`asset-adapter` ↔ `cmdb`) | код приходит из `SourceSystem`, адаптер его не выбирает | `Normalizer.java:97-100` | документация 🟡 |

**§3 matrix (сквозные последствия):** «Новый источник данных» затрагивает `source-spi`, новый `*-adapter` (новые строки в `adapter-core` не нужны: `EventMapper.sourceUrn` универсален), `normalizer`, `identity-resolution`, `graph-projector`, `source-stubs`, `docker`; «Смена формата raw payload» затрагивает `adapter-core`, `ingestion-service`, `graph-projector` (`Reconciler`).

**Связанные ADR / docs (только ссылки):** [ADR 0006](../../docs/adr/0006-postgres-inbox-event-backbone.md) (inbox как event backbone), [0007](../../docs/adr/0007-s3-raw-storage-seaweedfs.md) (raw в S3), [0010](../../docs/adr/0010-master-systems-are-stubs.md) (мастера — заглушки), [0013](../../docs/adr/0013-operator-ops-via-admin-endpoint.md) (reconcile через admin), [0014](../../docs/adr/0014-sla-webhook-to-neo4j.md) (SLA webhook → граф), [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) (tombstone, missing set после маркера; расхождение R-02), `docs/03-interfaces.md` §3 (webhook), §5.4 (CloudEvent). Спецификации смежных модулей: `_reversa_sdd/source-spi/`, `_reversa_sdd/event-schemas/`, `_reversa_sdd/eam-adapter/` и др. (если созданы R.6a), `_reversa_sdd/event-journal/`, `_reversa_sdd/normalizer/`, `_reversa_sdd/graph-projector/`.

## Состав и интерфейсы

| Символ | Видимость | Сигнатура | Назначение | Где |
|---|---|---|---|---|
| `SourceAdapter` | public final, `AutoCloseable` | `(AdapterConfig, EventJournal, RawPayloadStore)` / `(…, SourceConnector, EventJournal, RawPayloadStore)` | Сборка: коннектор + `WebhookHandler` + `Poller`. Первый конструктор создаёт `HttpSourceConnector(config.system(), config.sourceBaseUri())` | `AC/SourceAdapter.java:29-46` 🟢 |
| `SourceAdapter.startWebhook` | synchronized | `(InetSocketAddress) → WebhookServer` | Создаёт сервер один раз (`poller::snapshotOnce` для control-контекста); повторный вызов возвращает прежний сервер, **адрес игнорируется** | `:61-66` 🟢 |
| `SourceAdapter.startPolling` | synchronized | `()` | `scheduleWithFixedDelay(poll, 0, pollInterval)`; повторный вызов — no-op | `:72-90` 🟢 |
| `SourceAdapter.startReconciliation` | synchronized | `()` | `scheduleWithFixedDelay(snapshotOnce, reconcileInterval, reconcileInterval)` | `:97-115` 🟢 |
| `SourceAdapter.close` | synchronized | `()` | `shutdownNow` reconciler → scheduler, `server.close()`, `connector.close()` если `AutoCloseable` (сбой закрытия — WARNING с типом) | `:118-136` 🟢 |
| `Poller` | public final | `create(config, connector, journal, rawStore)`; полный конструктор принимает `Clock`, `Sleeper`, `Random`, `Supplier<String> runIds` | Циклы чтения | `AC/Poller.java:37-76` 🟢 |
| `Poller.pollOnce` / `snapshotOnce` | public | `() → PollResult` | см. [`incremental-poll`](incremental-poll/design.md), [`full-snapshot`](full-snapshot/design.md) | `:89-105` 🟢 |
| `Poller.pollerConsumer()` / `snapshotConsumer()` | public | `() → String` | `<code>-poller`, `<code>-snapshot` | `:79-86` 🟢 |
| `WebhookHandler.handle` | public | `(Function<String,String> header, String body) → int` | Статус HTTP | `AC/WebhookHandler.java:56-107` 🟢 |
| `WebhookServer` | public final, `AutoCloseable` | `(handler, address)` / `(handler, address, Supplier<PollResult> snapshot)` | JDK `HttpServer`; `snapshot == null` → `/control/snapshot` не создаётся | `AC/WebhookServer.java:38-60` 🟢 |
| `HttpSourceConnector` | public final | `(SourceSystem, URI baseUri)` | REST-чтение | `AC/HttpSourceConnector.java:33-157` 🟢 |
| `EventMapper` | public final, static | `toEvent`, `snapshotComplete`, `sourceUrn` (+ package-private `pollEventId`, `snapshotEventId`) | Формирование `CanonicalEvent` | `AC/EventMapper.java:26-109` 🟢 |
| `InboxWriter` | package-private | `record(eventId, change, correlationId, syncRunId)`, `record(marker, syncRunId)`, static `rawJson`, `markerRawJson` | Запись в inbox | `AC/InboxWriter.java:21-71` 🟢 |
| `RetryPolicy` | public record | `(maxAttempts, baseDelay, maxDelay)`, `defaults()`, `delay(attempt, failure, Random)` | Backoff | `AC/RetryPolicy.java:19-51` 🟢 |
| `AdapterConfig` | public record | 8 полей, `of(...)`, `sourceUrn()` | Конфигурация | `AC/AdapterConfig.java:21-75` 🟢 |
| `PollResult` | public record | `(Outcome outcome, long appended, String syncRunId)` | Итог цикла | `AC/PollResult.java:10-24` 🟢 |
| `Sleeper` | public functional interface | `sleep(Duration) throws InterruptedException`; `THREAD` | Подмена паузы в тестах | `AC/Sleeper.java` 🟢 |
| `Json` | public final | `parse`, `parseObject` (публичные); `write` — package-private | JSON | `AC/Json.java:16-277` 🟢 |

## Поток данных (общий)

```
 мастер-система ──GET /changes, /objects──► HttpSourceConnector ──SourceChange──► Poller / WebhookHandler
                                                                                       │
 мастер-система ──POST /webhook (HMAC)──► WebhookServer ──► WebhookHandler ────────────┤
 ingestion /admin/reconcile ─POST /control/snapshot─► WebhookServer ─► Poller.snapshotOnce
                                                                                       ▼
                                      InboxWriter.record: EventMapper.toEvent → RawPayloadStore.put (S3) → EventJournal.append (PG)
```

Инвариант порядка в `InboxWriter.record`: **raw в S3 первым, затем `append`**, чтобы строка inbox не ссылалась на несуществующий объект 🟢 (`AC/InboxWriter.java:37-38, 46-47`, Javadoc `:15-17`). Повтор безопасен: raw адресуется содержимым (`ES/RawPayloadStore.java`, Javadoc), `append` дедуплицирует.

Checkpoint-ы хранятся в том же `EventJournal` 🟢 (`AC/Poller.java:136, 170-171, 186`):

| consumer | source | cursor |
|---|---|---|
| `<code>-poller` | `urn:corp:<code>` | непрозрачный курсор коннектора |
| `<code>-snapshot` | `urn:corp:<code>` | `run:<syncRunId>` пока snapshot идёт, `done:<syncRunId>` после маркера (`AC/Poller.java:39-40`) |

## Алгоритмы

### `EventMapper.toEvent(system, eventId, change, correlationId)` 🟢 (`AC/EventMapper.java:52-77`)

1. `delete = change.operation() == DELETE`.
2. Если не `delete`: перенести в `payload` (LinkedHashMap, порядок источника) все пары с `v != null`; затем `payload["_completeness"] = completeness.name()`. Для `delete` `payload` пуст (даже `_completeness` нет).
3. `CanonicalEvent(id = eventId, source = "urn:corp:<code>", type = delete ? "architecture.asset.deleted.v1" : "architecture.asset.upserted.v1", subject = lower(sourceType, '_'→'-') + "/" + sourceId, time = change.updatedAt(), dataschema = delete ? "urn:corp:schema:asset-deleted:1" : "urn:corp:schema:asset-upserted:1", correlationid, data = AssetEventData(sourceType, sourceId, SourceVersion(Long.toString(sourceVersion)), payload))`.
4. `SourceVersion` проверяет `[0-9]{1,40}` (`ES/SourceVersion.java`): отрицательная версия даёт `IllegalArgumentException` → см. 🔴 R-03.
5. `CanonicalEvent` бросает `IllegalArgumentException` на пустом `id`/`source`/`type`/`dataschema` или `null` `time`/`data` (`ES/CanonicalEvent.java`); `eventId` из webhook не может быть пустым (`WebhookHandler.text` отсекает blank).

### `EventMapper.snapshotComplete(system, syncRunId, time, objectCount)` 🟢 (`AC/EventMapper.java:80-95`)

`id = "snapshot-complete:"+syncRunId`, `type = architecture.sync.snapshot-complete.v1`, `subject = "sync-run/"+syncRunId`, `dataschema = urn:corp:schema:snapshot-complete:1`, `correlationid = syncRunId`, `data = (sourceType "SYNC_RUN", sourceId syncRunId, SourceVersion "1", payload Map.of("syncRunId", syncRunId, "objectCount", objectCount))` (`objectCount` — `long`).

### `InboxWriter` 🟢 (`AC/InboxWriter.java`)

- `record(eventId, change, correlationId, syncRunId)`: `event = EventMapper.toEvent(...)`; `ref = rawStore.put(system.code(), rawJson(change) в UTF-8)`; `return journal.append(event, ref, syncRunId)` (`:34-39`).
- `record(marker, syncRunId)`: `rawStore.put(system.code(), markerRawJson(marker))`; `journal.append(marker, ref, syncRunId)` (`:45-48`).
- `rawJson`: ключи в порядке `sourceType, sourceId, sourceVersion (число), operation (имя enum), completeness (имя enum), updatedAt (ISO-8601 Instant строкой), payload` (`:60-70`); **payload — исходный, с `null`-значениями** (в отличие от события).
- `markerRawJson`: `syncRunId, objectCount, updatedAt` (`:51-57`).

### `Json` 🟢 (`AC/Json.java`)

- Разбор: рекурсивный спуск; глубина > 64 → `IllegalArgumentException("invalid JSON at offset N: too deep")` (`:47-50`); хвост после значения → `trailing data` (`:32-34`). Сообщения ошибок содержат смещение и класс проблемы, но **не** содержимое (`:212`; тест `JsonTest.rejectsMalformedWithoutEchoingContent`).
- Числа: если токен состоит только из `-` и цифр → `Long.parseLong`, иначе `Double.parseDouble`; `NumberFormatException` → `bad number` (`:160-174`). **Целое вне диапазона `long` не превращается в `Double`, а даёт `bad number`** → 🟡 U-04 (расходится с `code-analysis.md:259`).
- Лояльность: допускаются токены вида `+5`, `01`, `1e5` (парсятся `Double`/`Long`) — строгий RFC 8259 не соблюдается 🟡.
- Запись (`write`, package-private): `null`, `Boolean`, `Number` (`toString`), `Map` (ключи `String.valueOf`), `Collection`, прочее → `quote(value.toString())` (так `Instant`, enum и `String` попадают строкой) (`:215-254`); экранируются `" \ \n \r \t`, управляющие `< 0x20` как `\u00XX` (`:256-277`). Запись детерминирована при детерминированном порядке итерации (`JsonTest.writeIsDeterministic`).

## Конфигурация и константы 🟢

| Имя | Значение | Где |
|---|---|---|
| `DEFAULT_REPLAY_WINDOW` | 5 мин | `AC/AdapterConfig.java:32` |
| `pageSize` (в `of`) | 100 | `:61` |
| `pollInterval` (в `of`) | 30 с | `:62` |
| `DEFAULT_RECONCILE_INTERVAL` | 6 ч | `:57` |
| `RetryPolicy.defaults()` | 5 попыток, 500 мс…30 с | `AC/RetryPolicy.java:31-33` |
| `WebhookServer.MAX_BODY_BYTES` | 65 536 | `AC/WebhookServer.java:30` |
| connect-таймаут / таймаут запроса источника | 5 с / 10 с | `AC/HttpSourceConnector.java:38, 83` |
| `Json.MAX_DEPTH` | 64 | `AC/Json.java:18` |

Переменных окружения и `@ConfigurationProperties` в модуле **нет**: конфигурацию собирает вызывающий код (`platform/demo-launcher`) 🟢. `AdapterConfig.toString` печатает `system, sourceBaseUri, webhookSecret=***, replayWindow, pageSize` и не печатает `pollInterval`, `reconcileInterval`, `retry` (`:71-75`) 🟢.

## Решения дизайна

| Решение | Evidence | Уверенность |
|---|---|---|
| Свой JSON вместо библиотеки (новая зависимость запрещена `CLAUDE.md`) | `AC/Json.java:16`, `adapters/adapter-core/pom.xml` без Jackson | 🟡 (причина выведена) |
| JDK `HttpServer` вместо Spring: у адаптеров нет `main()`/Spring | `AC/WebhookServer.java:27, 49`; `docs/03-interfaces.md:316` | 🟢 |
| Замок внутрипроцессный, один экземпляр | `AC/Poller.java:34-35, 50` | 🟢 |
| Детерминированные `eventId` → идемпотентность через дедуп журнала | `AC/EventMapper.java:98-105` | 🟢 |
| Недоверенный источник: `Retry-After` ограничен `maxDelay`; `hasMore` без нового курсора — ошибка | `AC/RetryPolicy.java:45-48`, `AC/Poller.java:130-133` | 🟢 |
| Webhook несёт только уведомление; состояние берётся `fetchById` | `AC/WebhookHandler.java:84-89` | 🟢 |
| Tombstone по подписанному телу, если источник объект «забыл» | `AC/WebhookHandler.java:93-100` | 🟢 |

## Observability

Только `System.Logger` (JUL/SLF4J backend определяет окружение) 🟢. Сообщения (без содержимого источника):

| Событие | Уровень | Текст | Где |
|---|---|---|---|
| успешный цикл polling | DEBUG | `<code> poll <OUTCOME>` | `AC/SourceAdapter.java:81` |
| сбой цикла polling | ERROR | `<code> poll failed: <класс исключения>` | `:83-85` |
| итог reconciliation | INFO | `<code> reconcile <OUTCOME>` | `:106` |
| сбой reconciliation | ERROR | `<code> reconcile failed: <класс>` | `:108-110` |
| сбой закрытия коннектора | WARNING | `<code> connector close failed: <класс>` | `:132-133` |
| сбой обработки webhook | ERROR | `webhook processing failed: <класс>` | `AC/WebhookServer.java:120` |
| сбой `/control/snapshot` | ERROR | `snapshot failed: <класс>` | `:85` |

Метрик, трассировки и health-эндпоинта у модуля нет 🟢. `GET /health` адаптера, упомянутый в `tech-debt.md:31`, в `adapter-core` не реализован (grep `health` по `AC/` пуст) 🟡 — вероятно, в `demo-launcher`.

## Стан (Внутреннее состояние)

- `Poller`: `ReentrantLock running` (не fair, `tryLock()` без таймаута); других изменяемых полей нет 🟢 (`AC/Poller.java:50`).
- `SourceAdapter`: `server`, `scheduler`, `reconciler` — поля под `synchronized` методами 🟢.
- `HttpSourceConnector`: общий `HttpClient` (закрывается в `close()`) 🟢.
- Постоянное состояние — только checkpoint-ы в `EventJournal` (см. таблицу выше).

Автомат прогона snapshot (по checkpoint `<code>-snapshot`): `∅` → `run:<id>` (старт, `Poller.java:186`) → `done:<id>` (после маркера, `:170`); следующий snapshot из `done:` или `∅` выбирает новый UUID (`:181-187`). См. `_reversa_sdd/state-machines.md` строка 9 🟢.

## Риски и пробелы

- 🔴 R-01, R-02, R-03 — см. [`questions.md`](questions.md).
- 🟡 U-01: `incremental` возвращает `UP_TO_DATE`, даже если `appended > 0`, но курсор не изменился (источник вернул изменения без нового курсора) — `AC/Poller.java:141-142`.
- 🟡 U-02: webhook не сверяет `sourceType`/`sourceId` ответа `fetchById` с уведомлением (`AC/WebhookHandler.java:91-92, 105`); `subject` и `data.*` берутся из ответа источника, `eventId` — из уведомления.
- 🟡 U-03: `startWebhook` второй раз возвращает существующий сервер на старом адресе (`AC/SourceAdapter.java:62-65`).
- 🟡 U-05: `/webhook` и `/control/snapshot` регистрируются как JDK `HttpContext` с **prefix-соответствием**, поэтому пути вида `/webhook/x` тоже попадут в обработчик (поведение JDK `HttpServer`; в коде путь не проверяется) (`AC/WebhookServer.java:54-57`).
- 🟡 U-06: тело ответа `/control/snapshot` строится из `Map.of(...)`, порядок ключей не гарантирован (`AC/WebhookServer.java:82-83`); потребитель обязан разбирать JSON, а не сравнивать строку.
- 🟡 U-07: webhook-tombstone при каждой доставке берёт `updatedAt = clock.instant()` (`AC/WebhookHandler.java:99-100`), поэтому raw JSON у повторов различается и в S3 остаются «лишние» объекты для `DUPLICATE`-строк.
- 🟡 U-08: устаревший Javadoc конструктора `WebhookServer`: `@param port порт; 0 — свободный`, а параметр называется `address` (`AC/WebhookServer.java:36-38`).
