# acceptance-mcp-f1-f4: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (способы запуска, `awaitTrue`, JWT, изоляция). Требования: [`requirements.md`](requirements.md). Контракты: [`../contracts.md`](../contracts.md).

## Interface

Классы тестов package-private; публичного API нет. Общие члены, повторяющиеся в каждом из четырёх классов:

| Символ | Сигнатура | Назначение | Трассировка |
|--------|-----------|------------|-------------|
| `@BeforeAll loadGraphAndStartMcp` | `() -> void` | поднять адаптеры, снять snapshot, дождаться проекции, создать `mcp-reader`, запустить MCP | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:141-185`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:150-190`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:146-191`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:189-230` 🟢 |
| `startMcp` | `() -> ConfigurableApplicationContext` | второе приложение `McpServerApplication` с аргументами `--k=v` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:191-220`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:267-298` 🟢 |
| `client(token)` | `(String) -> McpSyncClient` | клиент MCP на Streamable HTTP, `Bearer`, таймаут 60 с | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:363-368`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:419-425` 🟢 |
| `call(tool, args, type)` | `-> T` | вызов tool с токеном `architecture.read`, `isError == false`, разбор `TextContent` в record результата (`JsonMapper`) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:378-385` 🟢 |
| `post(body, token)` | `(String, String) -> HttpResponse<String>` | сырой `POST /mcp` (`Accept: application/json, text/event-stream`), токен опционален | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:391-399` 🟢 |
| `count(cypher)` / `scalarLong(sql)` | `-> long` | проверки состояния Neo4j и журнала PostgreSQL | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:401-411` 🟢 |
| `awaitTrue(condition)` | `(BooleanSupplier) -> void` | опрос с шагом 200 мс до таймаута 90 с | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:413-427` 🟢 |
| `F2.footprint(gid, env)` / `awaitFootprint(env, cond)` / `awaitPipelineIdle()` / `poll(system)` | | вызов tool, ожидание изменения ответа, ожидание пустой очереди, `pollOnce` источника | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:432-492` 🟢 |
| `F3.trace` / `impact` / `assertToolError` / `percentile` | | вызовы `trace_dependencies`, проверка текста ошибки, перцентиль | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:447-500` 🟢 |
| `F4.linkAndLoad(eamId, scmId, name)` / `explain(gid, property)` / `adminPost(path, body)` | | crosswalk через admin REST + запись в eam-api и SCM + проекция; вызов `explain_provenance`; запрос с HS256-токеном `architecture.admin` | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:236-250, 458-466, 504-513` 🟢 |

## Fluxo Principal

1. `static {}` стартует Neo4j, PostgreSQL, S3 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:93-97`).
2. Spring-контекст ingestion (`@SpringBootTest`, RANDOM_PORT) поднимается с `@DynamicPropertySource` и диспетчером по умолчанию (интервал 1 с); `JwtDecoder` — HS256 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:99-126`).
3. `@BeforeAll`: `PostgresEventJournal`, `S3RawPayloadStore.ensureBucket`, `EamApiSupport`, `StubSources.seeded` → `StubSourceServer` → адаптеры; `pollOnce()` на каждом источнике → `SNAPSHOT_COMPLETED` (F4: `pollOnce` после crosswalk — исход не `SNAPSHOT_COMPLETED`: `pollIncremental`) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:141-165`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:212-214, 252-254`).
4. Ожидание: очередь `inbox_event` пуста и нет `QUARANTINED`; индексы Neo4j `ONLINE`; в F1 дополнительно есть узел `ITSystem Payments Core` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:166-178`).
5. `CREATE USER mcp-reader` в БД `system` (без ролей), запуск MCP; URL — `http://localhost:<local.server.port>` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:180-184`).
6. Сценарии `@Order(1..N)`: каждый открывает собственный `McpSyncClient` с токеном `architecture.read` и закрывает его (`try-with-resources`); негативы доступа идут через `post(...)`.
7. Мутирующие сценарии: F2 `s4`/`s5` меняют заглушку и вызывают `poll(system)`, затем `awaitFootprint` опрашивает tool до изменения ответа; F4 `s4`/`s7` меняют заглушку SCM/eam-api и ждут `awaitIdle()` и условие на `explain_provenance` (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:301-356, 464-492`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:375-448`).
8. `@AfterAll`: закрыть MCP, JWKS, адаптеры, сервера, `EamApiSupport`, хранилище (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:222-236`).

