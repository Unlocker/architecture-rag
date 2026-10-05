#!/usr/bin/env bash
# Проверка поднятого стенда через reverse-proxy: без токена /mcp даёт 401, токен из Keycloak (client_credentials)
# даёт 200 на initialize. Запуск из platform/docker: ./smoke.sh (нужен .env рядом).
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
base="http://localhost:${ARCHRAG_PUBLIC_PORT:-8080}"
init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
mcp() { curl -s -o /dev/null -w '%{http_code}' -X POST "$base/mcp" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' "$@" -d "$init"; }

code=$(mcp)
[ "$code" = 401 ] || { echo "expected 401 without token, got $code"; exit 1; }

token=$(curl -sf -X POST "$base/realms/archrag/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=archrag-demo -d "client_secret=$KEYCLOAK_CLIENT_SECRET" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
code=$(mcp -H "Authorization: Bearer $token")
[ "$code" = 200 ] || { echo "expected 200 with token, got $code"; exit 1; }
echo "smoke ok: 401 without token, 200 with token"
