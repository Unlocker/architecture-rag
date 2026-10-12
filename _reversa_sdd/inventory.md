# Инвентарь проекта architecture-rag

> Сгенерировано Scout. Источник: `pom.xml`, `*/pom.xml`, дерево `git ls-files` (613 файлов), `.github/workflows/ci.yml`.
> Шкала: 🟢 подтверждено в коде/конфиге · 🟡 выведено · 🔴 расхождение или пробел.

## 1. Сводка

| Показатель | Значение |
|---|---|
| Основной язык | Java 21 (405 файлов `.java`) 🟢 `pom.xml:24-25` |
| Прочие языки | TypeScript/TSX (33), SQL (7), Shell/PowerShell (19), JS (2 — конфиги) |
| Фреймворки | Spring Boot 4.1.1, Spring AI 2.0.1 (MCP server webmvc), React 18.3.1 + Webpack 5 (admin-ui) 🟢 `pom.xml:28-31` |
| Сборка | Maven multi-module, wrapper 3.9.11 🟢 `.mvn/wrapper/maven-wrapper.properties` |
| Хранилища | PostgreSQL 16 (inbox, crosswalk, аудит), Neo4j 5 Community, S3 (SeaweedFS 4.48) 🟢 `platform/docker/docker-compose.yml` |
| CI | GitHub Actions: `build`, `integration-tests`, `docker-stand` 🟢 `.github/workflows/ci.yml` |
| Тесты | 92 `*Test.java`, 31 `*IT.java` (все в `platform/integration-tests`), 6 `*.test.ts(x)` 🟢 (файлы, не методы) |
| Коммитов в истории | 303; корень репозитория — `docs/` (00–06, ADR 0001–0031) |

## 2. Модули из корневого `pom.xml`

Корневой `pom.xml:15-21` объявляет 5 агрегаторов; каждый перечисляет дочерние модули. Всего 5 агрегаторов + 20 листовых модулей.

### contracts (`contracts/pom.xml`)

| Модуль | Артефакт | main/Test/IT (файлов) | Внутренние зависимости | Назначение 🟡 |
|---|---|---|---|---|
| `contracts/canonical-model` | `canonical-model` | 38 / 7 / 0 | — | `NodeLabel`, `RelationType`, `GraphCommand`, `SourceRecord`, `AuthorityMatrix`, узлы (`ITSystem`, `Service`, `Deployment`, …) |
| `contracts/event-schemas` | `event-schemas` | 18 / 5 / 0 | canonical-model | `CanonicalEvent`, `EventJournal`, `Checkpoint`, `ProcessingStatus`, `RawPayloadStore` |
| `contracts/source-spi` | `source-spi` | 10 / 1 / 0 | canonical-model | `SourceConnector`, `SourceChange`, `ChangePage`, `WebhookEvent` |

### adapters (`adapters/pom.xml`)

| Модуль | main/Test/IT | Внутренние зависимости | Назначение 🟡 |
|---|---|---|---|
| `adapters/adapter-core` | 13 / 7 / 0 | source-spi, event-schemas; `source-stubs` (test) | `Poller`, `WebhookServer`, `InboxWriter`, `RetryPolicy`, `HttpSourceConnector` |
| `adapters/eam-adapter` | 6 / 5 / 0 | adapter-core, source-spi, event-schemas | Адаптер EAM |
| `adapters/scm-adapter` | 3 / 2 / 0 | adapter-core, source-spi, event-schemas | Адаптер Git/SCM |
| `adapters/asset-adapter` | 3 / 2 / 0 | adapter-core, source-spi, event-schemas | Адаптер материальных активов (CMDB) |
| `adapters/deploymap-adapter` | 3 / 3 / 0 | adapter-core, source-spi, event-schemas, canonical-model | Адаптер deploy map / Helm charts |

### ingestion (`ingestion/pom.xml`)

| Модуль | main/Test/IT | Внутренние зависимости | Назначение 🟡 |
|---|---|---|---|
| `ingestion/event-journal` | 5 / 1 / 0 | event-schemas | `PostgresEventJournal`, `S3RawPayloadStore`, Flyway-миграция V1 |
| `ingestion/normalizer` | 15 / 5 / 0 | canonical-model, event-schemas, source-spi | `Normalizer`, `EamMapper`, `ScmMapper`, `CmdbMapper`, `DeployMapMapper` → `GraphCommand` |
| `ingestion/identity-resolution` | 19 / 4 / 0 | canonical-model | `Crosswalk`, `IdentityMapping`, кандидаты, `SourceConflict`; миграции V2, V4, V5, V7 |
| `ingestion/graph-projector` | 21 / 7 / 0 | canonical-model, event-schemas, normalizer, identity-resolution | `GraphProjector`, `Reconciler`, `Neo4jSchema` |
| `ingestion/ingestion-service` | 27 / 7 / 0 | canonical-model, event-schemas, event-journal, normalizer, identity-resolution, graph-projector, adapter-core, source-spi | Spring Boot: `JournalDispatcher`, admin-эндпоинт (replay/rebuild/crosswalk), миграции V3, V6 |

### access (`access/pom.xml`)

