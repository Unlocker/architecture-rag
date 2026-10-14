# ingestion/event-journal: требования

> Reversa Writer, R.6a. Метки: 🟢 подтверждено в коде · 🟡 выведено · 🔴 пробел.
> Сокращения путей (от корня репозитория):
> `PEJ` = `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java`;
> `S3S` = `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java`;
> `SQL` = `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql`;
> `ES` = `contracts/event-schemas/src/main/java/io/github/unlocker/archrag/eventschemas`.
> Ссылки на `docs/`: ADR [0006](../../docs/adr/0006-postgres-inbox-event-backbone.md), [0007](../../docs/adr/0007-s3-raw-storage-seaweedfs.md); `docs/02-data-model.md`.

## Обзор

Реализация портов `contracts/event-schemas` на PostgreSQL (чистый JDBC, без ORM) и S3-совместимом хранилище (AWS SDK v2) плюс Flyway-миграция `V1`. Это «event backbone» PoC (ADR 0006): адаптер → inbox PostgreSQL → worker → Neo4j. Граница портов (`EventJournal`, `CanonicalEventPublisher`, `JournalReader`, `RawPayloadStore`) позволяет заменить реализацию (Kafka) без правок адаптеров и projector 🟢 (`PEJ:31-38`, `ES/EventJournal.java:7-12`, `ES/CanonicalEventPublisher.java:7`).

Модуль состоит из 4 классов main, `package-info` и одной SQL-миграции (`JournalException`, `JournalMigrations`, `PostgresEventJournal`, `S3RawPayloadStore`) 🟢.

## Ответственность

