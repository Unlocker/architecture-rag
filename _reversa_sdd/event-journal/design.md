# ingestion/event-journal: дизайн

> Сокращения путей как в [`requirements.md`](requirements.md): `PEJ`, `S3S`, `SQL`, `ES`. `JM` = `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java`.
> Use case-спецификации: [`append-event/`](append-event/design.md), [`transition-event/`](transition-event/design.md), [`read-journal/`](read-journal/design.md), [`raw-payload-store/`](raw-payload-store/design.md). Внешние контракты (SQL, ключ S3): [`contracts.md`](contracts.md).

## Интерфейс

Пакет `io.github.unlocker.archrag.eventjournal`. Реализуемые порты — в `contracts/event-schemas`.

| Символ | Сигнатура | Реализует | Результат / исключения |
|---|---|---|---|
| `PostgresEventJournal(DataSource)` | конструктор; схема должна быть применена `JournalMigrations.apply` | `EventJournal`, `JournalReader`, `CanonicalEventPublisher` | `PEJ:38-51` 🟢 |
| `append` | `(CanonicalEvent, RawPayloadRef /*nullable*/, String syncRunId /*nullable*/)` | `EventJournal` | `JournalEntry`; дубль: копия со статусом `DUPLICATE` (`PEJ:53-97`) 🟢 |
| `publish` | та же тройка | `CanonicalEventPublisher` | `boolean`: `append(...).status() != DUPLICATE` (`PEJ:99-102`) 🟢 |
| `find` | `(String source, String eventId)` | `EventJournal` | `Optional<JournalEntry>` (`PEJ:104-111`) 🟢 |
| `transition` | `(source, eventId, ProcessingStatus, errorCode, errorReason)` | `EventJournal` | `JournalEntry` после обновления; `IllegalArgumentException` (нет события), `IllegalStateException` (запрещённый переход) (`PEJ:113-129`, `PEJ:320-354`) 🟢 |
| `toDlq` | `(source, eventId, errorCode, reason)` | `EventJournal` | `JournalEntry` в `QUARANTINED`; `IllegalArgumentException` при пустом `errorCode` или неизвестном событии (`PEJ:131-160`) 🟢 |
| `read` | `(JournalQuery)` | `JournalReader` | `List<StoredEvent>`, порядок `(received_at, source, event_id)` (`PEJ:162-207`) 🟢 |
| `pending` | `(JournalKey after /*nullable*/, Instant retryNotAfter, int limit)` | `JournalReader` | `List<StoredEvent>`; `limit` вне `1..1000` → `IllegalArgumentException` (`PEJ:209-237`) 🟢 |
| `loadCheckpoint` | `(consumer, source)` | `EventJournal` | `Optional<Checkpoint>` (`PEJ:239-254`) 🟢 |
| `saveCheckpoint` | `(consumer, source, cursor)` | `EventJournal` | `Checkpoint` с `updatedAt = Instant.now()` (`PEJ:256-272`) 🟢 |
| `snapshotContents` | `(source, syncRunId)` | `EventJournal` | `SnapshotContents(Set<ObjectRef>, long events, Instant firstReceivedAt)` (`PEJ:274-299`) 🟢 |
| `objectsReceivedSince` | `(source, Instant since)` | `EventJournal` | `Set<ObjectRef>`, `received_at >= since` (`PEJ:301-318`) 🟢 |
| `S3RawPayloadStore.create` | `(URI endpoint, String accessKey, String secretKey, String bucket)` | фабрика | `S3RawPayloadStore` (`S3S:39-51`) 🟢 |
| `S3RawPayloadStore(S3Client, String bucket)` | публичный конструктор; владение клиентом переходит к хранилищу | | `S3S:54-57` 🟢 |
| `put` / `get` / `ensureBucket` / `close` | см. [`raw-payload-store/design.md`](raw-payload-store/design.md) | `RawPayloadStore`, `AutoCloseable` | `S3S:59-113` 🟢 |
| `JournalMigrations.apply` | `static (DataSource)` | | Flyway `migrate()` на `classpath:db/migration` (`JM:12-14`) 🟢 |
| `JournalException` | `(String message, Throwable cause)` extends `RuntimeException` | | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalException.java:5-9` 🟢 |

Типы DTO (`JournalEntry`, `StoredEvent`, `Checkpoint`, `SnapshotContents`, `ObjectRef`, `JournalKey`, `JournalQuery`, `RawPayloadRef`) определены в `contracts/event-schemas`; здесь только маппятся (`PEJ:372-391`) 🟢.

## Общая схема работы с БД

- Каждая операция берёт соединение из `DataSource` (try-with-resources, `PEJ:60`, `PEJ:106`, `PEJ:116`, `PEJ:136`, `PEJ:192`, `PEJ:218`, `PEJ:241`, `PEJ:259`, `PEJ:282`, `PEJ:303`) 🟢. Пулов, кэшей и состояния между вызовами нет 🟢.
- Операции записи с несколькими шагами (`append`, `transition`, `toDlq`) работают с `setAutoCommit(false)`, `commit()` на успехе и `rollback()` при `SQLException | RuntimeException` с повторным `throw` (`PEJ:61`, `PEJ:90-93`, `PEJ:117-125`, `PEJ:137-156`) 🟢.
- Одиночные операции (`saveCheckpoint`, чтения) идут в autocommit 🟢 (`PEJ:259-271`).
- `SQLException` (в том числе при `rollback`) → `JournalException("<операция> failed", cause)`; не-SQL исключения (`IllegalArgumentException`, `IllegalStateException`, `JournalException("inbox row vanished after conflict", null)`) пробрасываются как есть после `rollback` 🟢 (`PEJ:90-96`).
- Пустых `catch` в модуле нет; перехватываются только штатные ответы «нет объекта/бакета»: `NoSuchKeyException` в `exists` (`S3S:96-98`) и `NoSuchBucketException` в `ensureBucket` (`S3S:63-65`) 🟢.

## Основной поток (композиция сценариев)

1. Приложение (`IngestionServiceConfiguration`/`DemoLauncher`) вызывает `JournalMigrations.apply(dataSource)`, затем создаёт `PostgresEventJournal(dataSource)` и `S3RawPayloadStore.create(...)` + `ensureBucket()` 🟢 (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:46-48`, `:77-80`; `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:113-119`).
2. Адаптер кладёт raw в S3 (`put` → `RawPayloadRef`), затем `append(event, ref, syncRunId)` пишет строку `RECEIVED` (`adapters/adapter-core/.../InboxWriter.java:38`, `:47`) 🟢 → [`append-event`](append-event/design.md), [`raw-payload-store`](raw-payload-store/design.md).
3. Диспетчер берёт `pending(after, retryNotAfter, batchSize)` (`JournalDispatcher.java:107`) и ведёт событие по статусам через `transition`; сбой — `RETRYING` с кодом или `toDlq` → [`read-journal`](read-journal/design.md), [`transition-event`](transition-event/design.md).
4. Replay/rebuild читают журнал `read(JournalQuery)` (`ReplayService.java:68`) и создают новые строки `replay:<replayId>:...` через `append` (`ReplayService.java:97`) 🟢.
5. Проектор сдвигает checkpoint `saveCheckpoint("projector", source, eventId)` после проекции (`EventProcessor.java:84`, `:615`) 🟢; адаптеры — `poller:*`/`snapshot:*` (`Poller.java:136`, `:170-171`, `:186`) 🟢.
6. `Reconciler` берёт `snapshotContents` и `objectsReceivedSince` для missing set (`Reconciler.java:105`, `:113`) 🟢.

