# platform/docker: внешние контракты

> Что стенд публикует и принимает: порты, HTTP-границы, переменные `.env`, Docker secrets, тома, realm Keycloak, интерфейсы скриптов и артефакты backup. Значения секретов не приводятся, только имена. `DC` в тексте не используется: файл compose — `platform/docker/docker-compose.yml`.

## 1. Опубликованные на хост порты

Все привязаны к `127.0.0.1` (loopback) 🟢.

| Порт (переменная, по умолчанию) | Сервис : порт | Протокол | Назначение | Строки |
|---------------------------------|---------------|----------|------------|--------|
| `ARCHRAG_PUBLIC_PORT` = 8443 | `reverse-proxy` : 8443 | HTTPS (TLS 1.2/1.3, демо-CA) | `/mcp`, `/.well-known/oauth-protected-resource`, OIDC token endpoint, `/webhooks/*`, `/healthz` | `platform/docker/docker-compose.yml:450-451` |
| `ARCHRAG_KEYCLOAK_PORT` = 8080 | `keycloak` : 8080 | HTTP | OIDC для браузера консоли; тот же порт открывает master-админку Keycloak на loopback | `platform/docker/docker-compose.yml:158-161` |
| `ARCHRAG_CONSOLE_PORT` = 8090 | `admin-console` : 8080 | HTTP | SPA и `/api/**` консоли | `platform/docker/docker-compose.yml:292-293` |
| `GRAFANA_PORT` = 3000 (профиль `observability`) | `grafana` : 3000 | HTTP | дашборд «Arch RAG PoC» | `platform/docker/docker-compose.yml:515-516` |

Не публикуются: Neo4j 7474/7687, PostgreSQL 5432, SeaweedFS 8333, `ingestion` 8081, `mcp-server` 8080, адаптеры 8080/8090, заглушки 8080/8091, collector 4318/8889/13133, Prometheus 9090 🟢 (`platform/docker/smoke.sh:37-40`; `platform/docker/README.md:104, 151`).

## 2. HTTP-границы reverse-proxy

| Путь | Метод | Upstream | Аутентификация | Трассировка |
|------|-------|----------|----------------|-------------|
| `/healthz` | любой | сам nginx, `200 ok` | нет | `platform/docker/proxy/nginx.conf:13-16` 🟢 |
| `/mcp` (точное совпадение) | `POST` и др. | `mcp-server:8080` | Bearer JWT: issuer Keycloak, audience `ARCHRAG_MCP_RESOURCE_URI`, scope tool'а (на стороне MCP) | `platform/docker/proxy/nginx.conf:18-26` 🟢 |
| `/.well-known/oauth-protected-resource` | `GET` | `mcp-server:8080` | нет (RFC 9728) | `platform/docker/proxy/nginx.conf:29-33` 🟢 |
| `/realms/archrag/protocol/openid-connect/` (префикс) | любой | `keycloak:8080` | — (token endpoint, auth, certs) | `platform/docker/proxy/nginx.conf:36-40` 🟢 |
| `/webhooks/eam`, `/webhooks/scm`, `/webhooks/cmdb`, `/webhooks/deploymap` | `POST` | `adapter-<код>:8080/webhook` | HMAC-SHA256 заголовков `X-Archrag-Timestamp`/`X-Archrag-Signature` (проверяет адаптер, секрет `webhook_secret`) | `platform/docker/proxy/nginx.conf:43-62`; `_reversa_sdd/permissions.md` §7 🟢 |
| всё остальное | — | `404` nginx | — | отсутствие `location` 🟡 |

`/control/snapshot` адаптера и админка ingestion через proxy недоступны (`platform/docker/proxy/nginx.conf:42`; `platform/docker/docker-compose.yml:207`) 🟢.

## 3. Внутренние HTTP-интерфейсы, которые использует стенд

