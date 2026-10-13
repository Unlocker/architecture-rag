# ingestion/event-journal: внешние контракты

> Сокращения: `PEJ`, `S3S`, `SQL` как в [`requirements.md`](requirements.md). Контракты — то, на что опираются другие модули и роли: схема PostgreSQL, формат ключа S3, соглашения о значениях колонок.

## 1. Схема PostgreSQL (`SQL`, миграция `V1`) 🟢

```sql
CREATE TABLE inbox_event (
    source text NOT NULL, event_id text NOT NULL, type text NOT NULL, subject text,
    source_type text NOT NULL, source_id text NOT NULL, source_version text NOT NULL,
    correlation_id text, sync_run_id text, schema_version text NOT NULL,
    status text NOT NULL, attempts integer NOT NULL DEFAULT 0,
    error_code text, error_reason text, payload_key text, payload_hash text,
    received_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
    CONSTRAINT uq_inbox_event UNIQUE (source, event_id));            -- SQL:2-22
CREATE INDEX ix_inbox_event_status ON inbox_event (status, received_at);                    -- SQL:24
CREATE INDEX ix_inbox_event_object ON inbox_event (source, source_type, source_id);         -- SQL:25
CREATE TABLE consumer_checkpoint (consumer text NOT NULL, source text NOT NULL, cursor text NOT NULL,
    updated_at timestamptz NOT NULL, PRIMARY KEY (consumer, source));  -- SQL:27-33
CREATE TABLE dlq_entry (id bigserial PRIMARY KEY, source text NOT NULL, event_id text NOT NULL,
    reason text NOT NULL, error_code text NOT NULL, payload_key text,
    created_at timestamptz NOT NULL, replayed_at timestamptz,
    FOREIGN KEY (source, event_id) REFERENCES inbox_event (source, event_id));  -- SQL:35-45
CREATE UNIQUE INDEX uq_dlq_entry_open ON dlq_entry (source, event_id) WHERE replayed_at IS NULL; -- SQL:48
```

Соглашения о значениях (код пишет, читатели должны знать):

| Колонка | Значение | Evidence |
|---|---|---|
| `inbox_event.type` / `subject` | `CanonicalEvent.type()` / `subject()` (в `JournalEntry` их нет, они в `StoredEvent`) | `PEJ:66-67`, `PEJ:200` |
| `inbox_event.schema_version` | `CanonicalEvent.dataschema()` (имя колонки и поля различаются) | `PEJ:73` |
| `inbox_event.source_type`, `source_id`, `source_version` | из `event.data()`; версия — `SourceVersion.value()` как строка | `PEJ:68-70` |
| `inbox_event.status` | `ProcessingStatus.name()`; `CHECK`/enum в БД нет | `PEJ:74`, `SQL:13` |
| `inbox_event.attempts` | `0` при вставке; `+1` на каждый `transition` в `RETRYING` | `PEJ:58`, `PEJ:345` |
| `inbox_event.error_reason`, `dlq_entry.reason` | не длиннее 500 символов | `PEJ:344`, `PEJ:145` |
| `dlq_entry.reason` | `reason`, а при `null` — `errorCode` | `PEJ:145` |
| `inbox_event.event_id` | префиксы, задаваемые не модулем: `poll:`, `snap:<syncRunId>:`, `replay:<replayId>:`, `reconcile:<run>:`; модуль знает только `snap:` (в `snapshotContents`) и `replay:` (`JournalQuery.REPLAY_PREFIX`) | `PEJ:284`, `ES/JournalQuery.java:22` |
| `received_at`, `updated_at` | `Instant.now()` в момент вставки/обновления | `PEJ:55`, `PEJ:346` |
| `consumer_checkpoint.cursor` | непрозрачная строка; модуль монотонность не проверяет | `PEJ:256-272` |

Известные потребители схемы вне модуля: `ConsoleReadRepository` (роль `archrag_console_ro`, `GRANT SELECT` в `V6`), `IngestionMetrics` (`ingestion-service`, `IngestionMetrics.java:71`) 🟡.

Применение: Flyway, `locations("classpath:db/migration")`, таблица истории — по умолчанию Flyway (`flyway_schema_history`) 🟡 (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java:13`; имя таблицы по умолчанию не переопределено).

## 2. Контракт S3 🟢

| Параметр | Значение | Evidence |
|---|---|---|
| Ключ объекта | `raw/<source>/<sha256 hex lower-case, 64 символа>` | `S3S:74`, `S3S:101-108` |
| `<source>` | `[A-Za-z0-9:._-]{1,200}` и не состоит только из `.` | `S3S:33`, `S3S:70` |
| Метаданные/Content-Type | не задаются | `S3S:76` |
| Регион, стиль | `us-east-1` (заглушка), path-style | `S3S:41-43` |
| Checksum-ы SDK | `WHEN_REQUIRED` для запроса и ответа | `S3S:44-46` |
| Аутентификация | `StaticCredentialsProvider` с ключами из аргументов `create` | `S3S:47` |
| HTTP-клиент | `UrlConnectionHttpClient` | `S3S:48` |
| Конфигурация приложения | `ARCHRAG_S3_ENDPOINT`, `ARCHRAG_S3_ACCESS_KEY`, `ARCHRAG_S3_SECRET_KEY`, `ARCHRAG_S3_BUCKET` (значения не приводятся) | `ingestion/ingestion-service/src/main/resources/application.yml:27-31` |

Содержимое объекта — байты, переданные вызывающим (в системе это raw JSON адаптера); модуль не знает формат 🟢.

## 3. Порты, которые модуль реализует (поведенческие контракты) 🟢

| Порт | Гарантии, подтверждённые реализацией |
|---|---|
| `EventJournal.append` | уникальность `(source, eventId)`; повтор не меняет строку; возврат прежней записи со статусом `DUPLICATE` |
| `EventJournal.transition` | `IllegalArgumentException` — события нет; `IllegalStateException` — переход запрещён `canTransitionTo` |
| `EventJournal.toDlq` | `IllegalArgumentException` при пустом `errorCode`/неизвестном событии; одна открытая запись DLQ |
| `EventJournal.saveCheckpoint` | вызывать только после коммита проекции (ответственность вызывающего; журнал порядок не проверяет) |
| `JournalReader.read/pending` | порядок `(receivedAt, source, eventId)`; keyset строго после `after`; состояние строк не меняется |
| `CanonicalEventPublisher.publish` | at-least-once; `true` — принято впервые |
| `RawPayloadStore.put/get` | ключ `raw/<source>/<sha256>`; `get` проверяет hash |

Исходные интерфейсы и Javadoc: `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas/{EventJournal,JournalReader,CanonicalEventPublisher,RawPayloadStore}.java`.
