# admin-console-security: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты (claims, свойства консоли): [`../contracts.md`](../contracts.md) §3–§4. Под тестом: [`../../ingestion-service/requirements.md`](../../ingestion-service/requirements.md) (admin REST, замок, аудит), [`../../ingestion-service/load-crosswalk/requirements.md`](../../ingestion-service/load-crosswalk/requirements.md), [`../../ingestion-service/replay-rebuild/requirements.md`](../../ingestion-service/replay-rebuild/requirements.md), [`../../ingestion-service/reconcile-source/requirements.md`](../../ingestion-service/reconcile-source/requirements.md); консоль — [`../../flowcharts/admin-console.md`](../../flowcharts/admin-console.md), права — [`../../permissions.md`](../../permissions.md).

## Visão Geral

Четыре класса проверяют операторский контур поверх реальных хранилищ: `AdminEndpointIT` — admin REST ingestion-сервиса (rebuild, replay, crosswalks, reconcile, замок 409, аудит) с **настоящей** цепочкой JWT (подпись, issuer, audience, `azp`-allowlist, scope); `AdminConsoleIT` — REST консоли графа (`/api/graph/**`) под настоящим JWT и учёткой чтения; `SyncConsoleIT` — REST консоли журнала и идентичности (`/api/sync/**`, `/api/identity/**`, `/api/audit`), read-роль PostgreSQL `archrag_console_ro` и аудит чтений; `IngestionMetricsIT` — gauge лага и открытой DLQ. Это единственные тесты, где проверяются ловушки 3 (только чтение), 13 (scope на каждом интерфейсе), 14 (ошибки с кодом) для операторских интерфейсов, и критерии PoC 3 и 4 на уровне admin REST (ADR 0013, ADR 0026) 🟢.

## Regras de Negócio

