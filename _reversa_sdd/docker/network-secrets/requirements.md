# network-secrets: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Сети и секреты: [`../design.md`](../design.md), [`../contracts.md`](../contracts.md) §1, §2, §6. Блок-схема проверок: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §4. Права и учётки: [`../../permissions.md`](../../permissions.md) §5–§7.

## Visão Geral

Граница стенда держится тремя механизмами: раздельными сетями Docker (`edge`, `backend`, `graph`, … с `internal`), публикацией портов только на `127.0.0.1` и раздачей секретов по принципу минимума (Docker secrets). Эти свойства проверяют `smoke.sh` (внешняя граница, критерий 6) и `console-smoke.sh` (граница консоли, ADR 0026) на поднятом стенде 🟢 (`platform/docker/docker-compose.yml:1-3, 528-564`; `platform/docker/smoke.sh:1-3`; `platform/docker/console-smoke.sh:1-6`).

## Regras de Negócio

- С хоста доступны только `127.0.0.1:${ARCHRAG_PUBLIC_PORT}` (proxy, TLS), `127.0.0.1:${ARCHRAG_KEYCLOAK_PORT}`, `127.0.0.1:${ARCHRAG_CONSOLE_PORT}` и (профиль) `127.0.0.1:${GRAFANA_PORT}`; Neo4j 7474/7687, PostgreSQL 5432 и SeaweedFS 8333 не публикуются 🟢 (`platform/docker/docker-compose.yml:161, 293, 451, 516`; `platform/docker/smoke.sh:37-40`).
- Сети `backend`, `graph`, `observability` — `internal`: контейнеры в них не имеют выхода наружу и порты через них не публикуются; поэтому для публикации заведены отдельные не-`internal` сети `obs-edge` (Grafana) и `console-edge` (консоль) 🟢 (`platform/docker/docker-compose.yml:531-543`).
- `mcp-server` = `edge` + `graph` + `observability`: PostgreSQL и S3 ему не видны; в его окружении нет `ARCHRAG_PG_*`, `NEO4J_PASSWORD`, `ARCHRAG_NEO4J_USER`, `ARCHRAG_S3_*`, `ARCHRAG_WEBHOOK_SECRET`; `/run/secrets` содержит только `ARCHRAG_NEO4J_READER_PASSWORD` 🟢 (`platform/docker/docker-compose.yml:237-240`; `platform/docker/smoke.sh:43-53`).
- `ingestion` не в `edge`: reverse-proxy не резолвит его имя, админка недоступна снаружи 🟢 (`platform/docker/docker-compose.yml:207-208`; `platform/docker/smoke.sh:55-57`).
- `admin-console` не в `edge`: proxy её не резолвит; в её контейнере только секреты `ARCHRAG_NEO4J_READER_PASSWORD` и `ARCHRAG_CONSOLE_PG_PASSWORD`, нет writer/S3-переменных; при этом `neo4j:7687` и `postgres:5432` достижимы (положительный контроль) 🟢 (`platform/docker/docker-compose.yml:290-291`; `platform/docker/console-smoke.sh:110-124`).
- Neo4j принимает Bolt только по TLS: plain-соединение закрывается без ответа 🟢 (`platform/docker/docker-compose.yml:62`; `platform/docker/smoke.sh:81-87`).
- Proxy слушает только `8443 ssl` (TLS 1.2/1.3); plain HTTP на этот порт не отдаёт `200`; клиенты проверяют сертификат демо-CA (`--cacert`, не `-k`) 🟢 (`platform/docker/proxy/nginx.conf:7-10`; `platform/docker/smoke.sh:12, 33-35`; `platform/docker/README.md:214`).
- Admin REST ingestion вызывается только изнутри контейнера `ingestion` (`127.0.0.1:8081`); токен со scope `architecture.admin` аудитории админки → `200`, токен только `architecture.read` (аудитория MCP) → `401` 🟢 (`platform/docker/smoke.sh:59-79`).
- Токен консоли (`azp = archrag-admin-console`, аудитория админки, scope `architecture.admin`) в admin REST ingestion → `401` 🟢 (`platform/docker/console-smoke.sh:85-95`).
- `/control/snapshot` адаптера и админка Keycloak наружу не проксируются; через proxy идут только точные пути webhook'ов 🟢 (`platform/docker/proxy/nginx.conf:35-62`).
- 🟡 `POST /control/snapshot` адаптера не аутентифицируется; адаптеры в `edge` и `backend`, порт достижим из `edge` (П-15) — Q-22.
- 🟡 Reader Neo4j на уровне СУБД не ограничен (Community без ролей); запрет записи держит приложение (П-21).
- Секреты не передаются в `environment` compose (кроме перечисленных в Q-09), не хранятся в Git, в логи скриптов не печатаются 🟢/🟡 (`platform/docker/scripts/lib.sh:1-4`) — места попадания в argv: Q-08.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Запрет внешнего доступа к Neo4j, PostgreSQL, S3, admin REST, `control` | Must | AC-01 |
| RF-02 | `/mcp` без токена → 401, с токеном → 200 по TLS | Must | AC-02 |
| RF-03 | `mcp-server` без writer/PG/S3 секретов и сетевого пути к PostgreSQL | Must | AC-03 |
| RF-04 | `admin-console` только с reader и `console_ro`, только loopback | Must | AC-04 |
| RF-05 | Admin REST: audience и `azp` ограничивают вызов | Must | AC-05 |
| RF-06 | Bolt Neo4j только TLS | Must | AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|------|-----------|----------|-----------|
| Безопасность | Проверки используют положительные контроли (иначе «отсутствие» ничего не доказывает) | `platform/docker/smoke.sh:49-52, 56`; `platform/docker/console-smoke.sh:98, 111, 121-123` | 🟢 |
| Безопасность | Скрипты не зависят от `/etc/hosts` и системных прокси (`--connect-to`, `no_proxy="*"`) | `platform/docker/console-smoke.sh:14-19` | 🟢 |
| Безопасность | Временные файлы создаются с `umask 077`, токен — через временный файл заголовка, не argv | `platform/docker/console-smoke.sh:16, 43`; `platform/docker/smoke.sh:63-71` | 🟢 |
| Покрытие CI | `smoke.sh` запускается в CI; `console-smoke.sh` — нет | `.github/workflows/ci.yml:78, 81, 85, 92` | 🟢 [R.8: ответ владельца, вопрос 14] Q-07 |

