#!/usr/bin/env bash
# Клиент MCP для сценариев (DEMO.3): токен Keycloak, initialize, tools/call, вывод
# запроса и ответа с подсветкой. Подключается через `. "$(dirname "$0")/lib-mcp.sh"` (подключает scripts/lib.sh сам).
#
# Токен и секрет клиента идут только через stdin curl (`--config -`, `client_secret@-`): ни в argv, ни в файлы, ни в лог.
# Из значений запросов в stderr попадает только текст ошибки tool (до 200 символов).
# shellcheck disable=SC2034
. "$(dirname "${BASH_SOURCE[0]}")/../scripts/lib.sh"

DEMO_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
MCP_PORT="${ARCHRAG_PUBLIC_PORT:-8443}"
MCP_BASE="https://localhost:$MCP_PORT"
MCP_TOKEN_TTL_MARGIN=60   # токен Keycloak живёт 300 с; обновляем, когда осталось меньше этого запаса
MCP_TOKEN=""
MCP_TOKEN_UNTIL=0
MCP_TMP=""

# Куда складывать нормализованные ответы шагов (для demo-check.sh); пусто — только вывод на экран.
DEMO_OUT="${DEMO_OUT:-}"
# Цвет только на TTY: в CI и при перенаправлении вывод без ANSI.
if [ -t 2 ] && [ -z "${NO_COLOR:-}" ]; then MCP_COLOR=1; else MCP_COLOR=0; fi

# Рабочий каталог с CA стенда (публичный сертификат, не секрет). Вызывать не в подоболочке $(...): иначе путь теряется.
# Каталог чистится при выходе, если у скрипта нет своего trap EXIT; со своим trap вызовите mcp_cleanup.
mcp_init_tmp() {
  [ -n "$MCP_TMP" ] && return 0
  MCP_TMP=$(mktemp -d)
  [ -n "$(trap -p EXIT)" ] || trap mcp_cleanup EXIT
  dc cp certs:/certs/ca.crt "$MCP_TMP/ca.crt" >/dev/null 2>&1 || die "cannot copy the stand CA from the certs volume"
}
mcp_cleanup() { [ -z "$MCP_TMP" ] || rm -rf "$MCP_TMP"; }

mcp_ca() { echo "$MCP_TMP/ca.crt"; }

# Токен client_credentials (scope по умолчанию architecture.read) -> stdout. Секрет клиента — через stdin curl.
mcp_fetch_token() {
  printf '%s' "$KEYCLOAK_CLIENT_SECRET" | curl -sf --cacert "$(mcp_ca)" -X POST \
    "$MCP_BASE/realms/archrag/protocol/openid-connect/token" -d grant_type=client_credentials -d client_id=archrag-demo \
    --data-urlencode "client_secret@-" | jq -er '.access_token'
}

# Кэширует токен в переменной оболочки и обновляет его до истечения (exp - запас).
mcp_token() {
  mcp_init_tmp
  if [ -z "$MCP_TOKEN" ] || [ "$SECONDS" -ge "$MCP_TOKEN_UNTIL" ]; then
    MCP_TOKEN=$(mcp_fetch_token) || die "cannot get an access token"
    MCP_TOKEN_UNTIL=$((SECONDS + 300 - MCP_TOKEN_TTL_MARGIN))
  fi
}

# POST /mcp с телом из $1 -> тело ответа в stdout, код возврата curl. Токен — через конфиг из stdin.
# Если первым аргументом идёт "-", запрос уходит без токена; MCP_HTTP_CODE_FILE != "" вместо тела печатает HTTP-код.
mcp_post() { # <body> [token|-]
  local tok=${2:-$MCP_TOKEN}
  {
    [ "$tok" = "-" ] || printf 'header = "Authorization: Bearer %s"\n' "$tok"
  } | curl -s --cacert "$(mcp_ca)" -K - -X POST "$MCP_BASE/mcp" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' ${MCP_HTTP_CODE:+-o /dev/null -w '%{http_code}'} -d "$1"
}

# HTTP-код ответа на /mcp: токен берётся из $2 ("-" — без токена).
mcp_http_code() { # <body> <token|->
  MCP_HTTP_CODE=1 mcp_post "$1" "$2"
}

