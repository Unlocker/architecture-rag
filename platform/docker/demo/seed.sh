#!/usr/bin/env bash
# Демо-стенд с нуля: загрузка набора Nordhaven Bank (docs/demo/dataset.md), reconcile всех источников, ожидание проекции.
# Запуск: platform/docker/demo/seed.sh demo   (нужны .env и собранные jar: ../../mvnw -B package -DskipTests; на хосте — Docker)
#
#   down -v -> инфраструктура и ingestion -> заглушки (профиль demo) -> адаптеры по одному в порядке EAM, SCM, CMDB,
#   deploy map: каждый стартует, делает snapshot, затем reconcile и ожидание пустой очереди -> остальные сервисы стенда
#
# Переменные: SEED_RESET=0 не сносит стенд и тома (по умолчанию `docker compose down -v` проекта; на общем демоне
# проверьте имя проекта COMPOSE_PROJECT_NAME), SEED_SKIP_BUILD=1 не собирает образы. Профиль default тестами не задевается.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ "${1:-}" = demo ] || { echo "usage: $0 demo" >&2; exit 2; }

log "profile $ARCHRAG_STUB_PROFILE, compose project '$PROJECT'"
if [ "${SEED_RESET:-1}" != 0 ]; then
  log "resetting the stand (down -v)"
  dc down -v --remove-orphans
fi
[ "${SEED_SKIP_BUILD:-0}" = 1 ] || dc build

# Заглушки, ingestion (миграции журнала) и keycloak (токен администратора для reconcile); адаптеров пока нет: порядок
# загрузки задаём ниже сами.
dc up -d --wait --wait-timeout "${GRAPH_START_TIMEOUT:-900}" ingestion keycloak stub-eam stub-scm stub-cmdb stub-deploymap eam-api-stub

for src in "${SEED_SOURCES[@]}"; do
  log "load $src"
  dc up -d --wait --wait-timeout 300 "adapter-$src"
  run_reconcile "$src"
  wait_demo_queue 600
done

log "starting the rest of the stand"
dc up -d --wait --wait-timeout 300
wait_demo_queue 300

log "journal: $(psql_q "SELECT string_agg(status || '=' || n, ' ' ORDER BY status) FROM (SELECT status, count(*) n FROM inbox_event GROUP BY status) t")"
log "identity candidates: $(psql_q "SELECT string_agg(status || '=' || n, ' ' ORDER BY status) FROM (SELECT status, count(*) n FROM identity_candidate GROUP BY status) t")"
log "source conflicts: $(psql_q "SELECT count(*) FROM source_conflict")"
echo "OK: demo stand is loaded (profile demo)"
