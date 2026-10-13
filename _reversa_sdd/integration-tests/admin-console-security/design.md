# admin-console-security: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (стили запуска SB и SA, JWT). Требования: [`requirements.md`](requirements.md). Контракты: [`../contracts.md`](../contracts.md) §3–§4.

## Interface

| Символ | Сигнатура | Назначение | Трассировка |
|--------|-----------|------------|-------------|
| `AdminEndpointIT.JWT` | `static McpTestJwt` | подписанные токены, публичный ключ отдаёт JWKS; декодер собирает Spring Boot из `jwk-set-uri`, `issuer-uri`, `audiences` (проверяются подпись, issuer, audience, `azp`-allowlist) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:58-62, 96-99` 🟢 |
| `AdminEndpointIT.seed()` | `private synchronized void` | один раз: четыре адаптера над заглушками, `pollOnce()`, вторая версия `Payments Core v2`, затем `processAllReceived()` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:134-171` 🟢 |
| `AdminEndpointIT.processAllReceived()` | | диспетчер выключен (`archrag.dispatcher.enabled=false`), тест сам обходит журнал `JournalReader`-ом и вызывает `EventProcessor.process` для `RECEIVED` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:101, 173-191` 🟢 |
| `AdminEndpointIT.token(scope)` / `tokenFor(audience, scope)` / `post(path, token, body)` / `admin(path, body)` | | токен `operator-1` (audience `ADMIN_RESOURCE` = `McpTestJwt.RESOURCE`) и HTTP-вызов | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:195-215` 🟢 |
| `AdminEndpointIT.dump()` | `-> List<String>` | дамп всех узлов и связей без `elementId`, отсортированный; для равенства «до/после» | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:217-238` 🟢 |
| `AdminEndpointIT.runSnapshot(eam)` | | адаптер eam над `StubSourceServer`, `poller().snapshotOnce()`, затем `processAllReceived()` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:463-470` 🟢 |
| `AdminEndpointIT.EAM_CONTROL_PORT` / `freePort()` | | порт control-контекста адаптера eam выбирается до старта приложения; адаптер scm — на порт 1 (недоступен) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:73-82, 102-104` 🟢 |
| `AdminConsoleIT.start` / `startConsole(maxNodes)` / `get(ctx, path)` / `seed()` | | консоль — отдельное приложение `AdminConsoleApplication` (два контекста: `console` с 500 узлов и `tinyConsole` с 3); токен `jwt.tokenFor("admin-1", AUDIENCE, ADMIN)` в каждом запросе | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:74-157` 🟢 |
| `SyncConsoleIT.start` / `seedPostgres` / `startConsole` / `get(path, token)` / `ok(path)` | | применение миграций, пароль роли, сид PG и Neo4j (`SyncRun` двух прогонов), консоль с `archrag_console_ro`; `ok` берёт токен `architecture.admin` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:51-176` 🟢 |
| `IngestionMetricsIT.insert/dlq/gauge` | | вставка `inbox_event`, `dlq_entry` SQL-ом и чтение gauge из `SimpleMeterRegistry` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IngestionMetricsIT.java:63-93` 🟢 |

## Fluxo Principal

**`AdminEndpointIT`** (SB, диспетчер выключен):

1. `@DynamicPropertySource`: контейнеры, JWKS, `archrag.dispatcher.enabled=false`, `control-url` adapter eam на свободный порт, scm — на `127.0.0.1:1` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:84-105`).
2. Каждый тест, которому нужен граф, вызывает `seed()` (ленивый, `static boolean seeded`).
3. Вызов `admin(path, body)` с токеном `operator-1`, scope `architecture.admin`; проверка кода ответа, тела и состояния `admin_audit`, `inbox_event`, графа (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:195-215`).
4. Замок: тест берёт `lock.acquire()` (`try-with-resources`) и проверяет `409` на rebuild и replay, затем `200` после выхода из блока (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:402-410`).
5. Reconcile: `new StubSourceServer(eam)` + `eamApi.adapter(...)`, `adapter.startWebhook(loopback:EAM_CONTROL_PORT)` поднимает control-контекст; `POST /admin/reconcile/eam` → admin REST зовёт `POST control-url` → `snapshotOnce()` → `202` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:434-452`).

