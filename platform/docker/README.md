# Демо-стенд на Docker Compose

Все компоненты PoC одной командой. Сети `edge`/`backend`/`graph`, TLS на proxy и Bolt, Docker secrets и раздельные
креденшелы Neo4j reader/writer — E5.2 (UNLOCKER-197). На хост публикуется только `reverse-proxy` (`https://localhost:8443`).

## Запуск

Из корня репозитория, нужны Java 21 и Docker:

```bash
./mvnw -B -ntp package -DskipTests        # jar для образов (образы Maven не собирают)
cd platform/docker
cp .env.example .env                       # демо-значения, .env в .gitignore
docker compose build
docker compose up -d --wait --wait-timeout 300
```

`--wait` возвращается, когда все сервисы healthy. Первый запуск Keycloak занимает около минуты.

Два экземпляра MCP-сервера за proxy: `docker compose up -d --scale mcp-server=2 --wait`.

## Что поднимается

| Сервис | Роль |
|---|---|
| `neo4j`, `postgres`, `seaweedfs` | граф (Community), inbox/checkpoints/crosswalk, S3 raw; тома `neo4j-data`, `pg-data`, `s3-data` |
| `keycloak` | OIDC, realm `archrag` импортируется из `keycloak/archrag-realm.json` |
| `ingestion` | normalizer, identity, projector, админский API; накатывает миграции PostgreSQL и схему Neo4j |
| `mcp-server` | MCP на `/mcp`, масштабируется `--scale` |
| `adapter-{eam,scm,cmdb,deploymap}` | по одному экземпляру на источник (`replicas: 1`), образ `demo-launcher` |
| `stub-{eam,scm,cmdb,deploymap}` | заглушки мастер-систем с seed из `StubSources` (профиль `ARCHRAG_STUB_PROFILE=default`) или `DemoSources` (`demo`, см. «Демо-набор») |
| `eam-api-stub` | заглушка EAM в формате реального API (`docs/eam-api.yaml`, seed `EamApiSeed`); её читает `adapter-eam` (`PLATFORM`, `SOLUTION`, `IT_SYSTEM` с ключом `id`) |
| `reverse-proxy` | nginx, только TLS: `/mcp`, `/webhooks/<eam|scm|cmdb|deploymap>`, токен-endpoint Keycloak |
| `certs` | одноразовый: демо-CA и серверные сертификаты в volume `certs` (идемпотентно) |
| `neo4j-init` | одноразовый: пользователь Neo4j `archrag_reader` для `mcp-server` и `admin-console` |
| `console-pg-init` | одноразовый: пароль read-only роли PostgreSQL `archrag_console_ro` (роль создаёт миграция V6 ingestion); идемпотентен |
| `admin-console` | админ-консоль (read-only), только `127.0.0.1:${ARCHRAG_CONSOLE_PORT:-8090}`; секреты: reader Neo4j и `archrag_console_ro` |

## Заглушка EAM API

`eam-api-stub` отдаёт ИТ-системы, ИТ-решения и платформы так же, как реальный EAM (голый массив `ArchObject[]`, `page`/`page_size`/`sort`/`fields`).
Порт наружу не публикуется; проверка изнутри контейнера:

```bash
docker compose exec eam-api-stub sh -c 'curl -s -H "Authorization: Token $(cat /run/secrets/ARCHRAG_EAM_API_TOKEN)" "http://127.0.0.1:8080/api/itsystems/?page=1&page_size=10"'
```

Без заголовка ответ 401. Типы: `platforms`, `itproducts` (ИТ-решение, допущение), `itsystems`; метаданные: `/api/types/`, `/api/types/{id}/options/?kind=fields|system`.
`eam_id` ИТ-систем (`EAM-1042`, `EAM-2001`) совпадает с `systemCode` в SCM; ключ записи — целочисленный `id` (`5101`, `5102`).

Объекты меняются через управляющий вход (порт 8091 внутри контейнера, наружу не публикуется; `/api/**` остаётся только для чтения):

