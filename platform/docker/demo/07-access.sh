#!/usr/bin/env bash
# Сценарий 7 (DEMO.3): безопасность MCP. Запрос без токена и с поддельным токеном — 401; незарегистрированный tool
# закрыт по умолчанию (403); границы maxDepth/limit/gid — ошибка валидации, а не усечение; все tools только читают
# (readOnlyHint). Данные не меняет.
#
# Отказ «токен без scope» на стенде получить нельзя: Keycloak всегда добавляет клиенту archrag-demo default scope
# architecture.read. Этот отказ проверяют тесты mcp-server (ToolScopeFilter), здесь его заменяет отказ по tool без scope.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"

scenario "7. Безопасность: токен, scope, границы, только чтение"

list='{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
mcp_token
expect_code() { # <описание> <ожидаемый код> <фактический код>
  [ "$3" = "$2" ] || die "$1: expected HTTP $2, got $3"
  echo "  [ok] $1 -> HTTP $3" >&2
}
expect_code "tools/list без токена" 401 "$(mcp_http_code "$list" -)"
forged="${MCP_TOKEN%.*}.AAAA"
expect_code "tools/list с поддельной подписью токена" 401 "$(mcp_http_code "$list" "$forged")"
expect_code "tools/call tool, которому не назначен scope (upsert_asset), закрыт по умолчанию" 403 \
  "$(mcp_http_code '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"upsert_asset","arguments":{}}}' "$MCP_TOKEN")"
expect_code "tools/list с корректным токеном" 200 "$(mcp_http_code "$list" "$MCP_TOKEN")"

mcp_initialize >/dev/null
gid=$(asset_gid Service svc-ib-web)
step_error 07-limit-max-depth trace_dependencies "$(jq -cn --arg g "$gid" '{gid:$g,maxDepth:7}')" "maxDepth must be in 1..6"
step_error 07-limit-max-depth-zero trace_dependencies "$(jq -cn --arg g "$gid" '{gid:$g,maxDepth:0}')" "maxDepth must be in 1..6"
step_error 07-limit-search search_assets '{"query":"payments","limit":51}' "limit must be in 1..50"
step_error 07-invalid-gid get_asset '{"gid":"not-a-uuid"}' "gid must be a UUID"

echo "--- tools/list" >&2
tools=$(mcp_tools_list)
jq . <<<"$tools" >&2
jq -e 'length > 0 and all(.[]; .readOnly == true and .destructive == false)' <<<"$tools" >/dev/null ||
  die "every tool must be annotated readOnlyHint=true and destructiveHint=false"
echo "  [ok] все tools помечены readOnlyHint=true" >&2
if [ -n "$DEMO_OUT" ]; then mkdir -p "$DEMO_OUT"; printf '%s\n' "$tools" >"$DEMO_OUT/tools-list.json"; fi
