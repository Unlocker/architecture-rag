# platform/docker: дизайн

> Ссылки даны полными путями от корня репозитория. Требования: [`requirements.md`](requirements.md). Контракты: [`contracts.md`](contracts.md). Блок-схемы: [`../flowcharts/docker.md`](../flowcharts/docker.md).
> Use case-спецификации: [`stand-startup/`](stand-startup/design.md), [`network-secrets/`](network-secrets/design.md), [`keycloak-oidc/`](keycloak-oidc/design.md), [`backup-restore/`](backup-restore/design.md), [`observability-measure/`](observability-measure/design.md).
> Каталог сервисов, порядок старта, сети и секреты описаны здесь; в use case — только сценарии.

## Каталог сервисов

Файл — `platform/docker/docker-compose.yml`. Проект называется `archrag` (`platform/docker/docker-compose.yml:4`) 🟢.

| Сервис | Образ / сборка | Порт внутри | Сети | Опубликован | Секреты (target) | Healthcheck | Строки в `platform/docker/docker-compose.yml` |
|--------|----------------|-------------|------|-------------|-------------------|-------------|-----------|
| `certs` | `alpine/openssl:3.5.4`, одноразовый | — | backend | — | — | — | `platform/docker/docker-compose.yml:41-49` |
| `neo4j` | `neo4j:5-community` | Bolt 7687 (TLS), HTTP 7474 | graph | — | `neo4j_password` | `cypher-shell … RETURN 1` (10 с / 30 с / 12 / старт 20 с) | `platform/docker/docker-compose.yml:51-85` |
| `neo4j-init` | `neo4j:5-community`, одноразовый | — | graph | — | `neo4j_password`, `neo4j_reader_password` | — | `platform/docker/docker-compose.yml:89-100` |
| `postgres` | `postgres:16` | 5432 | backend | — | `pg_password` (`POSTGRES_PASSWORD_FILE`) | `pg_isready` (3 с / 3 с / 20) | `platform/docker/docker-compose.yml:102-117` |
| `seaweedfs` | `chrislusf/seaweedfs:4.48` | S3 8333 | backend | — | ключи в `environment` (`AWS_*`) | `wget` любой HTTP < 500 (5 с / 5 с / 30, старт 10 с) | `platform/docker/docker-compose.yml:119-135` |
| `keycloak` | `quay.io/keycloak/keycloak:26.4`, `start-dev --import-realm` | 8080, management 9000 | edge, backend | `127.0.0.1:${ARCHRAG_KEYCLOAK_PORT:-8080}` | пароли в `environment` | `/dev/tcp` к 9000 `/health/ready` (5 с / 10 с / 60, старт 60 с) | `platform/docker/docker-compose.yml:137-175` |
| `ingestion` | сборка `ingestion-service-*-exec.jar` → `archrag/ingestion:dev` | admin REST 8081 | backend, graph, observability | — | `pg_password`, `neo4j_password`, `s3_access_key`, `s3_secret_key` | `curl -s http://127.0.0.1:8081/` (любой ответ) | `platform/docker/docker-compose.yml:178-220` |
| `mcp-server` | сборка `mcp-server-*-exec.jar` → `archrag/mcp-server:dev` | 8080 | edge, graph, observability | — | `neo4j_reader_password` | `curl -fs http://127.0.0.1:8080/actuator/health` | `platform/docker/docker-compose.yml:222-252` |
| `console-pg-init` | `postgres:16`, одноразовый | — | backend | — | `pg_password`, `console_pg_password` | — | `platform/docker/docker-compose.yml:256-270` |
| `admin-console` | сборка `admin-console-*-exec.jar` → `archrag/admin-console:dev` | 8080 | backend, graph, console-edge | `127.0.0.1:${ARCHRAG_CONSOLE_PORT:-8090}` | `neo4j_reader_password`, `console_pg_password` | `curl -fs http://127.0.0.1:8080/actuator/health` | `platform/docker/docker-compose.yml:274-305` |
| `adapter-eam` | `archrag/demo-launcher:dev`, `command: ["adapter","EAM"]` | webhook 8080, health 8090 | edge, backend | — | `pg_password`, `s3_*`, `webhook_secret`, `eam_api_token` | `curl -fs http://127.0.0.1:8090/health` | `platform/docker/docker-compose.yml:311-336` |
| `adapter-scm` / `adapter-cmdb` / `adapter-deploymap` | тот же образ; `SCM`, `CMDB`, `DEPLOY_MAP` | 8080 / 8090 | edge, backend | — | `pg_password`, `s3_*`, `webhook_secret` | как `adapter-eam` | `platform/docker/docker-compose.yml:338-378` |
| `stub-eam` / `stub-scm` / `stub-cmdb` / `stub-deploymap` | тот же образ; `["stub", "EAM"]` и т. д. | 8080, control 8091 | edge | — | `webhook_secret` | `curl -fs 'http://127.0.0.1:8080/changes?limit=1'` (3 с / 3 с / 20) | `platform/docker/docker-compose.yml:381-428` |
| `eam-api-stub` | тот же образ; `["eam-api-stub"]` | API 8080, control 8091 | edge | — | `eam_api_token` | `curl` с заголовком `Authorization: Token` к `/api/platforms/?page_size=1` | `platform/docker/docker-compose.yml:434-445` |
| `reverse-proxy` | `nginx:1.27-alpine` | 8443 (TLS) | edge | `127.0.0.1:${ARCHRAG_PUBLIC_PORT:-8443}` | — (сертификаты из тома `certs`) | `wget --no-check-certificate https://127.0.0.1:8443/healthz` | `platform/docker/docker-compose.yml:448-468` |
| `otel-collector`, `prometheus`, `grafana` | `otel/opentelemetry-collector-contrib:0.140.1`, `prom/prometheus:v3.7.3`, `grafana/grafana:12.2.1`; профиль `observability` | 4318/8889/13133; 9090; 3000 | observability (+ obs-edge у grafana) | `127.0.0.1:${GRAFANA_PORT:-3000}` (только grafana) | `grafana_admin_password` | collector — нет; prometheus — цель collector `up`; grafana — `/api/health` | `platform/docker/docker-compose.yml:473-526` |