Пути потребителей в п.2-6 — от `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/` (`JournalDispatcher`, `ReplayService`), `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/` (`EventProcessor`, `Reconciler`), `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/` (`InboxWriter`, `Poller`).

## Альтернативные потоки и ошибки

- Пустой/неизвестный `source` в `put` → `IllegalArgumentException` без обращения к S3 (`S3S:69-71`) 🟢.
- S3 недоступен → исключения AWS SDK пробрасываются неизменными (`JournalException` для S3 не используется) 🟢 (`S3S:59-99`).
- Строка исчезла между конфликтом вставки и `SELECT` (теоретическая гонка с удалением) → `JournalException("inbox row vanished after conflict", null)` без причины 🟢 (`PEJ:86-87`).
- Поведение `pending(after, null, n)` не определено: `Timestamp.from(null)` бросит `NullPointerException` 🟡 (`PEJ:220`).

## Зависимости

| Компонент | Зачем | Evidence |
|---|---|---|
| `contracts/event-schemas` | порты и DTO | `ingestion/event-journal/pom.xml:17-19`, `PEJ:3-16` |
| `org.postgresql:postgresql` | драйвер JDBC | `pom.xml:22-25` |
| `flyway-core`, `flyway-database-postgresql` | миграции | `pom.xml:26-33` |
| `software.amazon.awssdk:s3` (без `netty-nio-client`, `apache-client`) + `url-connection-client` | S3-клиент и HTTP-транспорт | `pom.xml:34-51` |

