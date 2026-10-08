# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Что это

PoC «AI-native витрина архитектурных активов»: мастер-системы (EAM, SCM, CMDB/assets, deploy map) → адаптеры → журнал событий → нормализация/identity resolution → projector → **Neo4j как производная read-only витрина** → MCP-сервер с предметными tools для агентов. LLM никогда не пишет произвольный Cypher: только фиксированные шаблоны запросов. Документация и комментарии в коде — на русском; следуйте этому языку.

Источники истины по дизайну: `docs/00-vision.md` (видение, решения владельца для PoC в начале файла), `docs/01-development-order.md` (фичи/задачи `UNLOCKER-NNN`, порядок), `docs/04-poc-measurements.md`, `docs/05-production-gap-list.md`. Ветки: `epic/UNLOCKER-*`, `feature/UNLOCKER-*`, `develop`, `main`.

## Сборка и тесты

Maven multi-module, Java 21, Spring Boot 4.1 / Spring AI 2.0; используйте wrapper `./mvnw`.

```bash
./mvnw -B -ntp verify                              # unit-тесты, Docker не нужен
./mvnw -B -ntp verify -Pintegration-tests          # + failsafe (*IT) в platform/integration-tests, нужен Docker (Testcontainers)
./mvnw -pl access/mcp-server -am test              # один модуль с зависимостями
./mvnw -pl access/graph-query-core test -Dtest=ResultBudgetTest            # один тест-класс
./mvnw -pl platform/integration-tests verify -Pintegration-tests -Dit.test=F1AcceptanceIT   # один IT
```

Failsafe подключён только в профиле `integration-tests` (в `platform/integration-tests/pom.xml`); без профиля `*IT` не запускаются. Версии зависимостей меняются только в корневом `pom.xml` (BOM-ы: testcontainers и AWS SDK обязаны стоять *перед* spring-boot-dependencies — побеждает первый объявленный). Внутренние модули версионируются через `${project.version}` в корневом dependencyManagement; новый модуль нужно добавить и туда.

## Демо-стенд (Docker Compose, `platform/docker`)

```bash
cd platform/docker
../../mvnw -B -ntp -f ../../pom.xml package -DskipTests   # образы собираются из jar
cp .env.example .env
docker compose build && docker compose up -d --wait --wait-timeout 300
./smoke.sh                                  # 401/200 через TLS reverse-proxy
docker compose up -d --scale mcp-server=2 --wait
scripts/backup-restore-check.sh             # backup/restore Neo4j + replay
ARCHRAG_OBSERVABILITY=true docker compose --profile observability up -d --wait   # Prometheus/OTel/Grafana
scripts/measure.sh                          # замеры PoC (нужны Docker, bash, python3)
shellcheck -S warning scripts/*.sh
```

CI (`.github/workflows/ci.yml`) гоняет три параллельных job: `build`, `integration-tests`, `docker-stand` (весь список выше).

## Архитектура

Модули (`<group>/<module>`; пакеты `io.github.unlocker.archrag.<module без дефисов>`):

- `contracts/` — общий слой без бизнес-логики: `canonical-model` (типы узлов/связей, `GraphCommand`, authority matrix), `event-schemas`, `source-spi` (интерфейсы мастер-систем).
- `adapters/` — запись: `adapter-core` (Poller/webhook/retry/`InboxWriter` — общий каркас) и конкретные `eam-`, `scm-`, `asset-`, `deploymap-adapter`. Адаптер только забирает данные источника (polling + webhook) и кладёт сырые события в inbox.
- `ingestion/` — `event-journal` (PostgreSQL inbox как event backbone в PoC, Kafka — production; raw-снимки в S3/SeaweedFS), `normalizer`, `identity-resolution` (deterministic mapping/crosswalk + candidate matching/конфликты), `graph-projector` (идемпотентная проекция в Neo4j), `ingestion-service` (запускает конвейер; админский эндпоинт replay/rebuild/reconcile — не через MCP и не через внешний ingress).
- `access/` — чтение: `graph-query-core` (реестр шаблонов запросов, `ResultBudget`/`QueryLimits`/таймауты — ограничения на обходы) и `mcp-server` (Spring AI MCP: tools `search_assets`, `get_asset`, `find_runtime_footprint`, `trace_dependencies(mode=trace|impact)`; OIDC/JWT-безопасность и `ToolScopeFilter`; аудит вызовов через `ToolCallAuditAspect`).
- `platform/` — `source-stubs` (заглушки мастер-систем: в PoC все мастера — заглушки), `demo-launcher`, `integration-tests` (Testcontainers, приёмочные `F*AcceptanceIT`), `docker/` (compose, Dockerfile, сертификаты, скрипты).

Ключевые инварианты:

- Запись и чтение разделены: `graph-projector` пишет под учёткой `neo4j` (writer), `mcp-server` читает только под `archrag_reader`. В Neo4j Community нет ролей — read-only и domain-фильтрацию обеспечивает приложение (`GraphReaderConfiguration`, `init-reader.sh`).
- Мастер каждой связи/сущности определяется authority matrix (напр. `DEPENDS_ON` — EAM, `Deployment` — deploy map + Helm); в `SourceRecord` поле называется `source`, не `key`.
- `trace_dependencies` принимает `maxDepth` 1..6 и отклоняет остальное ошибкой (без clamp).
- Секреты на стенде раздаются как Docker secrets из `.env`; наружу публикуется только TLS reverse-proxy (Keycloak OIDC для токенов).
