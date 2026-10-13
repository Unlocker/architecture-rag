# contracts/event-schemas, Внешний контракт

> Модуль не выставляет HTTP/SQL. Контракт: структура канонического события, соглашения об идентификаторах, статусы и порты. Модуль не содержит JSON-сериализации: форма CloudEvent ниже — отображение полей record, пример из `docs/03-interfaces.md` §5.4 (`docs/03-interfaces.md:512-518`) 🟡.

## 1. CloudEvent `architecture.asset.upserted.v1`

| Поле | Тип | Обяз. | Значение/правило |
|---|---|---|---|
| `specversion` | string | да (метод) | всегда `"1.0"` (`ES/CanonicalEvent.java:28,44-46`) 🟢 |
| `id` | string | да | непусто; уникален в пределах `source` (`:34`) 🟢 |
| `source` | string | да | непусто; в проде `urn:corp:<код>` (`eam`, `scm`, `cmdb`, `deploymap`) — формат на уровне контракта не проверяется 🟡 |
| `type` | string | да | непусто; константа `architecture.asset.upserted.v1` (`:31`); прочие типы — вне контракта 🔴 R-01 |
| `subject` | string | нет | например `it-system/EAM-1042` (`:13`) |
| `time` | Instant | да | UTC-момент (`:38`) |
| `dataschema` | string | да | непусто, например `urn:corp:schema:asset-upserted:1` (`:14,37`) |
| `correlationid` | string | нет | `null` допустим (`:15`) |
| `data.sourceType` | string | да | непусто |
| `data.sourceId` | string | да | непусто, непрозрачный |
| `data.sourceVersion` | string `[0-9]{1,40}` | да | числовое сравнение |
| `data.payload` | map | нет | `null` → `{}`; `null`-значения = «поле не передано» |

## 2. Соглашения об `eventId` (вне контракта, зафиксированы в потребителях) 🟡

| Префикс/форма | Значение | Где определено |
|---|---|---|
| `replay:<replayId>:<исходный eventId>` | строка, созданная replay; `JournalQuery.REPLAY_PREFIX = "replay:"` | `ES/JournalQuery.java:21-22`; `ReplayService.java:25` |
| `snap:<syncRunId>:<sourceType>/<sourceId>/v<version>` | событие полного snapshot; `snapshotContents` выбирает по префиксу `snap:<syncRunId>:` | `ES/EventJournal.java:53`; `adapters/adapter-core/.../EventMapper.java:104`; `ingestion/event-journal/.../PostgresEventJournal.java:284` |
| `reconcile:<syncRunId>:<sourceType>/<sourceId>` | tombstone `Reconciler` | `ingestion/graph-projector/.../Reconciler.java:61` |

Маркер `snapshot-complete` в `snapshotContents` не учитывается (`eventCount` — «без маркера», `ES/SnapshotContents.java:11`) 🟢.

## 3. Статусы обработки

Десять значений `ProcessingStatus` (порядок enum: `RECEIVED, VALIDATED, NORMALIZED, RESOLVED, PROJECTED, QUARANTINED, RETRYING, SUPERSEDED, DUPLICATE, IGNORED_OLD_VERSION`, `ES/ProcessingStatus.java:9-18`) — строковые имена пишутся в `inbox_event.status` (без `CHECK`, `database/business-rules.md:40`); переименование enum ломает данные и консоль/UI 🟡. Таблица переходов — [`design.md`](design.md).

## 4. Коды ошибок

Модуль кодов ошибок не определяет: `errorCode` — произвольная строка (непустая для `toDlq`), `errorReason` — без содержимого источника (`ES/EventJournal.java:39`, `ES/JournalEntry.java:10-11`) 🟢. Конкретные коды (`UNSUPPORTED_EVENT_TYPE`, `UNKNOWN_SOURCE` и др.) задаёт `EventProcessor` (`docs/02-data-model.md` §5.2).

## 5. Ключи raw payload

`raw/<source>/<sha256>` (SHA-256 содержимого, hex) — идемпотентный put (`ES/RawPayloadStore.java:3,6`, `ES/RawPayloadRef.java:3-4`) 🟢. Формат содержимого raw JSON контрактом не задан (описан на стороне `adapter-core`/`StoredEventReader`) 🟡.
