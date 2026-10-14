# Словарь хранилищ: Neo4j, PostgreSQL, S3

> Data Master, этап R.3 (UNLOCKER-268). Сводный словарь схем. Свойства узлов и связей, формат payload и Java-типы остаются в [`_reversa_sdd/data-dictionary.md`](../data-dictionary.md) (§A, §E, §Q.1–Q.5); здесь они не дублируются, даны только схема, ограничения, индексы, роли, гранты и ключи S3. ERD — [`erd.md`](erd.md). Метки: 🟢 прочитано в DDL/коде · 🟡 выведено · 🔴 расхождение или пробел.

## 1. Схема Neo4j

Применяет `Neo4jSchema.apply(driver)` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:66-75`): каждый statement выполняется в своей write-транзакции, ошибки не глушатся 🟢. Все 20 объектов создаются с `IF NOT EXISTS` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:41-59`); определение существующего объекта с тем же именем при этом не сверяется (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:25-27`) 🟢. Вызывается при создании бина `Driver` ingestion-сервиса (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:70`) и в `*IT` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:33-34`) 🟢. В `access/*` схему никто не применяет 🟢.

### 1.1. Constraints (18) и индексы (2)

| # | Имя | Вид | Метка и свойства | Строка в `Neo4jSchema.java` |
|---|---|---|---|---|
| 1 | `it_system_gid` | UNIQUE | `ITSystem.gid` | 43-46 |
| 2 | `solution_gid` | UNIQUE | `Solution.gid` | 43-46 |
| 3 | `platform_gid` | UNIQUE | `Platform.gid` | 43-46 |
| 4 | `service_gid` | UNIQUE | `Service.gid` | 43-46 |
| 5 | `repository_gid` | UNIQUE | `Repository.gid` | 43-46 |
| 6 | `team_gid` | UNIQUE | `Team.gid` | 43-46 |
| 7 | `environment_gid` | UNIQUE | `Environment.gid` | 43-46 |
| 8 | `deployment_gid` | UNIQUE | `Deployment.gid` | 43-46 |
| 9 | `compute_instance_gid` | UNIQUE | `ComputeInstance.gid` | 43-46 |
| 10 | `virtual_machine_gid` | UNIQUE | `VirtualMachine.gid` | 43-46 |
| 11 | `physical_server_gid` | UNIQUE | `PhysicalServer.gid` | 43-46 |
| 12 | `namespace_gid` | UNIQUE | `Namespace.gid` | 43-46 |
| 13 | `kubernetes_cluster_gid` | UNIQUE | `KubernetesCluster.gid` | 43-46 |
| 14 | `source_record_key` | UNIQUE (составной) | `SourceRecord(source, sourceType, sourceId)` | 47-48 |
| 15 | `source_system_code` | UNIQUE | `SourceSystem.code` | 49-50 |
| 16 | `sync_run_id` | UNIQUE | `SyncRun.runId` | 51 |
| 17 | `pending_relation_key` | UNIQUE | `PendingRelation.key` | 52-53 |
| 18 | `environment_code` | UNIQUE | `Environment.code` | 54-55 |
| 19 | `it_system_eam_code` | RANGE-индекс | `ITSystem.eamCode` | 56 |
| 20 | `asset_text` | FULLTEXT | `ITSystem\|Service` по `name`, `description` | 57-58 |

Имена `<label>_gid` строятся из `NodeLabel.label()` (`CamelCase` → `snake_case`, `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:36-38`), порядок — по `ordinal` enum (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:43-46`); метки — `NodeLabel.canonical()` (13 из 16: без `SourceSystem`, `SourceRecord`, `SyncRun`, `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/NodeLabel.java:54-62`) 🟢. Тип «RANGE» для `it_system_eam_code` выведен из синтаксиса `CREATE INDEX` без указания вида (по умолчанию в Neo4j 5 это RANGE) 🟡.

### 1.2. Что намеренно не создаётся

NODE KEY, existence-constraints и vector-индекс (Community; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:15-20`, `docs/adr/0004-neo4j-community-constraints.md`). Индекса `deployment_env` нет (ADR 0025). Обязательность `source`/`sourceType`/`sourceId` у `SourceRecord` проверяет приложение, а не БД: узел без `sourceId` в UNIQUE не попадает (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:22-23`) 🟢.

### 1.3. Что проверяют тесты

| Проверка | Тест | Строки |
|---|---|---|
| Точный набор из 18 имён constraints (`containsExactlyInAnyOrder`) и отсутствие дублей после двойного `apply` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java` | 47-58 |
| Составной UNIQUE `SourceRecord`: дубль ключа отклоняется (`Neo.ClientError.Schema.ConstraintValidationFailed`), другой `sourceType` проходит | то же | 60-69 |
| Дубли `Service.gid` и `Environment.code` отклоняются | то же | 71-79 |
| FULLTEXT находит `ITSystem` по имени | то же | 81-89 |
| На каждую каноническую метку есть `gid`-constraint; список имён зафиксирован; все statements с `IF NOT EXISTS`; нет NODE KEY/`IS NOT NULL`/VECTOR; имена уникальны | `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchemaTest.java` | 15-75 |

Пробелы покрытия 🟡: IT проверяет точный состав только constraints; для индексов проверено лишь наличие `asset_text` и `it_system_eam_code` по одному разу (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:55-56`), лишний индекс тест не поймает. Реальный отказ дубля есть только для `SourceRecord`, `Service`, `Environment`; остальные `gid`-constraints проверены строкой запроса в `ingestion/graph-projector/src/test/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchemaTest.java:15-23`.

### 1.4. Запросы без индекса 🟡

Поиск по свойству, которого нет ни в одном индексе, идёт сканом метки. Найдено в коде:

| Запрос | Файл:строка | Индекс |
|---|---|---|
| `(:SourceRecord)-[:ASSERTS]->(n:Repository) WHERE n.url = $url` (привязка `DEPLOYED_FROM` по нормализованному URL) | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java:173` | нет (на `Repository.url`) |
| `(:SourceRecord)-[:ASSERTS]->(n:ITSystem) WHERE n.eamCode = $code` | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/GraphProjector.java:190` | есть: `it_system_eam_code` |
| `MATCH (n:ITSystem) WHERE n.eamCode = $query` (шаблон `search_assets`) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/templates/AssetTemplates.java:48` | есть: `it_system_eam_code` |

Для PoC объёмов это допустимо; индекс на `Repository.url` стоит заводить при росте числа репозиториев (передать в Detective/Architect как кандидата в техдолг).

### 1.5. Не-схемные операции над графом

- `TemporalRelationMigration.migrate` вызывается сразу после `Neo4jSchema.apply` при создании `Driver`: проставляет `validFrom` связям `HAS_DEPLOYMENT`, `IN_ENVIRONMENT`, `DECOMPOSED_INTO`, `IMPLEMENTED_IN`, у которых его нет (ADR 0028). Идемпотентна (`WHERE rel.validFrom IS NULL`) 🟢 — `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:72`, `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/TemporalRelationMigration.java:25-30, 44-60`.
- `rebuild` стирает все узлы пакетами: `MATCH (n) CALL { WITH n DETACH DELETE n } IN TRANSACTIONS OF 10000 ROWS`; constraints и индексы остаются (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/RebuildService.java:18`) 🟢.
- Версионирования схемы Neo4j нет: нет таблицы `schema_version`, нет `SHOW CONSTRAINTS` сверки при старте. Смена определения требует смены имени (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:25-27`) 🟡.

## 2. PostgreSQL: инвентарь таблиц

Схема `public`, JDBC без ORM (`docs/02-data-model.md:214`), время `timestamptz` UTC. Колонки, типы и ограничения — в [`erd.md`, §2](erd.md#2-postgresql) и в §Q.1/Q.3/Q.5 основного словаря; здесь — владелец, писатель, читатель и грант.

| Таблица | Миграция | Модуль-владелец DDL | PK / UNIQUE | Индексы | Пишет | Читает |
|---|---|---|---|---|---|---|
| `inbox_event` | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:2-22` | `ingestion/event-journal` | `UNIQUE (source, event_id)` (`uq_inbox_event`), PK нет | `ix_inbox_event_status (status, received_at)`, `ix_inbox_event_object (source, source_type, source_id)` | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:56` (insert), `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:339` (update) | `PostgresEventJournal`, `ConsoleReadRepository` |
| `consumer_checkpoint` | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:27-33` | `ingestion/event-journal` | PK `(consumer, source)` | — | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:260` (upsert) | журнал, консоль |
| `dlq_entry` | `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql:35-45`, `:48` | `ingestion/event-journal` | PK `id`; FK `(source, event_id) → inbox_event` | `uq_dlq_entry_open (source, event_id) WHERE replayed_at IS NULL` | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:140` | журнал, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:71`, консоль |
| `identity_mapping` | `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql:3-10` | `ingestion/identity-resolution` | PK `(source, source_type, source_id)` | `ix_identity_mapping_gid (gid)` | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityMapping.java:27` | `PostgresIdentityMapping` |
| `approved_crosswalk` | `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql:15-26` | `ingestion/identity-resolution` | PK по шести колонкам | — | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityMapping.java:135` | `PostgresIdentityMapping` |
| `admin_audit` | `ingestion/ingestion-service/src/main/resources/db/migration/V3__admin_audit.sql:2-13` | `ingestion/ingestion-service` | PK `id` | `ix_admin_audit_started (started_at)`, `ix_admin_audit_replay (replay_id)` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminAudit.java:34` (insert), `:53` (update) | консоль |
| `identity_feature` | `ingestion/identity-resolution/src/main/resources/db/migration/V4__identity_candidate.sql:5-14` | `ingestion/identity-resolution` | PK `(source, source_type, source_id, feature)` | `ix_identity_feature_lookup (feature, label_family, value)` (V4 `:16`) | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java:38-42` | то же |
| `identity_candidate` | `ingestion/identity-resolution/src/main/resources/db/migration/V4__identity_candidate.sql:21-35` | `ingestion/identity-resolution` | PK по шести колонкам | `ix_identity_candidate_right (right_source, right_type, right_id)` (`:37`) | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java:55` | то же, консоль |
| `property_assertion` | `ingestion/identity-resolution/src/main/resources/db/migration/V5__source_conflict.sql:6-17` | `ingestion/identity-resolution` | PK `(source, source_type, source_id, property)` | `ix_property_assertion_gid (gid, property)` (`:19`) | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java:37,39` | то же |
| `source_conflict` | `ingestion/identity-resolution/src/main/resources/db/migration/V5__source_conflict.sql:23-39`; `ingestion/identity-resolution/src/main/resources/db/migration/V7__source_conflict_kind.sql:4` добавляет `kind` | `ingestion/identity-resolution` | PK `(gid, property, dissent_source, dissent_type, dissent_id)` | — | `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java:49,62,65` | то же, консоль |

Полные пути миграций: `ingestion/event-journal/src/main/resources/db/migration/V1__event_journal.sql`, `ingestion/identity-resolution/src/main/resources/db/migration/V2__identity_mapping.sql` (V4, V5, V7 — там же), `ingestion/ingestion-service/src/main/resources/db/migration/V3__admin_audit.sql` (V6 — там же). Классы лежат в соответствующих модулях в `src/main/java/io/github/unlocker/archrag/{eventjournal,identityresolution,ingestionservice}/`; поиск по `INSERT/UPDATE/DELETE` выполнен по `ingestion`, `access`, `adapters` (в `access/` и `adapters/` записей в эти таблицы нет) 🟢.

Свойства данных 🟢/🟡:

- `DELETE` встречается только для `identity_feature` и `property_assertion` (замена набора записи целиком, `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java:39`, `ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresSourceConflicts.java:37`). Остальные таблицы только растут: `inbox_event`, `dlq_entry`, `admin_audit`, `identity_candidate`, `source_conflict` не чистятся, retention-политики нет 🟡.
- CHECK-ограничение только одно — `ck_identity_candidate_status` (V8); в остальных таблицах CHECK нет. Допустимость `status` (`inbox_event`, `identity_candidate`, `source_conflict`), `kind` (`source_conflict`) и `operation` (`admin_audit`) держит код, не БД 🟡.
- Триггеров, функций, процедур и представлений в миграциях нет (единственный блок — `DO $$` в V6, см. [`procedures.md`](procedures.md)) 🟢.
- `dlq_entry.replayed_at` читается и фильтруется (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:158`, `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:142`), но ни один код не пишет в неё значение: `UPDATE dlq_entry` в репозитории нет 🔴 (подтверждено поиском по `ingestion`, `access`, `adapters`; в тестах значение вставляется вручную, `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IngestionMetricsIT.java:86`).
- `identity_candidate.status`: `'OPEN'` по умолчанию, `'RESOLVED'` ставит `PostgresCandidateApprovals.approve` вместе с `resolved_at`/`resolved_by`; CHECK `ck_identity_candidate_status` (V8) 🟢 [UNLOCKER-282: реализовано]. Прежняя ссылка: (`ingestion/identity-resolution/src/main/java/io/github/unlocker/archrag/identityresolution/PostgresIdentityCandidates.java:55-61`).

## 3. Flyway: порядок применения и владельцы версий

**Единый поток версий.** Все `db/migration` лежат под одним classpath-путём `classpath:db/migration`, а Flyway запускается из одного места — `JournalMigrations.apply` (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java:12-14`) 🟢. Версии между тремя модулями сквозные и не пересекаются:

| Версия | Файл | Модуль | Зависит от таблиц |
|---|---|---|---|
| V1 | `V1__event_journal.sql` | `ingestion/event-journal` | — |
| V2 | `V2__identity_mapping.sql` | `ingestion/identity-resolution` | — |
| V3 | `V3__admin_audit.sql` | `ingestion/ingestion-service` | — |
| V4 | `V4__identity_candidate.sql` | `ingestion/identity-resolution` | — |
| V5 | `V5__source_conflict.sql` | `ingestion/identity-resolution` | — |
| V6 | `V6__console_readonly_role.sql` | `ingestion/ingestion-service` | V1 (`inbox_event`, `consumer_checkpoint`, `dlq_entry`), V3 (`admin_audit`), V4 (`identity_candidate`), V5 (`source_conflict`) |
| V7 | `V7__source_conflict_kind.sql` | `ingestion/identity-resolution` | V5 (`ALTER TABLE source_conflict`) |
| V8 | `V8__identity_candidate_resolution.sql` | `ingestion/identity-resolution` | V4 (`ALTER TABLE identity_candidate`: `resolved_at`, `resolved_by`, CHECK статуса) |

**Кто и когда применяет.**

- В боевом процессе — единственный вызов в `main`-коде: бин `PostgresEventJournal` ingestion-сервиса (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:45-49`), до бинов `IdentityMapping`, `IdentityCandidates`, `SourceConflicts`, которые зависят от него (`:51-65`) 🟢. Flyway из Spring Boot не подключён (Javadoc `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:37-39`); приёмочные IT дополнительно исключают `FlywayAutoConfiguration` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:196`) 🟢. Адаптеры в compose стартуют только после здорового `ingestion`, потому что миграции делает он (`platform/docker/docker-compose.yml:35-36`) 🟢.
- Другие приложения (`access/mcp-server`, `access/admin-console`, адаптеры, `platform/demo-launcher`) миграции не применяют. `admin-console` документирован так: нет `flyway` и `datasource` в `application.yml` (`access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleStartupTest.java:49`) 🟢.
- В `*IT` миграции применяет `JournalMigrations.apply(ds)` — например `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EventJournalIT.java:45`; двойной вызов проверен на идемпотентность (`:238-239`) 🟢.

**Что происходит при запуске модуля по отдельности** 🟡 (выведено из набора файлов на classpath, запуск не воспроизводился):

- В classpath `ingestion/event-journal` попадает только V1. На пустой БД создаются три таблицы; V2–V7 не применяются. Для тестов этого модуля достаточно.
- В classpath `ingestion/identity-resolution` — V2, V4, V5, V7 (зависимости от V1 нет). `V7` ссылается на V5 из того же модуля, поэтому порядок соблюдается. Прикладной код `PostgresIdentityMapping` и другие репозитории не требуют таблиц V1.
- `V6` лежит в `ingestion-service` и ссылается на таблицы из трёх модулей. Применить его можно только когда в classpath есть V1, V3, V4, V5, то есть в `ingestion-service` и в `platform/integration-tests` (зависит от всех модулей). Это единственная миграция, которая ломается при неполном наборе: `GRANT SELECT ... ON inbox_event` на отсутствующую таблицу вызывает ошибку 🟡.
- Если БД уже мигрирована до V7, а модуль запущен с неполным набором (например, только V1), Flyway по умолчанию не считает «будущие» версии ошибкой (`ignoreMigrationPatterns = *:future`), но при дырке между локальными версиями (например, есть V1 и V3, нет V2) на непустой БД `validate` сообщит об отсутствующей миграции 🟡. Конфигурации `outOfOrder`, `validateOnMigrate`, `baselineOnMigrate` в коде нет (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/JournalMigrations.java:13` использует умолчания) 🟢.
- Новая миграция в любом из трёх модулей обязана взять следующий свободный номер (V8): совпадение номера из двух модулей Flyway обнаружит только в рантайме при запуске `ingestion-service` (ошибка «Found more than one migration with version»). Автоматической проверки уникальности в сборке нет 🔴 (риск, не дефект).

**Миграции V4, V5, V7 живут в `identity-resolution`, а грант на их таблицы — в V6 из `ingestion-service`.** Следствие: если добавить таблицу консоли в `identity-resolution` и забыть про грант, консоль получит `42501`; документировано в основном словаре §Q.5.2 (🟡 «любое новое чтение консоли требует нового GRANT»).

**V7 поверх V5.** `ALTER TABLE source_conflict ADD COLUMN kind text NOT NULL DEFAULT 'PROPERTY'` (`ingestion/identity-resolution/src/main/resources/db/migration/V7__source_conflict_kind.sql:4`). PK не меняется, старые строки получают `PROPERTY`. Грант V6 — табличный `GRANT SELECT ON ... source_conflict` без списка колонок (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:15-16`), поэтому колонка `kind` читаема ролью без доп. гранта (в PostgreSQL табличный `SELECT` покрывает колонки, добавленные позже) 🟢. Хотя V6 применяется до V7, на уже работающей БД порядок не важен по той же причине.

## 4. Роли и гранты

| Роль | Хранилище | Где создаётся | Права | Кто использует |
|---|---|---|---|---|
| `archrag_console_ro` | PostgreSQL | миграция V6: `CREATE ROLE ... LOGIN` под условием `NOT EXISTS` (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:6-12`) | `USAGE` на схему `public`; `SELECT` на `inbox_event`, `consumer_checkpoint`, `dlq_entry`, `identity_candidate`, `source_conflict`, `admin_audit` (`:14-16`). Нет доступа к `identity_mapping`, `approved_crosswalk`, `identity_feature`, `property_assertion`; записи нигде не разрешены | `access/admin-console` (`--archrag.console-pg.username=archrag_console_ro` в `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:141`; в compose — `ARCHRAG_CONSOLE_PG_*`, `platform/docker/docker-compose.yml:272-295`) |
| пароль `archrag_console_ro` | PostgreSQL | не в миграции: `ALTER ROLE ... PASSWORD :'p'` в одноразовом сервисе `console-pg-init` (`platform/docker/postgres/init-console-ro.sh:9`, сервис — `platform/docker/docker-compose.yml:256-270`); в тестах — `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:60` | — | стенд, `*IT` |
| `archrag_reader` | **Neo4j** (пользователь, не роль PostgreSQL) | `CREATE USER archrag_reader IF NOT EXISTS ...` скриптом `platform/docker/neo4j/init-reader.sh:13-14`, сервис `neo4j-init` (`platform/docker/docker-compose.yml:89-100`); пароль обновляется при смене (`platform/docker/neo4j/init-reader.sh:18-21`) | Прав в БД не назначается: в Community нет ролей, пользователь получает полный доступ. Read-only обеспечивает приложение — только `executeRead` в `access/graph-query-core` (CLAUDE.md, `docs/adr/0005-readonly-mcp-by-application.md:14`) | `access/mcp-server` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:36-39`) и `access/admin-console` (`platform/docker/docker-compose.yml:285`) |
| `neo4j` (writer) | Neo4j | встроенный | полный доступ | `ingestion-service` (бин `Driver`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceConfiguration.java:67-74`), `neo4j-init` |
| пользователь миграций PostgreSQL (`POSTGRES_USER`) | PostgreSQL | образ `postgres:16` | нужно право `CREATEROLE` для V6 (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:3-4`); на стенде это суперпользователь контейнера (`platform/docker/docker-compose.yml:102-107`) | `ingestion-service` |

Замечания:

- 🔴 Имя `archrag_reader` в задаче названо «ролью», но в коде это учётная запись Neo4j без привилегий. Защита от записи через неё держится только на приложении; сама учётка писать может (ADR 0005 переносит запрет на уровне БД в production gap list). В `docs/02-data-model.md` про этих пользователей нет ни слова.
- 🟡 Отсутствие `GRANT ... ON ALL TABLES` / `ALTER DEFAULT PRIVILEGES`: новые таблицы консоль не получает автоматически — это сознательный allowlist.
- 🟢 Граница «консоль не видит raw payload»: в PostgreSQL его нет, только `payload_key`/`payload_hash`; консоль не получает credentials S3 (`platform/docker/console-smoke.sh:114-124`).
- 🟡 Идемпотентность создания роли V6 (`IF NOT EXISTS`) нужна, потому что роли в PostgreSQL общекластерные, а миграция выполняется внутри одной БД; повторный запуск на другой БД того же кластера роль не пересоздаёт, но гранты для новой БД выдаёт.

## 5. S3: раскладка ключей

| Параметр | Значение | Источник |
|---|---|---|
| Бакет | `ARCHRAG_S3_BUCKET` (на стенде `S3_BUCKET=archrag-raw`); создаётся при старте, если нет (`ensureBucket`) | `platform/docker/.env.example:26`, `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:60-66` |
| Ключ объекта | `raw/<source>/<sha256 hex>` | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:74` |
| `<source>` | `[A-Za-z0-9:._-]{1,200}`, не только из точек; иначе `IllegalArgumentException` | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:33, 70-72` |
| Содержимое | UTF-8 JSON: изменение источника или маркер `snapshot-complete` или raw tombstone | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/InboxWriter.java:37, 46`; `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/Reconciler.java:170` |
| Метаданные объекта | не задаются | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:76` |
| Идемпотентность | если объект с таким ключом есть (`HeadObject`), `PutObject` не вызывается | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:75-77, 92-99` |
| Целостность | при `get` пересчитывается SHA-256 и сверяется с `RawPayloadRef.contentHash`; расхождение — `IllegalStateException` | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:82-89` |
| Клиент | path-style, регион `us-east-1`, `RequestChecksumCalculation.WHEN_REQUIRED` | `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:39-51` |
| Конфигурация | `archrag.s3.{endpoint,access-key,secret-key,bucket}`; `toString` без ключей | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/S3Properties.java:6-12` |

**Связь с журналом.** `inbox_event.payload_key` хранит ключ, `payload_hash` — SHA-256 (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:56-59`); читают через `RawPayloadRef(key, hash)` из строки журнала (`:373`), в `JournalDispatcher` — `rawStore.get(ref)` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:142-148`). `dlq_entry.payload_key` — копия ссылки. В графе `SourceRecord.contentHash` равен тому же хэшу (основной словарь §Q.4.1). Одно содержимое под одним `<source>` даёт один объект независимо от числа событий 🟢.

**Сверка с ADR 0007.** ADR фиксирует S3-совместимое хранилище и SeaweedFS (`docs/adr/0007-s3-raw-storage-seaweedfs.md:14`), формат ключа не описывает; ADR не расходится с кодом 🟢. Бакет резервных копий (`BACKUP_BUCKET=archrag-backups`, `platform/docker/.env.example:48`) отделён от raw-бакета и описан в §W.1.7 основного словаря.

**Расхождения и риски**

- 🔴 **Два значения `<source>` в одном бакете.** Адаптеры кладут raw под кодом системы: `rawStore.put(system.code(), ...)` → `raw/eam/<sha>` (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/InboxWriter.java:37`, коды `eam|scm|cmdb|deploymap` — `contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/SourceSystem.java:5-9`). `Reconciler` кладёт raw tombstone под `source` события, то есть под URN: `raw/urn:corp:eam/<sha>` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/Reconciler.java:157, 170`; значение `urn:corp:<код>` — `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:97`, `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/package-info.java:9-10`). Тест фиксирует только URN-вариант (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/EventJournalIT.java:253`). Чтение не страдает, потому что идёт по `payload_key` из журнала, но префиксы расходятся, и `docs/02-data-model.md:327-329` (`raw/<source>/<sha256 hex>`) не говорит, какое именно `<source>`. Следствие: один и тот же raw-контент от адаптера и от reconcile не дедуплицируется между префиксами. Не чиню.
- 🟡 Retention, жизненный цикл и удаление объектов не реализованы; объекты не удаляются при `rebuild` (он трогает только Neo4j) — `docs/02-data-model.md:334-335`.
- 🟡 Содержимое raw — недоверенный текст источника (CLAUDE.md, ловушка 11); в журнале и логах хранятся только ключ и hash (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/S3RawPayloadStore.java:86` не пишет содержимое в сообщение об ошибке) 🟢.

## 6. Сквозная карта ключей

| Сущность | Neo4j | PostgreSQL | S3 |
|---|---|---|---|
| Идентичность канонического узла | `<Label>.gid` (строка UUID) | `identity_mapping.gid` (`uuid`) | — |
| Запись источника | `SourceRecord(source, sourceType, sourceId)`; `source` = `SourceSystemCode.name()` (`EAM`, …) | `identity_mapping`, `identity_feature`, `property_assertion` — `(source, source_type, source_id)` с кодом `SourceSystemCode`; `inbox_event.source` — URN `urn:corp:<код>` | — |
| Событие | — | `inbox_event(source, event_id)` | — |
| Содержимое события | `SourceRecord.contentHash` | `inbox_event.payload_hash` | ключ `raw/<source>/<hash>` |
| Прогон адаптера | `SyncRun.runId` | `inbox_event.sync_run_id` | — |
| Курсор обработки | — | `consumer_checkpoint(consumer, source)` | — |

Преобразование URN → `SourceSystemCode` делает `EventProcessor` (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/EventProcessor.java:97, 202`). Сравнение `inbox_event.source` и `identity_mapping.source` напрямую даст пустой результат: значения разные 🟡.

## 7. Объекты БД, которых нет

Для полноты (требование skill Data Master): триггеров, хранимых процедур, функций, представлений, материализованных представлений, секвенсов вручную, полиморфных связей и таблиц-связок «многие-ко-многим» в миграциях нет. `bigserial` у `dlq_entry.id` и `admin_audit.id` создаёт неявные последовательности 🟢.

## 8. Открытые вопросы для следующих этапов

| Этап | Вопрос |
|---|---|
| R.4 Detective | Почему `dlq_entry.replayed_at` не заполняется (ADR или недоделка). Жизненный цикл `identity_candidate.status`. |
| R.4 Detective | Правило «одна необработанная запись DLQ на событие» и частичный индекс — какая бизнес-инвариант за этим стоит (`ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:140-142`). |
| R.5 Architect | Двойной префикс `<source>` в S3 (раздел 5), отсутствие индекса `Repository.url` (раздел 1.4), единый Flyway-поток из трёх модулей (раздел 3) — в карту техдолга. |

## 9. Расхождения с `docs/02-data-model.md`

Сверка выполнена построчно по `docs/02-data-model.md` (362 строки). Исходники и документ не правились.

| № | Место в документе | Что написано | Что в коде / в том же документе | Метка |
|---|---|---|---|---|
| 1 | `docs/02-data-model.md:34` | «`V1` в `event-journal`, `V2` в `identity-resolution`, `V3` в `ingestion-service`. Миграций `V4+` в `develop` нет» | Есть V4, V5, V6, V7 (`ingestion/identity-resolution/src/main/resources/db/migration/V4__identity_candidate.sql`, `V5__source_conflict.sql`, `V7__source_conflict_kind.sql`; `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql`). Тот же документ описывает V5–V7 в `:307, :319` | 🔴 |
| 2 | `docs/02-data-model.md:214` | Миграции: V1, V2, V3 | Список неполный по той же причине | 🔴 |
| 3 | `docs/02-data-model.md:309` | «Не реализовано … Таблиц `identity_candidate` и `conflict` нет, миграций для них нет» | `identity_candidate` (V4), `identity_feature` (V4), `property_assertion` и `source_conflict` (V5) есть и используются; тот же документ в `:307` и `:360` («таблицы … есть») противоречит `:309` | 🔴 |
| 4 | `docs/02-data-model.md:14` (таблица хранилищ, PostgreSQL) | перечислены inbox, checkpoints, DLQ, mapping и crosswalk, аудит | не названы `identity_feature`, `identity_candidate`, `property_assertion`, `source_conflict` | 🟡 |
| 5 | `docs/02-data-model.md:139-157` (раздел 2.5) | таблица constraints содержит 16 `gid`-/ключевых строк и 2 индекса | В коде и в `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:51-54` constraints 18: в таблице нет `solution_gid` и `platform_gid` (метки `Solution`, `Platform` — `contracts/canonical-model/src/main/java/io/github/unlocker/archrag/canonicalmodel/node/NodeLabel.java:6-7`) | 🔴 |
| 6 | `docs/02-data-model.md:160` | «Состав сверяется с `SHOW CONSTRAINTS` / `SHOW INDEXES` тестом `Neo4jSchemaIT`» | Точно сверяются только constraints; индексы — только два имени (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java:55-56`) | 🟡 |
| 7 | `docs/02-data-model.md:160` | путь `platform/integration-tests/.../Neo4jSchemaIT.java` (сокращён) | полный путь `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/Neo4jSchemaIT.java` | 🟡 |
| 8 | `docs/02-data-model.md:327-329` | ключ `raw/<source>/<sha256 hex>` для адаптеров, `Reconciler` и маркеров | у адаптеров `<source>` — код (`eam`), у `Reconciler` — URN (`urn:corp:eam`) | 🔴 |
| 9 | документ целиком | роли PostgreSQL описаны в `:319`; пользователь Neo4j `archrag_reader` отсутствует | `archrag_reader` создаётся скриптом `platform/docker/neo4j/init-reader.sh:13-14`, упоминается в `docs/03-interfaces.md`, ADR 0005 и CLAUDE.md | 🟡 |
| 10 | `docs/02-data-model.md:160` | «`IF NOT EXISTS` молча пропускает constraint с тем же именем, но другим определением» | подтверждено кодом (`ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:25-27`) | 🟢 |
| 11 | `docs/02-data-model.md:156` | `environment_code` «совпадает с видением от 2026-10-04» | `ingestion/graph-projector/src/main/java/io/github/unlocker/archrag/graphprojector/schema/Neo4jSchema.java:54-55` совпадает с таблицей | 🟢 |

Остальные пункты `docs/02-data-model.md` §2.5, §5.1–5.4, §5.6, §6 (колонки `inbox_event`, `consumer_checkpoint`, `dlq_entry`, `identity_mapping`, `approved_crosswalk`, `admin_audit`, права V6, формат ключа S3, `contentHash`) сверены с DDL и кодом и совпадают 🟢.
