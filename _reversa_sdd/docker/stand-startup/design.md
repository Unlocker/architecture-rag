# stand-startup: дизайн

> Модульный дизайн: [`../design.md`](../design.md) (каталог сервисов, порядок, сети). Контракты: [`../contracts.md`](../contracts.md) §4, §7. Блок-схема: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §1.

## Interface

| Артефакт / команда | Назначение | Трассировка |
|--------------------|------------|-------------|
| `./mvnw -B -ntp package -DskipTests` | собрать `*-exec.jar` (ingestion, mcp-server, admin-console, demo-launcher) | `platform/docker/README.md:11`; `.github/workflows/ci.yml:73` 🟢 |
| `docker compose build` | собрать образы `archrag/ingestion:dev`, `archrag/mcp-server:dev`, `archrag/admin-console:dev`, `archrag/demo-launcher:dev` | `platform/docker/docker-compose.yml:6-12, 179-184, 223-228, 275-280` 🟢 |
| `docker compose up -d --wait --wait-timeout 300` | поднять и дождаться healthy | `platform/docker/README.md:15`; `.github/workflows/ci.yml:76` 🟢 |
| `docker compose up -d --scale mcp-server=2 --wait` | второй экземпляр MCP | `platform/docker/README.md:20` 🟢 |
| `docker compose --profile observability up -d --wait` | наблюдаемость | `platform/docker/README.md:134` 🟢 |
| `docker compose down [-v]` | остановка | `platform/docker/README.md:157-158` 🟢 |

## Fluxo Principal

1. **Сборка jar.** Maven собирает четыре исполняемых jar; пути жёстко прописаны в compose (`platform/demo-launcher/target/demo-launcher-0.1.0-SNAPSHOT-exec.jar`, `ingestion/ingestion-service/target/ingestion-service-0.1.0-SNAPSHOT-exec.jar`, `access/mcp-server/target/mcp-server-0.1.0-SNAPSHOT-exec.jar`, `access/admin-console/target/admin-console-0.1.0-SNAPSHOT-exec.jar`) (`platform/docker/docker-compose.yml:11, 183, 227, 279`) 🟢.
2. **Образы.** `build.context: ../..` (корень репозитория), `dockerfile: platform/docker/Dockerfile`, `args.JAR`; все четыре образа из одного Dockerfile; адаптеры, заглушки и `eam-api-stub` используют один образ `archrag/demo-launcher:dev` с разными `command` (`platform/docker/docker-compose.yml:6-12, 313, 383, 436`) 🟢.
3. **Уровень 0.** Параллельно стартуют `certs`, `postgres`, `seaweedfs`, `keycloak`, все `stub-*`, `eam-api-stub`. `certs` создаёт демо-CA и сертификаты (`gen-certs.sh`) и завершается (`platform/docker/certs/gen-certs.sh:7-41`) 🟢.
4. **Уровень 1–2.** После `certs` стартует `neo4j` (Bolt TLS, healthcheck через `cypher-shell`); затем параллельно `neo4j-init` (создаёт `archrag_reader`) и `ingestion` (миграции PostgreSQL V1…V7, схема Neo4j, `TemporalRelationMigration`, бакет S3) — `ingestion` ждёт `neo4j`, `postgres`, `seaweedfs` (`platform/docker/docker-compose.yml:77-78, 99-100, 209-212`) 🟢.
5. **Уровень 3.** После `ingestion`: `console-pg-init` (пароль роли `archrag_console_ro`, роль создала миграция V6), `mcp-server` (+ `neo4j-init`, `keycloak`), четыре адаптера (+ `postgres`, `seaweedfs`, свой `stub-*`; `adapter-eam` ещё и `eam-api-stub`) (`platform/docker/docker-compose.yml:32-36, 241-246, 269-270, 326-329`) 🟢.
6. **Уровень 4–5.** `admin-console` (после `console-pg-init`, `neo4j-init`, `keycloak`, `ingestion`, `neo4j`), затем `reverse-proxy` (после `certs`, `mcp-server`, `keycloak` и всех адаптеров) (`platform/docker/docker-compose.yml:294-299, 456-463`) 🟢.
7. `up --wait` возвращается, когда все сервисы с healthcheck healthy; одноразовые сервисы не блокируют ожидание, если завершились кодом 0 🟡 (поведение compose `--wait`; в коде репозитория оговорено только в README: «возвращается, когда все сервисы healthy», `platform/docker/README.md:18`).

