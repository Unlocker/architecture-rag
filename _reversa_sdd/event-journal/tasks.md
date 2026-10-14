# ingestion/event-journal: задачи реализации

> Сокращения: `PEJ`, `S3S`, `SQL`, `JM` как в [`requirements.md`](requirements.md) и [`design.md`](design.md). Задачи сценариев: [`append-event`](append-event/tasks.md), [`transition-event`](transition-event/tasks.md), [`read-journal`](read-journal/tasks.md), [`raw-payload-store`](raw-payload-store/tasks.md).

## Предпосылки

- [ ] Модуль `ingestion/event-journal` в Maven multi-module, родитель `ingestion`; зависимости `event-schemas`, `postgresql`, `flyway-core`, `flyway-database-postgresql`, `software.amazon.awssdk:s3` (без `netty-nio-client`, `apache-client`), `url-connection-client`; версии из BOM корня (`ingestion/event-journal/pom.xml:17-51`).
- [ ] Порты и DTO из `contracts/event-schemas` доступны (`EventJournal`, `JournalReader`, `CanonicalEventPublisher`, `RawPayloadStore`, `ProcessingStatus`).
- [ ] PostgreSQL 16 (в IT `postgres:16`) и S3-совместимое хранилище (SeaweedFS в тестах/на стенде).

## Задачи

- [ ] **T-01** Миграция `V1__event_journal.sql`: `inbox_event`, `consumer_checkpoint`, `dlq_entry`, индексы `ix_inbox_event_status`, `ix_inbox_event_object`, `uq_dlq_entry_open`.
  - Origin: `SQL:2-48`
  - Критерий готовности: схема воспроизводит [`contracts.md`](contracts.md) §1 дословно; `JournalMigrations.apply` дважды подряд проходит без ошибок.
  - Уверенность: 🟢
- [ ] **T-02** `JournalMigrations.apply`: `Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()`.
  - Origin: `JM:12-14`
  - Критерий готовности: `EventJournalIT.migrationsAreIdempotent` зелёный.
  - Уверенность: 🟢
- [ ] **T-03** `JournalException(String, Throwable) extends RuntimeException`.
  - Origin: `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalException.java:5-9`
  - Критерий готовности: причина `SQLException` сохраняется (`getCause()`).
  - Уверенность: 🟢
- [ ] **T-04** `PostgresEventJournal`: каркас (конструктор от `DataSource`, константы `MAX_REASON_LENGTH=500`, `COLUMNS` из 16 колонок, `map`, `withStatus`, `truncate`, `fingerprint`).
  - Origin: `PEJ:40-51`, `PEJ:356-402`
  - Критерий готовности: маппинг строки в `JournalEntry` полон (15 полей, `payloadRef = null`, если `payload_key IS NULL`); `fingerprint` = первые 12 hex SHA-256 от `source + "\0" + eventId`.
  - Уверенность: 🟢
- [ ] **T-05** `append`/`publish`/`find` — см. [`append-event/tasks.md`](append-event/tasks.md).
  - Origin: `PEJ:53-111`
  - Критерий готовности: AC-A1..AC-A4.
  - Уверенность: 🟢
- [ ] **T-06** `transition`/`toDlq` — см. [`transition-event/tasks.md`](transition-event/tasks.md).
  - Origin: `PEJ:113-160`, `PEJ:320-354`
  - Критерий готовности: AC-T1..AC-T7.
  - Уверенность: 🟢
- [ ] **T-07** `read`/`pending`/`snapshotContents`/`objectsReceivedSince` — см. [`read-journal/tasks.md`](read-journal/tasks.md).
  - Origin: `PEJ:162-237`, `PEJ:274-318`
  - Критерий готовности: AC-R1..AC-R6.
  - Уверенность: 🟢
- [ ] **T-08** `loadCheckpoint`/`saveCheckpoint`.
  - Origin: `PEJ:239-272`
  - Критерий готовности: upsert по `(consumer, source)`; `updated_at` = `Instant.now()`; возвращённый `Checkpoint` содержит то же время, что записано; отсутствие записи даёт `Optional.empty()` (AC-R5).
  - Уверенность: 🟢
- [ ] **T-09** `S3RawPayloadStore` — см. [`raw-payload-store/tasks.md`](raw-payload-store/tasks.md).
  - Origin: `S3S:30-113`
  - Критерий готовности: AC-S1..AC-S4.
  - Уверенность: 🟢
- [ ] **T-10** Обёртка ошибок: любой `SQLException` вне транзакционного блока и внутри него → `JournalException("<операция> failed", e)`; тексты: `append failed`, `find failed`, `transition failed`, `toDlq failed`, `read failed`, `pending failed`, `loadCheckpoint failed`, `saveCheckpoint failed`, `snapshotContents failed`, `objectsReceivedSince failed`.
  - Origin: `PEJ:95`, `109`, `127`, `158`, `205`, `235`, `252`, `270`, `296`, `316`
  - Критерий готовности: AC-E1 (теста в репозитории нет — написать TT-06).
  - Уверенность: 🟢

## Тестовые задачи

- [ ] **TT-01** `S3RawPayloadStoreTest` (unit): `sha256("abc")` = `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`; `put` отвергает `x/../../y`, `a/b`, `..`, пустую строку, `a b` (`IllegalArgumentException`). Существует.
- [ ] **TT-02** `EventJournalIT` (`platform/integration-tests`, Testcontainers `postgres:16`, S3 SeaweedFS): `duplicateEventIsNotInsertedTwice`, `sameEventIdFromAnotherSourceIsAccepted`, `transitionsAndDlqArePersisted`, `repeatedDlqKeepsSingleRecord`, `dlqRejectsBlankCodeAndUnknownEvent`, `finalStateCannotBeRolledBack`, `pendingReturnsReceivedAndMaturedRetryingInJournalOrderWithKeyset`, `readerPagesByKeysetInReceivedOrderAndFilters`, `readerRejectsBadLimit`, `checkpointSurvivesJournalRestart`, `migrationsAreIdempotent`, `rawPayloadRoundTripsAndKeyIsContentAddressed`. Существуют (12 методов).
- [ ] **TT-03** 🟡 Тест `snapshotContents` с `%`/`_` в `syncRunId` на реальном PG (прямого теста в `EventJournalIT` нет; метод косвенно используется `ReconciliationIT` через делегат и `PollerTest` на `InMemoryJournal`).
- [ ] **TT-04** 🟡 Тест `objectsReceivedSince` (границы `>=`, `DISTINCT`) на реальном PG; прямого теста нет.
- [ ] **TT-05** 🟡 Тест, что `toDlq` откатывается целиком, если вставка в `dlq_entry` падает (событие остаётся в прежнем статусе); теста нет.
- [ ] **TT-06** 🟡 Тест оборачивания `SQLException` в `JournalException` (AC-E1); теста нет.
- [ ] **TT-07** 🟡 Тест `ensureBucket` (создание, повтор) и `put` повторный не вызывает `putObject`; `rawPayloadRoundTripsAndKeyIsContentAddressed` проверяет только ref и round-trip.

## Порядок

1. T-01, T-02, T-03 (схема и исключение) → T-04 (каркас) → T-05 → T-06 → T-07/T-08 → T-10.
2. T-09 независим от PostgreSQL, делается параллельно.
3. Тесты: TT-01 сразу после T-09; TT-02 после T-01..T-08; TT-03..TT-07 по приоритету.

## Открытые 🔴

См. [`questions.md`](questions.md): R-1..R-4 закрыты UNLOCKER-281 (`replayed_at`, выход из карантина, удаление `SUPERSEDED`, лимит повторов).