Версии — через BOM корневого `pom.xml` (в модуле версий нет) 🟢. Spring в модуле не используется: конфигурация — аргументы конструкторов; свойства Spring (`ARCHRAG_S3_ENDPOINT`, `ARCHRAG_S3_ACCESS_KEY`, `ARCHRAG_S3_SECRET_KEY`, `ARCHRAG_S3_BUCKET`) лежат в `ingestion/ingestion-service/src/main/resources/application.yml:27-31` 🟢.

Правило направления зависимостей: `adapters/*` компилируются только против интерфейсов `event-schemas`, реализация подключается `scope=runtime`; `access/*` от модуля не зависит (нет writer-доступа к PostgreSQL/S3) 🟢 (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/package-info.java:3-8`).

## Зависит от / Связанные спецификации

- Контракты: `contracts/event-schemas` (`ProcessingStatus.canTransitionTo` определяет допустимые переходы, `ES/ProcessingStatus.java:26-42`) 🟢.
- Потребители: `ingestion-service` (диспетчер, replay, rebuild, метрики по `inbox_event`/`dlq_entry`: `IngestionMetrics.java:71`), `graph-projector` (`EventProcessor`, `Reconciler`), `adapter-core` (`InboxWriter`, `Poller`), `demo-launcher`.
- Связь через данные №5: `V6__console_readonly_role.sql` в `ingestion-service` выдаёт `GRANT SELECT` на `inbox_event`, `consumer_checkpoint`, `dlq_entry` роли консоли; `access/admin-console/.../ConsoleReadRepository.java` читает эти таблицы (`spec-impact-matrix.md:38`) 🟡.
- Связь №14: общий каталог `db/migration`; `V1` здесь, `V2/V4/V5/V7` — `identity-resolution`, `V3/V6` — `ingestion-service` (`spec-impact-matrix.md:47`). `JournalMigrations.apply` применяет всё, что есть на classpath; один модуль `event-journal` применяет только `V1` 🟢.
- Связи №1/№6/№7: формат raw JSON и типы событий `deleted`/`snapshot-complete` — вне модуля; он хранит байты 🟢.
- ADR: [0006](../../docs/adr/0006-postgres-inbox-event-backbone.md) (PostgreSQL inbox за портами), [0007](../../docs/adr/0007-s3-raw-storage-seaweedfs.md) (S3-совместимое хранилище, SeaweedFS в тестах/на стенде), [0020](../../docs/adr/0020-tombstone-instead-of-hard-delete.md) (tombstone пишется в журнал и S3 — реализует `Reconciler`, не модуль) 🟡.

- Спецификации потребителей журнала: [`graph-projector`](../graph-projector/design.md) (`EventProcessor`, `Reconciler`, переходы статусов) и [`ingestion-service`](../ingestion-service/design.md) (диспетчер, replay/rebuild, метрики). Открытые 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] R-2 (`QUARANTINED`), R-3 (`SUPERSEDED`), R-4 (лимит попыток) продолжены в [`graph-projector/questions.md`](../graph-projector/questions.md) (Q-01) и [`ingestion-service/questions.md`](../ingestion-service/questions.md) (Q-01, Q-02).

## Решения дизайна

| Решение | Evidence | Уверенность |
|---|---|---|
| JDBC без ORM, SQL-строки в коде | `PEJ:31-37` | 🟢 |
| Дедупликация через `INSERT ... ON CONFLICT (source,event_id) DO NOTHING RETURNING` | `PEJ:56-59` | 🟢 |
| `DUPLICATE` из `append` синтетический (в БД не пишется) | `PEJ:86-89`, `PEJ:393-397` | 🟢 |
| Блокировка строки `FOR UPDATE` вместо оптимистической версии | `PEJ:322-325` | 🟢 |
| Keyset-пагинация по составному ключу `(received_at, source, event_id)` | `PEJ:183-190`, `PEJ:216-217` | 🟢 |
| Промежуточные статусы (`VALIDATED`/`NORMALIZED`/`RESOLVED`) попадают в `pending` по таймауту `updated_at` как зависшие после падения | `PEJ:214-215`, Javadoc `ES/JournalReader.java:17-21` | 🟢 |
| Адресация raw по SHA-256 содержимого, ключ несёт hash | `S3S:27-30` (Javadoc), `S3S:74` | 🟢 |
| Дополнительные S3-checksum-ы SDK отключены (`WHEN_REQUIRED`), целостность держит наш SHA-256 | `S3S:44-47` | 🟢 |
| `starts_with` вместо `LIKE` для префикса `snap:<syncRunId>:` | `PEJ:276-284` | 🟢 |

## Состояние

Объектного состояния нет (поле `dataSource` / `s3`+`bucket`, final). Постоянное состояние:

- Статус обработки `inbox_event.status` (10 значений `ProcessingStatus`); переходы — таблица в [`transition-event/design.md`](transition-event/design.md).
- Запись DLQ: «открыта» (`replayed_at IS NULL`) / «переиграна». Код модуля создаёт только открытые записи; закрытие нигде в репозитории не выполняется 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] (R-1).

## Наблюдаемость

Логов и метрик в модуле нет 🟢. Метрики по таблицам журнала строит `IngestionMetrics` в `ingestion-service` (`archrag.ingestion.events{source,status}`, `archrag.ingestion.sync.lag`, `archrag.ingestion.dlq.open`) 🟡, см. `_reversa_sdd/integrations.md`.

## Риски и пробелы

- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] R-1: `dlq_entry.replayed_at` не записывается (`SQL:43`; в Java нет `UPDATE dlq_entry`); метрика открытых DLQ не уменьшается.
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2)] R-2: нет ограничений на значения `status` в БД (`SQL:13`) и нет перехода из `QUARANTINED` в вызывающем коде — см. [`questions.md`](questions.md).
- 🟡 `error_code`/`error_reason` затираются при каждом переходе; история причин не хранится (`PEJ:339-344`).
- 🟡 Повторный `toDlq` для уже карантинного события затирает `error_code`/`error_reason` в `inbox_event` новыми значениями, а запись `dlq_entry` остаётся с первыми (`PEJ:139`, `PEJ:142`).
- 🟡 Тело дубля не сравнивается с сохранённым (`PEJ:56-89`): повтор с тем же `eventId` и другим содержимым молча отбрасывается.
- 🟡 `snapshotContents` не фильтрует по статусу и держит результат в памяти (`PEJ:279-298`).
- 🟡 Индекса под условие `updated_at` в `pending` нет (`SQL:24-25`).
- 🟡 В `put` существующий объект не перепроверяется: повреждённый объект с тем же ключом не исправляется, ошибку даст только `get` (`S3S:75-79`, `S3S:81-89`).
