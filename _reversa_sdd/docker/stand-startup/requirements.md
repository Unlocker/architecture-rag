# stand-startup: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Каталог сервисов и порядок: [`../design.md`](../design.md). Блок-схема: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §1.

## Visão Geral

Сценарий «собрать и поднять стенд»: Maven собирает `*-exec.jar`, оператор создаёт `.env`, `docker compose build` собирает образы, `docker compose up -d --wait` поднимает сервисы в порядке `depends_on` и возвращает управление, когда все сервисы (кроме завершившихся одноразовых) healthy. Затем стенд можно масштабировать по `mcp-server`, остановить с сохранением данных или без них 🟢 (`platform/docker/README.md:6-20, 154-159`; `.github/workflows/ci.yml:73-81`).

## Regras de Negócio

- Образы приложений не собирают Maven: jar готовится до `docker build`; в контекст сборки попадают только `**/target/*-exec.jar` и `entrypoint.sh` 🟢 (`platform/docker/Dockerfile:1-2`; `platform/docker/Dockerfile.dockerignore:1-4`).
- Обязательные переменные `.env` (подстановка `${VAR:?}`): `POSTGRES_DB`, `POSTGRES_USER`, `S3_BUCKET`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `KEYCLOAK_CLIENT_SECRET`, `KEYCLOAK_CONSOLE_USER`, `KEYCLOAK_CONSOLE_PASSWORD`; без них compose завершается ошибкой 🟢 (`platform/docker/docker-compose.yml:17-18, 105-106, 124-125, 141-142, 149, 154-155`).
- `up --wait` возвращается, когда все сервисы с healthcheck стали healthy; `reverse-proxy` стартует последним (ждёт `mcp-server`, `keycloak`, все четыре адаптера и `certs`), поэтому успех `up --wait` означает готовность всей цепочки 🟢 (`platform/docker/docker-compose.yml:456-463`).
- Одноразовые сервисы (`certs`, `neo4j-init`, `console-pg-init`) имеют `restart: "no"`, идемпотентны и должны завершиться с кодом 0; зависящие от них сервисы ждут `service_completed_successfully` 🟢 (`platform/docker/docker-compose.yml:49, 98, 268, 78, 243, 296-297`).
- Первый старт Keycloak занимает около минуты (`start_period: 60s`, до 60 попыток по 5 с); первые `neo4j` и `ingestion` — десятки секунд 🟢 (`platform/docker/docker-compose.yml:164-175, 79-85, 213-220`; `platform/docker/README.md:18`).
- Миграции PostgreSQL, схему Neo4j и миграцию темпоральности применяет `ingestion` при старте; адаптеры, `mcp-server`, `console-pg-init` и `admin-console` до его готовности не стартуют 🟢 (`platform/docker/docker-compose.yml:35-36, 245-246, 269-270, 299`).
- `ingestion` считается готовым, когда порт `8081` отвечает любым HTTP-кодом: Boot открывает порт после старта контекста (миграции уже применены), а всё кроме `/admin` закрыто `denyAll` 🟢 (`platform/docker/docker-compose.yml:213-217`).
- Масштабирование: `docker compose up -d --scale mcp-server=2 --wait` поднимает второй MCP без перезапуска proxy (DNS-резолв на запрос); адаптеры не масштабируются 🟢 (`platform/docker/README.md:20`; `platform/docker/proxy/nginx.conf:1-4`; `platform/docker/docker-compose.yml:330`).
- `docker compose down` сохраняет тома; `down -v` удаляет данные, сертификаты и ключ CA 🟢 (`platform/docker/README.md:156-159`).
- Realm Keycloak импортируется только при создании контейнера; правка `archrag-realm.json` требует `up -d --force-recreate keycloak` 🟢 (`platform/docker/README.md:127`).
- Профиль `observability` добавляет `otel-collector`, `prometheus`, `grafana`; без профиля они не запускаются, а сервисы метрики не шлют (`ARCHRAG_OBSERVABILITY=false`) 🟢 (`platform/docker/docker-compose.yml:474, 484, 502`; `platform/docker/README.md:131`).
- Долгоживущие контейнеры без `restart`-политики: упавший сервис сам не поднимется 🟡 (Q-12).

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Сборка образов из jar одним Dockerfile | Must | AC-01 |
| RF-02 | Старт всей цепочки командой `up -d --wait` в порядке зависимостей | Must | AC-01, AC-02 |
| RF-03 | Идемпотентность и успешное завершение одноразовых сервисов | Must | AC-03 |
| RF-04 | Отказ при отсутствии обязательных переменных | Must | AC-04 |
| RF-05 | Масштабирование `mcp-server` и остановка с сохранением/удалением данных | Should | AC-05 |
| RF-06 | Включение профиля observability | Should | AC-06 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|------|-----------|----------|-----------|
| Время старта | `--wait-timeout 300` в CI и README; холодный старт Neo4j на нагруженной машине может быть дольше (healthcheck `timeout 30s`) | `.github/workflows/ci.yml:76`; `platform/docker/README.md:15`; `platform/docker/docker-compose.yml:81-85` | 🟢 |
| Воспроизводимость | Теги образов зафиксированы, кроме `neo4j:5-community`, `postgres:16`, `eclipse-temurin:21-jre` | `platform/docker/docker-compose.yml:52, 103`; `platform/docker/Dockerfile:3` | 🟢 (Q-01) |
| Безопасность | Java-образы без root; секреты не в `Config.Env` | `platform/docker/Dockerfile:6, 10`; `platform/docker/entrypoint.sh:2-4` | 🟢 |
| Диагностика | При падении CI собирает `docker compose ps -a` и `logs --tail 200`, затем `down -v` | `.github/workflows/ci.yml:98-101` | 🟢 |