```bash
docker compose exec eam-api-stub curl -s -X POST http://127.0.0.1:8091/control/upsert -H 'Content-Type: application/json' \
  -d '{"type":"itsystems","id":5101,"attrs":{"name":"Payments Core","eam_id":"EAM-1042","product_id":7201,"platform_id":3101,"ownerTeam":"TEAM-PAY"}}'
docker compose exec eam-api-stub curl -s -X POST http://127.0.0.1:8091/control/archive -H 'Content-Type: application/json' -d '{"type":"platforms","id":3102}'
```

`upsert` заменяет `attrs` целиком (пропущенные поля, в том числе ссылки, считаются удалёнными) и повышает `ver`.

## `adapter-eam`: eam-api и старая заглушка

`adapter-eam` — один источник `EAM` из двух половин: `eam-api-stub` отдаёт `PLATFORM`, `SOLUTION`, `IT_SYSTEM`; `TEAM` и
`SERVICE_DEPENDENCY` пока приходят из старой `stub-eam` (в `eam-api` таких данных нет; долг PoC, уходит с подключением реального
EAM). `IT_SYSTEM` старой заглушки отбрасывается. `ARCHRAG_EAM_MODE=legacy` в `.env` возвращает старый путь (откат): записи с
ключами `5101`… уйдут в tombstone следующим reconcile.

**Переход живого стенда** (`ITSystem` меняет ключ `EAM-1042` → `5101`): `docker compose up -d --build adapter-eam`, дождаться первого
полного snapshot (reconcile переведёт старые `IT_SYSTEM EAM-…` в tombstone: их нет в прогоне), затем `POST /admin/rebuild`
ingestion-сервиса. Rebuild переигрывает журнал, в том числе tombstone старых ключей, и SCM-записи получают связь на `ITSystem 5101`
через `eamCode`. Reconcile запускается `POST /admin/reconcile/eam` (иначе ждать интервала reconcile). Пара кандидатов «старый ключ — преемник» в `identity_candidate` ожидаема (одинаковое имя), её отклоняют. Без rebuild связи SCM на старые узлы живут до новой версии записи SCM. Crosswalk `EAM-1042 ↔ 5101` не создаётся:
со старым узлом он слил бы gid с архивируемым.

## Токен и проверка

```bash
./smoke.sh   # TLS, /mcp без токена → 401, с токеном → 200, сетевые границы и секреты
```

Токен вручную (порт из `ARCHRAG_PUBLIC_PORT`, по умолчанию 8443; CA демо-стенда берётся из volume):

```bash
docker compose cp certs:/certs/ca.crt ./ca.crt
curl -s --cacert ca.crt -X POST https://localhost:8443/realms/archrag/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=archrag-demo -d client_secret=<KEYCLOAK_CLIENT_SECRET из .env>
```

Токен содержит `iss=http://keycloak:8080/realms/archrag` (фиксируется `KC_HOSTNAME`) и audience
`https://localhost:<порт>/mcp`; с этим же issuer и resource URI работает `mcp-server`.

Admin REST ingestion проверяет audience `ARCHRAG_ADMIN_RESOURCE_URI` (в compose `urn:archrag:admin`, переменная обязательна). Keycloak кладёт этот `aud`
только в токены со scope `architecture.admin` (mapper на client scope); токен по умолчанию (`architecture.read`) получает один MCP `aud`, и admin REST отвечает ему `401`.

## Сети и секреты

| Сеть | Участники | Внешний доступ |
|---|---|---|
| `edge` | `reverse-proxy`, `mcp-server`, `keycloak`, адаптеры, заглушки | да |
| `backend` | `ingestion`, адаптеры, `keycloak`, `postgres`, `seaweedfs`, `certs` | `internal` |
| `graph` | `neo4j`, `neo4j-init`, `ingestion`, `mcp-server`, `admin-console` | `internal` |
| `console-edge` | `admin-console` | только публикация порта на `127.0.0.1` |

Сеть `default` не используется. `mcp-server` = `edge` + `graph`: PostgreSQL и SeaweedFS ему не видны. `ingestion` не в `edge`:
его админка недоступна с proxy. С хоста доступен только `127.0.0.1:${ARCHRAG_PUBLIC_PORT}` (proxy); 7474, 7687, 5432, 8333 не публикуются.

