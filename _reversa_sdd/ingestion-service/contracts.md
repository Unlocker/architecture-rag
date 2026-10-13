# ingestion/ingestion-service: внешние контракты

> Admin REST, SQL (`V3`, `V6`), метрики, конфигурация, коды DLQ. Секреты не приводятся, только имена переменных.

## 1. Admin REST (порт `ARCHRAG_ADMIN_PORT`, по умолчанию 8081)

Общее (🟢): все пути `POST`, тело JSON; `/admin/**` требует authority `SCOPE_architecture.admin`; прочие пути `denyAll` (403 с токеном, 401 без) (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:41-43`). Токен: issuer `ARCHRAG_OIDC_ISSUER_URI`, `aud` = `ARCHRAG_ADMIN_RESOURCE_URI`, `azp ∈ archrag.admin.allowed-clients`. Ошибки: `{"error":"<message>"}`.

| Метод и путь | Вход | Успех | Ошибки | Аудит (`admin_audit`) | Трассировка |
|--------------|------|-------|--------|----------------------|-------------|
| `POST /admin/replay` | JSON `ReplayRequest{source, receivedFrom?, receivedTo?, replayId?}`; `source` обязателен, `[A-Za-z0-9:._-]{1,200}`; `receivedFrom < receivedTo` (если оба); `replayId` — UUID (нормализуется `UUID.toString()`), иначе генерируется | `200 ReplayResult{replayId, total, statuses{status:count}}` | `400 source is required`; `400 receivedFrom must be before receivedTo`; `400 replayId must be a UUID`; `409 another admin operation is in progress`; прочее `500` | `REPLAY`, `request={source, receivedFrom, receivedTo}`, `replay_id`, `result={total, statuses}` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:59-82, 140-149` |
| `POST /admin/rebuild?confirm=true` | query `confirm` (по умолчанию `false`); тела нет | `200 ReplayResult` | `400 confirm=true is required`; `409` | `REBUILD`, `request={"confirm":true}`, `replay_id` (новый UUID), `result={total, statuses}` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:84-92` |
| `POST /admin/reconcile/{source}` | path: короткий код или `urn:corp:<код>` | статус и тело ответа адаптера как есть (`202`/`409`), `Content-Type: application/json` | `400 source is invalid`; `404 {"error":"unknown source"}` (нет `control-url`; аудит не пишется); `502 adapter is unavailable`; `409` от замка | `RECONCILE`, `request={"source":"<код>"}`, `replay_id = NULL`, `result={"adapterStatus":<код>}` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:94-115` |
| `POST /admin/crosswalks` | JSON-массив `CrosswalkItem{left{source,sourceType,sourceId}, right{...}, reason}`, 1..1000 элементов | `200 [CrosswalkResult{index, status, gid}]` | `400 items must contain 1..1000 elements`; `409` | запись `CROSSWALK` на каждый элемент | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:31, 117-123` |

Статусы `CrosswalkResult.Status`: `APPLIED` (gid заполнен), `CONFLICT`, `INVALID` (gid = `null`) (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkResult.java:12-19`) 🟢. Ключи `ReplayResult.statuses` — `ProcessingStatus.name()` плюс `SKIPPED_SNAPSHOT_MARKER`, `SKIPPED_NO_RAW` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/ReplayService.java:35-36`) 🟢; карта отсортирована (`TreeMap`).

Ответ `409` неоднозначен 🟡: его отдают и замок (`AdminBusyException`), и адаптер (`ALREADY_RUNNING`, пробрасывается как есть); различать нужно по телу.

Не отражённые в `docs/03-interfaces.md` особенности 🟡: короткая форма `source` принимается регулярным выражением в `replay`, но журнал хранит `urn:corp:<код>` — фильтр `source = ?` по короткому коду вернёт `total = 0` без ошибки (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:63`, `ingestion/event-journal/src/main/java/io/github/unlocker/archrag/eventjournal/PostgresEventJournal.java:167-170`); `reconcile` нормализует источник (`AdapterControlClient.code`), `replay` — нет.

## 2. SQL

### 2.1 `admin_audit` (`ingestion/ingestion-service/src/main/resources/db/migration/V3__admin_audit.sql:2-16`) 🟢