Подтверждающие строки внутренних портов: адаптеры — `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:123` (webhook 8080, health 8090); управляющий порт заглушек — `platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:54` (8091); `ingestion` — порт 8081 в healthcheck `platform/docker/docker-compose.yml:216` 🟢. Общие блоки: якоря `x-demo-launcher` (`platform/docker/docker-compose.yml:6-12`), `x-adapter-env` (`platform/docker/docker-compose.yml:16-20`), `x-adapter-secrets` (`platform/docker/docker-compose.yml:22-26`), `x-adapter-networks` (`platform/docker/docker-compose.yml:28-30`), `x-adapter-depends` (`platform/docker/docker-compose.yml:32-36`), `adapter-health` (`platform/docker/docker-compose.yml:331-336`), `stub-health` (`platform/docker/docker-compose.yml:390-395`) 🟢.

## Порядок запуска

Строится из `depends_on`; `✓` = `service_healthy`, `⏹` = `service_completed_successfully`, `▶` = `service_started` 🟢. Подробная схема — [`../flowcharts/docker.md`](../flowcharts/docker.md) §1.

| Уровень | Сервисы | Ждут |
|---------|---------|------|
| 0 | `certs`, `postgres`, `seaweedfs`, `keycloak`, все `stub-*`, `eam-api-stub` | — (`platform/docker/docker-compose.yml:41-175, 381-445`) |
| 1 | `neo4j` | `certs` ⏹ (`platform/docker/docker-compose.yml:77-78`) |
| 2 | `neo4j-init`; `ingestion` | `neo4j` ✓; `neo4j` ✓, `postgres` ✓, `seaweedfs` ✓ (`platform/docker/docker-compose.yml:99-100, 209-212`) |
| 3 | `console-pg-init`; `mcp-server`; `adapter-*` | `ingestion` ✓; `neo4j` ✓, `neo4j-init` ⏹, `keycloak` ✓, `ingestion` ✓; `postgres` ✓, `seaweedfs` ✓, `ingestion` ✓ и свой `stub-*` ✓ (у `adapter-eam` ещё `eam-api-stub` ✓) (`platform/docker/docker-compose.yml:269-270, 241-246, 32-36, 326-329`) |
| 4 | `admin-console` | `neo4j` ✓, `neo4j-init` ⏹, `console-pg-init` ⏹, `keycloak` ✓, `ingestion` ✓ (`platform/docker/docker-compose.yml:294-299`) |
| 5 | `reverse-proxy` | `certs` ⏹, `mcp-server` ✓, `keycloak` ✓, четыре `adapter-*` ✓ (`platform/docker/docker-compose.yml:456-463`) |
| профиль | `otel-collector` → `prometheus` (▶ collector) → `grafana` (✓ prometheus) | (`platform/docker/docker-compose.yml:491-492, 519-520`) |