**Секреты.** Значения лежат в `.env` (вне Git); compose отдаёт их сервисам как Docker secrets (`secrets: <имя>: environment: <VAR>`),
в `docker inspect` они не видны. Java-сервисы получают их через `entrypoint.sh`: каждый файл `/run/secrets/<ИМЯ>` экспортируется
в переменную `<ИМЯ>` перед `exec java`. Postgres читает `POSTGRES_PASSWORD_FILE`, Neo4j собирает `NEO4J_AUTH` из secret в своём entrypoint.
Каждый сервис получает только свои секреты: у `mcp-server` есть единственный secret, `ARCHRAG_NEO4J_READER_PASSWORD`.

**Neo4j.** Writer (`neo4j`) — только у `ingestion` и `neo4j-init`; `mcp-server` ходит под `archrag_reader`. В Community нет ролей и RBAC,
поэтому это разделение креденшелов, а не прав: read-only обеспечивает приложение (read-транзакции в `graph-query-core`).

**TLS.** Сервис `certs` генерирует демо-CA (`ca.crt`) и сертификаты с SAN `localhost`, `reverse-proxy`, `neo4j` в volume `certs`.
Proxy слушает только `8443 ssl`. Bolt: `server.bolt.tls_level=REQUIRED`, клиенты ходят по `bolt+ssc://neo4j:7687`.

## Админ-консоль

ADR [0026](../../docs/adr/0026-admin-console-readonly-loopback.md). Как открыть:

1. Запись `127.0.0.1 keycloak` в `/etc/hosts`: `iss` токенов — `http://keycloak:8080/realms/archrag`, и браузер должен дойти до Keycloak по этому имени. Keycloak публикуется на `127.0.0.1:${ARCHRAG_KEYCLOAK_PORT:-8080}` (так же открывается его master-админка: граница как у Grafana). Для UI порт на хосте должен быть 8080.
2. `http://127.0.0.1:${ARCHRAG_CONSOLE_PORT:-8090}` (не `localhost`: PKCE S256 требует secure context).
3. Логин `KEYCLOAK_CONSOLE_USER` / `KEYCLOAK_CONSOLE_PASSWORD` из `.env`.

Проверка: `./console-smoke.sh` (вход Authorization Code + PKCE на curl; 401 без токена, 403 без `architecture.admin`, 200 на `/api/graph/stats` и `/api/sync/sources`; токен консоли в admin REST ingestion — 401; консоль недоступна по внешнему IP хоста; в контейнере только reader- и `archrag_console_ro`-секреты). Он не зависит от `hosts` и подменяет адрес Keycloak через `curl --connect-to` по `ARCHRAG_KEYCLOAK_PORT`. Admin REST ingestion принимает токены только клиентов из `ARCHRAG_ADMIN_ALLOWED_CLIENTS` (по умолчанию `archrag-demo`).

Realm: клиент `archrag-admin-console` (публичный, PKCE S256, redirect только `127.0.0.1:${ARCHRAG_CONSOLE_PORT}`), роли `archrag-console-admin` (admin: `/admin/**`, `/api/**`, `/control/snapshot`) и `archrag-reader` (MCP), scope `roles` с claim `realm_access.roles`, демо-пользователь из `.env` и сервисный аккаунт `archrag-demo` (обе роли). Realm импортируется только при первом старте Keycloak: после правки `archrag-realm.json` пересоздайте контейнер (`docker compose up -d --force-recreate keycloak`).

## Наблюдаемость

Профиль `observability` добавляет otel-collector, Prometheus и Grafana. Без профиля стенд работает как раньше, а сервисы метрики не шлют.

```
ARCHRAG_OBSERVABILITY=true docker compose --profile observability up -d --wait
./smoke.sh   # даёт вызов tool ping и трафик через proxy
```

`ARCHRAG_OBSERVABILITY` (в `.env`, по умолчанию `false`) включает OTLP-push `mcp-server` и `ingestion` в `otel-collector:4318`. Collector отдаёт метрики на `:8889`, Prometheus скрейпит только его, так что `--scale mcp-server=2` работает без service discovery. Экземпляры различаются по `service_instance_id` (hostname контейнера).