| Интерфейс | Адрес | Кто вызывает | Строки |
|-----------|-------|--------------|--------|
| Admin REST ingestion | `http://127.0.0.1:8081/admin/{replay,rebuild,reconcile/{source},crosswalks}` (изнутри контейнера `ingestion`) | `restore.sh`, `smoke.sh`, `console-smoke.sh` | `platform/docker/scripts/lib.sh:114`; `platform/docker/smoke.sh:73`; `platform/docker/console-smoke.sh:92` 🟢 |
| `POST /control/snapshot` адаптера | `http://adapter-<код>:8080/control/snapshot` | `ingestion` (`/admin/reconcile`) | `platform/docker/docker-compose.yml:196-199` 🟢 |
| `GET /health` адаптера | `:8090` | healthcheck | `platform/docker/docker-compose.yml:331-336` 🟢 |
| `POST /control/upsert`, `/control/archive` заглушек | `:8091` внутри `stub-*` и `eam-api-stub` | сценарии и `measure.sh` через `docker compose exec` | `platform/docker/scripts/backup-restore-check.sh:16`; `platform/docker/scripts/deployment-commit-check.sh:107`; `platform/docker/README.md:51-57` 🟢 |
| Webhook заглушка → адаптер | `ARCHRAG_WEBHOOK_TARGET=http://adapter-<код>:8080/webhook` (мимо TLS-proxy) | `stub-*` | `platform/docker/docker-compose.yml:387, 403, 414, 425` 🟢 |
| `GET /actuator/health` | `mcp-server:8080`, `admin-console:8080` | healthcheck | `platform/docker/docker-compose.yml:247-252, 300-305` 🟢 |
| OTLP/HTTP метрик | `http://otel-collector:4318/v1/metrics` | `mcp-server`, `ingestion` при `ARCHRAG_OBSERVABILITY=true` | `platform/docker/docker-compose.yml:200-201, 235-236` 🟢 |
| Prometheus scrape | `otel-collector:8889` каждые 10 с | `prometheus` | `platform/docker/observability/prometheus.yml:3-8` 🟢 |

## 4. Переменные `.env` (источник — `platform/docker/.env.example`)

`.env` вне Git (`.gitignore:31`). «Обяз.» = подстановка `${VAR:?}` в compose; для секретов compose использует `secrets: …: environment:` 🟢. В таблице имена и значения по умолчанию, не секреты.

| Переменная | Обяз. | По умолчанию / пример | Потребитель | Строки |
|------------|-------|-----------------------|-------------|--------|
| `ARCHRAG_PUBLIC_PORT` | нет | `8443` | порт proxy, `ARCHRAG_MCP_RESOURCE_URI` (`https://localhost:<порт>/mcp`) у `keycloak` и `mcp-server` | `platform/docker/.env.example:5`; `platform/docker/docker-compose.yml:150, 232, 451` |
| `ARCHRAG_CONSOLE_PORT` | нет | `8090` | порт консоли, redirect URI клиента консоли | `platform/docker/.env.example:9`; `platform/docker/docker-compose.yml:152, 293` |
| `ARCHRAG_KEYCLOAK_PORT` | нет | `8080` | порт Keycloak на хосте | `platform/docker/.env.example:10`; `platform/docker/docker-compose.yml:161` |
| `POSTGRES_DB`, `POSTGRES_USER` | да | `archrag` (оба) | `postgres`, `ingestion`, адаптеры, `console-pg-init`, скрипты | `platform/docker/.env.example:12-13`; `platform/docker/docker-compose.yml:17-18, 105-106, 188-189, 259-261, 286` |
| `POSTGRES_PASSWORD` | секрет | демо-значение в примере | secret `pg_password` | `platform/docker/.env.example:14`; `platform/docker/docker-compose.yml:547-548` |
| `NEO4J_PASSWORD` | секрет | демо | secret `neo4j_password` (writer) | `platform/docker/.env.example:17`; `platform/docker/docker-compose.yml:549-550` |
| `NEO4J_READER_PASSWORD` | секрет | демо | secret `neo4j_reader_password`; не должен содержать `"` и `\` | `platform/docker/.env.example:19`; `platform/docker/docker-compose.yml:551-552`; `platform/docker/neo4j/init-reader.sh:7-9` |
| `CONSOLE_PG_PASSWORD` | секрет | демо | secret `console_pg_password` | `platform/docker/.env.example:22`; `platform/docker/docker-compose.yml:563-564` |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY` | да | демо | `seaweedfs` (`AWS_*`), secrets `s3_access_key`/`s3_secret_key`, `aws-cli` в скриптах | `platform/docker/.env.example:24-25`; `platform/docker/docker-compose.yml:124-125, 553-556`; `platform/docker/scripts/lib.sh:55-59` |
| `S3_BUCKET` | да | `archrag-raw` | бакет raw payload | `platform/docker/.env.example:26`; `platform/docker/docker-compose.yml:20, 194` |
| `BACKUP_BUCKET` | нет | `archrag-backups` | бакет копий (скрипты) | `platform/docker/.env.example:48`; `platform/docker/scripts/lib.sh:14` |
| `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD` | да | демо | bootstrap-админ Keycloak (в `environment`) | `platform/docker/.env.example:28-29`; `platform/docker/docker-compose.yml:141-142` |
| `KEYCLOAK_CLIENT_SECRET` | да | демо | секрет клиента `archrag-demo`, подставляется в realm; читают скрипты | `platform/docker/.env.example:31`; `platform/docker/docker-compose.yml:149`; `platform/docker/keycloak/archrag-realm.json:56` |
| `KEYCLOAK_CONSOLE_USER`, `KEYCLOAK_CONSOLE_PASSWORD` | да | демо | пользователь консоли в realm | `platform/docker/.env.example:34-35`; `platform/docker/docker-compose.yml:154-155`; `platform/docker/keycloak/archrag-realm.json:37-44` |
| `WEBHOOK_SECRET` | секрет | демо | secret `webhook_secret` (подпись webhook) | `platform/docker/.env.example:38`; `platform/docker/docker-compose.yml:557-558` |
| `EAM_API_TOKEN` | секрет | демо | secret `eam_api_token` (`Authorization: Token`) | `platform/docker/.env.example:41`; `platform/docker/docker-compose.yml:561-562` |
| `ARCHRAG_EAM_MODE` | нет | `eam-api` (иначе `legacy`) | режим `adapter-eam` | `platform/docker/.env.example:45`; `platform/docker/docker-compose.yml:324` |
| `ARCHRAG_OBSERVABILITY` | нет | `false` | включение OTLP-push | `platform/docker/.env.example:51`; `platform/docker/docker-compose.yml:200, 235` |
| `GRAFANA_PORT` | нет | `3000` | порт Grafana | `platform/docker/.env.example:53`; `platform/docker/docker-compose.yml:516` |
| `GRAFANA_ADMIN_PASSWORD` | секрет | демо | secret `grafana_admin_password` | `platform/docker/.env.example:54`; `platform/docker/docker-compose.yml:559-560` |

