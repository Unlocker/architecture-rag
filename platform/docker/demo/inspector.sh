#!/usr/bin/env bash
# MCP Inspector против демо-стенда (DEMO.3): тот же URL, CA и заголовок Authorization, что у agent-setup.sh.
# Запуск: platform/docker/demo/inspector.sh   (нужны Node.js и npx; версию задаёт MCP_INSPECTOR_VERSION, по умолчанию latest)
# В UI Inspector: Transport Type = Streamable HTTP, URL и заголовок из вывода ниже (Authentication -> Custom Headers).
# Токен печатается только в терминал и живёт около 5 минут (повторный запуск выпускает новый).
set +x
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"

command -v npx >/dev/null || die "npx not found: install Node.js"
ca=$(mcp_export_ca)
token=$(mcp_fetch_token) || die "cannot get an access token (is the stand up? run demo/seed.sh demo)"
{
  echo "URL:     $MCP_CLIENT_URL"
  echo "Header:  Authorization: Bearer $token"
  echo "Токен действителен ещё ~$(mcp_token_ttl <<<"$token") с."
} >&2
NODE_EXTRA_CA_CERTS=$ca exec npx -y "@modelcontextprotocol/inspector@${MCP_INSPECTOR_VERSION:-latest}"
