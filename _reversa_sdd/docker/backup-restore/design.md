# backup-restore: дизайн

> Модульный дизайн: [`../design.md`](../design.md). Контракты: [`../contracts.md`](../contracts.md) §9, §10. Блок-схема: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §3.

## Interface

### Общие функции `platform/docker/scripts/lib.sh`

| Функция | Назначение | Строки |
|---------|------------|--------|
| (инициализация) | `set -euo pipefail`; переход в `platform/docker`; `set -a; . ./.env; set +a`; `COMPOSE_IGNORE_ORPHANS=true`; `PROJECT` (по умолчанию `archrag`); `BACKUP_BUCKET` (по умолчанию `archrag-backups`); `AWSCLI_IMAGE`; `KEYCLOAK_TOKEN_URL`; `ADMIN_SCOPE`; `REPLAY_SOURCES` | `platform/docker/scripts/lib.sh:5-21` |
| `log`, `die`, `utc_stamp`, `utc_iso`, `dc` | журнал в stderr; выход с `FAILED: …` (1); время UTC; обёртка `docker compose` | `platform/docker/scripts/lib.sh:23-28` |
| `check_label` | допустимый алфавит метки | `platform/docker/scripts/lib.sh:31-33` |
| `cypher` | `cypher-shell` в контейнере `neo4j` под writer'ом, запрос из stdin | `platform/docker/scripts/lib.sh:36-39` |
| `vol_sh` | одноразовый контейнер образа `neo4j` под root с томом `backups`: `dc run --rm -T --no-deps --user root --entrypoint sh neo4j -c <script>` | `platform/docker/scripts/lib.sh:43-46` |
| `neo4j_admin` | `neo4j-admin` в одноразовом контейнере под пользователем `neo4j`; строки `Files:` отфильтрованы | `platform/docker/scripts/lib.sh:49-51` |
| `aws_s3` | `aws-cli` в сети `<проект>_backend`, ключи через stdin, регион `us-east-1`, endpoint `http://seaweedfs:8333` | `platform/docker/scripts/lib.sh:54-60` |
| `psql_q` | тихий `psql` в контейнере `postgres` | `platform/docker/scripts/lib.sh:62-64` |
| `pending_events`, `quarantined_events`, `wait_queue_empty` | счётчики очереди журнала и ожидание пустой очереди (шаг 2 с, `QUARANTINED` → `die`) | `platform/docker/scripts/lib.sh:67-83` |
| `neo4j_version` | версия образа: `neo4j-admin --version` | `platform/docker/scripts/lib.sh:86` |
| `start_graph_services`, `stop_graph_services` | `up -d --wait --wait-timeout ${GRAPH_START_TIMEOUT:-900} neo4j ingestion mcp-server` / `stop ingestion mcp-server neo4j` | `platform/docker/scripts/lib.sh:90-91` |
| `uuid` | UUID из `/proc` либо `uuidgen` | `platform/docker/scripts/lib.sh:93` |
| `run_replay` | `POST /admin/replay` по `REPLAY_SOURCES` изнутри `ingestion` | `platform/docker/scripts/lib.sh:98-119` |

Функции-обёртки обращаются к сервисам через `docker compose`, поэтому скрипты работают только на хосте с Docker и с `.env` рядом 🟢.

## Fluxo Principal

### Backup (`backup.sh`)

