# Блок-схемы: platform/integration-tests

> Archaeologist, R.2e. Пути относительно `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/` (`IT/`), если не указано иное. Поведение тестов — `code-analysis.md` §Z1, константы и форматы — `data-dictionary.md` §Z.1. Тесты не запускались: схемы описывают то, что делает код тестов. Метки 🟢 код · 🟡 выведено · 🔴 пробел.

## Запуск и поднятие окружения IT (`pom.xml:136-157`, `.github/workflows/ci.yml:35-55`, `IT/F1AcceptanceIT.java:87-97,141-185`) 🟢

```mermaid
flowchart TD
  S["./mvnw verify"] --> P{"профиль integration-tests?"}
  P -- нет --> SU["surefire: *Test\n(только IntegrationTestsSmokeTest)\nDocker не нужен"]
  P -- да --> FS["failsafe: integration-test + verify\nклассы *IT последовательно, 1 форк"]
  FS --> C{"способ запуска класса"}
  C -- "M: вручную (19 классов)" --> M1["@Testcontainers + @Container static final\n(Neo4j 5.26.31-community, postgres:16, SeaweedFS 4.48)"]
  M1 --> M2["@BeforeAll: Neo4jSchema.apply(driver),\nJournalMigrations.apply(ds)"]
  M2 --> M3["new PostgresEventJournal / GraphProjector /\nEventProcessor ... без Spring, без JWT"]
  C -- "SB: @SpringBootTest RANDOM_PORT\n(8 классов)" --> B1["контейнеры:\n@Container ИЛИ static { start() } при PER_CLASS\n(контекст Spring раньше расширения Testcontainers)"]
  B1 --> B2["@DynamicPropertySource:\ndatasource, archrag.neo4j.*, archrag.s3.*, jwt.*,\narchrag.dispatcher.*, adapters.*.control-url"]
  B2 --> B3["контекст IngestionServiceConfiguration:\nJournalMigrations.apply (V1-V7),\nNeo4jSchema.apply, TemporalRelationMigration"]
  C -- "SA: SpringApplicationBuilder.run(args)\n(консоли, MCP внутри F1-F4)" --> A1["свойства аргументами командной строки:\n--server.port=0, exclude DataSource/Flyway\n(на classpath два application.yml)"]
  A1 --> A2["Neo4j: CREATE USER mcp-reader|console-reader\nв БД system (Community: без ролей)"]
  M3 --> T["тесты класса"]
  B3 --> T
  A2 --> T
  T --> CL["очистка: MATCH DETACH DELETE / TRUNCATE\nили уникальные UUID / копится намеренно"]
  CL --> E["конец класса: контейнеры гасит Ryuk,\nпереиспользования между классами нет\n(26 Neo4j + 23 PG + 11 S3 запусков за прогон)"]
```

Профиль: обычный `verify` не требует Docker (комментарий `pom.xml:136`); CI запускает job `integration-tests` отдельно от `build` и выгружает `failsafe-reports` при падении. Три класса не вызывают `Neo4jSchema.apply` напрямую (`GraphQueryCoreIT`, `RuntimeFootprintIT` — вообще без схемы; `SB`-классы — через приложение) 🟡.

## Типовая приёмка F1–F4 (`IT/F1AcceptanceIT.java:75-97,141-221,242-358`, критерий 5) 🟢

Один сквозной сценарий с общим состоянием на класс (`@TestInstance(PER_CLASS)`, `@TestMethodOrder(OrderAnnotation)`).