| Колонка | Тип | Ограничение |
|---------|-----|-------------|
| `id` | `bigserial` | PK |
| `operation` | `text` | NOT NULL (`REPLAY`, `REBUILD`, `RECONCILE`, `CROSSWALK`) |
| `actor` | `text` | NOT NULL (`sub` токена) |
| `request` | `jsonb` | NOT NULL |
| `replay_id` | `text` | |
| `status` | `text` | NOT NULL (`STARTED`, `SUCCEEDED`, `FAILED`); `CHECK` нет |
| `result` | `jsonb` | |
| `error` | `text` | простое имя класса исключения, ≤ 200 символов |
| `started_at` | `timestamptz` | NOT NULL |
| `finished_at` | `timestamptz` | |

Индексы: `ix_admin_audit_started (started_at)`, `ix_admin_audit_replay (replay_id)`. Значения `operation` задаются строками в `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:76, 91, 112` и `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/CrosswalkService.java:24` 🟢.

### 2.2 Роль `archrag_console_ro` (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:6-16`) 🟢

Идемпотентное `CREATE ROLE archrag_console_ro LOGIN` (если нет в `pg_roles`); `GRANT USAGE ON SCHEMA public`; `GRANT SELECT ON inbox_event, consumer_checkpoint, dlq_entry, identity_candidate, source_conflict, admin_audit`. Пароль не задаётся (ставит init-скрипт стенда или тест). Миграции требуют у пользователя ingestion права `CREATEROLE`, если роль не создана заранее (комментарий `:3`) 🟢. Таблицы `identity_mapping`, `identity_feature`, `property_assertion` консоли не выданы 🟢.

Читает и пишет в этой схеме также (не в модуле): `inbox_event`, `dlq_entry`, `consumer_checkpoint` — event-journal; `identity_*`, `source_conflict` — identity-resolution (см. [`../event-journal/contracts.md`](../event-journal/contracts.md), [`../identity-resolution/contracts.md`](../identity-resolution/contracts.md)).

## 3. Метрики (Micrometer → OTLP)

| Имя | Тип | Теги | Семантика | Трассировка |
|-----|-----|------|-----------|-------------|
| `archrag.ingestion.events` | counter | `source` ∈ `SourceSystem.code()` ∪ `unknown`; `status` ∈ `ProcessingStatus` ∪ `ERROR` | +1 на обработанное диспетчером событие (итоговый статус; `null` → `ERROR`). Replay/rebuild счётчик не увеличивают | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:44, 118-125`, `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:133-137` 🟢 |
| `archrag.ingestion.sync.lag` | gauge (секунды) | `source` | возраст самого старого `inbox_event` в статусах `RECEIVED, VALIDATED, NORMALIZED, RESOLVED, RETRYING`; 0 если нет | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:45, 62-69` 🟢 |
| `archrag.ingestion.dlq.open` | gauge | `source` | `count(*)` в `dlq_entry` с `replayed_at IS NULL` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:46, 70-71` 🟢 (см. Q-03) |
| `archrag.normalizer.unmapped.values` | counter | `source` (нижний регистр `SourceSystemCode`), `property` | +1 за предупреждение `UNMAPPED_CRITICALITY` (→ `property=criticality`); остальные коды игнорируются | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:47, 50-52, 100-112` 🟢 |

