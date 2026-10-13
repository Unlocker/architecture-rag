# access/admin-console: внешние контракты

> HTTP API консоли, SQL-чтение, шаблоны графа, лог аудита, конфигурация, привязка к сети. MCP-tools в модуле нет. Секреты не приводятся, только имена переменных.

## 1. HTTP

Порт контейнера 8080 (по умолчанию Spring Boot; `server.port` не задан), на хосте `127.0.0.1:${ARCHRAG_CONSOLE_PORT:-8090}` (`platform/docker/docker-compose.yml:292-293`) 🟢. Все `/api/**` — только `GET` (контроллеры содержат только `@GetMapping`); авторизация `/api/**` не зависит от метода, неподдерживаемый метод отклоняется MVC (Q-07). Токен: `Authorization: Bearer <JWT>`; issuer `ARCHRAG_OIDC_ISSUER_URI`, `aud` = `ARCHRAG_CONSOLE_RESOURCE_URI`, authority `SCOPE_architecture.admin` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:25, 44-45`) 🟢. Ошибки приложения: `{"error":"<код>","message":"<текст>"}` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/ApiExceptionHandler.java:21`) 🟢. Ответы 401/403 формирует Spring Security (заголовок `WWW-Authenticate`, тела `ApiError` нет), `ApiExceptionHandler` их не обрабатывает 🟡.

### 1.1 Эндпоинты