**`AdminConsoleIT`** (SA, только Neo4j): `CREATE USER console-reader`, `Neo4jSchema.apply`, драйвер читателя, `seed()` графа Cypher-ом (пять фиксированных gid, свежие времена `2026-01-0*`), два контекста консоли; `get(ctx, path)` — `RestClient` с `defaultStatusHandler(s -> true)` (все статусы возвращаются как ответ), токен всегда валиден (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:74-168`).

**`SyncConsoleIT`** (SA, PG + Neo4j): `JournalMigrations.apply`, `ALTER ROLE archrag_console_ro PASSWORD …`, `seedPostgres` (5 событий `eam`, 1 `scm`, `dlq_entry`, `consumer_checkpoint`, `identity_candidate`, два `source_conflict`, три `admin_audit`), консоль подключается под ролью `archrag_console_ro`; `get(path, token)` допускает `null`-токен для 401-проверки (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:51-176`).

## Fluxos Alternativos

- **Подавленный диспетчер**: `AdminEndpointIT` обрабатывает журнал вручную, чтобы сам решать, когда идёт обработка; `SyncAcceptanceIT` включает диспетчер ради честного замера (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:100-101`) 🟢.
- **Реальные часы и tombstone**: `reconcileTombstoneSurvivesRebuild` использует `StubSources.eam(Clock.systemUTC())`, два `snapshotOnce`, `delete` в заглушке между ними; затем сверка `dump()` до и после rebuild (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:412-432`) 🟢.
- **Неизвестный источник reconcile**: короткий код вне списка (`nope`) → `404`; не путать с `502` для известного, но недоступного (`scm`) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:454-461`) 🟢.
- **Большой аудит**: запись `bigop` с `request`/`result` по 6000 символов возвращается как `null` + флаги `…Truncated` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:97-104, 239-248`) 🟢.
- **Недоступный PG-пул**: `AdminConsoleIT` указывает `archrag.console-pg.url` на `jdbc:postgresql://localhost:1/none` — пул ленивый и не используется (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:142-146`) 🟡.

## Dependências

`ingestion/ingestion-service` (`AdminController`, `AdminLock`, `AdminOperations`, `AdminAudit`, `IngestionMetrics`, `StoredEventReader`), `access/admin-console` (`AdminConsoleApplication`, контроллеры, `ConsoleReadRepository`), `access/graph-query-core`, `ingestion/event-journal` (`JournalMigrations`, `JournalReader`), `ingestion/graph-projector`, 4 адаптера, `platform/source-stubs`. Спецификации: [`../../ingestion-service/design.md`](../../ingestion-service/design.md), [`../../flowcharts/admin-console.md`](../../flowcharts/admin-console.md), [`../../flowcharts/admin-ui.md`](../../flowcharts/admin-ui.md), [`../../permissions.md`](../../permissions.md), [`../../database/business-rules.md`](../../database/business-rules.md) 🟢.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Admin REST проверяется с настоящей цепочкой JWT только здесь; остальные SB-классы подменяют `JwtDecoder` на HS256 | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:96-99`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:99-106` | 🟢 (Q-01) |
| Консоль — второе приложение с аргументами командной строки, свои audience и учётка чтения | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:127-151` | 🟢 |
| Пароль read-роли PG выставляет тест (`ALTER ROLE`), а не миграция | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:58-61` | 🟢 |
| Для консоли графа токен всегда валиден: отказы проверяет только `SyncConsoleIT` и только для двух путей | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminConsoleIT.java:158-168`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:211-218` | 🟡 (Q-01) |
| Метрики проверяются на детерминированных строках журнала, вставленных SQL-ом | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/IngestionMetricsIT.java:38-61` | 🟡 (Q-08) |

## Observabilidade

`SyncConsoleIT.sqlReadsAreRecordedInReadAudit` проверяет структурированный лог `archrag.audit.console` через `ListAppender` (ключи `endpoint`, `templates`, `rows`) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/SyncConsoleIT.java:220-237`) 🟢. Метрики приложения (`/actuator/prometheus`) не проверяются.

## Riscos e Lacunas

- 🔴 Q-01: негативы безопасности консоли, `crosswalks`, `reconcile` неполны. 🔴 Q-07, Q-08: тесты закрепляют недостижимые в проде состояния (`SyncRun SUCCEEDED`, `replayedAt`). 🟡 Q-32: статус `OK` в фикстуре `admin_audit`.
- 🟡 `AdminEndpointIT`: общий ленивый `seed()`; пустой `@BeforeAll noop()` с Javadoc, описывающим `seed()` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/AdminEndpointIT.java:119-121`).
- 🟡 П-9: один scope `architecture.admin` на консоль и admin REST (принято владельцем 2026-10-08), П-10 — ролей нет, П-19 — аудит чтений консоли только лог (retention не определён).
