#!/usr/bin/env bash
# Подключение внешнего агента (Claude Code) к MCP демо-стенда (DEMO.3, решение владельца 2026-10-17).
#   agent-setup.sh            печатает NODE_EXTRA_CA_CERTS, команду `claude mcp add ...` и срок жизни токена
#   agent-setup.sh --register то же и сразу регистрирует MCP в Claude Code (старая запись archrag заменяется)
#   agent-setup.sh --check    проверка стенда тем же способом, что клиент: initialize + tools/list по адресу агента
#                             (127.0.0.1, CA из NODE_EXTRA_CA_CERTS), набор tools сверяется с expected/tools-list.json
#
# Токен выпускает Keycloak стенда (client_credentials, scope architecture.read, без scope architecture.admin). Он живёт
# около 5 минут: для долгой работы обновляйте командой в конце вывода. Токен печатается только в терминал: ни в файлы
# этого скрипта, ни в лог CI он не попадает (в режиме --check токен не печатается вовсе). Claude Code хранит заголовок в
# собственной конфигурации (~/.claude.json) — удалить запись: `claude mcp remove archrag`.
set +x
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"

mode=print
case "${1:-}" in
  "") ;;
  --register) mode=register ;;
  --check) mode=check ;;
  *) echo "usage: $0 [--register|--check]" >&2; exit 2 ;;
esac

mcp_init_tmp
ca=$(mcp_export_ca)
token=$(mcp_fetch_token) || die "cannot get an access token (is the stand up? run demo/seed.sh demo)"
ttl=$(mcp_token_ttl <<<"$token")

if [ "$mode" = check ]; then
  # Клиентский путь: адрес и CA агента, заголовок Authorization через stdin curl (не argv).
  call() { # <тело JSON-RPC>
    printf 'header = "Authorization: Bearer %s"\n' "$token" | curl -sf --cacert "$ca" -K - -X POST "$MCP_CLIENT_URL" \
      -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' -d "$1"
  }
  server=$(call '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"agent-setup","version":"1"}}}' |
    jq -er '.result.serverInfo | "\(.name) \(.version)"') || die "initialize failed at $MCP_CLIENT_URL"
  tools=$(call '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' |
    jq -S '[.result.tools[] | {name, readOnly: .annotations.readOnlyHint, destructive: .annotations.destructiveHint}] | sort_by(.name)') ||
    die "tools/list failed at $MCP_CLIENT_URL"
  if ! diff -u "$HERE/expected/tools-list.json" <(printf '%s\n' "$tools"); then
    die "tools/list differs from expected/tools-list.json (see diff above)"
  fi
  echo "OK: $MCP_CLIENT_URL ($server), tools: $(jq -r 'map(.name) | join(", ")' <<<"$tools")" >&2
  exit 0
fi

echo "# токен действителен ещё ~${ttl} с; обновление: $0$([ "$mode" = register ] && echo ' --register')" >&2
echo "# Node-клиентам (Claude Code, Inspector) нужен CA стенда:"
echo "export NODE_EXTRA_CA_CERTS=$ca"
if [ "$mode" = register ]; then
  command -v claude >/dev/null || die "claude CLI not found in PATH; run without --register and execute the printed command"
  claude mcp remove archrag >/dev/null 2>&1 || true
  claude mcp add --transport http archrag "$MCP_CLIENT_URL" --header "Authorization: Bearer $token"
  echo "# зарегистрировано: claude mcp list; запускайте claude с NODE_EXTRA_CA_CERTS из строки выше" >&2
else
  echo "claude mcp add --transport http archrag $MCP_CLIENT_URL --header \"Authorization: Bearer $token\""
  echo "# удалить: claude mcp remove archrag" >&2
fi