1. `label = ${1:-utc_stamp}`; `check_label`; `started_at = utc_iso` (до остановки) (`platform/docker/scripts/backup.sh:11-15`).
2. `vol_sh`: `rm -rf /backups/<метка> && mkdir -p … && chown neo4j:neo4j` (`platform/docker/scripts/backup.sh:17-18`).
3. `trap ERR → start_graph_services`; `neo4j_ver = neo4j_version`; `stop_graph_services` (ingestion, mcp-server, neo4j) (`platform/docker/scripts/backup.sh:20-25`).
4. `neo4j_admin database dump neo4j --to-path=/backups/<метка>`; `trap - ERR`; `start_graph_services` (`platform/docker/scripts/backup.sh:26-30`).
5. `pg_dump -Fc` через `dc exec postgres` → `vol_sh 'cat > /backups/$1/postgres.dump'` (`platform/docker/scripts/backup.sh:32-34`).
6. `manifest.json` (метка, `backup_started_at`, версия Neo4j, sha256 обоих дампов) и `chown -R neo4j:neo4j` (`platform/docker/scripts/backup.sh:36-41`).
7. `aws_s3 s3api head-bucket … || aws_s3 s3 mb`; `aws_s3 s3 cp --recursive /backups/<метка> s3://$BACKUP_BUCKET/<метка>/`; печать метки (`platform/docker/scripts/backup.sh:43-48`).

### Restore (`restore.sh <метка>`)

1. Ровно один аргумент; `check_label` (`platform/docker/scripts/restore.sh:16-18`).
2. Очистить `/backups/<метка>`; `aws_s3 s3 cp --recursive s3://…/<метка>/ /backups/<метка>` (`platform/docker/scripts/restore.sh:20-22`).
3. `current_ver = neo4j_version`; проверки в `vol_sh`: `manifest.json` и `neo4j.dump` есть; sha256 `neo4j.dump`; sha256 `postgres.dump` (если файл есть); версия Neo4j из манифеста = `current_ver` → иначе `die "backup … failed verification"` (`platform/docker/scripts/restore.sh:24-35`).
4. `backup_started_at` из манифеста + проверка формата (`platform/docker/scripts/restore.sh:36-37`).
5. `trap ERR`; `stop_graph_services`; `neo4j_admin database load neo4j --from-path=… --overwrite-destination=true`; `trap - ERR`; `start_graph_services` (`platform/docker/scripts/restore.sh:39-46`).
6. Окно: `from = backup_started_at − 5 minutes`, `to = now + 1 minute`; `run_replay "$from" "$to"` (`platform/docker/scripts/restore.sh:48-51`).
7. `wait_queue_empty 600`; `log "restore … done"` (`platform/docker/scripts/restore.sh:52-54`).

### Сквозная приёмка (`backup-restore-check.sh`)

`label = check-<UTC>` → `wait_queue_empty 300` → `F0 = fingerprint` → `backup.sh` → `upsert 9001 "Backup Probe <метка>" EAM-9001` и `upsert 5101 "Payments Core <метка>" EAM-1042` в `eam-api-stub` (`POST :8091/control/upsert`, все `attrs` целиком) → `wait_event` (появление события с `source_id` и `source_version` в `inbox_event`, до 300 с) → `wait_queue_empty 300` → `F1` (`F1 != F0`, иначе `die`) → `restore.sh` → `F2` → печать трёх отпечатков; `F2 == F1` иначе `diff` и `die` (`platform/docker/scripts/backup-restore-check.sh:10-53`) 🟢.

### Отпечаток графа (`graph-fingerprint.sh`)

Запросы к Neo4j под writer'ом (`cypher`) возвращают: метки узлов и их число, типы связей и их число, ключи узлов (`gid`, иначе `runId`, `code`, `source/sourceType/sourceId`), ключи связей `начало тип конец`, свойства узлов и связей (кроме `EXCLUDED` и `SyncRun.status`); выход — строки `nodelabel X N`, `reltype X N` и sha256 по видам `NODE`, `REL`, `NODEPROP`, `RELPROP`, `TOTAL` 🟢 (`platform/docker/scripts/graph-fingerprint.sh:17-56`). Список исключений `EXCLUDED = ['fetchedAt','lastSeenAt','firstSeenAt','observedAt','startedAt','finishedAt','_lock']` — единственное место, где зафиксировано, какие свойства зависят от времени обработки, а не от данных источника (`platform/docker/scripts/graph-fingerprint.sh:10-17`) 🟢.

## Fluxos Alternativos