| Модуль | main/Test/IT | Внутренние зависимости | Назначение 🟡 |
|---|---|---|---|
| `access/graph-query-core` | 16 / 8 / 0 | canonical-model | `QueryTemplateRegistry`, шаблоны `*Templates`, `QueryLimits`, `GraphQueryExecutor` |
| `access/mcp-server` | 27 / 16 / 0 | graph-query-core | Spring Boot + Spring AI MCP: tools `search_assets`, `get_asset`, `trace_dependencies`, `find_runtime_footprint`, `explain_provenance`, `ping` |
| `access/admin-console` | 28 / 3 / 0 | graph-query-core | Spring Boot read-only консоль: Graph/Sync/Identity/Audit контроллеры |
| `access/admin-ui` | 0 java / 0 / 0 (TS: 23 tsx + 10 ts) | — (npm) | SPA React + TypeScript + Webpack; сборка через `frontend-maven-plugin` |

### platform (`platform/pom.xml`)

| Модуль | main/Test/IT | Внутренние зависимости | Назначение 🟡 |
|---|---|---|---|
| `platform/integration-tests` | 2 / 1 / 31 | почти все модули | Failsafe + Testcontainers (Neo4j, PostgreSQL), профиль `integration-tests` |
| `platform/source-stubs` | 12 / 5 / 0 | source-spi | Заглушки мастер-систем (`EamApiStub`, `StubSourceServer`, …) |
| `platform/demo-launcher` | 5 / 3 / 0 | адаптеры, source-stubs, event-journal, … | `DemoLauncher`: запуск адаптеров и заглушек на стенде |

Каталоги вне Maven: `platform/docker/` (compose, Dockerfile, `keycloak/`, `neo4j/`, `postgres/`, `proxy/`, `observability/`, `scripts/`) — модулем не является, `pom.xml` нет.

## 3. Точки входа

| Тип | Путь |
|---|---|
| MCP-сервер | `access/mcp-server/src/main/java/io/github/unlocker/archrag/mcpserver/McpServerApplication.java` |
| Ingestion-сервис | `ingestion/ingestion-service/src/main/java/io/github/unlocker/archrag/ingestionservice/IngestionServiceApplication.java` |
| Админ-консоль | `access/admin-console/src/main/java/io/github/unlocker/archrag/adminconsole/AdminConsoleApplication.java` |
| Запуск адаптеров/заглушек | `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java` |
| SPA | `access/admin-ui/src/index.tsx` |

## 4. Конфигурация, CI/CD, Docker

- `application.yml`: `access/mcp-server`, `access/admin-console`, `ingestion/ingestion-service` (+ тестовые в `src/test/resources/config/`).
- `platform/docker/.env.example` — демо-значения для стенда; рабочий `.env` в `.gitignore` 🟢.
- CI (`.github/workflows/ci.yml`): `./mvnw -B -ntp verify`; `verify -Pintegration-tests`; `docker-stand` (compose up, smoke, backup/restore, observability).
- Docker Compose (`platform/docker/docker-compose.yml`): postgres, seaweedfs, keycloak, ingestion, mcp-server, admin-console, четыре adapter-* и stub-*, eam-api-stub, reverse-proxy, otel-collector/prometheus/grafana (профиль observability).

## 5. Схемы БД (поверхностно; детали — Data Master)

- PostgreSQL (Flyway): `V1__event_journal`, `V2__identity_mapping`, `V3__admin_audit`, `V4__identity_candidate`, `V5__source_conflict`, `V6__console_readonly_role`, `V7__source_conflict_kind`.
- Neo4j: схема constraints/indexes задаётся кодом `ingestion/graph-projector/.../schema/Neo4jSchema.java`, DDL-файлов нет.

## 6. Тесты

JUnit 5 + AssertJ (корневой `pom.xml:163-172`), Testcontainers 2.0.5; образ Neo4j в тестах `neo4j:5.26.31-community` (`platform/integration-tests/.../TestImages.java:9`); Jest 29 для admin-ui.

## 7. Расхождения с контекстом задачи и `CLAUDE.md` (для архитектора)

1. 🔴 Фактическая раскладка шире схемы в `CLAUDE.md`: есть `adapters/adapter-core`, `ingestion/event-journal`, `ingestion/ingestion-service`, `access/admin-console`, `access/admin-ui`, `platform/source-stubs`, `platform/demo-launcher`. `platform/observability` как модуля нет — наблюдаемость лежит в `platform/docker/observability/`.
2. 🔴 `docs/adr/` содержит ADR 0001–0031; задача R.1 называет 0001–0028. ADR 0029–0031 (Helm chart, конфликт системы, инвариант платформы ИТ-системы) добавлены 2026-10-10…12. Soul ссылается на весь диапазон.
3. 🟡 `CLAUDE.md` требует `spring.ai.mcp.server.protocol=STREAMABLE`, код использует `STATELESS` (`access/mcp-server/src/main/resources/application.yml:11`); это сознательное уточнение, зафиксированное в ADR 0017 (UNLOCKER-224).
4. 🟡 `adapters/*` и `ingestion/normalizer` зависят от `platform/source-stubs` только в `test`-scope (`adapters/adapter-core/pom.xml`, `ingestion/normalizer/pom.xml`); направление «adapters → platform» нарушено только для тестов.