## Критерии приёмки

```gherkin
# AC-01: холодный старт, позитив (CI: .github/workflows/ci.yml:73-78)
Дано собраны jar и существует .env из .env.example
Когда docker compose build && docker compose up -d --wait --wait-timeout 300
Тогда команда завершается с кодом 0, docker compose ps показывает healthy у всех долгоживущих сервисов, одноразовые certs / neo4j-init / console-pg-init в состоянии Exited (0)

# AC-02: зависимость от готовности, негатив
Дано postgres не стал healthy
Когда выполняется up --wait
Тогда ingestion не запускается, адаптеры, mcp-server, admin-console и reverse-proxy остаются в ожидании, а --wait завершается по таймауту с ошибкой   # по depends_on service_healthy; сценария-теста нет 🟡

# AC-03: идемпотентность одноразовых сервисов
Дано стенд уже запускался (тома certs, neo4j-data, pg-data сохранены)
Когда up повторяется
Тогда certs печатает "certs: already generated"; neo4j-init создаёт пользователя только если его нет и меняет пароль только если reader с текущим паролем не входит; console-pg-init задаёт пароль роли без ошибки
Дано пароль NEO4J_READER_PASSWORD содержит двойную кавычку или обратную косую
Когда выполняется neo4j-init
Тогда сервис завершается с кодом 1 и сообщением без значения пароля                                 # platform/docker/neo4j/init-reader.sh:7-9

# AC-04: отсутствие обязательной переменной
Дано в .env нет KEYCLOAK_CLIENT_SECRET
Когда выполняется docker compose config
Тогда ошибка подстановки "${KEYCLOAK_CLIENT_SECRET:?}"                                              # проверка по коду; теста нет 🟡

# AC-05: масштабирование и остановка
Дано стенд работает
Когда docker compose up -d --scale mcp-server=2 --wait
Тогда docker compose ps -q mcp-server | wc -l = 2, а ./smoke.sh завершается "smoke ok"                # CI: .github/workflows/ci.yml:79-81
Когда docker compose down
Тогда тома сохранены; docker compose down -v удаляет их

# AC-06: профиль observability
Дано ARCHRAG_OBSERVABILITY=true
Когда docker compose --profile observability up -d --wait
Тогда otel-collector, prometheus, grafana запущены, Prometheus видит цель otel-collector:8889 в состоянии up   # CI: .github/workflows/ci.yml:87-96
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-04 | Must | Без старта нет стенда и приёмок |
| RF-05, RF-06 | Should | Масштабирование — критерий MCP ≥ 2 реплик; профиль — критерий 9 |
| Автоперезапуск, лимиты ресурсов, оркестрация rolling update | Won't | Production delta (gap 1.1) |

## Rastreabilidade de Código

| Файл | Назначение | Покрытие |
|------|------------|----------|
| `platform/docker/docker-compose.yml` | сервисы, `depends_on`, healthcheck | 🟢 |
| `platform/docker/Dockerfile`, `platform/docker/Dockerfile.dockerignore`, `platform/docker/entrypoint.sh` | образ | 🟢 |
| `platform/docker/certs/gen-certs.sh`, `platform/docker/neo4j/init-reader.sh`, `platform/docker/postgres/init-console-ro.sh` | одноразовые сервисы | 🟢 |
| `.github/workflows/ci.yml` | job `docker-stand` | 🟢 |