## Fluxos Alternativos

- **Повторный `up`.** `certs`: если есть `.ready` — «certs: already generated», код 0 (`platform/docker/certs/gen-certs.sh:7-10`). `neo4j-init`: `CREATE USER … IF NOT EXISTS`, затем `ALTER USER` только если reader с текущим паролем не входит (`ALTER` на тот же пароль Neo4j отвергает) (`platform/docker/neo4j/init-reader.sh:13-21`). `console-pg-init`: `ALTER ROLE archrag_console_ro PASSWORD :'p'`, пароль читается метакомандой psql из файла-секрета, не из argv (`platform/docker/postgres/init-console-ro.sh:7-10`) 🟢.
- **Пароль reader с опасными символами.** `init-reader.sh` отказывает (`*[\"\\]*` → exit 1) 🟢 (`platform/docker/neo4j/init-reader.sh:7-9`).
- **Нет `.env`/переменной.** Подстановка `${VAR:?}` завершает compose до создания контейнеров 🟢.
- **Сбой healthcheck.** `depends_on: service_healthy` держит зависимые сервисы; `--wait-timeout 300` завершает команду ошибкой; в CI при падении собираются `docker compose ps -a` и логи (`.github/workflows/ci.yml:98-99`) 🟢.
- **Старый том PostgreSQL с другим паролем.** `POSTGRES_PASSWORD_FILE` действует только при инициализации тома: смена пароля в `.env` без `down -v` оставляет старый пароль в БД 🟡 (поведение образа `postgres`, в репозитории не описано).
- **Перезапуск Keycloak.** Realm не переимпортируется, пока контейнер не пересоздан (`platform/docker/README.md:127`) 🟢.

## Dependências

Docker Compose v2 (`name:`, `x-` расширения, `secrets: environment:`, `profiles`, `--wait`); образы из [`../design.md`](../design.md); Maven-сборка приложений ([`../../ingestion-service/design.md`](../../ingestion-service/design.md), [`../../admin-ui/build-bundle/design.md`](../../admin-ui/build-bundle/design.md) — статика консоли внутри jar); CI — `.github/workflows/ci.yml:60-101`.

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Готовность определяется healthcheck'ами, а не `sleep`; proxy последним | `platform/docker/docker-compose.yml:456-468` | 🟢 |
| Одноразовые init-сервисы вместо init-скриптов внутри основных образов | `platform/docker/docker-compose.yml:41-49, 87-100, 254-270` | 🟢 |
| Один образ `demo-launcher` для адаптеров и заглушек, роль задаёт `command` | `platform/docker/docker-compose.yml:6-12, 313, 383` | 🟢 |
| `deploy: { replicas: 1 }` у адаптеров документирует ограничение (compose без swarm его не применяет) | `platform/docker/docker-compose.yml:307-309, 330` | 🟡 (`deploy.replicas` без swarm: поведение compose, не проверено) |
| Healthcheck Neo4j с большим `timeout 30s` (JVM `cypher-shell` под нагрузкой отвечает дольше 10 с) | `platform/docker/docker-compose.yml:81-85` | 🟢 |

## Riscos e Lacunas

- 🟢 [R.8: ответ владельца, вопрос 8] Плавающие теги `neo4j:5-community` / `postgres:16` — Q-01; README и комментарии compose расходятся с фактом публикации портов — Q-02, Q-03.
- 🟢 [R.8: ответ владельца, вопрос 12] Нет политики `restart`, лимитов ресурсов, SLO — Q-12.
- 🟡 `reverse-proxy` блокируется падением любого адаптера — Q-14; `GET /health` адаптера остаётся UP при падении потока polling — Q-18.
- 🟡 Версия `0.1.0-SNAPSHOT` зашита в пути jar — Q-10.
