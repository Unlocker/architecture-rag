# backup-restore: требования

> Модульные требования: [`../requirements.md`](../requirements.md). Дизайн: [`design.md`](design.md). Контракты: [`../contracts.md`](../contracts.md) §9, §10. Блок-схема: [`../../flowcharts/docker.md`](../../flowcharts/docker.md) §3. Replay на стороне ingestion: [`../../ingestion-service/replay-rebuild/requirements.md`](../../ingestion-service/replay-rebuild/requirements.md). Решение: ADR [0021](../../../docs/adr/0021-compose-demo-stand.md) (backup через `dump/load`).

## Visão Geral

Neo4j Community не умеет online backup, поэтому резервная копия — холодный `neo4j-admin database dump` на остановленном графе плюс `pg_dump` PostgreSQL, всё с `manifest.json` (sha256, версия Neo4j, время начала) выгружается в S3 (SeaweedFS). Восстановление — `database load` + `POST /admin/replay` по всем источникам за окно от `backup_started_at − 5 мин` до «сейчас + 1 мин»; PostgreSQL при restore не трогается, журнал остаётся источником replay. Сквозная приёмка `backup-restore-check.sh` доказывает: после изменений в источнике и restore + replay граф эквивалентен 🟢 (`platform/docker/scripts/backup.sh:1-7`; `platform/docker/scripts/restore.sh:1-12`; `platform/docker/scripts/backup-restore-check.sh:1-7`).

## Regras de Negócio

- Метка копии: `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` (попадает в пути S3 и shell внутри контейнера); по умолчанию UTC-штамп `YYYYMMDDTHHMMSSZ`; иная метка → `FAILED: invalid label` (код 1) 🟢 (`platform/docker/scripts/lib.sh:30-33`; `platform/docker/scripts/backup.sh:11-12`).
- `backup_started_at` фиксируется до остановки сервисов: от него `restore.sh` считает окно replay 🟢 (`platform/docker/scripts/backup.sh:14`).
- Backup останавливает `ingestion`, `mcp-server`, `neo4j`; адаптеры продолжают класть события в inbox; на время dump MCP и ingestion недоступны (`admin-console` не останавливается — Q-16) 🟢/🟡 (`platform/docker/scripts/backup.sh:5-7, 24-25`; `platform/docker/scripts/lib.sh:90-91`).
- Каталог `/backups/<метка>` в томе `backups` очищается перед записью: файлы одноимённой метки не попадут в копию 🟢 (`platform/docker/scripts/backup.sh:17-18`).
- Сбой между остановкой и стартом должен вернуть граф: `trap ERR → start_graph_services`; ловушка не срабатывает на сбой внутри функций `lib.sh` (нет `set -E`) 🟡 (`platform/docker/scripts/backup.sh:20-28`; Q-06).
- Состав копии: `neo4j.dump`, `postgres.dump` (`pg_dump -Fc`), `manifest.json` с `label`, `backup_started_at`, `neo4j_version`, `neo4j_dump_sha256`, `postgres_dump_sha256`; выгрузка `s3 cp --recursive` в `s3://$BACKUP_BUCKET/<метка>/`, бакет создаётся при отсутствии (`head-bucket || mb`) 🟢 (`platform/docker/scripts/backup.sh:33-45`).
- Restore **до** остановки любых сервисов проверяет: наличие `manifest.json` и `neo4j.dump`, sha256 `neo4j.dump` (и `postgres.dump`, если он есть), равенство версии Neo4j в манифесте и в образе; при расхождении — `backup '<метка>' failed verification` (код 1) 🟢 (`platform/docker/scripts/restore.sh:24-35`).
- `backup_started_at` из манифеста должен иметь формат `YYYY-MM-DDThh:mm:ssZ`, иначе `bad backup_started_at in manifest` 🟢 (`platform/docker/scripts/restore.sh:36-37`).
- Restore: останавливает `ingestion`, `mcp-server`, `neo4j`; `neo4j-admin database load neo4j --overwrite-destination=true`; поднимает сервисы (`--wait`, таймаут `GRAPH_START_TIMEOUT`, по умолчанию 900 с) 🟢 (`platform/docker/scripts/restore.sh:39-46`; `platform/docker/scripts/lib.sh:90`).
- Replay: для каждого из `urn:corp:eam|scm|cmdb|deploymap` вызывается `POST /admin/replay` с телом `{source, receivedFrom, receivedTo, replayId}` (новый UUID на источник), токен `client_credentials` клиента `archrag-demo` со scope `architecture.admin`; вызов изнутри контейнера `ingestion`; любой ответ ≠ 200 прерывает restore 🟢 (`platform/docker/scripts/lib.sh:19-21, 98-119`).
- После replay `wait_queue_empty 600`: ждёт, пока в `inbox_event` не останется статусов `RECEIVED/VALIDATED/NORMALIZED/RESOLVED/RETRYING`; наличие `QUARANTINED` сразу даёт `FAILED: N events are QUARANTINED` 🟢 (`platform/docker/scripts/restore.sh:52-53`; `platform/docker/scripts/lib.sh:66-83`).
- PostgreSQL при restore **не** восстанавливается; `postgres.dump` нужен для отдельного DR (ручной `pg_restore`) 🟢 (`platform/docker/scripts/restore.sh:10`; `platform/docker/README.md:181-184`).
- Известный пробел: удаления, сделанные reconcile после backup, replay не повторяет (маркеры `snapshot-complete` пропускаются); после restore — `POST /admin/reconcile/{source}` или новый snapshot 🟢 (`platform/docker/scripts/restore.sh:11-12`; `platform/docker/README.md:185-186`).
- Dump и load выполняются одной версией образа Neo4j (записана в манифест); при смене минорной версии restore старой копии откажется работать (плавающий тег — Q-01) 🟢 (`platform/docker/README.md:187-188`).
- Не делается: online/инкрементальный backup, PITR PostgreSQL, расписание, ротация 🟢 (`platform/docker/README.md:189`).
- Секреты не печатаются: ключи S3 идут в `aws-cli` через stdin контейнера, секрет клиента Keycloak — через stdin, токен — через временный файл заголовка с `umask 077` 🟢 (`platform/docker/scripts/lib.sh:53-60, 98-119`); но пароль writer Neo4j попадает в argv `cypher-shell` внутри контейнера — Q-08.

