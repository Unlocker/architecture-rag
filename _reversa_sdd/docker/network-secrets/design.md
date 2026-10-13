# network-secrets: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §1–§3, §6, §9. Блок-схема: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §4.

## Interface

| Скрипт | Запуск | Предусловия | Результат | Трассировка |
|--------|--------|-------------|-----------|-------------|
| `smoke.sh` | `platform/docker/smoke.sh` (из каталога `platform/docker`) | поднятый стенд, `.env` рядом, curl, python3, Docker | `smoke ok: …` либо `smoke FAILED: <причина>` (код 1) | `platform/docker/smoke.sh:1-10, 89` 🟢 |
| `console-smoke.sh` | `platform/docker/console-smoke.sh` | то же + openssl | `ok 1…7`, `console-smoke ok` либо `console-smoke FAILED: …` | `platform/docker/console-smoke.sh:1-19, 126` 🟢 |

## Fluxo Principal

### `smoke.sh` (порядок проверок)

| № | Проверка | Строки |
|---|----------|--------|
| 1 | `ca.crt` копируется из тома: `docker compose cp certs:/certs/ca.crt` (без `-k`) | `platform/docker/smoke.sh:12` |
| 2 | `POST /mcp` (`initialize`) без токена = 401 | `platform/docker/smoke.sh:13-19` |
| 3 | Токен `client_credentials` клиента `archrag-demo` через proxy; `POST /mcp` = 200; `tools/call ping` возвращает `result` | `platform/docker/smoke.sh:21-31` |
| 4 | Plain HTTP на TLS-порт ≠ 200 | `platform/docker/smoke.sh:33-35` |
| 5 | Хост-порты 7474, 7687, 5432 закрыты | `platform/docker/smoke.sh:37-40` |
| 6 | В `mcp-server` (индекс 1): env без PG/writer/S3/webhook; `/run/secrets` = `ARCHRAG_NEO4J_READER_PASSWORD`; контроль: `neo4j` резолвится и достижим; `postgres` не резолвится и недостижим | `platform/docker/smoke.sh:42-53` |
| 7 | В `reverse-proxy`: `mcp-server` резолвится (контроль), `ingestion` — нет | `platform/docker/smoke.sh:55-57` |
| 8 | Admin REST изнутри `ingestion`: токен scope `architecture.admin` → 200, scope `architecture.read` → 401 (отказ от проверки audience, не scope) | `platform/docker/smoke.sh:59-79` |
| 9 | Plain Bolt к `neo4j:7687` из `ingestion` закрывается без ответа (`CLOSED`) | `platform/docker/smoke.sh:81-87` |

### `console-smoke.sh` (порядок проверок)

| № | Проверка | Строки |
|---|----------|--------|
| 1 | `GET /console-config.json` называет клиент консоли; вход Authorization Code + PKCE S256 на curl (форма логина Keycloak → `code` → обмен на токен); direct grant для публичного клиента → 400/401 | `platform/docker/console-smoke.sh:47-56` |
| 2 | `/api/graph/stats`, `/api/sync/sources` без токена = 401 | `platform/docker/console-smoke.sh:58-62` |
| 3 | Токен консоли без `architecture.admin` (только `architecture.read`) = 403; токен агента (аудитория MCP) = 401 | `platform/docker/console-smoke.sh:64-71` |
| 4 | Токен с `architecture.admin`: `/api/graph/stats` = 200 (непустое тело), `/api/sync/sources` = 200 и ровно 4 источника | `platform/docker/console-smoke.sh:73-83` |
| 5 | Предпосылка: в токене консоли есть аудитория `urn:archrag:admin` и `azp = archrag-admin-console`; такой токен на `ingestion` `/admin/replay` = 401 | `platform/docker/console-smoke.sh:85-95` |
| 6 | Консоль достижима на `127.0.0.1` (контроль); на не-loopback IPv4 хоста порты консоли и Keycloak закрыты (без IP — `skip 6`); `docker compose port` для `admin-console` и `keycloak` начинается с `127.0.0.1:`; proxy резолвит `mcp-server`, но не `admin-console` | `platform/docker/console-smoke.sh:97-112` |
| 7 | В `admin-console`: `/run/secrets` = два секрета; env без `ARCHRAG_PG_USER/PASSWORD`, `NEO4J_PASSWORD`, `ARCHRAG_NEO4J_PASSWORD`, `ARCHRAG_NEO4J_USER`, `ARCHRAG_S3_*`, `ARCHRAG_WEBHOOK_SECRET`; контроль: `neo4j:7687` и `postgres:5432` достижимы | `platform/docker/console-smoke.sh:114-124` |