Поведение при отсутствии переменной секрета (без `:?`) не проверено 🟡. Переменная `ARCHRAG_ADMIN_ALLOWED_CLIENTS` в compose не задана: действует значение по умолчанию ingestion (`archrag-demo`) 🟢 (`platform/docker/README.md:125`).

## 5. Переменные окружения приложений (задаёт compose)

| Сервис | Переменные | Строки |
|--------|------------|--------|
| `ingestion` | `ARCHRAG_ADMIN_RESOURCE_URI=urn:archrag:admin`, `ARCHRAG_PG_URL`, `ARCHRAG_PG_USER`, `ARCHRAG_NEO4J_URI=bolt+ssc://neo4j:7687`, `ARCHRAG_NEO4J_USER=neo4j`, `ARCHRAG_S3_ENDPOINT=http://seaweedfs:8333`, `ARCHRAG_S3_BUCKET`, `ARCHRAG_OIDC_ISSUER_URI=http://keycloak:8080/realms/archrag`, `ARCHRAG_{EAM,SCM,CMDB,DEPLOYMAP}_CONTROL_URL`, `MANAGEMENT_OTLP_METRICS_EXPORT_{ENABLED,URL}` | `platform/docker/docker-compose.yml:185-201` |
| `mcp-server` | `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_MCP_RESOURCE_URI`, `ARCHRAG_NEO4J_READER_URI`, `ARCHRAG_NEO4J_READER_USERNAME=archrag_reader`, `MANAGEMENT_OTLP_*` | `platform/docker/docker-compose.yml:230-236` |
| `admin-console` | `ARCHRAG_OIDC_ISSUER_URI`, `ARCHRAG_CONSOLE_RESOURCE_URI=urn:archrag:console`, `ARCHRAG_NEO4J_READER_URI`, `ARCHRAG_NEO4J_READER_USERNAME`, `ARCHRAG_CONSOLE_PG_URL` | `platform/docker/docker-compose.yml:281-286` |
| адаптеры | `ARCHRAG_PG_URL`, `ARCHRAG_PG_USER`, `ARCHRAG_S3_ENDPOINT`, `ARCHRAG_S3_BUCKET`, `ARCHRAG_SOURCE_URL=http://stub-<код>:8080`; у `adapter-eam` ещё `ARCHRAG_EAM_MODE`, `ARCHRAG_EAM_API_URL=http://eam-api-stub:8080` | `platform/docker/docker-compose.yml:16-20, 321-325, 343-345, 357-359, 371-373` |
| `keycloak` | `KC_BOOTSTRAP_ADMIN_*`, `KC_HOSTNAME=http://keycloak:8080`, `KC_HTTP_ENABLED`, `KC_HEALTH_ENABLED`, подстановки realm: `KEYCLOAK_CLIENT_SECRET`, `ARCHRAG_MCP_RESOURCE_URI`, `ARCHRAG_ADMIN_RESOURCE_URI`, `ARCHRAG_CONSOLE_PORT`, `ARCHRAG_CONSOLE_RESOURCE_URI`, `KEYCLOAK_CONSOLE_USER`, `KEYCLOAK_CONSOLE_PASSWORD` | `platform/docker/docker-compose.yml:140-155` |
| `neo4j` | `NEO4J_server_bolt_tls__level=REQUIRED`, политика SSL `bolt` (каталог `/certs/neo4j`, ключ `private.key`, сертификат `public.crt`, `client_auth=NONE`) | `platform/docker/docker-compose.yml:62-67` |