Grafana — единственное, что профиль публикует на хост: `http://127.0.0.1:${GRAFANA_PORT:-3000}`, логин `admin`, пароль из `GRAFANA_ADMIN_PASSWORD` (Docker secret), анонимный доступ выключен. Дашборд «Arch RAG PoC» (папка Arch RAG) создаётся provisioning'ом:

| Панель | Запрос (имена рядов после OTLP → Prometheus) |
|---|---|
| p95 latency по tool | `histogram_quantile(0.95, sum by (le, tool) (rate(archrag_mcp_tool_call_milliseconds_bucket[5m])))` |
| Sync lag по источнику | `max by (source) (archrag_ingestion_sync_lag_seconds)` |
| Error / DLQ rate | `rate(archrag_ingestion_events_total{status=~"QUARANTINED\|ERROR"}[5m])` и `archrag_ingestion_dlq_open` |
| Throughput projection | `rate(archrag_ingestion_events_total{status="PROJECTED"}[5m])` |

Метрики ingestion (теги только `source`, `status`): `archrag.ingestion.events` (счётчик по итоговому статусу, `ERROR` при неожиданном исключении; все пары `source` × `status` стартуют с нулей), `archrag.ingestion.sync.lag` (секунды: возраст старейшего ожидающего события; 0 при пустой очереди), `archrag.ingestion.dlq.open`. Gauges пересчитываются раз в 15 с запросами к `inbox_event` и `dlq_entry`. `source` — короткий код (`eam`, `scm`, `cmdb`, `deploymap`). Ряд tool-латентности — `archrag.mcp.tool.call` (тег `tool`; Micrometer OTLP отдаёт время в миллисекундах).

Prometheus и collector на хост не публикуются; проверка изнутри:
`docker compose exec prometheus wget -qO- 'http://localhost:9090/api/v1/query?query=archrag_ingestion_events_total'`.

## Остановка

```bash
docker compose down        # данные в томах сохраняются
docker compose down -v     # вместе с томами
```

## Backup и restore

Скрипты в `scripts/` запускаются с хоста (нужен только Docker и `.env`), работают через `docker compose`.

```bash
scripts/backup.sh [метка]      # метка по умолчанию: UTC-штамп YYYYMMDDTHHMMSSZ
scripts/restore.sh <метка>
scripts/graph-fingerprint.sh   # отпечаток графа для сравнения «до/после»
scripts/backup-restore-check.sh  # сквозной сценарий backup -> изменения -> restore -> replay (приёмка E5.4)
```

- **backup.sh** останавливает `ingestion`, `mcp-server` и `neo4j` (Neo4j Community не умеет online backup), снимает
  `neo4j-admin database dump`, поднимает сервисы обратно, затем делает `pg_dump -Fc` и пишет `manifest.json`
  (метка, `backup_started_at`, версия Neo4j, sha256 файлов). Каталог из трёх файлов уходит в
  `s3://$BACKUP_BUCKET/<метка>/` (по умолчанию `archrag-backups`, SeaweedFS). Пока Neo4j остановлен, MCP и ingestion
  недоступны; адаптеры продолжают класть события в inbox.
- **restore.sh** скачивает копию, сверяет sha256 и версию Neo4j (при расхождении выходит до остановки сервисов),
  делает `neo4j-admin database load`, поднимает сервисы и вызывает `POST /admin/replay` по каждому источнику за
  `[backup_started_at - 5 мин, сейчас + 1 мин)`. Вызов идёт изнутри контейнера `ingestion` (админка не публикуется) с
  токеном `client_credentials` и scope `architecture.admin`. Уже применённые версии отсекает проверка версий.
- **PostgreSQL при restore не восстанавливается**: журнал — источник replay, его откат потерял бы изменения. `pg_dump`
  нужен для отдельного DR; вручную: остановить `ingestion` и адаптеры, затем
  `docker compose exec -T postgres pg_restore --clean --if-exists -U "$POSTGRES_USER" -d "$POSTGRES_DB" < postgres.dump`
  (файл берётся из `s3://$BACKUP_BUCKET/<метка>/`).
- **Известный пробел.** Удаления, сделанные reconciliation после backup, replay не повторяет (маркеры
  `snapshot-complete` пропускаются). После restore выполните `POST /admin/reconcile/{source}` или запустите новый snapshot.
