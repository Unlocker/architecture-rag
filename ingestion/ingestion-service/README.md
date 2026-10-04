# ingestion-service

Админский эндпоинт ингеста (E1.7): `POST /admin/replay`, `/admin/rebuild?confirm=true`, `/admin/reconcile/{source}`,
`/admin/crosswalks`. Доступен только с OIDC scope `architecture.admin`, в MCP и во внешний ingress не входит.

## Обязательные переменные окружения

| Переменная | Назначение |
|---|---|
| `ARCHRAG_PG_URL`, `ARCHRAG_PG_USER`, `ARCHRAG_PG_PASSWORD` | PostgreSQL (inbox, identity, аудит); миграции применяются при старте |
| `ARCHRAG_NEO4J_URI`, `ARCHRAG_NEO4J_USER`, `ARCHRAG_NEO4J_PASSWORD` | Neo4j с правами записи |
| `ARCHRAG_S3_ENDPOINT`, `ARCHRAG_S3_ACCESS_KEY`, `ARCHRAG_S3_SECRET_KEY`, `ARCHRAG_S3_BUCKET` | S3-совместимое хранилище raw payload |
| `ARCHRAG_OIDC_ISSUER_URI` | издатель токенов (проверяются подпись, срок и scope) |

Необязательные: `ARCHRAG_ADMIN_PORT` (по умолчанию 8081) и `ARCHRAG_<EAM|SCM|CMDB|DEPLOYMAP>_CONTROL_URL` — полный URL
`/control/snapshot` adapter-сервиса источника; без него `POST /admin/reconcile/<source>` отвечает 404.

Для PoC достаточно проверки издателя и scope; audience токена не проверяется (токен того же издателя со scope
`architecture.admin`, выданный для другого ресурса, будет принят).
