#!/usr/bin/env bash
# Общие функции демо-сценариев (DEMO.2): подключается через `. "$(dirname "$0")/lib.sh"`. Поверх scripts/lib.sh
# (compose, .env, psql, ожидание очереди) добавляет профиль заглушек demo, порядок загрузки источников и reconcile.
# shellcheck disable=SC2034
. "$(dirname "${BASH_SOURCE[0]}")/../scripts/lib.sh"

# Профиль данных всех пяти заглушек (docs/demo/dataset.md). Нужен каждому вызову `docker compose` демо-стенда: без него
# compose пересоздал бы заглушки с нынешним seed.
export ARCHRAG_STUB_PROFILE=demo

# Порядок загрузки (docs/demo/dataset.md, п. 7.5): маркер конфликта в trace_dependencies есть, только если конфликт найден
# «встречным» событием, поэтому система EAM идёт раньше SCM, а deploy map последним.
SEED_SOURCES=(eam scm cmdb deploymap)

# Единственное событие базового seed, которое обязано уйти в карантин (кейс К-11, docs/demo/dataset.md).
DEMO_QUARANTINE_ID="dep-card-tokenizer-test-badcommit"
DEMO_QUARANTINE_CODE="INVALID_PAYLOAD"

# Дожидается пустой очереди журнала (по умолчанию 300 с). В карантине допускается только запись К-11: любое другое
# QUARANTINED-событие — ошибка (в отличие от wait_queue_empty, который падает на любом).
wait_demo_queue() {
  local deadline=$((SECONDS + ${1:-300})) n bad
  while :; do
    bad=$(psql_q "SELECT source_id || ' ' || coalesce(error_code, '-') FROM inbox_event WHERE status = 'QUARANTINED'
                  AND NOT (source_id = '$DEMO_QUARANTINE_ID' AND error_code = '$DEMO_QUARANTINE_CODE')")
    [ -z "$bad" ] || die "unexpected QUARANTINED events (source_id error_code): $bad"
    n=$(pending_events)
    [ "$n" = 0 ] && return 0
    [ "$SECONDS" -lt "$deadline" ] || die "journal queue not empty after wait: $n pending events"
    sleep 2
  done
}

# POST /admin/reconcile/<source> изнутри контейнера ingestion (админка не публикуется): токен client_credentials со scope
# architecture.admin; секрет клиента идёт через stdin и временный файл контейнера (не argv). Опрос адаптера и reconcile
# делят замок snapshot, поэтому 409 ALREADY_RUNNING повторяется (до 5 минут). Успех только при outcome=SNAPSHOT_COMPLETED.
run_reconcile() { # <eam|scm|cmdb|deploymap>
  printf '%s' "$KEYCLOAK_CLIENT_SECRET" | dc exec -T ingestion sh -c '
    set -eu
    umask 077
    src=$1; scope=$2; token_url=$3
    sec=$(mktemp); hdr=$(mktemp); out=$(mktemp); trap "rm -f \"$sec\" \"$hdr\" \"$out\"" EXIT
    cat > "$sec"
    tries=0
    while :; do
      token=$(curl -sf "$token_url" -d grant_type=client_credentials -d client_id=archrag-demo \
        --data-urlencode "scope=$scope" --data-urlencode client_secret@"$sec" |
        sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p")
      [ -n "$token" ] || { echo "no access token" >&2; exit 1; }
      printf "Authorization: Bearer %s\n" "$token" > "$hdr"
      code=$(curl -sS -o "$out" -w "%{http_code}" -X POST "http://127.0.0.1:8081/admin/reconcile/$src" -H @"$hdr")
      case "$code" in
        202)
          grep -q "SNAPSHOT_COMPLETED" "$out" || { echo "reconcile $src: unexpected outcome $(cat "$out")" >&2; exit 1; }
          echo "reconcile $src: HTTP $code $(cat "$out")"; exit 0 ;;
        409)
          tries=$((tries + 1))
          [ "$tries" -lt 60 ] || { echo "reconcile $src: still ALREADY_RUNNING after $tries tries" >&2; exit 1; }
          sleep 5 ;;
        *)
          echo "reconcile $src: HTTP $code $(cat "$out")" >&2; exit 1 ;;
      esac
    done' reconcile "$1" "$ADMIN_SCOPE" "$KEYCLOAK_TOKEN_URL"
}