- Сбой `dump`/`load` после остановки: `trap ERR` пытается поднять граф (`start_graph_services >/dev/null || true`); при сбое внутри функций `lib.sh` ловушка не срабатывает (нет `set -E`), сервисы могут остаться остановленными — 🟡 Q-06 (`platform/docker/scripts/backup.sh:21`; `platform/docker/scripts/restore.sh:39`).
- Копия без `postgres.dump`: restore проверяет sha256 только `neo4j.dump` (условие `[ -f postgres.dump ]`) 🟢 (`platform/docker/scripts/restore.sh:31-33`).
- Метка уже существует в S3: каталог локально очищается, при выгрузке `s3 cp --recursive` перезаписывает одноимённые объекты; удаление лишних объектов старой копии не выполняется 🟡 (`platform/docker/scripts/backup.sh:17-18, 45`).
- Образ Neo4j сменил минорную версию между backup и restore → отказ по версии (`platform/docker/scripts/restore.sh:34`; `platform/docker/README.md:187-188`) 🟢.
- `replay` вернул не 200 (`400` формат, `409` занятый замок, `5xx`) → `exit 1` внутри контейнера, restore `die` 🟢 (`platform/docker/scripts/lib.sh:116-117`).
- Гонка с диспетчером: админская операция получает `409`, если диспетчер держит замок страницы — повтор вручную 🟡 (Q-06 в `../../ingestion-service/questions.md`).
- Удаления reconcile после backup не повторяются → ручной `POST /admin/reconcile/{source}` (`platform/docker/scripts/restore.sh:11-12`) 🟢.

## Dependências

Docker Compose, образ `neo4j` (для `neo4j-admin`, `cypher-shell`), `postgres` (`pg_dump`), `amazon/aws-cli:2.27.50`, SeaweedFS S3, Keycloak (admin-токен), `ingestion` (`POST /admin/replay`, [`../../ingestion-service/replay-rebuild/design.md`](../../ingestion-service/replay-rebuild/design.md)), `eam-api-stub` (`/control/upsert`); сущности: `inbox_event` ([`../../event-journal/design.md`](../../event-journal/design.md)).

## Decisões de Design Identificadas

| Решение | Evidence | Уверенность |
|---------|----------|-------------|
| Холодный dump вместо online backup (Community) | `platform/docker/scripts/backup.sh:5-6`; ADR [0021](../../../docs/adr/0021-compose-demo-stand.md) | 🟢 |
| PostgreSQL не восстанавливается: журнал — источник replay | `platform/docker/scripts/restore.sh:10`; `platform/docker/README.md:181-184` | 🟡 (ADR нет — D-10 `../decisions.md`) |
| Окно replay шире момента backup на 5 минут назад и 1 минуту вперёд; дубли отсекает проверка версий | `platform/docker/scripts/restore.sh:3-9, 48-49` | 🟢 |
| Эквивалентность графа — отпечаток, а не сравнение по запросам MCP | `platform/docker/scripts/graph-fingerprint.sh:1-12` | 🟢 |
| Версия Neo4j в манифесте как защита от несовместимого load | `platform/docker/scripts/backup.sh:23`; `platform/docker/scripts/restore.sh:34` | 🟢 |
| Ключи S3 и секрет клиента — через stdin контейнера, не argv | `platform/docker/scripts/lib.sh:53-60, 101-102` | 🟢 |

## Riscos e Lacunas

- 🔴 Плавающий тег Neo4j делает restore старой копии хрупким — Q-01.
- 🟡 `trap ERR` не срабатывает в функциях — Q-06; пароль writer в argv `cypher-shell` — Q-08.
- 🟡 Удаления reconcile не повторяются; `admin-console` не останавливается на время dump — Q-16.
- 🟡 Сценарий проверяет только изменения источника EAM (ИТ-системы `9001`, `5101`); остальные источники в приёмке не участвуют (`platform/docker/scripts/backup-restore-check.sh:35-39`).
- 🔴 RPO/RTO, расписание, ротация, PITR — не определены (Q-12).