# initialize: проверка рукопожатия, имя и версия сервера в stdout. Сессии у сервера нет (stateless).
mcp_initialize() {
  mcp_token
  mcp_post '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"archrag-demo","version":"1"}}}' |
    jq -er '.result.serverInfo | "\(.name) \(.version)"'
}

# tools/call <name> <arguments-json> -> текст result.content[0].text (JSON ответа tool). Ошибка tool (isError) или
# JSON-RPC error: короткий текст в stderr и код 1. Три попытки: сразу после старта индексы Neo4j могут быть не готовы.
mcp_call() {
  local body out text err=""
  mcp_token
  body=$(jq -cn --arg n "$1" --argjson a "$2" '{jsonrpc:"2.0",id:1,method:"tools/call",params:{name:$n,arguments:$a}}')
  for _ in 1 2 3; do
    if out=$(mcp_post "$body"); then
      # Ошибка валидации (isError) — ожидаемый ответ негативных сценариев и не повторяется; повтор только для таймаута
      # шаблона запроса: на холодном старте Neo4j первый запрос может не уложиться в лимит.
      if text=$(jq -er 'if .error then "rpc error: \(.error.message[:200])" | halt_error(1)
                        elif .result.isError then "tool error: \(.result.content[0].text | split("\n")[0] | .[:200])" | halt_error(1)
                        else .result.content[0].text end' <<<"$out" 2>&1); then
        printf '%s\n' "$text"
        return 0
      fi
      err=$text
      case $err in *"timed out"*) ;; *) printf '%s\n' "$err" >&2; return 1 ;; esac
    fi
    sleep 5
  done
  [ -z "$err" ] || printf '%s\n' "$err" >&2
  return 1
}

# tools/list -> [{name, readOnly, destructive}] по имени: то, что агент видит в списке tools и что сверяет
# expected/tools-list.json. Описания и схемы аргументов в проекцию не входят (они длинные и меняются с кодом сервера).
mcp_tools_list() {
  mcp_token
  mcp_post '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' |
    jq -S '[.result.tools[] | {name, readOnly: .annotations.readOnlyHint, destructive: .annotations.destructiveHint}] | sort_by(.name)'
}

# Подсветка полей, на которые смотрит ведущий: truncated, provenance, stale/lastSeenAt, conflicts. На TTY — цветом
# в самом JSON, всегда — списком «путь = значение» после него (в CI цвета нет, список остаётся).
MCP_HIGHLIGHT_KEYS='["truncated","provenance","stale","lastSeenAt","conflicts","conflictState","staleAfter","warnings","isCurrent","deletedAt"]'
mcp_highlight() { # stdin JSON
  local json
  json=$(cat)
  if [ "$MCP_COLOR" = 1 ]; then jq -C . <<<"$json" >&2; else jq . <<<"$json" >&2; fi
  jq -r --argjson keys "$MCP_HIGHLIGHT_KEYS" '
    def brief: tojson | if length > 90 then .[:87] + "..." else . end;
    [ paths(type != "object" and type != "array" or true) as $p
      | select(($p | length) > 0 and ($p[-1] | type) == "string" and ($keys | index($p[-1])))
      | { path: ($p | map(tostring) | join(".")), v: getpath($p) }
      | select(.v != null and .v != false and .v != [] and .v != {}) ]
    | .[:12][] | "  * \(.path) = \(.v | brief)"' <<<"$json" |
    {
      if [ "$MCP_COLOR" = 1 ]; then sed $'s/^  \\*/  \033[1;33m*\033[0m/' >&2; else cat >&2; fi
    }
}

