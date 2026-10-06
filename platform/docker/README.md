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
| `stub-{eam,scm,cmdb,deploymap}` | заглушки мастер-систем с seed из `StubSources` |
| `reverse-proxy` | nginx, только TLS: `/mcp`, `/webhooks/<eam|scm|cmdb|deploymap>`, токен-endpoint Keycloak |
| `certs` | одноразовый: демо-CA и серверные сертификаты в volume `certs` (идемпотентно) |
| `neo4j-init` | одноразовый: пользователь Neo4j `archrag_reader` для `mcp-server` |

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

## Сети и секреты

| Сеть | Участники | Внешний доступ |
|---|---|---|
| `edge` | `reverse-proxy`, `mcp-server`, `keycloak`, адаптеры, заглушки | да |
| `backend` | `ingestion`, адаптеры, `keycloak`, `postgres`, `seaweedfs`, `certs` | `internal` |
| `graph` | `neo4j`, `neo4j-init`, `ingestion`, `mcp-server` | `internal` |

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
(`N=20` upsert в `stub-eam` и опрос `get_asset`, порог p95 ≤ 30 с), throughput ingestion (burst `M=200`), sync lag,
долю ошибок и рост DLQ, поведение `trace_dependencies` при глубине 1…6 и за потолком. Параметры `N`, `M`, `K` (вызовов
каждого tool, 100), `P` (потоков, 4) задаются через env. Нужны Docker, bash и python3; Prometheus читается через
`docker compose exec`, порты не открываются. Код возврата 1: p95 больше 30 с, вырос DLQ/`QUARANTINED` или tool вернул
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

## Ограничения E5.1

- Proxy слушает только `127.0.0.1`: демо-секрет клиента Keycloak лежит в `.env.example`, а токен-endpoint открыт через proxy.
  Перед публикацией наружу смените секреты в `.env` и откройте доступ осознанно.
- `GET /health` адаптера остаётся UP после старта и не отслеживает падение потока polling/reconciliation. По SIGTERM
  адаптер не закрывается корректно (JVM завершается по hook'у): для демо принято.

- У `ingestion-service` actuator нужен только для метрик (эндпоинты наружу закрыты): healthcheck считает сервис готовым, когда порт 8081 отвечает (Boot открывает
  его после старта контекста, то есть после миграций и схемы Neo4j).
- otel-collector без healthcheck: в образе нет shell и wget. Готовность проверяет healthcheck Prometheus (цель collector в состоянии up).
- Адаптер каждого источника запускается ровно в одном экземпляре; `/control/snapshot` адаптера proxy не публикует.