- Идемпотентно принимать канонические события в таблицу `inbox_event` по ключу `(source, event_id)` 🟢 (`SQL:21`, `PEJ:53-97`).
- Хранить и менять состояние обработки (`ProcessingStatus`) под блокировкой строки, считать `attempts`, хранить `error_code`/`error_reason` 🟢 (`PEJ:320-354`).
- Вести DLQ (`dlq_entry`): не более одной открытой записи на событие 🟢 (`SQL:48`, `PEJ:131-160`).
- Отдавать журнал страницами (keyset) для replay/rebuild и выбирать «ожидающие» события для диспетчера 🟢 (`PEJ:162-237`).
- Хранить checkpoint-и consumer-ов (`consumer_checkpoint`) 🟢 (`PEJ:239-272`).
- Отдавать данные для reconciliation: объекты snapshot-прогона и объекты, о которых было событие с момента `since` 🟢 (`PEJ:274-318`).
- Хранить raw payload в S3 под ключом, адресуемым содержимым, и проверять целостность при чтении 🟢 (`S3S:68-89`).
- Применять Flyway-миграции из `classpath:db/migration` 🟢 (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java:12-14`).

Вне ответственности: решение, в какой статус переводить (это логика вызывающих), разбор payload, граф Neo4j, повторные попытки, retention, выход из карантина.

## Бизнес-правила

1. Дедупликация по `(source, event_id)`: повторный `append` не создаёт строку и не меняет сохранённую; возвращается существующая запись **с подставленным в памяти** статусом `DUPLICATE` (в БД статус остаётся прежним) 🟢 (`PEJ:56-59`, `PEJ:86-89`, `PEJ:393-397`; тест `EventJournalIT.duplicateEventIsNotInsertedTwice`).
2. Один и тот же `event_id` из другого `source` принимается как новое событие 🟢 (`SQL:21`; `EventJournalIT.sameEventIdFromAnotherSourceIsAccepted`).
3. Новая строка всегда создаётся со `status=RECEIVED`, `attempts=0`, `received_at = updated_at = Instant.now()` 🟢 (`PEJ:55`, `PEJ:58`, `PEJ:74`, `PEJ:77-78`).
4. Переход статуса: `SELECT ... FOR UPDATE` → `current.status().canTransitionTo(new)` → `UPDATE`; неизвестное событие → `IllegalArgumentException`; запрещённый переход → `IllegalStateException`; всё в одной транзакции 🟢 (`PEJ:324-338`).
5. `attempts` увеличивается на 1 при каждом вызове с целевым статусом `RETRYING` (включая `RETRYING → RETRYING`), в остальных случаях не меняется 🟢 (`PEJ:340`, `PEJ:345`).
6. `error_code` и `error_reason` **перезаписываются при каждом переходе**; переход без ошибок (`null`) обнуляет их 🟢 (`PEJ:339-344`).
7. `error_reason` усекается до 500 символов (`MAX_REASON_LENGTH`) 🟢 (`PEJ:40`, `PEJ:344`, `PEJ:399-402`).
8. `toDlq`: `errorCode` обязателен (не `null`, не пустой, не из пробелов); событие переводится в `QUARANTINED`, в `dlq_entry` вставляется запись; повтор для события с открытой записью вторую не создаёт; `reason` в DLQ — это `reason`, а при `null` — `errorCode`, усечённое до 500 🟢 (`PEJ:133-150`, `SQL:47-48`; `EventJournalIT.repeatedDlqKeepsSingleRecord`, `dlqRejectsBlankCodeAndUnknownEvent`).
9. Ключ S3-объекта `raw/<source>/<sha256 hex>`; `source` — только `[A-Za-z0-9:._-]{1,200}` и не только из точек, иначе `IllegalArgumentException` (защита от выхода из префикса ключа) 🟢 (`S3S:33`, `S3S:68-74`; `S3RawPayloadStoreTest.rejectsSourceThatCouldEscapeKeyPrefix`).
10. Запись в S3 идемпотентна: `HEAD`, при `NoSuchKey` — `PUT`; чтение пересчитывает SHA-256 и при расхождении бросает `IllegalStateException` 🟢 (`S3S:75-78`, `S3S:81-89`, `S3S:91-99`).
11. Checkpoint — upsert по `(consumer, source)`; монотонность курсора модуль не проверяет (курсор непрозрачный) 🟢 (`PEJ:256-272`; Javadoc `ES/EventJournal.java:46-49`).
12. Внешние значения (`source`, `eventId`) не попадают в тексты исключений целиком: только 12 hex-символов SHA-256 «отпечатка» 🟢 (`PEJ:330`, `PEJ:336-337`, `PEJ:356-359`).
13. Содержимое raw payload модуль не читает и не логирует: в PostgreSQL лежат только `payload_key` и `payload_hash` 🟢 (`SQL:17-18`, `PEJ:75-76`). Логов в модуле нет 🟢 (grep по `LOG`/`slf4j` в модуле: ничего).

## Требования функциональные

| ID | Требование | MoSCoW | Критерий приёмки | Уверенность |
|---|---|---|---|---|
| RF-01 | `append` идемпотентно записывает событие в `inbox_event` (use case `append-event`) | Must | AC-A1, AC-A2 | 🟢 |
| RF-02 | `publish` возвращает `true` только для впервые принятого события | Must | AC-A3 | 🟢 |
| RF-03 | `transition` проверяет допустимость перехода под `FOR UPDATE` (use case `transition-event`) | Must | AC-T1, AC-T3, AC-T4 | 🟢 |
| RF-04 | `toDlq` атомарно карантинит событие и создаёт единственную открытую запись DLQ | Must | AC-T5, AC-T6, AC-T7 | 🟢 |
| RF-05 | `read` отдаёт страницы по keyset `(received_at, source, event_id)` с фильтрами (use case `read-journal`) | Must | AC-R1, AC-R2 | 🟢 |
| RF-06 | `pending` выбирает `RECEIVED` и «созревшие» повторы/промежуточные статусы | Must | AC-R3, AC-R4 | 🟢 |
| RF-07 | `loadCheckpoint`/`saveCheckpoint` хранят курсор на `(consumer, source)` | Must | AC-R5 | 🟢 |
| RF-08 | `snapshotContents` и `objectsReceivedSince` дают основу missing set | Must | AC-R6 | 🟢 |
| RF-09 | `find` возвращает запись по ключу дедупликации или пусто | Must | AC-A4 | 🟢 |
| RF-10 | `S3RawPayloadStore.put/get` — адресуемое содержимым хранилище с проверкой hash (use case `raw-payload-store`) | Must | AC-S1..AC-S3 | 🟢 |
| RF-11 | `S3RawPayloadStore.ensureBucket` создаёт бакет, если его нет (вызывается при старте) | Should | AC-S4 | 🟢 |
| RF-12 | `JournalMigrations.apply` применяет миграции и безопасна при повторе | Must | AC-M1 | 🟢 |
| RF-13 | Ошибки SQL оборачиваются в `JournalException` с причиной | Must | AC-E1 | 🟢 |

## Требования нефункциональные

Только с evidence в коде.

| Тип | Требование | Evidence | Уверенность |
|---|---|---|---|
| Целостность | Дедупликация и защита от гонок делегированы БД: `UNIQUE (source, event_id)` + `ON CONFLICT DO NOTHING` | `SQL:21`, `PEJ:59` | 🟢 |
| Целостность | Проверка перехода и запись идут под блокировкой строки в одной транзакции | `PEJ:322-325`, `PEJ:116-123` | 🟢 |
| Целостность | DLQ и перевод в `QUARANTINED` — одна транзакция | `PEJ:136-152` | 🟢 |
| Целостность | Внешний ключ DLQ → `inbox_event(source, event_id)` | `SQL:44` | 🟢 |
| Целостность | Hash содержимого сверяется при чтении raw | `S3S:81-89` | 🟢 |
| Безопасность | SQL только с параметрами; динамические фрагменты фиксированы в коде | `PEJ:164-190`, `PEJ:214-217` | 🟢 |
| Безопасность | `LIKE` не применяется к внешнему `syncRunId` (`starts_with`) | `PEJ:276-278` | 🟢 |
| Безопасность | Ключ объекта S3 не допускает `/`, `..`, пробелов | `S3S:33`, `S3S:70` | 🟢 |
| Безопасность | Ключи доступа к S3 принимаются аргументами и не попадают в исключения/логи | `S3S:39-51` | 🟢 |
| Безопасность | Внешние идентификаторы в сообщениях исключений заменены отпечатком | `PEJ:356-359` | 🟢 |
| Безопасность | Причина ошибки (недоверенный текст) хранится усечённой до 500 символов | `PEJ:399-402` | 🟢 |
| Производительность | Размер страницы `pending` `1..1000` | `PEJ:211-213`, `ES/JournalQuery.java:25` | 🟢 |
| Производительность | Индексы под `status`+`received_at` и под объект | `SQL:24-25` | 🟢 |
| Время | Все времена `timestamptz`, значения из `Instant`/`Timestamp.from(Instant)` | `SQL:19-20`, `PEJ:55`, `PEJ:77-78` | 🟢 |
| Эксплуатация | Клиент S3: path-style, регион `us-east-1`, checksum-ы `WHEN_REQUIRED`, `UrlConnectionHttpClient` (Netty и Apache исключены из зависимостей) | `S3S:39-51`, `ingestion/event-journal/pom.xml:35-51` | 🟢 |

Retention `inbox_event`, `dlq_entry`, S3 raw не определён (сознательно для PoC) 🟡 (`_reversa_sdd/domain.md` §5; `docs/02-data-model.md:335`).

## Критерии приёмки

Модульные критерии; сценарные AC (A*, T*, R*, S*) лежат в use case-папках. Поименованные тесты: `S3RawPayloadStoreTest` (модуль, unit), `EventJournalIT` (`platform/integration-tests`, Testcontainers `postgres:16` + S3 SeaweedFS).

```gherkin
# AC-M1 (миграции идемпотентны)
Дано PostgreSQL с применённой схемой V1
Когда вызывается JournalMigrations.apply(dataSource) повторно
Тогда ошибки нет и схема не меняется
# trace: EventJournalIT.migrationsAreIdempotent

# AC-E1 (ошибка хранилища)
Дано DataSource, у которого getConnection бросает SQLException
Когда вызывается find / transition / toDlq / read / pending / loadCheckpoint / saveCheckpoint / snapshotContents / objectsReceivedSince / append
Тогда выбрасывается JournalException с сообщением "<операция> failed" и исходной причиной
# trace: PEJ:94-96, 108-110, 126-128, 157-159, 204-206, 234-236, 251-253, 269-271, 295-297, 315-317; тестов нет 🔴
```

## MoSCoW

| Требование | Приоритет | Причина |
|---|---|---|
| Идемпотентный `append`, `transition`, `toDlq`, `find` | Must | Ядро конвейера; все потребители журнала |
| `read`, `pending`, checkpoint | Must | Диспетчер, replay, rebuild, адаптеры |
| `snapshotContents`, `objectsReceivedSince` | Must | Reconciliation (удаление только после полного snapshot) |
| S3 `put/get` | Must | Raw нужен для replay/rebuild |
| `ensureBucket` | Should | Вызывается при старте; бакет можно создать вне приложения |
| Журнал попыток ошибок (history), retention, выход из карантина | Won't | В коде нет, см. `questions.md` |

## Зависит от / связанные спецификации

(Спец-импакт-матрица §1, §2: `_reversa_sdd/traceability/spec-impact-matrix.md:13`, `:17`, `:38`, `:47`.)

- `contracts/event-schemas`: порты, DTO, `ProcessingStatus`, `JournalQuery` (меняются порт или DTO — пересмотреть этот модуль). Новый статус события затрагивает `event-schemas`, `event-journal`, `graph-projector`, `ingestion-service`, `admin-console`, `admin-ui` (`spec-impact-matrix.md:57`).
- Затрагивают этот модуль (потребители): `ingestion-service` (диспетчер, replay/rebuild, метрики, сборка bean-ов), `demo-launcher`, `adapter-core` (`InboxWriter`, `Poller`; только через интерфейс, реализация `scope=runtime`), `graph-projector` (`EventProcessor`, `Reconciler`).
- Связь №5 (через данные): консольные `SELECT` по `inbox_event`, `dlq_entry`, `consumer_checkpoint` защищены `GRANT SELECT` в `V6__console_readonly_role.sql` (модуль `ingestion-service`); новая колонка/таблица здесь требует правки `V6` и `ConsoleReadRepository` (`spec-impact-matrix.md:38`) 🟡.
- Связь №14: каталог `db/migration` общий для трёх модулей (`V1` — здесь, `V2/V4/V5/V7` — `identity-resolution`, `V3/V6` — `ingestion-service`); номер новой миграции сквозной (`spec-impact-matrix.md:47`) 🟡.
- Связь №1/№6/№7: формат raw JSON в S3 пишут адаптеры и `Reconciler`, читает `ingestion-service`; сам модуль формат не знает (хранит байты) 🟢.
- Родственные спецификации: `adapter-core` (источник событий и `InboxWriter`), `graph-projector` (`EventProcessor`, `Reconciler`), `ingestion-service` (`JournalDispatcher`, `ReplayService`), `event-schemas`.

## Открытые вопросы

См. [`questions.md`](questions.md): 🟢 [UNLOCKER-281: реализовано] R-1..R-4.

## Прослеживаемость кода

| Файл | Класс / символ | Покрытие |
|---|---|---|
| `PEJ` | `PostgresEventJournal` | 🟢; тесты: `EventJournalIT` (pending, duplicate, transitions, dlq, checkpoint, reader), `JournalDispatcherIT`, `ReconciliationIT` |
| `S3S` | `S3RawPayloadStore` | 🟢; `S3RawPayloadStoreTest` (2), `EventJournalIT.rawPayloadRoundTripsAndKeyIsContentAddressed` |
| `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java` | `JournalMigrations.apply` | 🟢; `EventJournalIT.migrationsAreIdempotent` |
| `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalException.java` | `JournalException` | 🟢; прямого теста нет |
| `SQL` | `inbox_event`, `consumer_checkpoint`, `dlq_entry`, индексы | 🟢 |