## Fluxos Alternativos

- **F2 `s2b`** (`@Order(2)` повторно) вставляет в Neo4j `sourceCommit`, `repositoryUrl` и два ребра `DEPLOYED_FROM` Cypher-ом в обход `GraphProjector` (закрытое со старым commit и открытое с новым), вызывает tool и в `finally` удаляет вставленное 🟡 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:358-395`).
- **F2 `s5`**: удаление развёртывания идёт явной записью `DELETE` в заглушке и инкрементальным опросом (`poll`), а не пропажей из snapshot, поэтому ловушка 9 здесь не затрагивается 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:346-356`).
- **F4 `s7`**: «tombstone» мастера делает reconcile — eam-api не умеет отдавать удаление; объект архивируется в `EamApiStore`, `snapshotOnce()` завершается `SNAPSHOT_COMPLETED`, проекция ставит tombstone 🟢 (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F4AcceptanceIT.java:436-441`).
- **Отказ ожидания**: `awaitTrue` бросает `AssertionError("condition not reached within …")` без текущего состояния системы 🟡.
- **Сбой порядка**: падение предыдущего `@Order`-метода оставляет следующие в неопределённом состоянии (общий граф), одиночный запуск метода недопустим 🟢.

## Dependências

`adapters/adapter-core` (`Poller`, `PollResult`), `adapters/{eam,scm,asset,deploymap}-adapter`, `platform/source-stubs`, `ingestion/event-journal` (`PostgresEventJournal`, `S3RawPayloadStore`), `ingestion/ingestion-service` (`IngestionServiceApplication`), `access/mcp-server` (`McpServerApplication`, tools), клиент MCP (`io.modelcontextprotocol`), `contracts/source-spi`; F4 — ещё `identity-resolution`, `normalizer`, `graph-projector` (`@Primary EventProcessor` с `ScmItSystemMapper`) 🟢 (импорты классов). Спецификации: [`../../adapter-core/requirements.md`](../../adapter-core/requirements.md), [`../../ingestion-service/requirements.md`](../../ingestion-service/requirements.md), [`../../source-stubs/requirements.md`](../../source-stubs/requirements.md), [`../../flowcharts/mcp-server.md`](../../flowcharts/mcp-server.md).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Второе приложение (MCP) в том же JVM, а не `@SpringBootTest` ingestion: у MCP свой `application.yml` и свой набор бинов | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:187-190` | 🟢 |
| `tool-scopes` регистрируются только для tools класса (F2 — `ping` + `find_runtime_footprint`) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F2AcceptanceIT.java:212-213`; `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:207-209` | 🟢 (🟡 Q-25) |
| `McpTestJwt` для MCP (настоящая проверка), HS256 для ingestion (только подпись) | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F1AcceptanceIT.java:99-106, 203-206` | 🟢 |
| Пороги задержки F3: холодный ≤ 15 с, p95 ≤ 5 с; замер включает создание клиента и выпуск токена на каждый вызов | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:248-249, 400-407` | 🟡 |
| Состояние накапливается намеренно: сценарии зависят от порядка и общего графа | `platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:392-396` | 🟢 |

## Observabilidade

В `System.out` печатаются замеры F3 (`F3 latency cold impact=…, trace n=… p50/p95/max`) (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/F3AcceptanceIT.java:409-411`) 🟢. Прочей телеметрии нет.

## Riscos e Lacunas

- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 11); ответ владельца: вопросы 14] Нет проверки `stale=true` (Q-06), неверного audience/exp (Q-01), `tools/list` без scope (Q-12), полного набора `tool-scopes` (Q-25).
- 🔴 Дубль `@Order(2)` в F2 (Q-04); 🟡 F2 `s4b` помечен «Дефект E1» (Q-26).
- 🟡 [R.8: решение принято, реализация — UNLOCKER-276 (п. 4)] Правило tombstone мастера (`F4 s7`) уже текста ADR 0020 (Q-05).
- 🟡 Время p95 при n=10 равно максимуму; «холодный» вызов не первый в классе (Q-17). 🟡 Дублирование каркаса (Q-21).