Особенности 🟢: `ingestion` накатывает миграции PostgreSQL и схему Neo4j при старте, поэтому адаптеры, `mcp-server`, `console-pg-init` и `admin-console` ждут его готовности (`platform/docker/docker-compose.yml:35-36, 245-246`); `ingestion` считается готовым, когда порт 8081 отвечает любым HTTP-кодом (Boot открывает порт после старта контекста) (`platform/docker/docker-compose.yml:213-220`); `reverse-proxy` стартует последним и ждёт все четыре адаптера, хотя проксирует к ним только webhook'и 🟡 (`platform/docker/docker-compose.yml:460-463`; `platform/docker/proxy/nginx.conf:43-62`); `mcp-server` и `ingestion` не зависят от `otel-collector`: при `ARCHRAG_OBSERVABILITY=true` они лишь начинают push (`platform/docker/docker-compose.yml:200-201, 235-236`).

## Сети

| Сеть | Тип | Участники | Назначение |
|------|-----|-----------|------------|
| `edge` | обычная | `reverse-proxy`, `mcp-server`, `keycloak`, `adapter-*`, `stub-*`, `eam-api-stub` | внешняя поверхность; заглушки изображают внешние мастер-системы (`platform/docker/docker-compose.yml:528-530`) |
| `backend` | `internal` | `certs`, `postgres`, `seaweedfs`, `keycloak`, `ingestion`, `adapter-*`, `console-pg-init`, `admin-console` | PostgreSQL/S3/JWKS (`platform/docker/docker-compose.yml:531-533`) |
| `graph` | `internal` | `neo4j`, `neo4j-init`, `ingestion`, `mcp-server`, `admin-console` | доступ к Neo4j (`platform/docker/docker-compose.yml:534-536`) |
| `observability` | `internal` | `mcp-server`, `ingestion`, `otel-collector`, `prometheus`, `grafana` | OTLP и скрейп (`platform/docker/docker-compose.yml:537-539`) |
| `obs-edge` | обычная | `grafana` | только для публикации порта (internal-сеть порты не публикует) (`platform/docker/docker-compose.yml:540-541`) |
| `console-edge` | обычная | `admin-console` | только для публикации порта (`platform/docker/docker-compose.yml:542-543`) |

Сеть `default` не используется 🟢 (`platform/docker/docker-compose.yml:2`). `mcp-server` не в `backend`, `ingestion` не в `edge`, `admin-console` не в `edge`: из них следуют проверки `smoke.sh`/`console-smoke.sh` 🟢.

## Секреты и учётки

