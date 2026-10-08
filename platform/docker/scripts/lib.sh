#!/usr/bin/env bash
# Общие функции скриптов backup/restore (E5.4). Подключается через `. "$(dirname "$0")/lib.sh"`; скрипты запускаются
# с хоста, на хосте нужен только Docker. Пароли и ключи в аргументы командной строки и вывод не попадают.
# shellcheck disable=SC2034
set -euo pipefail

DOCKER_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$DOCKER_DIR"
set -a; . ./.env; set +a

# На общем Docker-демоне могут жить другие проекты с теми же сервисами; их контейнеры скрипты не трогают.
export COMPOSE_IGNORE_ORPHANS=true
PROJECT="${COMPOSE_PROJECT_NAME:-archrag}"
BACKUP_BUCKET="${BACKUP_BUCKET:-archrag-backups}"
# Версия фиксирована: `latest` не используем.
AWSCLI_IMAGE="amazon/aws-cli:2.27.50"
# Внутри сетей стенда токен выпускается по тому же issuer, что проверяет ingestion.
KEYCLOAK_TOKEN_URL="http://keycloak:8080/realms/archrag/protocol/openid-connect/token"
ADMIN_SCOPE="architecture.admin"
# Источники в формате поля source событий (AdminController требует конкретный source, null он отклоняет).
REPLAY_SOURCES=(urn:corp:eam urn:corp:scm urn:corp:cmdb urn:corp:deploymap)

log() { echo "[$(date -u +%H:%M:%S)] $*" >&2; }
die() { echo "FAILED: $*" >&2; exit 1; }
utc_stamp() { date -u +%Y%m%dT%H%M%SZ; }
utc_iso() { date -u -d "${1:-now}" +%Y-%m-%dT%H:%M:%SZ; }

dc() { docker compose "$@"; }

# Метка попадает в пути S3 и shell внутри контейнера: допускаем только безопасный алфавит.
check_label() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "invalid label '$1' (allowed: A-Z a-z 0-9 . _ -)"
}

# cypher-shell в контейнере neo4j; запрос читается из stdin, пароль writer берётся из Docker secret внутри контейнера.
cypher() {
  dc exec -T neo4j bash -c \
    'exec cypher-shell -a bolt+ssc://localhost:7687 -u neo4j -p "$(cat /run/secrets/neo4j_password)" "$@"' cypher "$@"
}

# Скрипт sh в одноразовом контейнере образа neo4j с volume backups (root: каталог dump принадлежит neo4j).
# Аргументы после скрипта доступны как $1, $2, ... Stdin пробрасывается.
vol_sh() {
  local script=$1; shift
  dc run --rm -T --no-deps --user root --entrypoint sh neo4j -c "$script" vol_sh "$@"
}

# Индикатор прогресса neo4j-admin (строки `Files: ...`) в лог не пишем.
neo4j_admin() {
  dc run --rm -T --no-deps --user neo4j --entrypoint neo4j-admin neo4j "$@" | sed -u '/^Files:/d'
}

# aws-cli в сети backend; ключи S3 передаются через stdin и экспортируются внутри контейнера (не argv и не `-e`).
aws_s3() {
  printf '%s\n%s\n' "$S3_ACCESS_KEY" "$S3_SECRET_KEY" | docker run --rm -i --network "${PROJECT}_backend" \
    -v "${PROJECT}_backups:/backups" --entrypoint sh "$AWSCLI_IMAGE" -c '
      read -r AWS_ACCESS_KEY_ID; read -r AWS_SECRET_ACCESS_KEY
      export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_DEFAULT_REGION=us-east-1
      exec aws --endpoint-url http://seaweedfs:8333 "$@"' aws "$@"
}

psql_q() {
  dc exec -T postgres psql -X -qAt -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "$1"
}

# События журнала, которые ещё не дошли до конечного статуса (QUARANTINED требует оператора и считается отдельно).
pending_events() {
  psql_q "SELECT count(*) FROM inbox_event WHERE status IN ('RECEIVED','VALIDATED','NORMALIZED','RESOLVED','RETRYING')"
}
quarantined_events() { psql_q "SELECT count(*) FROM inbox_event WHERE status = 'QUARANTINED'"; }

# Ждёт пустой очереди журнала (по умолчанию 300 с); QUARANTINED — сразу ошибка.
wait_queue_empty() {
  local deadline=$((SECONDS + ${1:-300})) n q
  while :; do
    q=$(quarantined_events)
    [ "$q" = 0 ] || die "$q events are QUARANTINED"
    n=$(pending_events)
    [ "$n" = 0 ] && return 0
    [ "$SECONDS" -lt "$deadline" ] || die "journal queue not empty after wait: $n pending events"
    sleep 2
  done
}

# Версия Neo4j в образе: dump и load допустимы только на одной версии.
neo4j_version() { neo4j_admin --version | tr -d '\r' | tail -1; }

# Поднимает сервисы графа и ждёт healthy.
# Холодный старт Neo4j на нагруженной машине бывает долгим: таймаут настраивается.
start_graph_services() { dc up -d --wait --wait-timeout "${GRAPH_START_TIMEOUT:-900}" neo4j ingestion mcp-server; }
stop_graph_services() { dc stop ingestion mcp-server neo4j; }

uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr '[:upper:]' '[:lower:]'; }

# Replay журнала по всем источникам за [from, to) через POST /admin/replay изнутри контейнера ingestion (админка не
# публикуется). Токен client_credentials со scope architecture.admin; секрет клиента идёт через stdin, токен — через
# временный файл заголовка в контейнере (не argv). Возвращает ошибку, если любой вызов ответил не 200.
run_replay() {
  local from=$1 to=$2 src args=()
  for src in "${REPLAY_SOURCES[@]}"; do args+=("$src=$(uuid)"); done
  printf '%s' "$KEYCLOAK_CLIENT_SECRET" | dc exec -T ingestion sh -c '
    set -eu
    umask 077
    from=$1; to=$2; scope=$3; token_url=$4; shift 4
    hdr=$(mktemp); out=$(mktemp); trap "rm -f \"$hdr\" \"$out\"" EXIT
    token=$(curl -sf "$token_url" -d grant_type=client_credentials -d client_id=archrag-demo \
      --data-urlencode "scope=$scope" --data-urlencode client_secret@- |
      sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p")
    [ -n "$token" ] || { echo "no access token" >&2; exit 1; }
    printf "Authorization: Bearer %s\n" "$token" > "$hdr"
    for pair in "$@"; do
      src=${pair%%=*}; id=${pair#*=}
      body=$(printf "{\"source\":\"%s\",\"receivedFrom\":\"%s\",\"receivedTo\":\"%s\",\"replayId\":\"%s\"}" "$src" "$from" "$to" "$id")
      code=$(curl -sS -o "$out" -w "%{http_code}" -X POST http://127.0.0.1:8081/admin/replay \
        -H @"$hdr" -H "Content-Type: application/json" -d "$body")
      echo "replay $src: HTTP $code $(cat "$out")"
      [ "$code" = 200 ] || exit 1
    done' replay "$from" "$to" "$ADMIN_SCOPE" "$KEYCLOAK_TOKEN_URL" "${args[@]}"
}