Период обновления gauges: `archrag.metrics.refresh-interval`, по умолчанию `15s` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:131`) 🟢. Экспорт: `management.otlp.metrics.export.enabled=false` по умолчанию, `step: 10s`; `service.name=ingestion` (`ingestion/ingestion-service/src/main/resources/application.yml:53-61`) 🟢.

## 4. Конфигурация (`application.yml`)

| Ключ | Env | Умолчание | Прим. | Трассировка |
|------|-----|-----------|-------|-------------|
| `server.port` | `ARCHRAG_ADMIN_PORT` | `8081` | | `application.yml:4` |
| `spring.datasource.url/username/password` | `ARCHRAG_PG_URL`, `ARCHRAG_PG_USER`, `ARCHRAG_PG_PASSWORD` | нет (обязательны) | пароль — Docker secret | `:8-10` |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `ARCHRAG_OIDC_ISSUER_URI` | нет | | `:15` |
| `...jwt.audiences` | `ARCHRAG_ADMIN_RESOURCE_URI` | нет | без значения сервис не стартует | `:17` |
| `archrag.admin.allowed-clients` | `ARCHRAG_ADMIN_ALLOWED_CLIENTS` | `archrag-demo` | не пусто | `:22`, `AdminProperties.java:13` |
| `archrag.neo4j.uri/username/password` | `ARCHRAG_NEO4J_URI`, `ARCHRAG_NEO4J_USER`, `ARCHRAG_NEO4J_PASSWORD` | нет | учётные данные writer | `:24-26` |
| `archrag.s3.endpoint/access-key/secret-key/bucket` | `ARCHRAG_S3_ENDPOINT`, `ARCHRAG_S3_ACCESS_KEY`, `ARCHRAG_S3_SECRET_KEY`, `ARCHRAG_S3_BUCKET` | нет | | `:28-31` |
| `archrag.adapters.{eam,scm,cmdb,deploymap}.control-url` | `ARCHRAG_EAM_CONTROL_URL`, `ARCHRAG_SCM_CONTROL_URL`, `ARCHRAG_CMDB_CONTROL_URL`, `ARCHRAG_DEPLOYMAP_CONTROL_URL` | пусто = источник не настроен | полный URL `/control/snapshot` | `:33-41` |
| `archrag.dispatcher.enabled` | `ARCHRAG_DISPATCHER_ENABLED` | `true` | | `:44`, `DispatcherProperties.java:18` |
| `archrag.dispatcher.poll-interval` | `ARCHRAG_DISPATCHER_POLL_INTERVAL` | `1s` | > 0 | `:45`, `DispatcherProperties.java:19, 25-27` |
| `archrag.dispatcher.batch-size` | `ARCHRAG_DISPATCHER_BATCH_SIZE` | `100` | 1..1000 | `:46`, `DispatcherProperties.java:20, 31-33` |
| `archrag.dispatcher.retry-delay` | `ARCHRAG_DISPATCHER_RETRY_DELAY` | `30s` | ≥ 0 | `:47`, `DispatcherProperties.java:21, 28-30` |
| `archrag.dispatcher.shutdown-timeout` | `ARCHRAG_DISPATCHER_SHUTDOWN_TIMEOUT` | `30s` | | `:48`, `DispatcherProperties.java:22` |
| `archrag.metrics.refresh-interval` | нет в yml | `15s` | | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionMetrics.java:131` |
| `management.otlp.metrics.export.enabled` / `.step` | `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` (в compose) | `false` / `10s` | | `:53-56` |

(`application.yml` = `ingestion/ingestion-service/src/main/resources/application.yml`.) Переменные `ARCHRAG_ADMIN_ALLOWED_CLIENTS`, `ARCHRAG_DISPATCHER_*` в `.env.example` не описаны (см. `../data-dictionary.md:1313`) 🟡.

## 5. Коды ошибок диспетчера (`dlq_entry.error_code`, `inbox_event.error_code`)

| Код | Когда | Причина (`reason`) | Трассировка |
|-----|-------|--------------------|-------------|
| `NO_RAW_PAYLOAD` | у строки нет `payloadRef`, тип не `snapshot-complete` | `event has no raw payload` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:47-48, 143-145` 🟢 |
| `MARKER_NO_RAW` | нет `payloadRef`, тип `architecture.sync.snapshot-complete.v1` | `event has no raw payload` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:49, 144-145` 🟢 |
| `INVALID_RAW_PAYLOAD` | `StoredEventReader.toEvent` бросил `IllegalArgumentException` | `raw payload is not a valid event` | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/JournalDispatcher.java:47, 150-154` 🟢 |

Остальные коды (`UNSUPPORTED_EVENT_TYPE`, `UNKNOWN_SOURCE`, `GRAPH_*` и др.) ставит `EventProcessor` — см. спецификацию graph-projector.

## 6. Формат raw payload, который читает модуль (`StoredEventReader`)

JSON-объект (иначе `IllegalArgumentException`). Для типа `architecture.sync.snapshot-complete.v1`: `{objectCount >= 0 (целое; иначе поле в payload не попадает и маркер уходит в карантин `SNAPSHOT_OBJECT_COUNT_MISSING`), updatedAt (Instant ISO-8601)}` (`syncRunId` берётся из `entry.sourceId()`, не из raw). Для прочих типов: `updatedAt` обязателен (→ `time`); `operation == "DELETE"` → пустой payload и `completeness` не нужен; иначе `payload` (объект, `null`-поля верхнего уровня отбрасываются), `completeness` обязателен и кладётся в payload как `_completeness`; целые числа читаются как `Long` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/StoredEventReader.java:33-35, 40, 44-115`) 🟢. Источник формата — `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/EventMapper.java` (сверка `StoredEventReaderContractTest`); подробности записи — [`../adapter-core/contracts.md`](../adapter-core/contracts.md).