- Dump и load выполняются одной версией образа Neo4j (она записана в manifest); образ в compose — плавающий тег
  `neo4j:5-community`, поэтому при смене минорной версии restore старой копии откажется работать.
- Не делается: online/инкрементальный backup, PITR PostgreSQL, расписание, ротация.

## Замеры

```bash
scripts/measure.sh | tee measure.md    # нужен стенд с профилем observability (ARCHRAG_OBSERVABILITY=true)
```

`measure.sh` снимает критерий 9: p95 каждого MCP tool (клиентский и из Prometheus), задержку «источник → граф»
(`N=20` upsert в `stub-eam` и опрос `get_asset`; путь задаёт `E2E_PATH=webhook|poll|both`, по умолчанию `both`: по
webhook порог p95 ≤ 30 с, по polling — интервал + 5 с), throughput ingestion (burst `M=200`), sync lag,
долю ошибок и рост DLQ, поведение `trace_dependencies` при глубине 1…6 и за потолком. Параметры `N`, `M`, `K` (вызовов
каждого tool, 100), `P` (потоков, 4) задаются через env. Нужны Docker, bash и python3; Prometheus читается через
`docker compose exec`, порты не открываются. Код возврата 1: p95 «источник → граф» выше порога своего пути, вырос DLQ/`QUARANTINED` или tool вернул
ошибку. Сырые данные пишутся в `measurements/<штамп>/` (в `.gitignore`), таблица — в stdout.

Результаты и методика — в [`docs/04-poc-measurements.md`](../../docs/04-poc-measurements.md), отличия от production —
в [`docs/05-production-gap-list.md`](../../docs/05-production-gap-list.md).

## Допущения демо и gap list

Полный список отличий от production — в [`docs/05-production-gap-list.md`](../../docs/05-production-gap-list.md).

- `bolt+ssc` не проверяет цепочку сертификата: для production нужен доверенный CA и `bolt+s`. Демо-CA и сертификаты живут, пока жив volume `certs`.
- Сертификаты выпускаются на 825 дней и сами не перевыпускаются: после истечения выполните `docker compose down -v` (или удалите volumes `certs` и `ca-key`).
- Proxy использует самоподписанный демо-CA: клиенты передают `--cacert`, а не `-k`.
- Ключи SeaweedFS (`AWS_*`) и bootstrap-админ Keycloak остаются в `environment`: у образов нет `_FILE`-вариантов. Это видно в `docker inspect`.
- Секрет клиента Keycloak в realm и демо-значения в `.env.example` — только для стенда; перед внешней публикацией смените их.
- Разделение reader/writer Neo4j — только креденшелы (Community без RBAC); настоящие роли требуют Enterprise.
- Сети `backend` и `graph` помечены `internal`; `edge` выход наружу не ограничивает.

- **Webhook из заглушек идёт напрямую адаптеру внутри сети `edge`, мимо TLS-proxy** (`ARCHRAG_WEBHOOK_TARGET`, секрет —
  Docker secret `webhook_secret`). Допущение демо: внешние мастер-системы здесь — те же контейнеры. `POST /control/upsert`
  заглушки после изменения шлёт подписанный webhook и возвращает `webhookStatus` (HTTP-статус адаптера, `0` — не доставлен;
  ошибка доставки upsert не отменяет, изменение догонит polling); `"notify": false` в теле отключает отправку.
  Без `ARCHRAG_WEBHOOK_TARGET` заглушка webhook не шлёт.

## Ограничения E5.1

- Proxy слушает только `127.0.0.1`: демо-секрет клиента Keycloak лежит в `.env.example`, а токен-endpoint открыт через proxy.
  Перед публикацией наружу смените секреты в `.env` и откройте доступ осознанно.
