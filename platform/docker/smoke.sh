#!/usr/bin/env bash
# Проверка поднятого стенда: TLS на proxy, 401/200 на /mcp, сетевые границы и секреты (критерий 6, инфраструктурная часть).
# Запуск из platform/docker: ./smoke.sh (нужен .env рядом). Сертификат демо-CA берётся из volume certs, `-k` не используется.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
port="${ARCHRAG_PUBLIC_PORT:-8443}"
base="https://localhost:$port"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail() { echo "smoke FAILED: $*" >&2; exit 1; }

docker compose cp certs:/certs/ca.crt "$tmp/ca.crt" >/dev/null
init='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}'
mcp() { curl -s --cacert "$tmp/ca.crt" -o /dev/null -w '%{http_code}' -X POST "$base/mcp" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' "$@" -d "$init"; }

# --- аутентификация поверх TLS ---
code=$(mcp)
[ "$code" = 401 ] || fail "expected 401 without token, got $code"

token=$(curl -sf --cacert "$tmp/ca.crt" -X POST "$base/realms/archrag/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=archrag-demo -d "client_secret=$KEYCLOAK_CLIENT_SECRET" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
code=$(mcp -H "Authorization: Bearer $token")
[ "$code" = 200 ] || fail "expected 200 with token, got $code"

# --- plain HTTP на TLS-порту не отвечает 200 ---
code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://localhost:$port/mcp" || true)
[ "$code" != 200 ] || fail "plain HTTP to the TLS port answered 200"

# --- с хоста закрыты Neo4j (7474, 7687) и PostgreSQL (5432) ---
for p in 7474 7687 5432; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then fail "host port $p is open"; fi
done

# --- MCP не видит writer-креденшелы и PostgreSQL ---
mcp_exec() { docker compose exec -T --index 1 mcp-server "$@"; }
if mcp_exec env | grep -qE '^(ARCHRAG_PG_|NEO4J_PASSWORD=|ARCHRAG_NEO4J_PASSWORD=|ARCHRAG_NEO4J_USER=|ARCHRAG_S3_|ARCHRAG_WEBHOOK_SECRET=)'; then
  fail "mcp-server environment contains writer/PostgreSQL/S3 settings"
fi
secrets=$(mcp_exec ls /run/secrets | tr -d '\r')
[ "$secrets" = ARCHRAG_NEO4J_READER_PASSWORD ] || fail "mcp-server /run/secrets must hold only the reader password, got: $secrets"
if [ -n "$(mcp_exec getent hosts postgres | tr -d '\r')" ]; then fail "mcp-server resolves postgres"; fi
if mcp_exec timeout 3 bash -c 'exec 3<>/dev/tcp/postgres/5432' 2>/dev/null; then fail "mcp-server reaches postgres:5432"; fi

# --- с proxy не видна админка ingestion ---
if [ -n "$(docker compose exec -T reverse-proxy getent hosts ingestion | tr -d '\r')" ]; then fail "reverse-proxy resolves ingestion"; fi

# --- plain Bolt к Neo4j отклоняется (tls_level=REQUIRED): сервер закрывает соединение без ответа на handshake ---
reply=$(docker compose exec -T ingestion timeout 5 bash -c \
  'exec 3<>/dev/tcp/neo4j/7687; printf "\x60\x60\xb0\x17\x00\x00\x05\x04\x00\x00\x04\x04\x00\x00\x03\x04\x00\x00\x00\x00" >&3; timeout 3 head -c 4 <&3 | od -An -tx1' || true)
[ -z "$(echo "$reply" | tr -d '[:space:]')" ] || fail "plain Bolt handshake to neo4j was answered: $reply"

echo "smoke ok: TLS, 401 without token, 200 with token, network boundaries and secrets"