```mermaid
flowchart TD
  ST["static { NEO4J.start(); POSTGRES.start(); S3.start(); }"] --> BA["@BeforeAll"]
  BA --> AD["StubSources.seeded(clock) →\nStubSourceServer + реальные адаптеры\nEAM / SCM / CMDB / DEPLOY_MAP"]
  AD --> PO["poller().pollOnce() по каждому источнику\nтребует SNAPSHOT_COMPLETED"]
  PO --> JR["PostgresEventJournal (inbox_event)\n+ S3RawPayloadStore (бакет класса)"]
  JR --> DI["диспетчер ingestion-service (в SpringBootTest)\nEventProcessor → GraphProjector → Neo4j"]
  DI --> W{"awaitTrue (sleep 200 мс, таймаут 90 с):\nочередь пуста и QUARANTINED = 0?"}
  W -- нет --> W
  W -- да --> IX["ожидание индексов Neo4j ONLINE (FULLTEXT)"]
  IX --> RU["CREATE USER mcp-reader в БД system"]
  RU --> MC["второй контекст: McpServerApplication\nprotocol=STATELESS, /mcp,\ntool-scopes.TOOL=architecture.read,\nneo4j.reader=mcp-reader, лимиты 6/500/50/5s/512KB"]
  MC --> SC["тесты @Order s1..sN:\nMcpSyncClient (Streamable HTTP) + Bearer McpTestJwt"]
  SC --> T1["s1 tools/list"]
  SC --> T2["s2..sK вызовы tool и проверка ответа\n(F1 search/get_asset, F2 footprint,\nF3 trace/impact, F4 explain_provenance)"]
  SC --> T3["ошибки: неизвестный gid → tool error\nбез стектрейса и без эха значения"]
  SC --> T4["последний: сырой POST /mcp tools/call\nбез токена → 401, scope other → 403"]
  T2 --> MU{"мутирует источник?"}
  MU -- "F2 s4/s5, F4 s4/s7" --> PL["изменение заглушки + pollOnce / snapshotOnce →\nopros tool через MCP до изменения ответа"]
  MU -- "F1, F3" --> NO["граф не меняется после загрузки"]
  PL --> T2
```

F3 в конце замеряет задержки (s8: холодный impact ≤ 15 с, p95 ≤ 5 с по 10 вызовам — p95 при n=10 равен максимуму, 🟡), F4 перед сценарием связывает два актива через `POST /admin/crosswalks` и подставляет `ScmItSystemMapper` (`@Primary EventProcessor`). `SyncAcceptanceIT` устроен так же, но без MCP: webhook-ами и `POST /admin/reconcile|rebuild` проверяются критерии 2, 3, 4 (`k1…k4`).

## Проверка доступа 401/403 в IT (`IT/McpTestJwt.java:40-88`, `IT/F1AcceptanceIT.java:350-358`, `IT/AdminEndpointIT.java:375-401`, `IT/SyncConsoleIT.java:211-218`) 🟢

```mermaid
flowchart TD
  J["McpTestJwt: RSA-2048 в памяти,\nJWKS на HttpServer (loopback:0, GET /jwks, kid=test)"] --> TK["token(scopes) / tokenFor(sub, aud, scopes) /\ntokenForClient(azp, sub, aud, scopes)\niss=aud=https://mcp.test/mcp, azp=archrag-demo,\niat -60 с, exp +5 мин, RS256"]
  TK --> TG{"какое приложение проверяет?"}
  TG -- "MCP (F1-F4)" --> M1["jwk-set-uri + issuer-uri + audiences = RESOURCE;\nScope-фильтр по tool-scopes"]
  M1 --> M2{"запрос tools/call"}
  M2 -- "нет токена" --> M401["401"]
  M2 -- "scope architecture.other" --> M403["403, WWW-Authenticate: insufficient_scope"]
  M2 -- "architecture.read" --> MOK["200, результат tool"]
  M2 -. "чужой audience / просроченный токен" .-> MGAP["🔴 не проверяется"]
  TG -- "admin REST ingestion (AdminEndpointIT)" --> A1["реальная цепочка: подпись + issuer + audience\n+ azp-allowlist (AuthorizedPartyValidator)"]
  A1 --> A2{"токен"}
  A2 -- "aud=https://other.test/api" --> A401["401 (rebuild, replay)"]
  A2 -- "azp=archrag-admin-console" --> A401
  A2 -- "мусор / нет токена" --> A401
  A2 -- "scope architecture.read или пусто" --> A403["403 (rebuild, replay, crosswalks)"]
  A2 -- "archrag-demo + architecture.admin" --> AOK["200"]
  A2 -. "crosswalks / reconcile: audience, azp" .-> AGAP["🔴 не проверяется"]
  TG -- "консоль (SyncConsoleIT)" --> C1["issuer + audience urn:archrag:console\n(azp в консоли не проверяется)"]
  C1 --> C2{"запрос"}
  C2 -- "/api/sync/events без токена" --> C401["401"]
  C2 -- "/api/sync/events, /api/audit со scope read" --> C403["403"]
  C2 -. "/api/graph/*, /api/identity/*, audience, azp" .-> CGAP["🔴 не проверяется"]
  TG -- "ingestion в F1-F4, SyncAcceptance, EamKey, Dispatcher" --> H["JwtDecoder подменён на HS256 (общий KEY);\nissuer / audience / azp не проверяются"]
```