Объявление: `secrets: <имя>: environment: <ПЕРЕМЕННАЯ_ИЗ_.env>` (`platform/docker/docker-compose.yml:545-564`) — девять секретов: `pg_password` (`POSTGRES_PASSWORD`), `neo4j_password` (`NEO4J_PASSWORD`), `neo4j_reader_password` (`NEO4J_READER_PASSWORD`), `s3_access_key` (`S3_ACCESS_KEY`), `s3_secret_key` (`S3_SECRET_KEY`), `webhook_secret` (`WEBHOOK_SECRET`), `grafana_admin_password` (`GRAFANA_ADMIN_PASSWORD`), `eam_api_token` (`EAM_API_TOKEN`), `console_pg_password` (`CONSOLE_PG_PASSWORD`) 🟢. Внутри контейнера это файл `/run/secrets/<target>`; Java-образы экспортируют его в переменную `<target>` (`platform/docker/entrypoint.sh:5-10`). Neo4j собирает `NEO4J_AUTH` в собственном entrypoint из `neo4j_password` (`platform/docker/docker-compose.yml:53-58`); PostgreSQL читает `POSTGRES_PASSWORD_FILE` (`platform/docker/docker-compose.yml:107`); Grafana — `GF_SECURITY_ADMIN_PASSWORD__FILE` (`platform/docker/docker-compose.yml:506`) 🟢. Не секреты (лежат в `environment`): ключи SeaweedFS `AWS_*`, bootstrap-админ Keycloak, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_CONSOLE_PASSWORD` (`platform/docker/docker-compose.yml:122-125, 141-142, 149, 155`) 🟢 — Q-09.

Учётки хранилищ: Neo4j `neo4j` (writer) — только `ingestion` и `neo4j-init`; `archrag_reader` — `mcp-server`, `admin-console` (создаёт `platform/docker/neo4j/init-reader.sh:13-14`; роли в Community нет, read-only обеспечивает приложение); PostgreSQL владелец — `ingestion` и адаптеры, `archrag_console_ro` — `admin-console` (роль создаёт миграция V6, пароль задаёт `platform/docker/postgres/init-console-ro.sh:7-10`) 🟢.

## Образ и сборка

`Dockerfile` (корень контекста — репозиторий): `FROM eclipse-temurin:21-jre`, `ARG JAR`, системный пользователь `archrag` (uid 10001, без домашнего каталога), `COPY ${JAR} /app/app.jar`, `COPY --chmod=755 platform/docker/entrypoint.sh`, `USER 10001`, `ENTRYPOINT ["/app/entrypoint.sh"]` (`platform/docker/Dockerfile:3-14`) 🟢. `Dockerfile.dockerignore` оставляет в контексте только `**/target/*-exec.jar` и `entrypoint.sh` (`platform/docker/Dockerfile.dockerignore:2-4`) 🟢. Пути jar в compose содержат версию `0.1.0-SNAPSHOT` (`platform/docker/docker-compose.yml:11, 183, 227, 279`) 🟡 — Q-10. `curl` для healthcheck есть в базовом образе (`platform/docker/Dockerfile:12`) 🟢.

## Reverse-proxy (`platform/docker/proxy/nginx.conf`)

Слушает `8443 ssl`, `TLSv1.2/1.3`, сертификат `/certs/proxy/tls.crt` (`platform/docker/proxy/nginx.conf:6-10`); DNS-резолвер `127.0.0.11 valid=5s`, адреса upstream в переменных `$mcp`, `$kc`, `$up` — резолв на каждый запрос (`platform/docker/proxy/nginx.conf:1-4, 19, 30, 37, 44`). Маршруты: `= /healthz` → `200 ok` (`platform/docker/proxy/nginx.conf:13-16`); `= /mcp` → `mcp-server:8080`, `proxy_buffering off`, `proxy_read_timeout 120s`, `X-Forwarded-For` (`platform/docker/proxy/nginx.conf:18-26`); `/.well-known/oauth-protected-resource` → `mcp-server` (`platform/docker/proxy/nginx.conf:29-33`); `/realms/archrag/protocol/openid-connect/` → `keycloak:8080`, админка Keycloak не проксируется (`platform/docker/proxy/nginx.conf:35-40`); `= /webhooks/{eam,scm,cmdb,deploymap}` → `adapter-*:8080` с `rewrite ^ /webhook break` (`platform/docker/proxy/nginx.conf:43-62`); `/control/snapshot` наружу не публикуется (`platform/docker/proxy/nginx.conf:42`). Заголовков безопасности, лимитов запросов и ограничения размера тела нет 🟡 — Q-11.

## Скрипты

| Скрипт | Назначение | Где запускается | Код выхода | Подробнее |
|--------|------------|-----------------|------------|-----------|
| `platform/docker/smoke.sh` | границы внешней точки, MCP, секреты, сети, admin REST, Bolt | хост (Docker, curl, python3) | 0 / 1 | [`network-secrets/design.md`](network-secrets/design.md) |
| `platform/docker/console-smoke.sh` | вход PKCE, 401/403/200, граница с ingestion, loopback, секреты консоли | хост (Docker, curl, openssl, python3) | 0 / 1 | `network-secrets/design.md` |
| `platform/docker/scripts/backup.sh [метка]` | dump Neo4j + pg_dump → S3 | хост (Docker) | 0 / 1 | [`backup-restore/design.md`](backup-restore/design.md) |
| `platform/docker/scripts/restore.sh <метка>` | load Neo4j + replay | хост | 0 / 1 | `backup-restore/design.md` |
| `platform/docker/scripts/backup-restore-check.sh` | сквозная приёмка E5.4 | хост | 0 / 1 | `backup-restore/design.md` |
| `platform/docker/scripts/graph-fingerprint.sh` | отпечаток графа | хост | 0 / 1 | `backup-restore/design.md` |
| `platform/docker/scripts/measure.sh` | замеры критерия 9 | хост (Docker, python3) | 0 / 1 | [`observability-measure/design.md`](observability-measure/design.md) |
| `platform/docker/scripts/deployment-commit-check.sh` | смена commit развёртывания | хост (Docker, python3) | 0 / 1 | `observability-measure/design.md` |
| `platform/docker/scripts/lib.sh` | общие функции (подключается `.`) | — | — | `backup-restore/design.md` |

Скрипты читают `.env` рядом (`platform/docker/scripts/lib.sh:9`, `platform/docker/smoke.sh:6`), не используют `latest`-образы (`platform/docker/scripts/lib.sh:15-16`), не принимают пароли аргументами скрипта 🟢; места, где секрет всё же попадает в argv процесса, — Q-08.

## Зависит от / Связанные спецификации

Источник: [`../traceability/spec-impact-matrix.md`](../traceability/spec-impact-matrix.md) §1, §2, §3.

**Прямые зависимости.** Maven-зависимостей нет (не модуль). Образы собираются из `*-exec.jar`: `ingestion/ingestion-service`, `access/mcp-server`, `access/admin-console`, `platform/demo-launcher` (`platform/docker/docker-compose.yml:11, 183, 227, 279`) 🟢. Матрица §1, строка `platform/docker` (`_reversa_sdd/traceability/spec-impact-matrix.md:26`): «все контейнеры стенда, Keycloak realm, nginx; проверка — `docker-stand` job в CI».

| Модуль | Что берёт стенд | Спецификация |
|--------|-----------------|--------------|
| `ingestion/ingestion-service` | образ, admin REST `:8081`, миграции V3/V6 (роль `archrag_console_ro`), `ARCHRAG_*_CONTROL_URL`, `ARCHRAG_ADMIN_RESOURCE_URI`, `ARCHRAG_ADMIN_ALLOWED_CLIENTS` | [`../ingestion-service/design.md`](../ingestion-service/design.md) |
| `access/mcp-server` | образ, `/mcp`, `ARCHRAG_MCP_RESOURCE_URI`, reader Neo4j | [`../flowcharts/mcp-server.md`](../flowcharts/mcp-server.md) |
| `access/admin-console`, `access/admin-ui` | образ, порт консоли, клиент Keycloak | [`../admin-ui/design.md`](../admin-ui/design.md), [`../flowcharts/admin-console.md`](../flowcharts/admin-console.md) |
| `platform/demo-launcher`, `platform/source-stubs` | `adapter`/`stub`/`eam-api-stub`, seed (`StubSources`, `EamApiSeed`) | [`../flowcharts/demo-launcher.md`](../flowcharts/demo-launcher.md), [`../flowcharts/source-stubs.md`](../flowcharts/source-stubs.md) |
| `adapters/adapter-core` | `/webhook`, `/control/snapshot`, `/health`, polling 30 с | [`../adapter-core/design.md`](../adapter-core/design.md) |
| `platform/integration-tests` | те же образы SeaweedFS/Neo4j в Testcontainers (`TestImages`) | [`../flowcharts/integration-tests.md`](../flowcharts/integration-tests.md) |

**Неявные связи (§2) и сквозные последствия (§3):**

| № | Связь | Где в стенде | Защита |
|---|-------|--------------|--------|
| 2, 3 | Коды источников и их регистр (`eam`, `scm`, `cmdb`, `deploymap`; `urn:corp:<код>`) | `platform/docker/scripts/lib.sh:21` (`REPLAY_SOURCES`); `platform/docker/docker-compose.yml:196-199` (`ARCHRAG_*_CONTROL_URL`); `platform/docker/docker-compose.yml:313, 346, 368` (`adapter EAM/SCM/CMDB/DEPLOY_MAP`); `platform/docker/proxy/nginx.conf:43-62` (`/webhooks/*`) | нет теста «покрыты все источники» 🟢 [R.8: ответ владельца, вопрос 9] (Q-05) |
| 15 | Имя модуля ≠ код источника (`asset-adapter` ↔ `cmdb`, `deploymap-adapter` ↔ `DEPLOY_MAP`/`deploymap`) | `platform/docker/docker-compose.yml:352-378` (`adapter-cmdb` запускает `CMDB`, `adapter-deploymap` — `DEPLOY_MAP`) | документация 🟡 |
| 16 | Формат deploy map (`deploymap-poc-0/1`) | `platform/docker/scripts/deployment-commit-check.sh:104` (payload `deploymap-poc-1`) ↔ `platform/source-stubs` | `DeployMapFixturesContractTest` для адаптера; скрипт — только запуск 🟡 |
| — | §3 «Новый источник данных»: правки в `docker` | новый `adapter-*` и `stub-*` в compose, `ARCHRAG_<КОД>_CONTROL_URL` у `ingestion`, маршрут `/webhooks/<код>` в nginx, элемент `REPLAY_SOURCES`, healthcheck и `depends_on` у `reverse-proxy` | см. [`stand-startup/tasks.md`](stand-startup/tasks.md) T-08 |
| — | Новый scope или роль (§3) | `platform/docker/keycloak/archrag-realm.json:5-28`, audience-переменные `platform/docker/docker-compose.yml:149-155` | [`keycloak-oidc/design.md`](keycloak-oidc/design.md) |

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Compose вместо Kubernetes; Docker secrets вместо Vault; сети вместо NetworkPolicy | `platform/docker/docker-compose.yml:1-3`; ADR [0021](../../docs/adr/0021-compose-demo-stand.md) | 🟢 |
| SeaweedFS вместо MinIO (MinIO больше не публикует образы) | `platform/docker/docker-compose.yml:119-135`; ADR [0007](../../docs/adr/0007-s3-raw-storage-seaweedfs.md) | 🟢 |
| Один Dockerfile на все приложения; jar собирает Maven до `docker build` | `platform/docker/Dockerfile:1-2` | 🟢 |
| Секреты → env внутри контейнера (`entrypoint.sh`), не через `environment` compose | `platform/docker/entrypoint.sh:2-4` | 🟢 |
| Keycloak `KC_HOSTNAME=http://keycloak:8080` фиксирует `iss`; браузеру нужна запись `127.0.0.1 keycloak` | `platform/docker/docker-compose.yml:143-145`; `platform/docker/README.md:121` | 🟢 |
| Холодный backup: остановка `ingestion`, `mcp-server`, `neo4j` на время dump | `platform/docker/scripts/backup.sh:5-7, 24-25` | 🟢 |
| Адаптеры не масштабируются (замок snapshot — in-process) | `platform/docker/docker-compose.yml:307-309, 330` | 🟢 |
| Healthcheck `ingestion` — «любой HTTP-ответ» (нет актуатора в проверке) | `platform/docker/docker-compose.yml:213-217` | 🟢 |
| `admin-console` в отдельной сети `console-edge` ради публикации порта на loopback | `platform/docker/docker-compose.yml:290-291, 540-543` | 🟢 |

## Estado Interno

Состояние стенда — в томах: `certs` (демо-PKI, `.ready`), `ca-key` (ключ CA, только `certs`), `neo4j-data`, `pg-data`, `s3-data`, `backups` (рабочий каталог dump), `prometheus-data`, `grafana-data` (`platform/docker/docker-compose.yml:566-574`) 🟢. `down` сохраняет тома, `down -v` удаляет (`platform/docker/README.md:156-159`). Состояние заглушек (`ver` в `eam-api-stub`, правки `stub-deploymap`) живёт в памяти контейнеров и переживает прогоны сценариев до пересоздания контейнера 🟡 (`platform/docker/scripts/backup-restore-check.sh:12`).

## Riscos e Lacunas

- 🔴 Плавающие теги `neo4j:5-community` (и `postgres:16`): `restore.sh` откажется читать копию после смены минорной версии; в тестах тег уже точный (`5.26.31`) — Q-01.
- 🟢 [R.8: ответ владельца, вопрос 8] Документация утверждает «наружу публикуется только reverse-proxy», фактически — четыре порта на loopback — Q-02.
- 🟢 [R.8: ответ владельца, вопрос 14] CI `docker-stand` не запускает `console-smoke.sh`, `deployment-commit-check.sh`, `measure.sh`, хотя `CLAUDE.md` обещает «весь список» — Q-07.
- 🟡 Роль консоли `archrag-console-admin` не проверяется; токен консоли получает любой пользователь realm (принято владельцем, П-9) — Q-04.
- 🟡 `trap ERR` в `backup.sh`/`restore.sh` не срабатывает на сбой внутри функций `lib.sh` — Q-06.
- 🟡 Секреты попадают в argv процессов в нескольких местах — Q-08; часть секретов лежит в `environment` — Q-09.
