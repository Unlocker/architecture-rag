# Правила, закреплённые в схеме хранилищ

> Data Master, этап R.3 (UNLOCKER-268). Только то, что обеспечивает сама БД (ограничения, индексы, `ON CONFLICT`, гранты). Правила уровня приложения (машины состояний, версии, авторитетность) — задача Detective (R.4); здесь они помечены ссылкой на место, где БД их не держит. Метки: 🟢 DDL/код · 🟡 выведено · 🔴 пробел.

## 1. Что гарантирует PostgreSQL

| № | Правило | Механизм | Источник |
|---|---|---|---|
| D1 | Событие уникально по `(source, event_id)`; повторный append не создаёт строку и возвращает существующую со статусом `DUPLICATE` | `UNIQUE uq_inbox_event` + `INSERT ... ON CONFLICT (source, event_id) DO NOTHING RETURNING` | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:21`; `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:56-59` 🟢 |
| D2 | У события не больше одной необработанной записи DLQ | частичный `UNIQUE uq_dlq_entry_open (source, event_id) WHERE replayed_at IS NULL` + `ON CONFLICT (source, event_id) WHERE replayed_at IS NULL DO NOTHING` | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:48`; `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:140-142` 🟢 |
| D3 | В DLQ нельзя положить событие, которого нет в журнале | `FOREIGN KEY (source, event_id) REFERENCES inbox_event` | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:44` 🟢 |
| D4 | Один checkpoint на пару `(consumer, source)`; сдвиг — upsert | `PRIMARY KEY (consumer, source)` + `ON CONFLICT (consumer, source) DO UPDATE SET cursor` | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:32`; `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:260-262` 🟢 |
| D5 | Одно событие меняет статус под блокировкой строки | `SELECT ... FOR UPDATE` внутри транзакции перехода (допустимость перехода проверяет код, `ProcessingStatus.canTransitionTo`) | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:325` 🟢 |
| D6 | `gid` для ключа записи создаётся один раз; гонка сходится к одному значению | `PRIMARY KEY (source, source_type, source_id)` + `INSERT ... ON CONFLICT DO NOTHING` + чтение | `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql:9`; `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityMapping.java:27-28` 🟢 |
| D7 | Повторное подтверждение crosswalk идемпотентно | PK по шести колонкам + `ON CONFLICT DO NOTHING` | `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql:25`; `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityMapping.java:135-137` 🟢 |
| D8 | Пара кандидатов/crosswalk хранится в нормализованном порядке | **не в БД**: порядок задаёт приложение (`SourceKeys.compare`), CHECK не ставили, потому что он зависел бы от коллации | `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql:14` (комментарий) 🟢 |
| D9 | Повтор кандидата обновляет `matched`, `score`, `updated_at`; `first_seen_at` и `status` не меняет | `ON CONFLICT (...) DO UPDATE` без этих колонок | `ingestion/identity-resolution/src/main/resources/db/migration/V4__identity_candidate.sql:18-20`; `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java:59-61` 🟢 |
| D10 | Конфликт не удаляется: закрытый остаётся историей, новое расхождение открывает тот же ключ повторно, `opened_at` сохраняется | PK `(gid, property, dissent_*)` + `ON CONFLICT ... DO UPDATE`; `UPDATE ... SET status='RESOLVED'` | `ingestion/identity-resolution/src/main/resources/db/migration/V5__source_conflict.sql:21-22`; `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java:49-65` 🟢 |
| D11 | Признаки и утверждения записи заменяются целиком | `DELETE ... WHERE (source, source_type, source_id)` + `INSERT` в одной транзакции | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java:38-42`; `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java:37-39` 🟢 |
| D12 | Старые конфликты получают вид `PROPERTY` | `ADD COLUMN kind text NOT NULL DEFAULT 'PROPERTY'` | `ingestion/identity-resolution/src/main/resources/db/migration/V7__source_conflict_kind.sql:4` 🟢 |
| D13 | Консоль читает только перечисленные таблицы и не пишет | роль `archrag_console_ro`: `GRANT SELECT` на 6 таблиц, прав записи нет | `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:14-16` 🟢; `INSERT`, `UPDATE`, `DELETE` и чтение `identity_mapping` отвергаются с SQLSTATE `42501` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:182-196`) |
| D14 | Необходимость времени с поясом | все временные колонки `timestamptz` (во всех миграциях) | V1–V7 🟢 |

## 2. Что гарантирует Neo4j

| № | Правило | Механизм | Источник |
|---|---|---|---|
| N1 | У канонического узла один `gid` на метку | 13 UNIQUE-constraints `<label>_gid` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:43-46` 🟢 |
| N2 | Запись источника уникальна по `(source, sourceType, sourceId)` | `source_record_key` (UNIQUE составной; **не** NODE KEY) | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:47-48` 🟢 |
| N3 | `SourceSystem.code`, `SyncRun.runId`, `PendingRelation.key`, `Environment.code` уникальны | четыре UNIQUE-constraints | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:49-55` 🟢 |
| N4 | Повторное применение схемы безопасно | `IF NOT EXISTS` у каждого statement | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:12-14, 45-58` 🟢 |
| N5 | Полнотекстовый поиск по `ITSystem` и `Service` | FULLTEXT `asset_text` (`name`, `description`) | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:57-58` 🟢 |
| N6 | Точный поиск системы по `eam_id` | индекс `it_system_eam_code` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:56` 🟢 |

## 3. Чего БД не гарантирует (правило держит только код)

| Правило | Почему не в БД | Где проверяется |
|---|---|---|
| Обязательность `source`, `sourceType`, `sourceId` у `SourceRecord` | Community: нет NODE KEY и existence-constraints | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:22-23`, `SourceKey` в canonical-model 🟢 |
| Допустимые значения `inbox_event.status`, `identity_candidate.status`, `source_conflict.status`/`kind`, `admin_audit.operation` | нет CHECK и enum-типов в DDL | `ProcessingStatus` в `contracts/event-schemas`, перечисления в `identity-resolution` 🟡 |
| Порядок пары `left < right` | см. D8 | `PostgresIdentityMapping` 🟢 |
| Допустимые концы связей и тип связи | в Neo4j нет схемы связей | `RelationType` (+ `Invariants`) 🟢 |
| `firstSeenAt <= lastSeenAt`, `isCurrent == (deletedAt == null)` | нет проверок на свойства | `Lifecycle` в canonical-model 🟢 |
| Отсутствие записи в `identity_mapping` при чужом конце связи | приложение не создаёт `gid` чужого конца | `UNRESOLVED_ENDPOINT` / `DeferRelation` 🟢 |
| Один `gid` на пару, подтверждённую crosswalk | `approved_crosswalk` не ограничивает `gid` | `CrosswalkConflictException` в `PostgresIdentityMapping` 🟢 |
| Retention: `inbox_event`, `dlq_entry`, `admin_audit`, S3 raw не чистятся | сознательный выбор PoC | `docs/02-data-model.md:335` 🟡 |
| `dlq_entry.replayed_at` | в коде нет записи значения | 🔴 см. [`data-dictionary.md`, §2](data-dictionary.md#2-postgresql-инвентарь-таблиц) |
| `identity_candidate.status` кроме `OPEN` | в коде нет перехода | 🔴 то же |