## Requisitos Funcionais

| ID | Требование | Приоритет | Критерий приёмки |
|----|-----------|-----------|------------------|
| RF-01 | Backup: dump Neo4j, pg_dump, manifest, выгрузка в S3 | Must | AC-01 |
| RF-02 | Валидация метки | Must | AC-02 |
| RF-03 | Restore с проверкой sha256 и версии до остановки | Must | AC-03, AC-04 |
| RF-04 | Replay по всем источникам в окне, ожидание пустой очереди | Must | AC-05 |
| RF-05 | Сквозная проверка F2 == F1, F1 != F0 | Must | AC-06 |
| RF-06 | Отпечаток графа как эталон эквивалентности | Must | AC-07 |

## Requisitos Não Funcionais

| Тип | Требование | Evidence | Уверенность |
|------|-----------|----------|-----------|
| Время | Холодный старт графа после dump/load — до 900 с (настраивается `GRAPH_START_TIMEOUT`); ожидание очереди — 300 с (backup-check) и 600 с (restore) | `platform/docker/scripts/lib.sh:88-90`; `platform/docker/scripts/restore.sh:53`; `platform/docker/scripts/backup-restore-check.sh:29` | 🟢 |
| Безопасность | Версии `aws-cli` закреплены (`amazon/aws-cli:2.27.50`); `latest` не используется | `platform/docker/scripts/lib.sh:15-16` | 🟢 |
| Надёжность | Проверка копии до разрушительных действий (остановка, load) | `platform/docker/scripts/restore.sh:24-37` | 🟢 |
| Доступность | На время dump/load недоступны `ingestion` и `mcp-server`; RPO/RTO не определены | `platform/docker/README.md:175-176`; gap 3.2 | 🟡 (Q-12) |

## Критерии приёмки

```gherkin
# AC-01: backup, позитив
Дано стенд работает, .env на месте
Когда scripts/backup.sh nightly-1
Тогда neo4j, ingestion, mcp-server остановлены на время dump и подняты обратно; в s3://$BACKUP_BUCKET/nightly-1/ лежат neo4j.dump, postgres.dump, manifest.json; скрипт печатает метку и завершается кодом 0

# AC-02: недопустимая метка, негатив
Дано метка "bad label/../x"
Когда scripts/backup.sh "bad label/../x"
Тогда "FAILED: invalid label", код 1, сервисы не останавливались

# AC-03: восстановление, позитив
Дано копия nightly-1 существует и версия Neo4j в манифесте равна версии образа
Когда scripts/restore.sh nightly-1
Тогда sha256 сверены, граф загружен, replay по четырём источникам вернул 200, очередь журнала пуста, код 0

# AC-04: повреждённая или несовместимая копия, негатив
Дано в копии нет manifest.json, или sha256 neo4j.dump не совпал, или версия Neo4j другая
Когда scripts/restore.sh <метка>
Тогда "backup '<метка>' failed verification" (код 1) до остановки сервисов; граф не тронут

# AC-05: replay и очередь
Дано после load выполнен replay
Когда в журнале остались события QUARANTINED
Тогда restore завершается "FAILED: N events are QUARANTINED" (код 1)
Дано любой вызов /admin/replay вернул не 200
Тогда restore прерывается (код 1)

# AC-06: сквозная приёмка (CI: .github/workflows/ci.yml:84)
Дано стенд settled (очередь пуста), F0 — отпечаток графа
Когда backup → upsert новой ИТ-системы 9001 и изменение 5101 в eam-api-stub → события в inbox → очередь пуста → F1 → restore
Тогда F1 != F0 и F2 == F1, печатается "OK: restore + replay reproduced the graph"
Дано F1 == F0 (изменения не дошли)
Тогда "F1 equals F0 …" (код 1): сценарий ничего не проверяет

# AC-07: отпечаток
Дано граф Neo4j
Когда graph-fingerprint.sh
Тогда печатает число узлов по меткам, связей по типам и sha256 множеств NODE, REL, NODEPROP, RELPROP, TOTAL; свойства fetchedAt, lastSeenAt, firstSeenAt, observedAt, startedAt, finishedAt, _lock и SyncRun.status в хэш не входят
```

## Prioridade (MoSCoW)

| Требование | MoSCoW | Justificativa |
|-----------|--------|---------------|
| RF-01..RF-05 | Must | Критерий 8 PoC (E5.4) |
| RF-06 | Must | Эталон эквивалентности |
| Online/инкрементальный backup, расписание, ротация, PITR | Won't | Community без online backup; PoC (ADR 0021) |
| Автоматический reconcile после restore | Won't | Известный пробел; делается вручную (Q-16) |

## Rastreabilidade de Código

| Файл | Назначение | Покрытие |
|------|------------|----------|
| `platform/docker/scripts/lib.sh` | общие функции | 🟢 |
| `platform/docker/scripts/backup.sh` | backup | 🟢 |
| `platform/docker/scripts/restore.sh` | restore | 🟢 |
| `platform/docker/scripts/backup-restore-check.sh` | сквозная приёмка | 🟢 |
| `platform/docker/scripts/graph-fingerprint.sh` | отпечаток графа | 🟢 |
