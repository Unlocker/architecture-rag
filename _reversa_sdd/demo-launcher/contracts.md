# platform/demo-launcher: внешние контракты

> Командная строка, переменные окружения, порты и HTTP-ручки управляющих входов, коды выхода, compose-привязка. Секреты не приводятся, только имена переменных. Все ссылки на код даны полными путями от корня репозитория.

## 1. Командная строка и коды выхода

| Команда (`command:` в compose) | Роль | Конфигурация | Трассировка |
|--------------------------------|------|--------------|-------------|
| `adapter <EAM\|SCM\|CMDB\|DEPLOY_MAP>` | адаптер мастер-системы | `LauncherConfig.adapter` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:72-78 🟢 |
| `stub <EAM\|SCM\|CMDB\|DEPLOY_MAP>` | заглушка источника + управляющий вход | `LauncherConfig.stub` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:72-78 🟢 |
| `eam-api-stub` | заглушка EAM в формате реального API + управляющий вход | `LauncherConfig.eamApiStub` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:59-67 🟢 |

Код источника — точное имя элемента `SourceSystem` (`EAM`, `SCM`, `CMDB`, `DEPLOY_MAP`), регистр важен (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:98-104) 🟢.

| Ситуация | stderr | Код выхода | Трассировка |
|----------|--------|------------|-------------|
| число аргументов ≠ 2 (и не `eam-api-stub`) либо первый не `adapter`/`stub` | `usage: DemoLauncher <adapter\|stub> <EAM\|SCM\|CMDB\|DEPLOY_MAP> \| DemoLauncher eam-api-stub` | `2` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:68-71 🟢 |
| неизвестный источник или не заданы обязательные переменные | `configuration error: <сообщение с именами переменных>` | `2` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:60-66, 79-82 🟢 |
| штатная остановка (SIGTERM/SIGINT) | — | `0` (выход `main`) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:162-167 🟡 |
| прочее необработанное исключение | стек JVM | `1` (поведение JVM) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:58 🟡 |

## 2. Переменные окружения

Секреты приходят как Docker secrets: `entrypoint.sh` экспортирует каждый файл `/run/secrets/<ИМЯ>` как переменную `<ИМЯ>` (`platform/docker/entrypoint.sh:1-10`; `platform/docker/docker-compose.yml:14-26`) 🟢.

### 2.1 Адаптер (`adapter <SRC>`)

| Переменная | Обяз. | Умолчание | Валидация | Источник на стенде | Трассировка |
|------------|-------|-----------|-----------|--------------------|-------------|
| `ARCHRAG_PG_URL` | да | — | не пусто | `jdbc:postgresql://postgres:5432/${POSTGRES_DB}` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:17, 109; platform/docker/docker-compose.yml:17 |
| `ARCHRAG_PG_USER` | да | — | не пусто | `${POSTGRES_USER}` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:18, 110; platform/docker/docker-compose.yml:18 |
| `ARCHRAG_PG_PASSWORD` | да | — | не пусто; секрет | Docker secret `pg_password` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:19, 111; platform/docker/docker-compose.yml:23 |
| `ARCHRAG_S3_ENDPOINT` | да | — | `URI.create` | `http://seaweedfs:8333` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:20, 112; platform/docker/docker-compose.yml:19 |
| `ARCHRAG_S3_ACCESS_KEY` / `ARCHRAG_S3_SECRET_KEY` | да | — | не пусто; секреты | Docker secrets `s3_access_key`, `s3_secret_key` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:21-22, 113-114; platform/docker/docker-compose.yml:24-25 |
| `ARCHRAG_S3_BUCKET` | да | — | не пусто | `${S3_BUCKET}` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:23, 115; platform/docker/docker-compose.yml:20 |
| `ARCHRAG_SOURCE_URL` | да | — | `URI.create` | `http://stub-<src>:8080` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:24, 116 |
| `ARCHRAG_WEBHOOK_SECRET` | да | — | не пусто; секрет подписи | Docker secret `webhook_secret` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:25, 117; platform/docker/docker-compose.yml:26 |
| `ARCHRAG_WEBHOOK_PORT` | нет | `8080` | целое 1..65535 | не задан | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:26, 174-188 |
| `ARCHRAG_HEALTH_PORT` | нет | `8090` | целое 1..65535 | не задан | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:27, 174-188 |
| `ARCHRAG_POLL_INTERVAL_SECONDS` | нет | `30` | целое ≥ 1 | не задан | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:28, 190-204 |
| `ARCHRAG_RECONCILE_INTERVAL_SECONDS` | нет | `21600` (6 ч) | целое ≥ 1 | не задан | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:29, 190-204 |
| `ARCHRAG_EAM_MODE` | нет (только EAM) | пусто = `legacy` | `legacy` или `eam-api`, иначе ошибка | `${ARCHRAG_EAM_MODE:-eam-api}` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:33, 35-36, 128-139; platform/docker/docker-compose.yml:324 |
| `ARCHRAG_EAM_API_URL` | при `eam-api` | — | `URI.create(strip)` | `http://eam-api-stub:8080` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:34, 136; platform/docker/docker-compose.yml:325 |
| `ARCHRAG_EAM_API_TOKEN` | при `eam-api` | — | не пусто; секрет | Docker secret `eam_api_token` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:31, 137; platform/docker/docker-compose.yml:319 |