`McpTestJwt.exp` фиксирован (+5 мин): просроченный токен получить нельзя; `jwkSetUri()` отдаёт `localhost`, а сервер привязан к `getLoopbackAddress()` (`IT/McpTestJwt.java:45,59`, 🟡).

## Reconcile: удаление только после полного snapshot (`IT/ReconciliationIT.java:161-290`, `IT/JournalDispatcherIT.java:172-240`) 🟢

```mermaid
flowchart TD
  P["Poller.pollOnce: полный snapshot источника"] --> PG{"страницы получены без сбоя?"}
  PG -- "нет: 503 на середине (ReconciliationIT: GAVE_UP)" --> NM["маркера snapshot-complete нет →\nничего не удаляется;\nрестарт Poller продолжает тот же syncRunId"]
  NM --> P
  PG -- да --> MK["запись маркера snapshot-complete:RUN\nПОСЛЕДНЕЙ в прогоне (AdapterInboxIT)"]
  MK --> DP["диспетчер / EventProcessor передаёт маркер Reconciler"]
  DP --> CNT{"число объектов прогона в журнале\n= заявленному в маркере?"}
  CNT -- нет --> RT["удаление не выполняется;\nмаркер RETRYING RECONCILIATION_TRIGGER_FAILED, attempts >= 2\n(JournalDispatcherIT)"]
  CNT -- да --> MS["missing set = активные записи источника\nминус объекты прогона"]
  MS --> F{"объект в missing set?"}
  F -- "в карантине (QUARANTINED)" --> KP["считается присутствующим → active"]
  F -- "создан webhook-ом в окне snapshot (high-water mark)" --> KP2["не удаляется этим прогоном"]
  F -- "действительно пропал" --> TB["tombstone SourceRecord: active=false;\nузел закрывается, если нет других MASTER-assertions"]
  TB --> RC["снимаются assertions источника в PG,\nконфликты → RESOLVED, маркеры ASSERTS.conflicts сняты"]
  MK --> RP["повторный reconcile того же маркера → 0 tombstone"]
```

Негативы «на середине загрузки» закреплены `ReconciliationIT.failureInTheMiddleOfSnapshotDeletesNothingAndRestartCompletesTheSameRun` и `JournalDispatcherIT.markerClaimingMoreObjectsThanJournalHoldsDeletesNothing`; в `SourceConflictIT:295-299` и `ExplainProvenanceIT:241-255` маркер подделан (событие `TYPE_ASSET_UPSERTED` / прямой вызов `Reconciler`) 🟡. Правило закрытия узла «нет активных MASTER-assertions» (`F4AcceptanceIT:432-433`) уже, чем текст ADR 0020 («других активных assertions», `docs/adr/0020-tombstone-instead-of-hard-delete.md:14`) 🟡.

## Ожидание в сквозных тестах: `awaitTrue` и порядок `@Order` (`IT/F1AcceptanceIT.java:413-427`, `IT/SyncAcceptanceIT.java:524-538`, `IT/JournalDispatcherIT.java:282-293`) 🟢

```mermaid
flowchart TD
  A["awaitTrue(condition, timeout)"] --> D["deadline = nanoTime + timeout"]
  D --> L{"condition.getAsBoolean()?"}
  L -- да --> OK["return"]
  L -- нет --> TO{"nanoTime > deadline?"}
  TO -- да --> AE["AssertionError 'condition not reached within …'\n(текущее состояние в сообщение не попадает)"]
  TO -- нет --> SL["Thread.sleep(200 мс; 100 мс в JournalDispatcherIT)"]
  SL --> L
  subgraph TMO["таймауты по классам"]
    T90["F1-F4, EamKeyMigrationIT: 90 с"]
    T60["SyncAcceptanceIT: 60 с (+ sleep 2 с в k2b)"]
    T30["JournalDispatcherIT: 30 с"]
  end
```

Опрос заменяет Awaitility, который в проект не входит. Порядок методов `@Order` обязателен для `F1–F4`, `SyncAcceptanceIT`, `EamKeyMigrationIT`; в `F2AcceptanceIT` два метода с `@Order(2)` (`:255`, `:360`) 🔴.