# Шаг сценария: печатает запрос и ответ, пишет нормализованный ответ в $DEMO_OUT/<step>.json (если задан).
# STEP_JQ (необязательно) сужает сохраняемое: для ответов, у которых часть содержимого от запуска к запуску не стабильна.
# Возвращает ответ в stdout для дальнейшего разбора, например `gid=$(step 01-find search_assets '{...}' | jq -r ...)`.
step() { # <step> <tool> <arguments-json> [comment]
  local name=$1 tool=$2 args=$3 resp
  {
    printf '\n%s== %s: %s%s\n' "$([ "$MCP_COLOR" = 1 ] && printf '\033[1;36m')" "$name" "${4:-$tool}" "$([ "$MCP_COLOR" = 1 ] && printf '\033[0m')"
    printf 'tools/call %s\n' "$tool"
    jq . <<<"$args"
    echo "--- ответ"
  } >&2
  resp=$(mcp_call "$tool" "$args") || die "step $name: tools/call $tool failed"
  mcp_highlight <<<"$resp"
  if [ -n "$DEMO_OUT" ]; then
    mkdir -p "$DEMO_OUT"
    jq -S -f "$DEMO_DIR/normalize.jq" <<<"$resp" | jq -S "${STEP_JQ:-.}" >"$DEMO_OUT/$name.json"
  fi
  printf '%s\n' "$resp"
}

# Шаг, который обязан завершиться ошибкой tool (проверка границ): печатает запрос и текст ошибки, возвращает ошибку,
# если tool ответил успехом. Сообщение ошибки сохраняется в $DEMO_OUT/<step>.json как строка.
step_error() { # <step> <tool> <arguments-json> <ожидаемый фрагмент сообщения>
  local name=$1 tool=$2 args=$3 want=$4 err
  {
    printf '\n== %s: %s (ожидается отказ)\n' "$name" "$tool"
    jq . <<<"$args"
  } >&2
  if err=$(mcp_call "$tool" "$args" 2>&1 >/dev/null); then die "step $name: $tool must be rejected, but succeeded"; fi
  echo "--- ответ: $err" >&2
  case "$err" in *"$want"*) ;; *) die "step $name: error '$err' does not contain '$want'" ;; esac
  if [ -n "$DEMO_OUT" ]; then
    mkdir -p "$DEMO_OUT"
    jq -n --arg e "$err" '{error: $e}' >"$DEMO_OUT/$name.json"
  fi
}

# gid актива по точному sourceId (search_assets: точное совпадение идёт первым, matchType=EXACT) для типа; пусто, если нет.
asset_gid() { # <type> <sourceId>
  mcp_call search_assets "$(jq -cn --arg q "$2" --arg t "$1" '{query:$q,types:[$t],limit:5}')" |
    jq -r '[.items[] | select(.matchType == "EXACT")][0].gid // empty'
}

# Проверка кейса на ответе шага (stdin): jq-выражение должно дать истину. Падение — ошибка seed или кода, а не повод
# подгонять эталон под ответ.
check() { # <описание> <jq-выражение>; ответ — в stdin
  local in
  in=$(cat)
  jq -e "$2" <<<"$in" >/dev/null || die "check failed: $1"
  echo "  [ok] $1" >&2
}

# Заголовок сценария на экран.
scenario() { printf '\n########## %s\n' "$*" >&2; }

# Публичный адрес MCP для клиентов на хосте (Claude Code, Inspector) и каталог с CA стенда. CA — публичный сертификат,
# не секрет; кладётся в кэш пользователя, чтобы Node-клиенты брали его из NODE_EXTRA_CA_CERTS.
MCP_CLIENT_URL="https://127.0.0.1:$MCP_PORT/mcp"
MCP_CLIENT_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/archrag-demo"

# Выгружает CA стенда в $MCP_CLIENT_DIR/ca.crt и печатает путь.
mcp_export_ca() {
  mcp_init_tmp
  mkdir -p "$MCP_CLIENT_DIR"
  cp "$(mcp_ca)" "$MCP_CLIENT_DIR/ca.crt"
  echo "$MCP_CLIENT_DIR/ca.crt"
}

# Срок жизни токена в секундах по claim exp (подпись не проверяется, токен выдал Keycloak стенда секундой ранее).
mcp_token_ttl() { # токен в stdin
  local payload
  payload=$(cut -d. -f2 | tr '_-' '/+')
  while [ $((${#payload} % 4)) -ne 0 ]; do payload+="="; done
  printf '%s' "$payload" | base64 -d 2>/dev/null | jq -r '.exp - now | floor'
}
