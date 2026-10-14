# contracts/event-schemas, Дизайн

> `ES/` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/`. Требования: [`requirements.md`](requirements.md). Контрактные таблицы: [`contracts.md`](contracts.md). Use case: [`processing-status-lifecycle`](processing-status-lifecycle/design.md), [`journal-append-dedup-dlq`](journal-append-dedup-dlq/design.md), [`journal-keyset-read`](journal-keyset-read/design.md), [`source-version-compare`](source-version-compare/design.md).

## Интерфейс

Пакет `io.github.unlocker.archrag.eventschemas` (18 main-файлов: 16 типов + `EventSchemasModule` + `package-info`) 🟢.

### Records и константы

| Символ | Компоненты / константы | Валидация конструктора | Файл |
|---|---|---|---|
| `CanonicalEvent` | `id, source, type, subject, time: Instant, dataschema, correlationid, data: AssetEventData`; `SPEC_VERSION="1.0"`, `TYPE_ASSET_UPSERTED="architecture.asset.upserted.v1"`; метод `specversion()` | IAE: `id`, `source`, `type`, `dataschema` не пусты; IAE `"time and data are required"`; `subject`, `correlationid` свободны 🟢 | `ES/CanonicalEvent.java:17-52` |
| `AssetEventData` | `sourceType, sourceId, sourceVersion: SourceVersion, payload: Map<String,Object>` | IAE: `sourceType`, `sourceId` не пусты; IAE `"sourceVersion is required"`; `payload` null → `Map.of()`, иначе `unmodifiableMap(new LinkedHashMap(payload))` 🟢 | `ES/AssetEventData.java:14-24` |
| `SourceVersion` | `value: String`, `Comparable<SourceVersion>` | IAE если `null` или не `[0-9]{1,40}` 🟢 | `ES/SourceVersion.java:10-16` |
| `RawPayloadRef` | `key, contentHash` | IAE если любой `null`/blank 🟢 | `ES/RawPayloadRef.java:7-13` |
| `JournalEntry` | 15 компонентов: `source, eventId, correlationId, syncRunId, sourceType, sourceId, sourceVersion, schemaVersion, status, errorCode, errorReason, attempts:int, payloadRef, receivedAt, updatedAt` | нет 🟢 | `ES/JournalEntry.java:14-29` |
| `StoredEvent` | `entry, type, subject` | NPE на `entry`, `type` 🟢 | `ES/StoredEvent.java:12-17` |
| `Checkpoint` | `consumer, source, cursor, updatedAt` | нет 🟢 | `ES/Checkpoint.java:13` |
| `ObjectRef` | `sourceType, sourceId` | нет 🟢 | `ES/ObjectRef.java:7` |
| `SnapshotContents` | `objects: Set<ObjectRef>, eventCount: long, firstReceivedAt` | `objects = Set.copyOf(objects)` (NPE при `null`) 🟢 | `ES/SnapshotContents.java:14-18` |
| `JournalKey` | `receivedAt, source, eventId`; `of(JournalEntry)` | NPE на все три 🟢 | `ES/JournalKey.java:11-22` |
| `JournalQuery` | `source, receivedFrom, receivedTo, excludeReplays, after: JournalKey, limit`; `REPLAY_PREFIX="replay:"`, `MAX_LIMIT=1000`; `after(JournalKey)` | IAE `"limit must be in 1..1000"`; IAE `"receivedFrom must be before receivedTo"` 🟢 | `ES/JournalQuery.java:18-39` |
| `ProcessingStatus` | enum, порядок: `RECEIVED, VALIDATED, NORMALIZED, RESOLVED, PROJECTED, QUARANTINED, RETRYING, DUPLICATE, IGNORED_OLD_VERSION`; `canTransitionTo(next)` | — | `ES/ProcessingStatus.java:8-42` |

### Порты

| Порт | Операция | Гарантия (Javadoc) | Файл |
|---|---|---|---|
| `EventJournal` | `JournalEntry append(CanonicalEvent, RawPayloadRef /*nullable*/, String syncRunId /*nullable*/)` | атомарно `RECEIVED`, если нет; повтор → прежняя запись со статусом `DUPLICATE`, строка не меняется | `ES/EventJournal.java:15-22` |
| | `Optional<JournalEntry> find(source, eventId)` | по ключу дедупликации | `:24-25` |
| | `JournalEntry transition(source, eventId, status, errorCode, errorReason)` | IAE если события нет; ISE если `!canTransitionTo` | `:27-33` |
| | `JournalEntry toDlq(source, eventId, errorCode, reason)` | `QUARANTINED` + запись DLQ; при необработанной записи DLQ вторая не создаётся; IAE если `errorCode` пуст или события нет | `:35-41` |
| | `Optional<Checkpoint> loadCheckpoint(consumer, source)` | — | `:43-44` |
| | `Checkpoint saveCheckpoint(consumer, source, cursor)` | upsert; только после коммита проекции; монотонность не проверяется | `:46-50` |
| | `SnapshotContents snapshotContents(source, syncRunId)` | события с id `snap:<syncRunId>:...`; читает журнал, не граф | `:52-57` |
| | `Set<ObjectRef> objectsReceivedSince(source, since)` | объекты, по которым в журнал попало любое событие не раньше `since` | `:59-60` |
| `JournalReader` | `List<StoredEvent> read(JournalQuery)` | не более `limit`, порядок `(receivedAt, source, eventId)`, только чтение | `ES/JournalReader.java:14-15` |
| | `List<StoredEvent> pending(JournalKey after, Instant retryNotAfter, int limit)` | `RECEIVED` + `RETRYING`/`VALIDATED`/`NORMALIZED`/`RESOLVED`, не менявшиеся с `retryNotAfter` включительно; replay не исключаются | `:17-28` |
| `RawPayloadStore` | `RawPayloadRef put(String source, byte[] content)` | ключ `raw/<source>/<sha256>`, идемпотентно | `ES/RawPayloadStore.java:6-7` |
| | `byte[] get(RawPayloadRef ref)` | проверяет `contentHash`, при расхождении исключение | `:9-10` |
| `CanonicalEventPublisher` | `boolean publish(CanonicalEvent, RawPayloadRef, String syncRunId)` | at-least-once; `true` — впервые, `false` — дубликат | `ES/CanonicalEventPublisher.java:9-14` |

## Автомат `ProcessingStatus` 🟢 (`ES/ProcessingStatus.java:26-42`)

Переход «в себя» разрешён всегда (`:27-29`). Иначе (`switch` по текущему состоянию):

| Из | Допустимые `next` |
|---|---|
| `RECEIVED` | `VALIDATED`, `RETRYING`, `QUARANTINED`, `DUPLICATE`, `IGNORED_OLD_VERSION` (`:31-32`) |
| `VALIDATED` | `NORMALIZED`, `RETRYING`, `QUARANTINED`, `IGNORED_OLD_VERSION` (`:33`) |
| `NORMALIZED` | `RESOLVED`, `RETRYING`, `QUARANTINED`, `IGNORED_OLD_VERSION` (`:34`) |
| `RESOLVED` | `PROJECTED`, `RETRYING`, `QUARANTINED`, `IGNORED_OLD_VERSION` (`:35`) |
| `RETRYING` | `VALIDATED`, `NORMALIZED`, `RESOLVED`, `PROJECTED`, `QUARANTINED`, `IGNORED_OLD_VERSION` (`:36-37`) |
| `QUARANTINED` | `RETRYING` (`:38`) |
| `PROJECTED`, `DUPLICATE`, `IGNORED_OLD_VERSION` | никуда, кроме себя |

Свойства: из `RECEIVED` нельзя прыгнуть в `NORMALIZED`/`RESOLVED`/`PROJECTED` напрямую; `PROJECTED` нельзя вернуть в `RETRYING`; `RETRYING → RECEIVED` запрещён; `QUARANTINED → IGNORED_OLD_VERSION` запрещён (сначала `RETRYING`) 🟢. Диаграмма совпадает с `docs/02-data-model.md` §5.2 (все стрелки диаграммы присутствуют в коде и наоборот) 🟢.

## Фрагменты контракта

### Основной поток события (оркестрируют внешние модули) 🟡
1. Адаптер сохраняет raw: `RawPayloadStore.put(source, bytes)` → `RawPayloadRef` (сначала S3, затем журнал: Javadoc `EventJournal.append` говорит об «уже сохранённом raw», `ES/EventJournal.java:19`).
2. `EventJournal.append(event, ref, syncRunId)` → `RECEIVED` либо `DUPLICATE`.
3. Потребитель (graph-projector) двигает статус `VALIDATED → NORMALIZED → RESOLVED → PROJECTED` через `transition` и после коммита Neo4j вызывает `saveCheckpoint` (`ES/Checkpoint.java:6`, `ES/EventJournal.java:46-49`).
4. Сбой: `transition(..., QUARANTINED|RETRYING, code, reason)` либо `toDlq` для карантина с записью DLQ.
5. Чтение для replay/rebuild/диспетчера: `JournalReader.read` / `pending` (см. [`journal-keyset-read`](journal-keyset-read/design.md)).

### Сравнение версий
`SourceVersion.compareTo` — числовое; `isOlderThan` — строго меньше. Решение «применять/игнорировать» принимает `GraphProjector` (`VersionDecision`), не контракт 🟢 (`ES/SourceVersion.java:18-26`; [`source-version-compare`](source-version-compare/design.md)).

## Зависит от / Связанные спецификации

| Связь | Куда | Основание |
|---|---|---|
| Maven-зависимость | `canonical-model` | `contracts/event-schemas/pom.xml:15-19` 🟢; классы модуля её не используют (grep `canonicalmodel` по `src/` пуст) 🟡 |
| Прямые потребители (spec-impact-matrix §1) | `adapter-core`, `event-journal`, `normalizer`, `graph-projector`, `ingestion-service` (+ `demo-launcher`, `integration-tests` по `pom.xml`) | `traceability/spec-impact-matrix.md` §1 строка `contracts/event-schemas` 🟢 |
| Через данные | таблица `inbox_event` (колонка `status` без `CHECK`), консоль (`SyncSources.java:19-31` — список статусов продублирован), UI (`EVENT_STATUSES`) | матрица §1; `database/business-rules.md:40` |
| Что проверять при изменении | `PostgresEventJournal` на реальном PG (только IT), `JournalDispatcherIT` | матрица §1 |
| § 2 № 1 | Литералы типов `deleted`/`snapshot-complete` вне контракта | `EventMapper.java:29,32` ↔ `EventProcessor.java:92-94` ↔ `StoredEventReader.java:34` ↔ `ReplayService.java:37` 🔴 |
| § 2 № 2, № 3 | Таблица кодов источников и регистр `source` (`urn:corp:<код>`); контракт `source` — произвольная непустая строка | `ES/CanonicalEvent.java:12`; `EventMapper.java:46-48`; `GraphProjector.java:697-698` 🔴 |
| § 2 № 6 | Формат raw JSON, лежащего под `RawPayloadRef` (запись `EventMapper`, чтение `StoredEventReader`); контракт его не описывает | `StoredEventReaderContractTest` 🟢 |
| § 2 № 7 | Raw tombstone `Reconciler` ↔ `InboxWriter` | `Reconciler.java:197-201` ↔ `InboxWriter.java:34-48` 🟡 |
| § 3 «Новый статус события» | `event-schemas` → `event-journal` → `graph-projector` → `ingestion-service` → `admin-console` → `admin-ui` | матрица §3 |
| Документация | `docs/00-vision.md:376-387`, `docs/02-data-model.md` §5 (`inbox_event`, статусы, checkpoint), `docs/03-interfaces.md` §5.3-5.4; ADR 0006 (inbox), 0007 (raw в S3), 0020 (tombstone/snapshot) | `adrs/retroactive-map.md` |
| Смежные спецификации | `canonical-model`, `source-spi` (`SourceChange.sourceVersion: long` ↔ `SourceVersion` до 40 цифр, R-05), `event-journal` (реализация портов), `adapter-core` (`InMemoryJournal` — тестовая реализация), `graph-projector` (`EventProcessor`, `VersionDecision`) | — |

## Решения проекта

| Решение | Evidence | Уверенность |
|---|---|---|
| Версия источника — строка с числовой семантикой (`BigInteger`), нечисловая отвергается, а не сравнивается лексикографически | `ES/SourceVersion.java:5-9,12-21` | 🟢 |
| `null` в payload сохраняется: `Map.copyOf` не используется (не принимает `null`) | `ES/AssetEventData.java:22-23` | 🟢 |
| `type`/`subject` не входят в `JournalEntry`, а вынесены в `StoredEvent` | `ES/StoredEvent.java:5-12` | 🟢 |
| `eventId` непрозрачен и может содержать любые символы; объект берётся из полей `sourceType`/`sourceId` журнала, а не из id | `ES/ObjectRef.java:3-6` | 🟢 |
| keyset-пагинация по тройке `(receivedAt, source, eventId)`, т. к. `eventId` уникален только в пределах `source` | `ES/JournalKey.java:6-10` | 🟢 |
| Контракт `ProcessingStatus.canTransitionTo` — pure-функция, без хранения истории | `ES/ProcessingStatus.java:26-42` | 🟢 |
| Идентичность события: `(source, id)`; идемпотентность проекции решается версией, а не `eventId` (за пределами модуля) | `ES/CanonicalEvent.java:8-9`; `_reversa_sdd/code-analysis.md` (graph-projector) | 🟡 |

## Состояние

Модуль не хранит состояния; изменяемых полей нет (все типы — records/enum/интерфейсы, `payload` — неизменяемая копия) 🟢.

## Наблюдаемость

Логов, метрик и трассировок в модуле нет 🟢 (нет логгера в `ES/`).

## Риски и лакуны
- 🔴 R-01 … R-05 — см. [`requirements.md`](requirements.md) §«Открытые вопросы» и [`questions.md`](questions.md).
- 🟡 `JournalEntry`, `Checkpoint`, `ObjectRef` без валидации — реализация контракта может записать любые `null`/пустые значения (`ES/JournalEntry.java:14`, `ES/Checkpoint.java:13`, `ES/ObjectRef.java:7`).
- 🟡 Единообразия исключений в модуле нет: `CanonicalEvent`/`AssetEventData`/`RawPayloadRef`/`SourceVersion` бросают `IllegalArgumentException` и на `null`, а `JournalKey`/`StoredEvent` — `NullPointerException` (`ES/CanonicalEvent.java:48-52`, `ES/JournalKey.java:14-16`, `ES/StoredEvent.java:15-16`).
- 🟡 Javadoc `CanonicalEvent` (`ES/CanonicalEvent.java:6`) описывает класс как событие `...upserted.v1`, хотя тип — любая непустая строка и класс несёт и `deleted`, и `snapshot-complete`.
- 🟢 Javadoc `ProcessingStatus` и `canTransitionTo` согласованы: `PROJECTED` конечный (UNLOCKER-281).