Инварианты, которые нельзя разводить 🟢: `ARCHRAG_MCP_RESOURCE_URI` у `keycloak` и `mcp-server` — один и тот же URI (`platform/docker/docker-compose.yml:150, 232`); `urn:archrag:admin` у `keycloak` и `ingestion` (`platform/docker/docker-compose.yml:151, 187`); `urn:archrag:console` у `keycloak` и `admin-console` (`platform/docker/docker-compose.yml:153, 283`); issuer `http://keycloak:8080/realms/archrag` у `ingestion`, `mcp-server`, `admin-console` совпадает с `KC_HOSTNAME` (`platform/docker/docker-compose.yml:145, 195, 231, 282`).

## 6. Docker secrets → переменные

Файл `/run/secrets/<target>` → переменная `<target>` (Java-образы). Таблица распределения:

| Сервис | target'ы |
|--------|----------|
| `neo4j` | `neo4j_password` (`platform/docker/docker-compose.yml:68-69`) |
| `neo4j-init` | `neo4j_password`, `neo4j_reader_password` (`platform/docker/docker-compose.yml:92-94`) |
| `postgres` | `pg_password` (`platform/docker/docker-compose.yml:108-109`) |
| `ingestion` | `ARCHRAG_PG_PASSWORD`, `ARCHRAG_NEO4J_PASSWORD`, `ARCHRAG_S3_ACCESS_KEY`, `ARCHRAG_S3_SECRET_KEY` (`platform/docker/docker-compose.yml:202-206`) |
| `mcp-server` | `ARCHRAG_NEO4J_READER_PASSWORD` (`platform/docker/docker-compose.yml:238-239`) |
| `console-pg-init` | `pg_password`, `console_pg_password` (`platform/docker/docker-compose.yml:262-264`) |
| `admin-console` | `ARCHRAG_NEO4J_READER_PASSWORD`, `ARCHRAG_CONSOLE_PG_PASSWORD` (`platform/docker/docker-compose.yml:287-289`) |
| `adapter-scm`, `adapter-cmdb`, `adapter-deploymap` | `ARCHRAG_PG_PASSWORD`, `ARCHRAG_S3_ACCESS_KEY`, `ARCHRAG_S3_SECRET_KEY`, `ARCHRAG_WEBHOOK_SECRET` (`platform/docker/docker-compose.yml:22-26`) |
| `adapter-eam` | те же + `ARCHRAG_EAM_API_TOKEN` (`platform/docker/docker-compose.yml:314-319`) |
| `stub-*` | `ARCHRAG_WEBHOOK_SECRET` (`platform/docker/docker-compose.yml:388-389`) |
| `eam-api-stub` | `ARCHRAG_EAM_API_TOKEN` (`platform/docker/docker-compose.yml:438-439`) |
| `grafana` | `grafana_admin_password` (`platform/docker/docker-compose.yml:509-510`) |

Состав проверяют `smoke.sh` (у `mcp-server` только `ARCHRAG_NEO4J_READER_PASSWORD`) и `console-smoke.sh` (у `admin-console` ровно два секрета) 🟢 (`platform/docker/smoke.sh:47-48`; `platform/docker/console-smoke.sh:116-117`).

## 7. Тома

`certs` (CA-сертификат, серверные сертификаты proxy и Neo4j, флаг `.ready`), `ca-key` (приватный ключ CA; монтируется только в `certs`), `neo4j-data`, `backups` (рабочий каталог dump, монтируется в `neo4j`), `pg-data`, `s3-data`, `prometheus-data`, `grafana-data` 🟢 (`platform/docker/docker-compose.yml:44-47, 70-75, 111-112, 126-127, 489, 514, 566-574`). Привязки с хоста (только чтение): `gen-certs.sh`, `init-reader.sh`, `init-console-ro.sh`, `archrag-realm.json`, `nginx.conf`, конфигурации observability.

