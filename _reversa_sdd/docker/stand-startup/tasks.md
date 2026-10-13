# stand-startup: задачи

## Pré-requisitos
- [ ] Общие задачи T-01..T-05 из [`../tasks.md`](../tasks.md) (образ, якоря, `.env.example`, nginx, init-сервисы)
- [ ] Jar собраны (`./mvnw -B -ntp package -DskipTests`)

## Tarefas

- [ ] T-01: Сервисы уровня 0: `postgres` (`POSTGRES_PASSWORD_FILE`, `pg_isready`), `seaweedfs` (`server -s3 -dir=/data`, healthcheck «HTTP < 500 на 8333»), `keycloak` (`start-dev --import-realm`, `KC_HOSTNAME`, healthcheck `/health/ready` на 9000)
  - Origin: `platform/docker/docker-compose.yml:102-175`
  - Критерий готовности: AC-01; healthy в пределах `retries × interval`
  - Уверенность: 🟢
- [ ] T-02: `certs` (демо-CA, сертификаты proxy и Neo4j, SAN `localhost`, `reverse-proxy`, `neo4j`, `127.0.0.1`; ключ CA в отдельном томе; `.ready`)
  - Origin: `platform/docker/certs/gen-certs.sh:5-41`; `platform/docker/docker-compose.yml:41-49`
  - Критерий готовности: AC-03 (идемпотентность); `ca.crt` доступен из тома `certs`
  - Уверенность: 🟢
- [ ] T-03: `neo4j` (Bolt TLS REQUIRED, секрет → `NEO4J_AUTH` в entrypoint, тома `neo4j-data`, `backups`, `certs`; healthcheck `cypher-shell`)
  - Origin: `platform/docker/docker-compose.yml:51-85`
  - Критерий готовности: клиент `bolt+ssc://neo4j:7687` соединяется, plain Bolt закрывается (см. [`../network-secrets/requirements.md`](../network-secrets/requirements.md) AC-06)
  - Уверенность: 🟢
- [ ] T-04: `neo4j-init` и `console-pg-init` (идемпотентные, без секретов в argv там, где возможно)
  - Origin: `platform/docker/neo4j/init-reader.sh:4-22`; `platform/docker/postgres/init-console-ro.sh:4-11`; `platform/docker/docker-compose.yml:87-100, 254-270`
  - Критерий готовности: AC-03; Q-08 (пароли в argv `cypher-shell`) учтён
  - Уверенность: 🟢
- [ ] T-05: `ingestion`, `mcp-server`, `admin-console` (образы, окружение, секреты, сети, `depends_on`, healthcheck)
  - Origin: `platform/docker/docker-compose.yml:178-252, 272-305`
  - Критерий готовности: AC-01; состав окружения и секретов — [`../contracts.md`](../contracts.md) §5, §6
  - Уверенность: 🟢
- [ ] T-06: Адаптеры и заглушки (якоря, `ARCHRAG_SOURCE_URL`, `ARCHRAG_WEBHOOK_TARGET`, `ARCHRAG_EAM_MODE`, `ARCHRAG_EAM_API_URL`)
  - Origin: `platform/docker/docker-compose.yml:307-445`
  - Критерий готовности: каждый адаптер healthy после своего stub; `adapter-eam` читает `eam-api-stub` и `stub-eam`
  - Уверенность: 🟢
- [ ] T-07: `reverse-proxy` (порт на loopback, тома, `depends_on`, healthcheck `/healthz`)
  - Origin: `platform/docker/docker-compose.yml:447-468`
  - Критерий готовности: AC-01; `https://127.0.0.1:8443/healthz` → `ok`
  - Уверенность: 🟢
- [ ] T-08: Профиль `observability` (collector, Prometheus, Grafana) — по [`../observability-measure/tasks.md`](../observability-measure/tasks.md)
  - Origin: `platform/docker/docker-compose.yml:470-526`
  - Критерий готовности: AC-06
  - Уверенность: 🟢

## Tarefas de Teste

- [ ] TT-01: CI `docker-stand`: `build`, `up --wait`, `ps`, `smoke.sh`, `--scale mcp-server=2`, проверка `wc -l = 2`, `smoke.sh` повторно (`.github/workflows/ci.yml:75-81`)
- [ ] TT-02: 🟡 нет автоматической проверки AC-02 (зависимости при падении `postgres`) и AC-04 (отсутствие переменных)
- [ ] TT-03: 🟡 `ContainersSmokeIT` проверяет те же образы SeaweedFS/PostgreSQL/Neo4j в Testcontainers, но не compose (`platform/integration-tests/src/test/java/io/github/unlocker/archrag/integrationtests/ContainersSmokeIT.java:35`)

## Ordem Sugerida
T-02 → T-01 → T-03 → T-04 → T-05 → T-06 → T-07 → T-08.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-01, Q-02, Q-03, Q-12 (🔴); Q-10, Q-14, Q-18 (🟡).
