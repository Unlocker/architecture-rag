#!/usr/bin/env bash
# Мутации во время сценариев (DEMO.3): управляющие эндпоинты заглушек и admin REST ingestion. Подключается после
# lib.sh и lib-mcp.sh: `. "$HERE/lib-stubs.sh"`. Секреты идут через stdin контейнера, в argv и лог не попадают.
# shellcheck disable=SC2034

# Базовое состояние стенда (после seed.sh demo): сервис, который сценарий 09 выводит из эксплуатации, ещё действует.
# Иначе exit 2: мутирующие сценарии необратимы, повторный прогон делается через новый seed.sh demo.
require_base_state() {
  local gid
  gid=$(asset_gid Service svc-legacy-statements)
  [ -n "$gid" ] || { echo "stand is not in the base state (svc-legacy-statements is not found): rerun demo/seed.sh demo" >&2; exit 2; }
  mcp_call get_asset "$(jq -cn --arg g "$gid" '{gid:$g,includeRelations:false}')" | jq -e '.isCurrent == true' >/dev/null ||
    { echo "stand is not in the base state (svc-legacy-statements is closed): rerun demo/seed.sh demo" >&2; exit 2; }
  [ "$(psql_q "SELECT count(*) FROM identity_candidate WHERE status = 'OPEN'")" = 2 ] ||
    { echo "stand is not in the base state (identity candidates were resolved): rerun demo/seed.sh demo" >&2; exit 2; }
}

# POST на управляющий вход заглушки (порт 8091 внутри контейнера, наружу не публикуется) -> sourceVersion в stdout.
stub_control() { # <eam|scm|cmdb|deploymap> <upsert|delete> <json-тела>
  local out
  out=$(printf '%s' "$3" | dc exec -T "stub-$1" curl -sf -X POST "http://127.0.0.1:8091/control/$2" \
    -H 'Content-Type: application/json' --data-binary @-) || die "stub-$1 /control/$2 failed"
  jq -er '.sourceVersion' <<<"$out"
}

# Удаление объекта заглушки без webhook: запись исчезает из источника, а закрывает её snapshot (Reconciler).
stub_delete() { # <eam|scm|cmdb|deploymap> <тип> <id>
  stub_control "$1" delete "$(jq -cn --arg t "$2" --arg i "$3" '{type:$t,id:$i,notify:false}')" >/dev/null
}

# Ждёт события в журнале по sourceId и sourceVersion (адаптер заберёт изменение webhook'ом или polling'ом).
wait_event() { # <source_id> <source_version>
  local deadline=$((SECONDS + ${EVENT_WAIT_SECONDS:-300}))
  until [ "$(psql_q "SELECT count(*) FROM inbox_event WHERE source_id = '$1' AND source_version = '$2'")" -gt 0 ]; do
    [ "$SECONDS" -lt "$deadline" ] || die "event $1 v$2 did not reach the journal"
    sleep 3
  done
}

# POST в admin REST ingestion изнутри контейнера (токен client_credentials со scope architecture.admin; секрет клиента
# и токен не попадают в argv). Печатает «<HTTP-код> <тело>»; код != 2xx — ошибка вызывающего.
admin_post() { # <путь> <json-тела>
  printf '%s' "$KEYCLOAK_CLIENT_SECRET" | dc exec -T ingestion sh -c '
    set -eu
    umask 077
    path=$1; body=$2; scope=$3; token_url=$4
    sec=$(mktemp); hdr=$(mktemp); out=$(mktemp); trap "rm -f \"$sec\" \"$hdr\" \"$out\"" EXIT
    cat > "$sec"
    token=$(curl -sf "$token_url" -d grant_type=client_credentials -d client_id=archrag-demo \
      --data-urlencode "scope=$scope" --data-urlencode client_secret@"$sec" |
      sed -n "s/.*\"access_token\":\"\([^\"]*\)\".*/\1/p")
    [ -n "$token" ] || { echo "no access token" >&2; exit 1; }
    printf "Authorization: Bearer %s\n" "$token" > "$hdr"
    code=$(curl -sS -o "$out" -w "%{http_code}" -X POST "http://127.0.0.1:8081$path" -H @"$hdr" \
      -H "Content-Type: application/json" -d "$body")
    echo "$code $(cat "$out")"' admin "$1" "$2" "$ADMIN_SCOPE" "$KEYCLOAK_TOKEN_URL"
}

# Число открытых (validTo IS NULL) структурных связей узла по gid: закрытый актив не должен иметь ни одной.
open_relations() { # <gid>
  cypher --format plain <<CYPHER | tail -1 | tr -d ' '
MATCH (n {gid: '$1'})-[r:DECOMPOSED_INTO|IMPLEMENTED_IN|DEPENDS_ON|HAS_DEPLOYMENT|IN_ENVIRONMENT|DEPLOYED_FROM|RUNS_ON]-()
WHERE r.validTo IS NULL
RETURN count(r);
CYPHER
}