- **Rebuild.** `POST /admin/rebuild?confirm=true` → `200`; канонический дамп графа (узлы и связи без `elementId`) равен дампу до rebuild; в `admin_audit` запись `REBUILD`/`SUCCEEDED`; в журнал добавлены строки `replay:*`, исходные не менялись; отложенные связи (зависимость приходит раньше сервисов) воспроизводятся без остатка `PendingRelation`. Без `confirm` и при `confirm=false` → `400`, граф цел 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:255-300`).
- **Replay.** С тем же `replayId` не плодит строки (`replay:<id>:…`) и не меняет граф, в аудите `REPLAY` ровно 2 записи с этим `replay_id`; после полной потери графа replay по четырём источникам (полные URN `urn:corp:*`) восстанавливает его; replay пустого диапазона → `200 {"total":0}`, журнал не меняется 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:302-344`).
- **Crosswalks.** По элементам: валидная пара `APPLIED`, пара ключей, уже связанных с разными `gid` (`5101` и `5102`), — `CONFLICT`, неизвестный `source` и пустая причина — `INVALID`; в аудите ≥ 4 записи `CROSSWALK` с `actor = operator-1` (`sub` токена); повтор того же тела идемпотентен 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:346-372`).
- **Токены admin REST.** Чужой audience → `401` (rebuild, replay); клиент `archrag-admin-console` (`azp` вне allowlist) с верным audience и scope → `401`, `archrag-demo` → `200`; без токена `401`, без scope либо со scope `architecture.read` → `403` (rebuild, replay, crosswalks), мусорный токен → `401` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:374-400`).
- **Замок.** Пока удерживается `AdminLock`, rebuild и replay → `409`; после освобождения replay → `200` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:402-410`).
- **Reconcile.** Объект, пропавший из источника, после полного snapshot получает tombstone (событие `reconcile:…:TEAM/TEAM-GONE` → `PROJECTED`), и tombstone переживает rebuild (дамп равен); `POST /admin/reconcile/eam` проксируется в control-контекст адаптера → `202` (тело содержит `SNAPSHOT_COMPLETED`), пишется ровно один новый маркер `snapshot-complete.v1` и запись аудита `RECONCILE`/`SUCCEEDED`; неизвестный источник → `404`, недоступный адаптер → `502`, `architecture.read` → `403`, без токена → `401` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:412-461`).
- **Консоль графа** (`AdminConsoleIT`, токен `architecture.admin`, audience `urn:archrag:console`): `/api/graph/stats` — узлы по меткам и связи по типам (нули для пустых), `lastUpdatedAt`; `/api/graph/search` — fulltext и точный gid, `limit` → `truncated`; карточка узла с `SourceRecord` (`authority`, `active`), `404` для неизвестного gid; окрестность глубины 1 и 2, фильтр `relTypes`, `DEPLOYED_FROM` принимается; `depth=3` → `400` до любого запроса (отказ, не clamp); при `max-nodes=3` окрестность усекается (`truncated`); все шаблоны консоли и три asset-шаблона проходят проверку EXPLAIN «read-only», искусственный шаблон `CREATE` отвергается `IllegalStateException`, узлов не прибавилось 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:179-309`). Учётка Neo4j Community технически может писать, защита — проверка EXPLAIN (комментарий `AdminConsoleIT.java:292`) 🟡.
- **Роль PostgreSQL.** `archrag_console_ro` читает `inbox_event`; `INSERT` в `dlq_entry`, `UPDATE inbox_event`, `DELETE admin_audit` и чтение `identity_mapping` падают с SQLSTATE `42501`; пул консоли открывает соединения с `default_transaction_read_only = on`; повторная миграция при существующей роли проходит без ошибки (тест без `assert`) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:182-259`).
- **Доступ к журналу консоли.** Без токена `/api/sync/events` → `401`; токен `architecture.read` → `403` на `/api/sync/events` и `/api/audit` — единственные негативные проверки доступа консоли 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:211-218`).
- **Аудит чтений.** Каждое SQL-чтение пишется в read-аудит (логгер `archrag.audit.console`): `endpoint = "GET /api/sync/events"`, `templates = ["sql:inbox_event"]`, `rows = 2`; `request`/`result` больше 6000 символов не возвращаются (`null` и флаги `requestTruncated`/`resultTruncated`) 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:220-248`).
- **Журнал событий.** Фильтры `source`/`status`/период и порядок по убыванию времени; пагинация и потолок `size` (201 → `400`); метаданные события содержат `payloadRef`/`contentHash`, но **не payload**; детали события с историей DLQ, неизвестное → `404`; список DLQ с `replayedAt = null`; сводка источников (checkpoint, счётчики по статусам, `lastProjectedAt`, `lagSeconds`, последний `SyncRun`, нули и `null` для пустого источника); конфликты (по умолчанию `OPEN`, `RESOLVED` явным фильтром) и кандидаты; аудит операций с `request`/`result` 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:263-375`).
- **Метрики.** `archrag.ingestion.sync.lag` по источнику — возраст старейшего нетерминального события (`RECEIVED`/`RETRYING`); `PROJECTED` и `QUARANTINED` не в счёт; у пустых источников и неизвестного `cmdb` — 0; `archrag.ingestion.dlq.open` считает только непереигранные записи 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IngestionMetricsIT.java:38-61`).
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 1, 2)] Тесты закрепляют состояния, недостижимые в проде: `replayedAt = null` и «переигранные» DLQ-записи, вставленные SQL-ом (Р-3); `SyncRun` со статусом `SUCCEEDED`, вставленный Cypher-ом (Р-5) — Q-07, Q-08.
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 11)] Негативы безопасности неполны: `/admin/crosswalks` без 401, audience, `azp`; `/admin/reconcile/*` без audience, `azp`; консоль — без audience и `azp` вообще, `/api/graph/*`, `/api/identity/*`, `/api/sync/dlq|sources` без отказов — Q-01.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Rebuild воспроизводит граф и аудируется; без `confirm` отказ | Must | AC-01 |
| RF-02 | Replay идемпотентен по `replayId`; crosswalk поэлементен | Must | AC-02 |
| RF-03 | Admin REST: подпись, issuer, audience, `azp`, scope | Must | AC-03 |
| RF-04 | Одна админская операция одновременно: `409` при занятом замке | Must | AC-04 |
| RF-05 | Reconcile проксируется в adapter, tombstone переживает rebuild | Must | AC-05 |
| RF-06 | REST консоли графа читает только, глубина > 2 отвергается | Must | AC-06 |
| RF-07 | Консоль журнала читает PG под read-ролью, аудирует чтения | Must | AC-07 |
| RF-08 | Метрики лага и открытой DLQ | Should | AC-08 |

