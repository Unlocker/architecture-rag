# backup-restore: задачи

## Pré-requisitos
- [ ] Поднятый стенд; `.env`; образ `amazon/aws-cli:2.27.50` (подтягивается при первом вызове)
- [ ] Том `backups` и сеть `<проект>_backend` существуют ([`../stand-startup/tasks.md`](../stand-startup/tasks.md))

## Tarefas

- [ ] T-01: `lib.sh`: окружение, `dc`, `vol_sh`, `neo4j_admin`, `aws_s3`, `psql_q`, `neo4j_version`, `start/stop_graph_services`
  - Origin: `platform/docker/scripts/lib.sh:5-60, 62-64, 86-91`
  - Критерий готовности: функции работают из любого каталога (скрипт делает `cd` в `platform/docker`)
  - Уверенность: 🟢
- [ ] T-02: Очередь журнала: `pending_events`, `quarantined_events`, `wait_queue_empty`
  - Origin: `platform/docker/scripts/lib.sh:66-83`
  - Критерий готовности: AC-05 (`QUARANTINED` → `die`; таймаут → `die "journal queue not empty…"`)
  - Уверенность: 🟢
- [ ] T-03: `run_replay` (токен `client_credentials`, `scope=architecture.admin`, `POST /admin/replay` по четырём источникам, любой не-200 → ошибка)
  - Origin: `platform/docker/scripts/lib.sh:95-119`
  - Критерий готовности: AC-03, AC-05
  - Уверенность: 🟢
- [ ] T-04: `backup.sh` (метка, `started_at`, dump, pg_dump, manifest, выгрузка)
  - Origin: `platform/docker/scripts/backup.sh:8-48`
  - Критерий готовности: AC-01, AC-02
  - Уверенность: 🟢
- [ ] T-05: `restore.sh` (скачать, проверить, load, replay, ждать)
  - Origin: `platform/docker/scripts/restore.sh:13-54`
  - Критерий готовности: AC-03, AC-04, AC-05
  - Уверенность: 🟢
- [ ] T-06: `graph-fingerprint.sh` (запросы, исключения времени обработки, хэши)
  - Origin: `platform/docker/scripts/graph-fingerprint.sh:14-56`
  - Критерий готовности: AC-07; одинаковый граф → одинаковый вывод
  - Уверенность: 🟢
- [ ] T-07: `backup-restore-check.sh` (F0 → backup → upsert → F1 → restore → F2)
  - Origin: `platform/docker/scripts/backup-restore-check.sh:7-53`
  - Критерий готовности: AC-06
  - Уверенность: 🟢
- [ ] T-08: Устранить Q-06 (`set -E`/`trap … EXIT`), если архитектор подтвердит
  - Origin: `platform/docker/scripts/lib.sh:5`; `platform/docker/scripts/backup.sh:21`; `platform/docker/scripts/restore.sh:39`
  - Критерий готовности: после искусственного сбоя `dump` сервисы графа подняты
  - Уверенность: 🟡 (задача по открытому вопросу)

## Tarefas de Teste

- [ ] TT-01: `backup-restore-check.sh` в CI (`.github/workflows/ci.yml:83-85`)
- [ ] TT-02: 🟡 негативные сценарии AC-02, AC-04, AC-05 не автоматизированы
- [ ] TT-03: `shellcheck -S warning scripts/*.sh` (`.github/workflows/ci.yml:83`)

## Ordem Sugerida
T-01 → T-02 → T-03 → T-06 → T-04 → T-05 → T-07.

## Lacunas Pendentes (🔴)
См. [`../questions.md`](../questions.md): Q-01, Q-12 (🔴); Q-06, Q-08, Q-16 (🟡).