| Метод и путь | Scope | Параметры | Успех | Ошибки | Трассировка |
|--------------|-------|-----------|-------|--------|-------------|
| `GET /api/graph/stats` | `architecture.admin` | — | `200 Stats{nodes[{name,count}], relations[{name,count}], lastUpdatedAt}` | `504 query_timeout` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:56-65` 🟢 |
| `GET /api/graph/search` | `architecture.admin` | `q` (обязателен, ≤200 символов после `strip`), `types` (список: допустимы повторы `types=A&types=B` и запятая `types=A,B`; allowlist `SEARCHABLE_TYPES`), `limit` (1..50, по умолчанию 10) | `200 SearchResponse{items[AssetHit], truncated}` | `400 q must not be blank`; `400 q is longer than 200 characters`; `400 limit must be in 1..50`; `400 Unknown type; allowed: ...`; `400 malformed or missing request parameter`; `504` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:68-102` 🟢 |
| `GET /api/graph/nodes/{gid}` | `architecture.admin` | `gid` — UUID | `200 NodeCard{gid,type,properties,firstSeenAt,lastSeenAt,deletedAt,isCurrent,sources[]}` | `400 gid must be a UUID`; `404 asset not found`; `504` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:105-124` 🟢 |
| `GET /api/graph/nodes/{gid}/neighborhood` | `architecture.admin` | `gid` — UUID; `depth` (1..2, по умолчанию 1); `relTypes` (список: повторы или запятая, подтверждено запросом `relTypes=OWNED_BY,DEPENDS_ON`; allowlist `NEIGHBORHOOD_RELATION_TYPES`, по умолчанию все) | `200 Neighborhood{center,nodes[GraphNode],edges[GraphEdge],truncated}` | `400 depth must be in 1..2`; `400 Unknown relation type; allowed: ...`; `404 asset not found`; `504` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:127-167` 🟢 |
| `GET /api/sync/sources` | `architecture.admin` | — | `200 [SourceStatus]` — всегда 4 элемента `eam, scm, cmdb, deploymap` | `504` (последние прогоны из графа) | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:56-75` 🟢 |
| `GET /api/sync/events` | `architecture.admin` | `source` (код из allowlist), `status` (из 10 статусов), `from`/`to` (ISO-8601 `Instant`, `[from, to)`), `page` (≥0), `size` (1..200, по умолчанию 50) | `200 Page<Event>`, новые сверху | `400 Unknown source; allowed: ...`; `400 Unknown status; allowed: ...`; `400 from must be before to`; `400 page must be >= 0`; `400 size must be in 1..200` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:78-91` 🟢 |
| `GET /api/sync/events/{source}/{eventId}` | `architecture.admin` | `source` — код; `eventId` — без `/` | `200 EventDetail{event, errorReason, dlqEntries[], historyNote}` | `400 Unknown source`; `404 event not found` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:97-110` 🟢 |
| `GET /api/sync/dlq` | `architecture.admin` | `source`, `replayed` (`true`/`false`), `page`, `size` | `200 Page<DlqEntry>`, новые сверху | `400` как выше | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:113-121` 🟢 |
| `GET /api/identity/conflicts` | `architecture.admin` | `status` (`[A-Za-z_]{1,64}`, по умолчанию `OPEN`), `gid` (UUID), `page`, `size` | `200 Page<Conflict>` | `400 status is malformed`; `400 gid must be a UUID` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:30-38` 🟢 |
| `GET /api/identity/candidates` | `architecture.admin` | `status` (по умолчанию `OPEN`), `labelFamily` (`[A-Za-z_]{1,64}`), `page`, `size` | `200 Page<Candidate>` | `400 ... is malformed` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/IdentityController.java:41-49` 🟢 |
| `GET /api/audit` | `architecture.admin` | `operation`, `status` (`[A-Za-z_]{1,64}`), `from`, `to` (`[from, to)` по `started_at`), `page`, `size` | `200 Page<Audit>`, новые сверху | `400 from must be before to`; `400 ... is malformed` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/AuditController.java:25-39` 🟢 |
| `GET /console-config.json` | публично | — | `200 {issuer, clientId, scope}` | — | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/ConsoleConfigController.java:27-30` 🟢 |
| `GET /actuator/health/**` | публично | — | `200` (liveness/readiness) | — | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:42-43`; `access/admin-console/src/main/resources/application.yml:51-58` 🟢 |
| `GET /**` (статика, маршруты SPA) | публично | — | файл из `META-INF/resources` или `index.html` для путей без расширения (кроме `api/**`, `actuator/**`) | `404` для отсутствующих файлов с расширением | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:32-40, 62-70` 🟢 |

Прочие пути: `/actuator/**` (кроме health) — `denyAll` (401 без токена, 403 с токеном); любой не-`GET` вне `/api/**` — `denyAll`; неизвестный `/api/...` с токеном и scope — `404`, не HTML (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:46-52`; тесты `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:121, 129`) 🟢.

### 1.2 Заголовки ответа

`X-Content-Type-Options: nosniff`; `Content-Security-Policy: default-src 'self'; connect-src 'self' <origin issuer>; frame-ancestors 'none'; base-uri 'self'; object-src 'none'` на статике и API; статика с `Cache-Control: no-cache` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:34-37, 61-66`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/config/SpaWebConfiguration.java:37`; тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:146`) 🟢.

### 1.3 Формы ответов

| Тип | Поля | Трассировка |
|-----|------|-------------|
| `Count` | `name`, `count` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphResponses.java:12` 🟢 |
| `AssetHit` | `gid, type, name, score, matchType (EXACT|FULLTEXT), sources[], lastSeenAt` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphResponses.java:32-39` 🟢 |
| `NodeCard` | `gid, type, properties{без gid,isCurrent,firstSeenAt,lastSeenAt,deletedAt}, firstSeenAt, lastSeenAt, deletedAt, isCurrent, sources[] (записи ASSERTS: source, sourceType, sourceId, sourceVersion, fetchedAt, contentHash, active, deletedAt, authority, confidence, conflicts)` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphResponses.java:47-55`; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphController.java:46-47, 221-231` 🟢 |
| `GraphNode` / `GraphEdge` | `gid, type, name, isCurrent, distance` / `type, source, target` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/GraphResponses.java:58, 61` 🟢 |
| `Event` | `source (код), eventId, type, subject, sourceType, sourceId, sourceVersion, correlationId, syncRunId, schemaVersion, status, attempts, errorCode, payloadRef, contentHash, receivedAt, updatedAt` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:15-32` 🟢 |
| `EventDetail` | `event, errorReason, dlqEntries[], historyNote` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:35` 🟢 |
| `DlqEntry` | `id, source, eventId, errorCode, reason, payloadRef, createdAt, replayedAt` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:38-46` 🟢 |
| `SourceStatus` | `source, checkpoints[{consumer,cursor,updatedAt}], lastSyncRun{runId,startedAt,endedAt,status,fetched,applied,failed}, eventsByStatus{10 статусов}, lastProjectedAt, lagSeconds` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:97-116` 🟢 |
| `Conflict` | `gid, property, dissentSource/Type/Id/Value, masterSource/Type/Id/Value, status, openedAt, updatedAt, resolvedAt, kind` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:49-64` 🟢 |
| `Candidate` | `leftSource/Type/Id, rightSource/Type/Id, labelFamily, matched (JSON), score, status, firstSeenAt, updatedAt` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:67-79` 🟢 |
| `Audit` | `id, operation, actor, status, replayId, request, requestTruncated, result, resultTruncated, error, startedAt, finishedAt` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java:82-94` 🟢 |
| `Page<T>` | `items, page, size, total` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Page.java:13` 🟢 |
| `ApiError` | `error, message` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/ApiExceptionHandler.java:21` 🟢 |

Допустимые значения `status` события: `RECEIVED, VALIDATED, NORMALIZED, RESOLVED, PROJECTED, QUARANTINED, RETRYING, SUPERSEDED, DUPLICATE, IGNORED_OLD_VERSION`; `source`: `eam, scm, cmdb, deploymap` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/SyncSources.java:19-33`) 🟢. Поле `lagSeconds` = `max(0, now − max(updated_at) среди PROJECTED)`, `null`, если событий `PROJECTED` нет (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/api/SyncController.java:69-70`) 🟢.

## 2. PostgreSQL (только чтение)

Подключение `archrag.console-pg.*` под ролью `archrag_console_ro`; права — миграция `V6` (`USAGE` на схему `public`, `SELECT` на шесть таблиц) (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:14-16`) 🟢.

| Таблица | Выборки | Фильтры | Трассировка |
|---------|---------|---------|-------------|
| `consumer_checkpoint` | `consumer, source, cursor, updated_at` | — | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:55` 🟢 |
| `inbox_event` | счётчики `GROUP BY source, status`; `max(updated_at)` для `PROJECTED`; список; по ключу `(event_id, source)`; `error_reason` | `source`, `status`, `received_at` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:70, 85, 99-125, 129` 🟢 |
| `dlq_entry` | список и записи события | `source`, `replayed_at IS [NOT] NULL` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:141, 154-167` 🟢 |
| `source_conflict` | список | `status`, `gid` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:172-200` 🟢 |
| `identity_candidate` | список | `status`, `label_family` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:203-228` 🟢 |
| `admin_audit` | список (`request`/`result` как текст до 4000 символов) | `operation`, `status`, `started_at` | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:233-266` 🟢 |

Колонок payload нет; `payload_key` и `payload_hash` отдаются как `payloadRef` и `contentHash` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsoleReadRepository.java:38-40`) 🟢. Все соединения `readOnly` и `SET default_transaction_read_only = on` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/ConsolePgConfiguration.java:28-30`) 🟢.

## 3. Neo4j (только чтение)

Реестр шаблонов консоли: `graph_stats_nodes`, `graph_stats_relations`, `graph_last_update`, `console_neighborhood_nodes`, `console_neighborhood_edges`, `console_latest_sync_runs`, `search_assets`, `get_asset`, `explain_provenance` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/graph/GraphReaderConfiguration.java:44-52`) 🟢. Описание шаблонов, параметров и колонок — [`../graph-query-core/contracts.md`](../graph-query-core/contracts.md) §4. Учётка — `archrag.neo4j.reader.*` (в Community не ограничена на уровне СУБД, П-21) 🟡.

## 4. Конфигурация (env)

| Свойство | Переменная окружения | По умолчанию | Трассировка |
|----------|----------------------|--------------|-------------|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `ARCHRAG_OIDC_ISSUER_URI` | обязательна | `access/admin-console/src/main/resources/application.yml:14` 🟢 |
| `archrag.console.resource-uri` (и `jwt.audiences`) | `ARCHRAG_CONSOLE_RESOURCE_URI` | обязательна; в Compose `urn:archrag:console` | `access/admin-console/src/main/resources/application.yml:15, 20`; `platform/docker/docker-compose.yml:283` 🟢 |
| `archrag.console.client-id` | — | `archrag-admin-console` | `access/admin-console/src/main/resources/application.yml:21` 🟢 |
| `archrag.console.scope` | — | `openid architecture.admin` | `access/admin-console/src/main/resources/application.yml:22` 🟢 |
| `archrag.neo4j.reader.uri` / `username` / `password` | `ARCHRAG_NEO4J_READER_URI` / `_USERNAME` / `_PASSWORD` | обязательны (пароль — Docker secret) | `access/admin-console/src/main/resources/application.yml:24-29` 🟢 |
| `archrag.console-pg.url` / `username` / `password` | `ARCHRAG_CONSOLE_PG_URL` / `_USERNAME` / `_PASSWORD` | `username` = `archrag_console_ro`; остальные обязательны | `access/admin-console/src/main/resources/application.yml:30-34` 🟢 |
| `archrag.query.limits.*` | — | `max-depth 6`, `max-nodes 500`, `max-paths 50`, `timeout 5s`, `max-response-bytes 512KB` | `access/admin-console/src/main/resources/application.yml:35-41` 🟢 |
| `server.shutdown`, `spring.lifecycle.timeout-per-shutdown-phase` | — | `graceful`, `20s` | `access/admin-console/src/main/resources/application.yml:4-5, 43-44` 🟢 |
| `management.endpoints.web.exposure.include` | — | `health` | `access/admin-console/src/main/resources/application.yml:51-54` 🟢 |

В тестах свойства reader и `console-pg` заданы фиктивными, драйвер и пул ленивые (`access/admin-console/src/test/resources/config/application.yml:1-12`) 🟢.

## 5. Лог аудита чтений

Логгер `archrag.audit.console`, сообщение `console read`, structured key-values: `sub`, `endpoint`, `templates`, `rows`, `truncated`, `status`, `durationMs` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java:25, 44-53`) 🟢. Ни токена, ни query-параметров, ни значений path-переменных в записи нет (тест `access/admin-console/src/test/java/io/github/unlocker/archrag/adminconsole/ConsoleApiTest.java:367, 391`) 🟢.

## 6. Сеть и привязка

| Аспект | Значение | Трассировка |
|--------|----------|-------------|
| Публикация порта | `127.0.0.1:${ARCHRAG_CONSOLE_PORT:-8090}:8080`, только loopback | `platform/docker/docker-compose.yml:292-293` 🟢 |
| Сети контейнера | `backend` (PostgreSQL, JWKS Keycloak), `graph` (Neo4j), `console-edge` (только публикация порта); в сети `edge` и за reverse proxy консоли нет | `platform/docker/docker-compose.yml:290-291, 543` 🟢 |
| Привязка внутри приложения | `server.address` не задан: сервис слушает все интерфейсы контейнера | `access/admin-console/src/main/resources/application.yml:43-44` 🔴 (Q-01) |
| Healthcheck | `curl -fs http://127.0.0.1:8080/actuator/health` | `platform/docker/docker-compose.yml:300-305` 🟢 |
| Секреты контейнера | только `neo4j_reader_password`, `console_pg_password` | `platform/docker/docker-compose.yml:287-289` 🟢 |
| Keycloak-клиент | `archrag-admin-console`, PKCE `S256`, scope `architecture.admin` optional | `platform/docker/keycloak/archrag-realm.json:77-90` 🟢 |

## 7. Метрики

Своих метрик нет; открыт только `health` (`access/admin-console/src/main/resources/application.yml:51-58`) 🟢.
