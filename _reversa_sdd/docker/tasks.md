# platform/docker: задачи

> Задачи use case: [`stand-startup/`](stand-startup/tasks.md), [`network-secrets/`](network-secrets/tasks.md), [`keycloak-oidc/`](keycloak-oidc/tasks.md), [`backup-restore/`](backup-restore/tasks.md), [`observability-measure/`](observability-measure/tasks.md). Здесь — сквозные части: образ, общие файлы, чек-лист изменений. Задачи описывают, как воспроизвести стенд «с нуля»; чекбоксы не отмечены, потому что код существует.

## Pré-requisitos
- [ ] Docker с compose v2 и `docker compose --wait`; Java 21 для сборки jar; bash, curl, openssl, python3 для скриптов
- [ ] Собраны jar: `./mvnw -B -ntp package -DskipTests` (создаёт `*-exec.jar` четырёх приложений) (`platform/docker/README.md:8-16`; `.github/workflows/ci.yml:73`)
- [ ] `platform/docker/.env` создан из `platform/docker/.env.example` (`cp .env.example .env`; файл в `.gitignore:31`)

## Tarefas

- [ ] T-01: Образ приложений (`Dockerfile`, `Dockerfile.dockerignore`, `entrypoint.sh`)
  - Origin: `platform/docker/Dockerfile:3-14`; `platform/docker/Dockerfile.dockerignore:2-4`; `platform/docker/entrypoint.sh:5-11`
  - Критерий готовности: образ запускается от uid 10001; `/run/secrets/<X>` попадает в переменную `<X>`; аргументы команды доходят до `main`; в контексте сборки только `*-exec.jar` и `entrypoint.sh`
  - Уверенность: 🟢 (автотеста образа нет; проверяет `up --wait` в CI)
- [ ] T-02: Общие блоки compose (якоря `x-demo-launcher`, `x-adapter-env`, `x-adapter-secrets`, `x-adapter-networks`, `x-adapter-depends`; секции `networks`, `secrets`, `volumes`)
  - Origin: `platform/docker/docker-compose.yml:4-36, 528-574`
  - Критерий готовности: `docker compose config` без ошибок; девять secrets, шесть сетей (`backend`, `graph`, `observability` — `internal`), восемь томов
  - Уверенность: 🟢
- [ ] T-03: Конфигурационный шаблон `.env.example` (имена и демо-значения переменных стенда)
  - Origin: `platform/docker/.env.example:1-54`
  - Критерий готовности: все `${VAR:?}` compose'а имеют ключ в примере; реальные секреты в Git не попадают
  - Уверенность: 🟢
- [ ] T-04: nginx (TLS, `/healthz`, `/mcp`, `/.well-known/oauth-protected-resource`, OIDC token endpoint, `/webhooks/*`)
  - Origin: `platform/docker/proxy/nginx.conf:1-63`
  - Критерий готовности: см. [`network-secrets/requirements.md`](network-secrets/requirements.md) AC-05; админка Keycloak и `/control/snapshot` не проксируются
  - Уверенность: 🟢
- [ ] T-05: Одноразовые сервисы `certs`, `neo4j-init`, `console-pg-init`
  - Origin: `platform/docker/certs/gen-certs.sh:5-41`; `platform/docker/neo4j/init-reader.sh:4-22`; `platform/docker/postgres/init-console-ro.sh:4-11`
  - Критерий готовности: [`stand-startup/requirements.md`](stand-startup/requirements.md) AC-03
  - Уверенность: 🟢
- [ ] T-06: Документация стенда `README.md` (запуск, сервисы, сети, секреты, консоль, наблюдаемость, backup, замеры, допущения)
  - Origin: `platform/docker/README.md:1-244`
  - Критерий готовности: утверждения совпадают с compose — сейчас нет (Q-02, Q-03)
  - Уверенность: 🟢 (описание) / 🔴 (актуальность)
- [ ] T-07: Job CI `docker-stand`
  - Origin: `.github/workflows/ci.yml:60-101`
  - Критерий готовности: сборка jar, `cp .env.example .env`, `build`, `up --wait`, `smoke.sh`, `--scale mcp-server=2`, `shellcheck`, `backup-restore-check.sh`, профиль observability + запрос к Prometheus, логи при падении, `down -v`
  - Уверенность: 🟢
- [ ] T-08: Чек-лист добавления нового источника (§3 матрицы)
  - Origin: `platform/docker/docker-compose.yml:196-199, 338-350, 397-406`; `platform/docker/proxy/nginx.conf:43-62`; `platform/docker/scripts/lib.sh:21`
  - Критерий готовности: новый `adapter-<код>` и `stub-<код>` (healthcheck, `depends_on`), `ARCHRAG_<КОД>_CONTROL_URL` у `ingestion`, маршрут `/webhooks/<код>`, строка в `REPLAY_SOURCES`, зависимость `reverse-proxy`, проверка в `smoke.sh`/`console-smoke.sh` (число источников = 4)
  - Уверенность: 🟡 (перечень выведен из мест использования; автоматической сверки нет — Q-05)

## Tarefas de Teste

- [ ] TT-01: `smoke.sh` (внешняя граница, критерий 6) — запускается в CI два раза
- [ ] TT-02: `scripts/backup-restore-check.sh` (критерий 8) — запускается в CI
- [ ] TT-03: `console-smoke.sh` (граница консоли) — 🟢 [R.8: ответ владельца, вопрос 14] в CI не запускается (Q-07)
- [ ] TT-04: `scripts/deployment-commit-check.sh` (UNLOCKER-248) — 🟢 [R.8: ответ владельца, вопрос 14] в CI не запускается (Q-07)
- [ ] TT-05: `scripts/measure.sh` (критерий 9) — вручную, нужен профиль observability
- [ ] TT-06: `shellcheck -S warning scripts/*.sh` — в CI; `smoke.sh`, `console-smoke.sh` и скрипты вне `scripts/` под shellcheck не попадают 🟡 (`.github/workflows/ci.yml:83`)
- [ ] TT-07: 🟡 нет проверки `docker compose config` без `.env` (AC-02 в [`requirements.md`](requirements.md))

## Tarefas de Migração de Dados

n/a: стенд не владеет схемой данных; миграции PostgreSQL и схему Neo4j накатывает `ingestion` при старте.

## Ordem Sugerida
1. T-03, T-01 → 2. T-05, T-02 → 3. use case `stand-startup`, `keycloak-oidc` → 4. T-04 → 5. use case `network-secrets` → 6. `backup-restore` → 7. `observability-measure` → 8. T-07, T-06.

## Lacunas Pendentes (🔴)
См. [`questions.md`](questions.md): Q-01, Q-02, Q-03, Q-05, Q-07, Q-12 (🔴); Q-04, Q-06, Q-08..Q-11, Q-13..Q-22 (🟡).