Механика входа (`login()`): `code_verifier` — 64 случайных символа base64url, `code_challenge` — SHA-256 → base64url, `state` — 8 случайных байт hex; форма логина парсится регулярным выражением из HTML, пароль читается из файла (`password@file`), `state` из redirect сверяется с отправленным 🟢 (`platform/docker/console-smoke.sh:22-41`).

## Fluxos Alternativos

- Любая проверка проваливается → `fail()` печатает причину в stderr и завершает скрипт кодом 1; последующие проверки не выполняются 🟢 (`platform/docker/smoke.sh:10`; `platform/docker/console-smoke.sh:18`).
- Нет внешнего IPv4 у хоста → проверка 6 пропускается (`skip 6`), публикация проверяется только по `docker compose port` 🟢 (`platform/docker/console-smoke.sh:99-108`).
- Форма логина Keycloak не найдена (Keycloak недоступен на `127.0.0.1:<порт>`) → `fail "login form not found …"` 🟢 (`platform/docker/console-smoke.sh:32`).
- Редирект без `code` (неверные учётные данные или redirect URI) → `fail "no authorization code in redirect …"` 🟢 (`platform/docker/console-smoke.sh:36-37`).
- `hostname -I` недоступен → `ext` пуст, как «нет IP» 🟡 (`platform/docker/console-smoke.sh:99`).
- Токен агента вместо admin-токена на `admin_replay` с scope `architecture.read` → `401`, а не `403`: отказ приходит от проверки audience; 403 вернулся бы, если бы токен проходил аудиторию, но не имел scope 🟢 (`platform/docker/smoke.sh:59-60, 78-79`).

## Dependências

Docker Compose (`docker compose cp/exec/port`), `curl`, `openssl`, `python3 -I` (разбор JSON/HTML), Keycloak (клиенты `archrag-demo`, `archrag-admin-console`), `ingestion`, `mcp-server`, `admin-console`, `reverse-proxy` 🟢. Связанные спецификации: [`../keycloak-oidc/design.md`](../keycloak-oidc/design.md), [`../../ingestion-service/design.md`](../../ingestion-service/design.md) (admin REST, `azp`), [`../../admin-ui/oidc-login/design.md`](../../admin-ui/oidc-login/design.md) (клиентская сторона входа).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Проверять отсутствие (не резолвится, не достижимо) только вместе с положительным контролем | `platform/docker/smoke.sh:49-52, 56`; `platform/docker/console-smoke.sh:111, 121-123` | 🟢 |
| Admin REST проверяется изнутри контейнера, потому что порт не публикуется | `platform/docker/smoke.sh:59-61` | 🟢 |
| `console-smoke.sh` обходит `/etc/hosts` через `curl --connect-to` | `platform/docker/console-smoke.sh:4-6, 19` | 🟢 |
| Plain Bolt проверяется ручным handshake'ом (`60 60 b0 17 …`) средствами bash, без драйвера | `platform/docker/smoke.sh:82-86` | 🟢 |
| `python3 -I` для разбора ответов (изоляция от каталога) | `platform/docker/console-smoke.sh:31, 36, 40, 75` | 🟢 |

## Riscos e Lacunas

- 🟢 [R.8: ответ владельца, вопрос 14] `console-smoke.sh` в CI не запускается; граница консоли в CI не проверяется — Q-07.
- 🟡 Секрет клиента попадает в argv `curl` у `smoke.sh` и `console-smoke.sh` — Q-08.
- 🟡 `POST /control/snapshot` адаптера не аутентифицируется — Q-22.
- 🟡 Роль консоли не принудительна — Q-04.
- 🟡 Наличие Keycloak master-админки на loopback — Q-15.