## Requisitos Não Funcionais

| Tipo | Требование | Evidence | Уверенность |
|------|------------|----------|-------------|
| Segurança | Admin REST проверяет подпись, issuer, audience, `azp`, scope через настоящий JWKS | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:96-99, 195-201` | 🟢 |
| Segurança | Консоль: audience `urn:archrag:console`, scope `architecture.admin`; `azp` не проверяется (в коде консоли `AuthorizedPartyValidator` отсутствует) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:40-41, 136-139` | 🟡 (Q-01) |
| Segurança | PostgreSQL: роль `archrag_console_ro` получает только `GRANT SELECT` на таблицы журнала и идентичности (миграция V6, `ingestion/ingestion-service/src/main/resources/db/migration/V6__console_readonly_role.sql:15`); второй рубеж — `default_transaction_read_only` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:182-197, 250-259` | 🟢 |
| Segurança | Метаданные события не отдают payload; большой аудит не отдаётся | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:238-248, 289-299` | 🟢 |
| Isolamento | `AdminEndpointIT`: общий ленивый `seed()` и мутирующие тесты (полный `DETACH DELETE`, добавление `TEAM-GONE`) — падение посередине оставит пустой граф следующим | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:116, 134-136, 325` | 🟡 |
| Desempenho | Таймауты и бюджеты консоли: `max-nodes` 500 и 3 (`tinyConsole`); потолок страницы 200 | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:84-86`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:275-288` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01: rebuild (AdminEndpointIT.rebuildOnWipedGraphGivesEquivalentGraphAndAuditsIt, rebuildReproducesRelationsThatWereDeferredUntilTheirEndpointsArrived, rebuildWithoutConfirmIsRejectedAndGraphIsUntouched)
Дано граф построен из четырёх источников
Когда POST /admin/rebuild?confirm=true с токеном operator (azp archrag-demo, scope architecture.admin)
Тогда 200, дамп графа равен прежнему, admin_audit содержит REBUILD/SUCCEEDED, в inbox_event есть replay:*, PendingRelation = 0
Когда POST /admin/rebuild без confirm либо confirm=false
Тогда 400 и граф не изменён

# AC-02: replay и crosswalk (AdminEndpointIT.replayIsIdempotentForSameReplayIdAndRestoresLostProjection, replayOfEmptyRangeDoesNothing, crosswalkAppliesValidItemAndReportsConflictAndInvalidPerItem)
Когда POST /admin/replay дважды с одним replayId
Тогда число строк replay:<id>:* не растёт, граф прежний, в admin_audit две записи REPLAY
Когда граф удалён и выполнен replay по четырём источникам
Тогда дамп равен исходному
Когда replay диапазона 2000-01-01..2000-01-02
Тогда 200 с "total":0, inbox_event не изменился
Когда POST /admin/crosswalks из четырёх элементов (валидный, конфликтный, неизвестный source, пустая причина)
Тогда статусы APPLIED, CONFLICT, INVALID, INVALID; аудит CROSSWALK >= 4 записей от operator-1; повтор идемпотентен

# AC-03: токены admin REST (AdminEndpointIT.tokenForForeignAudienceIsUnauthorized, tokenOfForeignClientIsUnauthorizedEvenWithAdminAudienceAndScope, scopeIsEnforced)
Когда токен с audience https://other.test/api
Тогда 401 на rebuild и replay
Когда токен клиента archrag-admin-console с верными audience и scope
Тогда 401; токен archrag-demo — 200
Когда нет токена, токен без scope, scope architecture.read, либо мусорная строка
Тогда 401, 403, 403, 401 соответственно

# AC-04: замок (AdminEndpointIT.concurrentAdminOperationIsRejectedWith409)
Дано AdminLock удерживается
Когда POST /admin/rebuild?confirm=true и POST /admin/replay
Тогда 409 на оба
Когда замок освобождён
Тогда replay отвечает 200

# AC-05: reconcile (AdminEndpointIT.reconcileTombstoneSurvivesRebuild, reconcileProxiesToAdapterControlContext, reconcileUnknownSourceIs404UnreachableAdapterIs502AndScopeIsEnforced)
Дано объект TEAM-GONE пропал из источника и выполнен полный snapshot
Тогда запись неактивна, событие reconcile:*:TEAM/TEAM-GONE в статусе PROJECTED; после rebuild дамп равен и запись всё ещё неактивна
Когда POST /admin/reconcile/eam при слушающем adapter
Тогда 202, тело содержит SNAPSHOT_COMPLETED, число маркеров +1, аудит RECONCILE/SUCCEEDED
Когда источник неизвестен
Тогда 404
Когда control-url недоступен
Тогда 502
Когда токен architecture.read либо без токена
Тогда 403 либо 401

# AC-06: консоль графа (AdminConsoleIT.*)
Когда GET /api/graph/stats
Тогда счётчики по меткам и типам, нули для пустых, lastUpdatedAt
Когда GET /api/graph/nodes/{gid}/neighborhood?depth=3
Тогда 400 до выполнения запроса
Когда max-nodes=3 и depth=2
Тогда truncated=true и не более 3 узлов
Когда выполняется шаблон CREATE через GraphQueryExecutor консоли
Тогда IllegalStateException "not read-only", узлов не прибавилось

# AC-07: консоль журнала (SyncConsoleIT.consoleRoleCannotWriteOrReadOtherTables, connectionsDefaultToReadOnlyTransactions, withoutScopeApiIsForbiddenAndWithoutTokenUnauthorized, sqlReadsAreRecordedInReadAudit, oversizedAuditResultIsNotReturned, eventsPaginateAndSizeIsCapped, eventMetadataHasRefAndHashButNoPayload)
Дано роль archrag_console_ro
Когда INSERT dlq_entry, UPDATE inbox_event, DELETE admin_audit, SELECT identity_mapping
Тогда SQLSTATE 42501 для каждого
Когда GET /api/sync/events без токена
Тогда 401
Когда токен architecture.read на /api/sync/events либо /api/audit
Тогда 403
Когда успешное чтение /api/sync/events?source=eam&size=2
Тогда запись read-аудита endpoint "GET /api/sync/events", templates ["sql:inbox_event"], rows 2
Когда size > 200
Тогда 400; метаданные события содержат payloadRef и contentHash, но не payload

# AC-08: метрики (IngestionMetricsIT.lagAndOpenDlqPerSourceFromJournal)
Дано eam: RECEIVED (120 с) и RETRYING (30 с), PROJECTED (900 с); scm: только терминальные и QUARANTINED; DLQ scm: одна открытая и одна переигранная
Когда IngestionMetrics.refresh
Тогда lag eam в диапазоне 119..180 с, lag scm и cmdb равны 0; dlq.open scm = 1, eam и deploymap = 0

# AC-09 (непокрыто) 🔴
Дано токен консоли без audience, с чужим azp, либо /admin/crosswalks без токена
Когда вызывается /api/graph/*, /api/identity/* либо /admin/crosswalks, /admin/reconcile/*
Тогда ожидается 401/403; тестов нет (Q-01)
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-07 | Must | ADR 0013, 0026; критерии PoC 3, 4; ловушки 3, 13 |
| RF-08 | Should | Критерий 9 (метрики) |
| Роли внутри `architecture.admin` | Won't | Одна роль «оператор» (П-10) |
| Закрытие DLQ и перевод кандидата | Won't | Не реализовано (Р-3, Р-4; 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 2, 3)] Q-08, Q-09) |

## Rastreabilidade de Código

| Файл | Класс | Покрытие |
|------|-------|----------|
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java` | 13 тестов | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java` | 8 тестов | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java` | 14 тестов | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IngestionMetricsIT.java` | 1 тест | 🟢 |
| `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/McpTestJwt.java` | выпуск токенов | 🟢 |
