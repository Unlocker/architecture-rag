# Демо-стенд на Docker Compose

Все компоненты PoC одной командой. Сети, TLS и Docker secrets — E5.2 (UNLOCKER-197): сейчас одна сеть по умолчанию,
на хост публикуется только `reverse-proxy`.

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
| `reverse-proxy` | nginx: `/mcp`, `/webhooks/<eam|scm|cmdb|deploymap>`, токен-endpoint Keycloak |

## Токен и проверка

```bash
./smoke.sh   # без токена /mcp → 401; токен client_credentials → initialize → 200
```

Токен вручную (порт из `ARCHRAG_PUBLIC_PORT`, по умолчанию 8080):

```bash
curl -s -X POST http://localhost:8080/realms/archrag/protocol/openid-connect/token \
  -d grant_type=client_credentials -d client_id=archrag-demo -d client_secret=<KEYCLOAK_CLIENT_SECRET из .env>
```

Токен содержит `iss=http://keycloak:8080/realms/archrag` (фиксируется `KC_HOSTNAME`) и audience
`http://localhost:<порт>/mcp`; с этим же issuer и resource URI работает `mcp-server`.

## Остановка

```bash
docker compose down        # данные в томах сохраняются
docker compose down -v     # вместе с томами
```

## Ограничения E5.1

- Proxy слушает только `127.0.0.1`: демо-секрет клиента Keycloak лежит в `.env.example`, а токен-endpoint открыт через proxy.
  Перед публикацией наружу смените секреты в `.env` и откройте доступ осознанно (TLS и секреты — E5.2).
- `GET /health` адаптера остаётся UP после старта и не отслеживает падение потока polling/reconciliation. По SIGTERM
  адаптер не закрывается корректно (JVM завершается по hook'у): для демо принято.

- У `ingestion-service` нет actuator: healthcheck считает сервис готовым, когда порт 8081 отвечает (Boot открывает
  его после старта контекста, то есть после миграций и схемы Neo4j).
- Reader MCP ходит в Neo4j под тем же пользователем, что и writer: Community не имеет ролей, разделение — в E5.2.
- Адаптер каждого источника запускается ровно в одном экземпляре; `/control/snapshot` адаптера proxy не публикует.