Все обязательные переменные собираются в один список `missing`; ошибка — `required environment variables are not set: [имена]` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:107-125) 🟢. Имена констант `STUB_PORT`, `EAM_API_TOKEN`, `WEBHOOK_TARGET` и др. заданы в platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:17-36 🟢.

### 2.2 Заглушка источника (`stub <SRC>`)

| Переменная | Обяз. | Умолчание | Прим. | Трассировка |
|------------|-------|-----------|-------|-------------|
| `ARCHRAG_STUB_PORT` | нет | `8080` | порт источника (`StubSourceServer`) | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:30, 142-153 |
| `ARCHRAG_WEBHOOK_TARGET` | нет | нет (webhook не шлётся) | URL `/webhook` адаптера; `trim` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:32, 143-152; platform/docker/docker-compose.yml:387 |
| `ARCHRAG_WEBHOOK_SECRET` | только при `WEBHOOK_TARGET` | — | без цели секрет игнорируется | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:148-151; platform/docker/docker-compose.yml:389 |

### 2.3 Заглушка EAM API (`eam-api-stub`)

| Переменная | Обяз. | Умолчание | Прим. | Трассировка |
|------------|-------|-----------|-------|-------------|
| `ARCHRAG_EAM_API_TOKEN` | да | — | значение для `Authorization: Token <значение>`; секрет | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:156-163; platform/docker/docker-compose.yml:439 |
| `ARCHRAG_STUB_PORT` | нет | `8080` | порт `EamApiStub` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/LauncherConfig.java:162 |

Не конфигурируются через env (константы кода): порт управляющего входа `8091`, размер страницы polling `100`, окно replay (`AdapterConfig.DEFAULT_REPLAY_WINDOW`), `RetryPolicy.defaults()` (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:54, 114-116) 🟢. Переменные `ARCHRAG_POLL_INTERVAL_SECONDS`, `ARCHRAG_RECONCILE_INTERVAL_SECONDS` в `.env.example` не описаны (`_reversa_sdd/data-dictionary.md:1313`) 🟡.

## 3. Порты и HTTP

### 3.1 Адаптер

