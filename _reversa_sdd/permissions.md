# Матрица прав и доступа

> Detective, этап R.4 (UNLOCKER-269). Источник — код и конфигурация ветки `epic/UNLOCKER-260`. Права в Arch RAG складываются из четырёх слоёв: OAuth2-scope'ы на HTTP-входах, audience и `azp` токена, учётные записи хранилищ (Neo4j, PostgreSQL, S3) и сегментация сетей Docker Compose. RBAC по пользователям в PoC нет (ADR 0004: контроль доступа в приложении, Community без ролей). Решения уже зафиксированы в ADR 0005, 0013, 0017, 0018, 0026; здесь — **как это реализовано и где граница**. Шкала: 🟢 прочитано в коде · 🟡 выведено · 🔴 пробел. Расхождения — в [`detective-gaps.md`](detective-gaps.md).

## 1. Субъекты

| Субъект | Что это | Откуда токен / учётка |
|---|---|---|
| Агент / MCP-клиент | клиент `archrag-demo` (service account), scope `architecture.read` по умолчанию | `platform/docker/keycloak/archrag-realm.json:57-61` 🟢 |
| Оператор admin REST | тот же клиент `archrag-demo`, запрашивает optional-scope `architecture.admin` | `archrag-realm.json:60-61` 🟢 |
| Пользователь консоли | публичный клиент `archrag-admin-console` (Authorization Code + PKCE), оба scope optional | `archrag-realm.json:88-90` 🟢 |
| Адаптеры источников | не OIDC: запись в inbox/S3 через учётные данные PostgreSQL/S3, вход webhook — подпись HMAC | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookHandler.java:56-65` 🟢 |
| Ingestion-сервис | единственный писатель Neo4j (`neo4j`) и PostgreSQL (владелец схемы) | `platform/docker/README.md:111`; `platform/docker/docker-compose.yml:178-208` 🟢 |

## 2. OAuth2 scope'ы

| Scope | Кому нужен | Что открывает | Audience в токене |
|---|---|---|---|
| `architecture.read` | MCP-клиент | `tools/call` всех шести tools | `ARCHRAG_MCP_RESOURCE_URI` (маппер `mcp-audience` на клиенте `archrag-demo`) |
| `architecture.admin` | оператор, консоль | `/admin/**` ingestion; `/api/**` консоли | `ARCHRAG_ADMIN_RESOURCE_URI` (маппер на самом scope), у консоли дополнительно `ARCHRAG_CONSOLE_RESOURCE_URI` |

`archrag-realm.json:5-20, 62-72, 91-102` 🟢. Audience проверяется каждым сервисом (`spring.security.oauth2.resourceserver.jwt.audiences`): `mcp-server` — `archrag.mcp.security.resource-uri` (`access/mcp-server/src/main/resources/application.yml:18-20, 25`), `admin-console` — `archrag.console.resource-uri` (`access/admin-console/src/main/resources/application.yml:15-19`), ingestion — `ARCHRAG_ADMIN_RESOURCE_URI` (`ingestion/ingestion-service/src/main/resources/application.yml:17`) 🟢.

## 3. MCP-сервер (`access/mcp-server`)

### 3.1. Матрица tool → scope

| Tool | Требуемый scope | Что читает | Запись |
|---|---|---|---|
| `ping` | `architecture.read` | — | нет |
| `search_assets` | `architecture.read` | точный и полнотекстовый поиск `ITSystem`/`Service` | нет |
| `get_asset` | `architecture.read` | карточка, источники, связи 1 уровня | нет |
| `find_runtime_footprint` | `architecture.read` | сервис → развёртывание → compute | нет |
| `trace_dependencies` (`mode=trace|impact`) | `architecture.read` | обход глубиной ≤ 6 | нет |
| `explain_provenance` | `architecture.read` | происхождение факта | нет |

`access/mcp-server/src/main/resources/application.yml:28-34` 🟢. Все шесть требуют один scope: градации «обычный/привилегированный tool» нет 🟡.

### 3.2. Как проверяется

- Цепочка Spring Security: без сессий и CSRF; `/actuator/health/**` и `/.well-known/oauth-protected-resource/**` публичны, всё остальное `authenticated()` (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/McpSecurityConfiguration.java:37-47`) 🟢.
- Фильтр `ToolScopeFilter` стоит после `AuthorizationFilter` и **читает тело `POST /mcp`** (лимит `max-request-bytes`, 1 МБ) (`McpSecurityConfiguration.java:61`; `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/security/ToolScopeFilter.java:60-80`) 🟢. Для `tools/call` берётся имя из `params.name`; нет записи в `tool-scopes` → отказ `DENIED_UNKNOWN_TOOL`; нет `SCOPE_<scope>` → `DENIED_SCOPE` и `WWW-Authenticate: Bearer error="insufficient_scope", scope="…"` (`ToolScopeFilter.java:82-95, 124-130`) 🟢. То есть **новый tool без записи в `tool-scopes` недоступен** (fail-closed).
- 🟡 Скоуп проверяется **только** для метода `tools/call`: `initialize`, `tools/list` и прочие методы требуют лишь валидный токен с audience MCP (`ToolScopeFilter.java:82-95`). Любой держатель такого токена видит список и описания tools.
- Метаданные ресурса (RFC 9728): `resource`, `authorization_servers = issuer`, `scopes_supported` собирается из значений `tool-scopes` (`McpSecurityConfiguration.java:33, 48-60`) 🟢.
- Транспорт — Streamable HTTP в режиме `STATELESS` (без сессий) по ADR 0017 (`access/mcp-server/src/main/resources/application.yml:7-12`) 🟢. Режим зафиксирован ADR 0017 (уточнение — коммит `fd2fe4f`); код ему соответствует.

### 3.3. Read-only гарантия (ADR 0005)

| Слой | Механизм | Источник |
|---|---|---|
| Учётка | `mcp-server` получает только `ARCHRAG_NEO4J_READER_*` (пользователь `archrag_reader`); writer-секретов нет | `platform/docker/docker-compose.yml:222-240`; `access/mcp-server/src/main/resources/application.yml:36-41` 🟢 |
| Драйвер | отдельный reader-драйвер, один на приложение; `spring.neo4j.*` автоконфигурация не используется | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/graph/GraphReaderConfiguration.java:23` 🟢 |
| Сессия | `AccessMode.READ` + только `executeRead` | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/GraphQueryExecutor.java:27-36, 78-82` 🟢 |
| Шаблоны | только именованные шаблоны `QueryTemplateRegistry`; произвольного Cypher нет (ADR 0018) | `access/graph-query-core/src/main/java/io/github/unlocker/archrag/graphquerycore/QueryTemplateRegistry.java:35-38` 🟢 |
| Проверка плана | `EXPLAIN` шаблона обязан дать `READ_ONLY` | `GraphQueryExecutor.java:27-28, 115-118` 🟢 |
| Сеть | `mcp-server` в сетях `edge`, `graph`, `observability`, но **не** в `backend`: PostgreSQL недостижим | `docker-compose.yml:240` 🟢 |

🟡 **Сама учётка `archrag_reader` писать может** (известное ограничение: ADR 0005, перенос в production gap list; наследовано из R.3, не новое расхождение). `init-reader.sh` делает только `CREATE USER … IF NOT EXISTS` без `DENY`/ролей (`platform/docker/neo4j/init-reader.sh:14`), а Community не знает привилегий; запрет записи держится приложением Это согласуется с R.3 (`_reversa_sdd/database/data-dictionary.md:141`).

### 3.4. Аудит вызовов (UNLOCKER-180)

- Каждый `tools/call`, включая отказ по scope/неизвестному tool, пишется `ToolCallAuditor`: `principal` (`sub` и `azp`), имя tool (санитизируется, ≤ 64 символов), нормализованные аргументы, использованные шаблоны, решение, строки, `truncated`, длительность, класс ошибки; сам токен не читается (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolCallAuditor.java:32, 101-141`; `ToolDecision.java:7-9`) 🟢.
- Аргументы пишутся в нормализованном виде: ключи отсортированы, строки усечены до 256 символов, коллекции до 20 элементов, глубина ≤ 5, значения ключей, похожих на `token|secret|password|authorization|credential`, заменены на `***`; маскирование эвристическое, по именам ключей (`access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/audit/ToolArguments.java:10-27`) 🟡: секрет под «безобидным» именем ключа в лог попадёт.

## 4. Admin REST ingestion-сервиса (`/admin/**`)

### 4.1. Операции

| Операция | Метод | Эффект | Аудит (`admin_audit`) | Замок |
|---|---|---|---|---|
| replay | `POST /admin/replay` | новые строки `replay:*`, проход конвейера | `REPLAY` (до и после) | `AdminLock` |
| rebuild | `POST /admin/rebuild?confirm=true` | пересбор графа из журнала | `REBUILD` | `AdminLock` |
| reconcile | `POST /admin/reconcile/{source}` | запускает полный snapshot адаптера (его `/control/snapshot`) | `RECONCILE` | `AdminLock` |
| crosswalks | `POST /admin/crosswalks` | approved crosswalk, 1…1000 пар | `CROSSWALK` по элементам | `AdminLock` (через `locked`) |

`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AdminController.java:61-123` 🟢. Валидация входа: `source` по шаблону `[A-Za-z0-9:._-]{1,200}`; `receivedFrom < receivedTo`; `rebuild` без `confirm=true` — 400; `replayId` — UUID (`AdminController.java:32, 63-69, 85-88, 140-149`) 🟢.

### 4.2. Кто допускается

- `/admin/**` требует `SCOPE_architecture.admin`, всё остальное `denyAll()` (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/SecurityConfiguration.java:41-45`) 🟢. Без токена 401, без scope 403 (Javadoc `SecurityConfiguration.java:15-22`).
- Дополнительно валидатор `AuthorizedPartyValidator`: `azp` токена обязан входить в `archrag.admin.allowed-clients` (по умолчанию `archrag-demo`); пустой список запрещён (`ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/AuthorizedPartyValidator.java:10-28`; `AdminProperties.java:13-19`) 🟢. Причина — scope `architecture.admin` выдаёт и audience админки консоли (ADR 0026): без проверки `azp` токен консоли дошёл бы до записывающего REST.
- `actor` для аудита и `approvedBy` crosswalk — `sub` токена (`AdminController.java:77, 122`) 🟢.
- Сеть: `ingestion` в `backend`, `graph`, `observability`; **не** в `edge`; через reverse-proxy публикуются только `/mcp`, protected-resource-метаданные, Keycloak и `/webhooks/*` (`docker-compose.yml:208`; `platform/docker/proxy/nginx.conf:13-61`) 🟢. Admin REST извне недоступен (ADR 0013).

### 4.3. Что админ может и чего не может

- Может: перезапустить обработку, пересобрать граф, вызвать snapshot, объединить `gid` (через crosswalk) — то есть влиять на данные витрины косвенно.
- Не может: править узлы/связи напрямую, закрыть конфликт, вернуть событие из карантина, пометить DLQ переигранным (операций нет — 🔴, см. [`state-machines.md`](state-machines.md), §1.5, §2).
- 🟡 Один оператор — все права: ролей внутри `architecture.admin` нет; разделить «запускать snapshot» и «утверждать crosswalk» нельзя.

## 5. Админ-консоль (`access/admin-console`)

- `/api/**` требует `SCOPE_architecture.admin` (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/security/ConsoleSecurityConfiguration.java:44-45`; `ConsoleProperties.java:20`) 🟢. Публично: `GET /actuator/health/**`, `GET /console-config.json`, статика SPA (`GET /**`); `/actuator/**` — `denyAll`; любой не-`GET` вне `/api/**` — `denyAll` (`ConsoleSecurityConfiguration.java:42-52`) 🟢.
- Контроллеры содержат только `@GetMapping` (`/api/graph/{stats,search,nodes/{gid},nodes/{gid}/neighborhood}`, `/api/sync/{sources,events,events/{source}/{eventId},dlq}`, `/api/identity/{conflicts,candidates}`, `/api/audit`) — записывающих эндпоинтов нет (ADR 0026) 🟢.
- Данные: граф — `archrag_reader` через шаблоны `graph-query-core`; журнал — роль PostgreSQL `archrag_console_ro` (`GRANT SELECT` на `inbox_event`, `consumer_checkpoint`, `dlq_entry`, `identity_candidate`, `source_conflict`, `admin_audit`; прав записи и доступа к `identity_mapping`, `identity_feature`, `property_assertion` нет) (`ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:8-16`) 🟢. Отказы проверяются IT с SQLSTATE `42501` (`_reversa_sdd/database/business-rules.md`, D13).
- Raw payload не отдаётся: только `payloadRef` и `contentHash` (ADR 0026; `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/pg/Rows.java`) 🟢.
- Аудит чтений: логгер `archrag.audit.console` — `sub`, метод и шаблон маршрута, ID шаблонов, число строк, `truncated`, статус, длительность; токен и query не пишутся (`access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/audit/ReadAuditInterceptor.java:18-46`) 🟢. Это *лог*, а не таблица (в отличие от `admin_audit`) 🟡.
- Только loopback: порты консоли и Keycloak публикуются на `127.0.0.1`, в сети `edge` консоли нет, reverse-proxy её не публикует (`docker-compose.yml:158-161, 273, 291-293`; ADR 0026) 🟢.
- Ограничение, принятое владельцем 2026-10-08 (ADR 0026): **ролевого ограничения на scope `architecture.admin` нет** — любой пользователь realm получает токен консоли через `archrag-admin-console`; на стенде один демо-администратор (gap list п. 2.7) 🟡.

## 6. Учётные записи хранилищ

| Хранилище | Учётка | Права | Кто использует |
|---|---|---|---|
| Neo4j | `neo4j` (writer) | полные | `ingestion` (проектор), `neo4j-init` |
| Neo4j | `archrag_reader` | на уровне СУБД не ограничена 🟡 (ADR 0005); запись исключена приложением | `mcp-server`, `admin-console` |
| PostgreSQL | владелец схемы (Flyway) | полные | `ingestion`, адаптеры (inbox) |
| PostgreSQL | `archrag_console_ro` | `SELECT` на 6 таблиц | `admin-console` |
| S3 (SeaweedFS) | ключи записи | запись/чтение raw | адаптеры, `ingestion` |

`platform/docker/README.md:111-112`; `docker-compose.yml:87-97, 254-300`; `platform/docker/console-smoke.sh:114-124` (проверка, что в контейнере консоли только секреты reader и `archrag_console_ro`) 🟢.

## 7. Входы источников (webhook и control)

- Webhook: `POST /webhook` адаптера проверяет `X-Archrag-Timestamp` и `X-Archrag-Signature` = `HMAC-SHA256(secret, timestamp + "." + body)` в окне `replayWindow`; невалидно → 401. Тело уведомления **не** содержит данных: адаптер делает `fetchById` у источника и кладёт в inbox то, что получил от него (`contracts/source-spi/src/main/java/io/github/unlocker/archrag/sourcespi/WebhookSignature.java:16, 47-90`; `WebhookHandler.java:17-22, 56-65`) 🟢.
- Через reverse-proxy публикуются `/webhooks/{eam,scm,cmdb,deploymap}` (`platform/docker/proxy/nginx.conf:43-61`) 🟢.
- 🟡 `POST /control/snapshot` адаптера **не аутентифицируется**: проверяется только метод (`adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookServer.java:54-71`). Это документированное ограничение (`docs/03-interfaces.md:33, 368, 560, 567`; комментарий `platform/docker/proxy/nginx.conf:42`): защита только сетевая. Адаптеры состоят в сетях `edge` и `backend` (`platform/docker/docker-compose.yml:28-30, 320`), поэтому порт `:8080/control/snapshot` достижим из `edge` (`mcp-server`, заглушки, Keycloak, reverse-proxy, который, однако, публикует только точные пути `/webhooks/*`) и из `backend` (`ingestion`, `admin-console`, `postgres`, `keycloak`). Запуск snapshot сам по себе ничего не удаляет, но запускает `Reconciler` на маркере; любой сервис этих сетей может его вызвать.

## 8. Сводная матрица «субъект × ресурс»

| Субъект \ Ресурс | MCP tools | Admin REST | Консоль `/api` | Neo4j | PostgreSQL | S3 |
|---|---|---|---|---|---|---|
| Агент (`archrag-demo`, `read`) | вызов | — | — | — | — | — |
| Оператор (`archrag-demo`, `admin`) | вызов (если и `read`) | все операции | `/api` — отказ по audience (токен `archrag-demo` не несёт audience консоли) | — | — | — |
| Пользователь консоли (`archrag-admin-console`, `admin`) | — | 401 (`azp` не в списке) | чтение | — (косвенно через reader) | — (косвенно `console_ro`) | — |
| `mcp-server` | — | — | — | read (учётка `archrag_reader`) | нет доступа | нет |
| `admin-console` | — | — | — | read | `SELECT` 6 таблиц | нет |
| `ingestion` | — | — | — | write | владелец | read/write |
| Адаптер | — | `/control` без auth | — | нет (сеть `graph` отсутствует) | запись inbox | запись raw |

🟡 Строка «Оператор»: одновременное наличие `read` и `admin` в одном токене клиента `archrag-demo` возможно (оба scope выдаются клиенту), поэтому разделение «агент» / «оператор» держится на выборе scope при запросе токена, а не на разных учётках.
