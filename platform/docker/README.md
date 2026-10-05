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

## Остановка

```bash
docker compose down        # данные в томах сохраняются
docker compose down -v     # вместе с томами
```

## Допущения демо и gap list

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

- У `ingestion-service` нет actuator: healthcheck считает сервис готовым, когда порт 8081 отвечает (Boot открывает
  его после старта контекста, то есть после миграций и схемы Neo4j).
- Адаптер каждого источника запускается ровно в одном экземпляре; `/control/snapshot` адаптера proxy не публикует.