| Порт (умолч.) | Метод и путь | Кто поднимает | Ответы | Аутентификация | Трассировка |
|---------------|--------------|---------------|--------|----------------|-------------|
| `ARCHRAG_HEALTH_PORT` (8090) | `GET /health` | `DemoLauncher` | `200 {"status":"UP"}` после старта webhook, polling и reconciliation; иначе (и на любой не-`GET`) `503 {"status":"DOWN"}` | нет | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:147-160, 121-127 🟢 |
| `ARCHRAG_WEBHOOK_PORT` (8080) | `POST /webhook` | `adapter-core` `WebhookServer` | `405` не-POST; `413` тело > 64 КиБ; код обработчика подписи; `500` при сбое | HMAC-подпись (`ARCHRAG_WEBHOOK_SECRET`) | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookServer.java:30, 54, 96-123` 🟢 |
| `ARCHRAG_WEBHOOK_PORT` (8080) | `POST /control/snapshot` | `adapter-core` `WebhookServer` | `202` цикл завершён; `409` `ALREADY_RUNNING`; `503` `GAVE_UP`; `500` сбой; тело `{"outcome","appended","syncRunId"}` | нет (граница сети, П-15) | `adapters/adapter-core/src/main/java/io/github/unlocker/archrag/adaptercore/WebhookServer.java:18-22, 67-94` 🟡 |

В compose наружу через proxy публикуются только `/webhooks/*` (в `/webhook` адаптера); `/control/snapshot` не публикуется (`platform/docker/proxy/nginx.conf:43-62`; `platform/docker/README.md:236`) 🟢.

### 3.2 Заглушка источника (`stub <SRC>`)

| Порт | Метод и путь | Кто поднимает | Ответы | Трассировка |
|------|--------------|---------------|--------|-------------|
| `ARCHRAG_STUB_PORT` (8080) | `GET /changes?cursor=&limit=` | `source-stubs` `StubSourceServer` | страница `ChangePage` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:26-27, 53` 🟢 |
| `ARCHRAG_STUB_PORT` (8080) | `GET /objects/{type}/{id}` | `StubSourceServer` | снимок объекта, `404` для неизвестного | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/StubSourceServer.java:26-27, 54` 🟢 |
| `8091` (`STUB_CONTROL_PORT`) | `POST /control/upsert` | `StubControl` | см. ниже | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:44; platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/DemoLauncher.java:54 🟢 |

`POST /control/upsert` (заглушка источника) — тело JSON `{"type":string,"id":string,"payload":object,"notify"?:boolean}`:

| Условие | Ответ | Трассировка |
|---------|-------|-------------|
| метод не `POST` | `405 {"error":"POST expected"}` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:56-58 🟢 |
| невалидный JSON, тело > 64 КиБ, `type` или `id` не непустая строка, `payload` не объект | `400 {"error":"invalid request"}` (без эха ввода); источник не меняется | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:60-66, 77-80, 106-119 🟢 |
| успех, webhook не настроен или `notify:false` | `200 {"sourceVersion":N}` | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:69-76 🟢 |
| успех, webhook настроен | `200 {"sourceVersion":N,"webhookStatus":S}`, `S` — HTTP-статус адаптера либо `0`, если доставить не удалось; сбой доставки upsert не откатывает | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/StubControl.java:72-76, 89-104 🟢 |

Webhook подписывается секретом `ARCHRAG_WEBHOOK_SECRET`; заголовки — по `WebhookSignature` (`platform/demo-launcher/src/test/java/io/github/unlocker/archrag/demolauncher/StubControlTest.java:93-138`) 🟢.

### 3.3 Заглушка EAM API (`eam-api-stub`)

| Порт | Метод и путь | Кто поднимает | Ответы | Трассировка |
|------|--------------|---------------|--------|-------------|
| `ARCHRAG_STUB_PORT` (8080) | `GET /api/<type>/`, `GET /api/<type>/{id}/`, `GET /api/types/...` | `source-stubs` `EamApiStub` | `Authorization: Token <token>` обязателен, иначе `401`; изменяющие методы `405` | `platform/source-stubs/src/main/java/io/github/unlocker/archrag/sourcestubs/EamApiStub.java:26-31, 65, 93-97` 🟢 |
| `8091` | `POST /control/upsert` | `EamApiControl` | см. ниже | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:33 🟢 |
| `8091` | `POST /control/archive` | `EamApiControl` | см. ниже | platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:34 🟢 |

`POST /control/upsert` — тело `{"type":"platforms|itproducts|itsystems","id":integer,"attrs":object}` → `200 {"ver":N}` (`attrs` заменяются целиком; `ver` растёт при изменении). `POST /control/archive` — тело `{"type","id"}` → `200 {}`; идемпотентно, неизвестный объект тоже `200`. Общие ответы: не `POST` → `405 {"error":"POST expected"}`; невалидный JSON/тело > 64 КиБ/`type` вне allowlist/`id` не целое/`attrs` не объект → `400 {"error":"invalid request"}` без эха (platform/demo-launcher/src/main/java/io/github/unlocker/archrag/demolauncher/EamApiControl.java:42-98) 🟢.

## 4. Привязка к compose (`platform/docker/docker-compose.yml`)

| Сервис | `command` | Сети | Healthcheck | Трассировка |
|--------|-----------|------|-------------|-------------|
| `adapter-eam` | `["adapter","EAM"]` | `edge`, `backend` | `curl -fs http://127.0.0.1:8090/health` | platform/docker/docker-compose.yml:311-336 |
| `adapter-scm` / `adapter-cmdb` / `adapter-deploymap` | `["adapter","SCM"]` / `["adapter","CMDB"]` / `["adapter","DEPLOY_MAP"]` | `edge`, `backend` | то же | platform/docker/docker-compose.yml:338-378 |
| `stub-eam` / `stub-scm` / `stub-cmdb` / `stub-deploymap` | `["stub","<SRC>"]` | `edge` | `curl -fs 'http://127.0.0.1:8080/changes?limit=1'` | platform/docker/docker-compose.yml:381-428 |
| `eam-api-stub` | `["eam-api-stub"]` | `edge` | `curl` с токеном на `/api/platforms/?page_size=1` | platform/docker/docker-compose.yml:434-445 |

Образ `archrag/demo-launcher:dev` собирается из `platform/demo-launcher/target/demo-launcher-0.1.0-SNAPSHOT-exec.jar` (platform/docker/docker-compose.yml:6-12) 🟢. Скрипты стенда вызывают control изнутри контейнера: `platform/docker/scripts/backup-restore-check.sh:16`, `platform/docker/scripts/deployment-commit-check.sh:107`, `platform/docker/scripts/measure.sh:196, 260` 🟢.