- `GET /health` адаптера остаётся UP после старта и не отслеживает падение потока polling/reconciliation. По SIGTERM
  адаптер не закрывается корректно (JVM завершается по hook'у): для демо принято.

- У `ingestion-service` actuator нужен только для метрик (эндпоинты наружу закрыты): healthcheck считает сервис готовым, когда порт 8081 отвечает (Boot открывает
  его после старта контекста, то есть после миграций и схемы Neo4j).
- otel-collector без healthcheck: в образе нет shell и wget. Готовность проверяет healthcheck Prometheus (цель collector в состоянии up).
- Адаптер каждого источника запускается ровно в одном экземпляре; `/control/snapshot` адаптера proxy не публикует.

## Сценарий: смена commit развёртывания

```bash
scripts/deployment-commit-check.sh   # приёмка фичи UNLOCKER-248 (ADR 0029); нужны Docker и python3 на хосте
```

Скрипт ждёт пустой очереди журнала, читает `find_runtime_footprint` по `Payments Core` (токен и CA — как в разделе «Токен и проверка») и берёт у `dep-payments-api-prod` `sourceCommit` и `deployedFrom`. Затем через `POST /control/upsert` заглушки `stub-deploymap` отправляет ту же запись формата `deploymap-poc-1` с другим commit, ждёт события в inbox (webhook или polling) и пустой очереди. Повторное чтение MCP должно дать новый commit, а в Neo4j у развёртывания должно остаться ровно одно открытое ребро `DEPLOYED_FROM` (новый commit) и одно закрытое (`validTo` задан) со старым. При повторном запуске commit переключается обратно. Код выхода не 0 при любом расхождении.

## Демо-набор (профиль заглушек `demo`)

`ARCHRAG_STUB_PROFILE` выбирает данные всех пяти заглушек (`stub-*`, `eam-api-stub`) разом: `default` (по умолчанию, нынешний seed, на нём держатся тесты) или `demo` (вымышленный банк Nordhaven Bank, [docs/demo/dataset.md](../../docs/demo/dataset.md)). Неизвестное значение останавливает заглушку с кодом 2. Данные демо лежат в `DemoSources` (`source-stubs`); контрактный тест `DemoDatasetContractTest` сверяет их с таблицами документа.

```bash
cd platform/docker
../../mvnw -B -ntp -f ../../pom.xml package -DskipTests && cp .env.example .env
demo/seed.sh demo        # down -v -> заглушки demo -> адаптеры по очереди EAM, SCM, CMDB, deploy map (snapshot, reconcile, пустая очередь) -> остальной стенд
```

`seed.sh` сносит стенд и тома проекта (`SEED_RESET=0` оставляет), порядок загрузки нужен для маркера `conflicts` в `trace_dependencies` (раздел 7 документа). После него в базовом состоянии 2 открытых кандидата identity, 2 `source_conflict` и карантин записи `dep-card-tokenizer-test-badcommit` (`INVALID_PAYLOAD`; событий в карантине два: по одному от стартового snapshot адаптера и от reconcile). Остальные `docker compose`-команды демо-стенда выполняйте с `ARCHRAG_STUB_PROFILE=demo` (его выставляет `demo/lib.sh`), иначе compose пересоздаст заглушки с профилем по умолчанию.

Кейсы «во времени» вызывают управляющие эндпоинты заглушек на порту 8091 (внутри контейнера): `POST /control/upsert` и `POST /control/delete` (`{"type","id"}`, исчезновение объекта; повторный и неизвестный объект дают 400) у `stub-*`, `upsert`/`archive` у `eam-api-stub`; затем `run_reconcile <источник>` из `demo/lib.sh`.

### Сценарии демо и проверка (`demo/`)

Скрипты ходят в MCP стенда по HTTPS: токен Keycloak (`architecture.read`), `initialize`, `tools/call`; запрос и ответ печатаются через `jq`, поля `truncated`, `provenance`, `stale`/`lastSeenAt`, `conflicts` подсвечиваются (цвет только на TTY, в CI и при перенаправлении вывод без ANSI). Токен и секрет клиента идут через stdin `curl`, в argv и файлы не попадают.

| № | Скрипт | Кейсы | Мутирует |
|---|---|---|---|
| 01 | `01-search-card.sh` | поиск по `eam_id`/имени/домену, `get_asset`, К-10 | нет |
| 02 | `02-runtime-footprint.sh` | К-2, К-3, К-8 | нет |
| 03 | `03-trace.sh` | К-1 `trace`, `relationTypes`, `truncated`, маркер `conflicts` | нет |
| 04 | `04-impact.sh` | К-1 и К-2 `impact`, `environment` | нет |
| 05 | `05-provenance-conflicts.sh` | К-4а, К-4б, К-9, К-10, `explain_provenance` | нет |
| 06 | `06-freshness-unresolved.sh` | К-7, К-8 | нет |
| 07 | `07-access.sh` | 401 без токена и с поддельным, закрытый по умолчанию tool, `maxDepth=7`/`limit=51`, `readOnlyHint` | нет |
| 08 | `08-redeploy.sh` | смена commit `dep-card-gateway-prod` → webhook → граф → MCP | да |
| 09 | `09-lifecycle.sh` | К-6: логическое удаление и reconcile | да |
| 10 | `10-admin.sh` | К-5а approve, К-5б остаётся `OPEN`, К-11 карантин, шаги для консоли | да |

```bash
demo/seed.sh demo                # базовое состояние (около 10 минут)
demo/run-all.sh                  # все сценарии; `run-all.sh 01 04` — выборочно
demo/demo-check.sh               # узлы и рёбра, домены, сценарии, сверка ответов с expected/*.json
demo/demo-check.sh --update      # переписать эталоны (после правки данных или кода; сверить глазами с dataset.md, раздел 5)
```

`run-all.sh` и `demo-check.sh` работают только на базовом состоянии: мутации (08–10) не откатываются, на изменённом стенде скрипты завершаются с кодом 2 и просят `demo/seed.sh demo`. Эталоны нормализует `normalize.jq` (убирает `gid` и моменты времени, подставляет «Метка:имя» вместо `gid`, сортирует массивы); `demo-check.sh` возвращает 1 с `diff -u` при расхождении. Широкий `trace` от `ib-web` хранит в эталоне только `truncated` и число путей: какие именно 50 путей войдут в ответ, не гарантируется.

Визуализация графа: `docs/demo/graph.html` — один офлайн-файл (Cytoscape.js 3.30.4 и данные внутри, внешних запросов нет), открывается двойным кликом. Слои Governance/Delivery/Runtime, фильтры по меткам, окружениям и источникам, поиск по имени, карточка узла с provenance и пометками «конфликт», «stale», «удалён», «неразрешённая ссылка», переключатель «сценарий N» (`demo/graph/scenarios.json`), светлая и тёмная темы. Пересборка на базовом состоянии стенда: `demo/build-graph.sh` (`demo/export-graph.sh` выгружает узлы и рёбра под reader-учёткой из одноразового контейнера `neo4j-init`, шаблон и Cytoscape лежат в `demo/graph/`; `build-graph.sh --from <json>` собирает из готовой выгрузки). `demo-check.sh` сверяет число узлов и рёбер в странице со стендом и просит пересобрать её при расхождении.

Внешний агент: `demo/agent-setup.sh` печатает `NODE_EXTRA_CA_CERTS` (CA стенда копируется в `~/.cache/archrag-demo/ca.crt`), команду `claude mcp add --transport http archrag https://127.0.0.1:8443/mcp --header "Authorization: Bearer …"` и срок жизни токена (около 5 минут; для обновления запустить скрипт снова, `--register` сам вызывает `claude mcp`: токен попадает в argv `claude mcp add` и сохраняется в `~/.claude.json`, удалить — `claude mcp remove archrag`). `demo/agent-setup.sh --check` делает `initialize` и `tools/list` по адресу агента и сверяет набор tools с `expected/tools-list.json`. `demo/inspector.sh` (нужен `MCP_INSPECTOR_VERSION`: версия пакета не плавающая) запускает MCP Inspector (`npx`) и печатает URL и заголовок для него.

**Шаг CI** (в конец job `docker-stand`, перед `if: failure()`; `ci.yml` агент не пушит, у токена нет scope `workflow`, шаг вставляет владелец):

```yaml
      # DEMO.3: сценарии демо и эталоны на профиле demo (seed.sh сносит стенд, поэтому шаг последний)
      - name: demo scenarios
        run: SEED_SKIP_BUILD=1 SEED_RESET_GRACE=0 demo/seed.sh demo && demo/demo-check.sh && demo/agent-setup.sh --check
```