## 8. Realm Keycloak `archrag`

Подробно — [`keycloak-oidc/requirements.md`](keycloak-oidc/requirements.md). Кратко: scope'ы `architecture.read`, `architecture.admin` (с audience-mapper на `ARCHRAG_ADMIN_RESOURCE_URI`); роль `archrag-console-admin`; клиенты `archrag-demo` (confidential, `client_credentials`, default scope `architecture.read`, optional `architecture.admin`, mapper audience MCP) и `archrag-admin-console` (public, standard flow, PKCE S256, redirect `http://127.0.0.1:<порт консоли>/*`, optional `architecture.admin`/`architecture.read`, mapper audience консоли) 🟢 (`platform/docker/keycloak/archrag-realm.json:5-111`).

## 9. Интерфейсы скриптов

| Скрипт | Аргументы / env | Результат | Коды выхода |
|--------|-----------------|-----------|-------------|
| `platform/docker/smoke.sh` | `.env` рядом; `ARCHRAG_PUBLIC_PORT` | строка `smoke ok: …` | 0; 1 при первой проваленной проверке (`smoke FAILED: …` в stderr) (`platform/docker/smoke.sh:10, 89`) |
| `platform/docker/console-smoke.sh` | `.env`; `ARCHRAG_CONSOLE_PORT`, `ARCHRAG_KEYCLOAK_PORT` | строки `ok 1..7`, `console-smoke ok` | 0; 1 (`platform/docker/console-smoke.sh:18, 126`) |
| `platform/docker/scripts/backup.sh [метка]` | метка `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, по умолчанию UTC-штамп `YYYYMMDDTHHMMSSZ`; `GRAPH_START_TIMEOUT` (900 с) | печатает метку; `s3://$BACKUP_BUCKET/<метка>/{neo4j.dump,postgres.dump,manifest.json}` | 0; 1 (`platform/docker/scripts/lib.sh:24, 31-33, 90`; `platform/docker/scripts/backup.sh:11-12, 47-48`) |
| `platform/docker/scripts/restore.sh <метка>` | ровно один аргумент | восстановленный граф, пустая очередь | 0; 1 (`platform/docker/scripts/restore.sh:16-18`) |
| `platform/docker/scripts/backup-restore-check.sh` | `EVENT_WAIT_SECONDS` (300) | три отпечатка и `OK: …` | 0; 1 (`platform/docker/scripts/backup-restore-check.sh:21, 53`) |
| `platform/docker/scripts/graph-fingerprint.sh` | — | строки `nodelabel/reltype …` и хэши `NODE/REL/NODEPROP/RELPROP/TOTAL` | 0; 1 при сбое cypher-shell (`platform/docker/scripts/graph-fingerprint.sh:46, 50-56`) |
| `platform/docker/scripts/measure.sh` | `N`=20, `M`=200, `K`=100, `P`=4, `E2E_PATH`=`webhook\|poll\|both`, `E2E_JITTER_MAX` | markdown-таблица в stdout, сырые данные в `platform/docker/measurements/<UTC-штамп>/` | 0; 1 при нарушении порогов, росте DLQ/QUARANTINED или ошибке tool (`platform/docker/scripts/measure.sh:6-10, 376-380`) |
| `platform/docker/scripts/deployment-commit-check.sh` | `EVENT_WAIT_SECONDS` (300) | `OK: commit A -> B …` | 0; 1 (`platform/docker/scripts/deployment-commit-check.sh:70, 133`) |

Хостовые зависимости: Docker (compose v2), bash; `curl`, `openssl`, `python3` — для `smoke.sh`, `console-smoke.sh`, `measure.sh`, `deployment-commit-check.sh`; `sha256sum`/`sed` внутри контейнеров 🟢 (`platform/docker/console-smoke.sh:4`; `platform/docker/scripts/measure.sh:4`; `platform/docker/scripts/lib.sh:1-5`).

## 10. Манифест копии (`manifest.json`)

Формируется `backup.sh` (`platform/docker/scripts/backup.sh:37-41`): `label`, `backup_started_at` (UTC `…Z`, до остановки сервисов), `neo4j_version`, `neo4j_dump_sha256`, `postgres_dump_sha256` 🟢. `restore.sh` разбирает его `sed`-ом и требует формат `^YYYY-MM-DDThh:mm:ssZ$` для `backup_started_at` (`platform/docker/scripts/restore.sh:29-37`) 🟢.