## Критерии приёмки

```gherkin
# AC-01: внешняя поверхность (platform/docker/smoke.sh:37-40)
Дано стенд работает
Когда проверяются хост-порты 7474, 7687, 5432
Тогда соединение не устанавливается
Дано стенд работает
Когда от reverse-proxy резолвится ingestion
Тогда имя не резолвится (контроль: mcp-server резолвится)

# AC-02: MCP по TLS (platform/docker/smoke.sh:18-35)
Дано демо-CA взят из тома certs
Когда POST /mcp без токена
Тогда 401
Когда токен archrag-demo (client_credentials)
Тогда 200 и tools/call ping возвращает result
Когда plain HTTP на TLS-порт
Тогда ответ не 200

# AC-03: MCP не видит лишнего (platform/docker/smoke.sh:42-53)
Дано контейнер mcp-server
Когда env содержит ARCHRAG_PG_*, NEO4J_PASSWORD, ARCHRAG_NEO4J_USER, ARCHRAG_S3_* или ARCHRAG_WEBHOOK_SECRET, либо /run/secrets не равен ARCHRAG_NEO4J_READER_PASSWORD, либо postgres резолвится/достижим
Тогда smoke FAILED (код 1)

# AC-04: консоль (platform/docker/console-smoke.sh:98-124)
Дано стенд работает
Когда консоль опубликована на не-loopback адресе или proxy резолвит admin-console, или в /run/secrets консоли не ровно два секрета
Тогда console-smoke FAILED (код 1)
Дано внешний IPv4 хоста отсутствует
Тогда проверка 6 пропускается с сообщением "skip 6", остальные проверки выполняются

# AC-05: admin REST (platform/docker/smoke.sh:59-79; platform/docker/console-smoke.sh:85-95)
Дано токен archrag-demo со scope architecture.admin
Когда POST /admin/replay изнутри ingestion
Тогда 200
Дано токен archrag-demo со scope architecture.read
Тогда 401
Дано токен консоли (aud админки, azp консоли)
Тогда 401

# AC-06: Bolt (platform/docker/smoke.sh:81-87)
Дано plain Bolt-handshake к neo4j:7687
Когда сервер получает его при tls_level=REQUIRED
Тогда соединение закрыто без ответа (CLOSED); REPLIED/TIMEOUT/UNREACHABLE — провал проверки
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-06 | Must | Критерий 6 PoC; ADR 0026 |
| Проверка Bolt на CA-цепочку (`bolt+s`) | Won't | `bolt+ssc` — допущение демо (gap 2.4) |
| Аутентификация `/control/snapshot` | Won't | Нет в коде (П-15) |
| Rate limiting на proxy | Won't | gap 2.5 (Q-11) |

## Rastreabilidade de Código

| Файл | Назначение | Покрытие |
|------|------------|----------|
| `platform/docker/docker-compose.yml` | сети, порты, секреты | 🟢 |
| `platform/docker/proxy/nginx.conf` | внешняя граница | 🟢 |
| `platform/docker/smoke.sh`, `platform/docker/console-smoke.sh` | проверки границ | 🟢 |
| `platform/docker/certs/gen-certs.sh` | демо-PKI | 🟢 |
