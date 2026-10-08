#!/usr/bin/env bash
# Проверка админ-консоли на поднятом стенде (U.5): вход Authorization Code + PKCE S256 через Keycloak, 401/403/200 на API,
# граница с admin REST ingestion, loopback-only и состав секретов контейнера. Запуск из platform/docker: ./console-smoke.sh
# (нужны .env рядом, Docker, curl, openssl, python3). Не зависит от записи `keycloak` в /etc/hosts: адрес Keycloak
# подменяется через `curl --connect-to`, имя и порт в URL остаются теми, что в iss токенов.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a
console_port="${ARCHRAG_CONSOLE_PORT:-8090}"
kc_port="${ARCHRAG_KEYCLOAK_PORT:-8080}"
console="http://127.0.0.1:$console_port"
issuer="http://keycloak:8080/realms/archrag"
redirect="$console/"
# Стенд локальный: прокси окружения (http_proxy) не должен перехватывать запросы к loopback и keycloak.
export no_proxy="*" NO_PROXY="*"
umask 077
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail() { echo "console-smoke FAILED: $*" >&2; exit 1; }
kc() { curl -s --connect-to "keycloak:8080:127.0.0.1:$kc_port" "$@"; }

# Логин демо-администратора: auth endpoint -> форма -> redirect с code -> обмен code на токен (PKCE). Печатает access token.
login() {
  local scope=$1 jar="$tmp/jar.$RANDOM" verifier challenge state page action loc code
  verifier=$(openssl rand -base64 48 | tr -d '=+/\n' | cut -c1-64)
  challenge=$(printf '%s' "$verifier" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
  state=$(openssl rand -hex 8)
  page=$(kc -c "$jar" -G "$issuer/protocol/openid-connect/auth" \
    --data-urlencode client_id=archrag-admin-console --data-urlencode response_type=code \
    --data-urlencode "redirect_uri=$redirect" --data-urlencode "scope=$scope" --data-urlencode "state=$state" \
    --data-urlencode "code_challenge=$challenge" --data-urlencode code_challenge_method=S256)
  action=$(printf '%s' "$page" | python3 -I -c 'import html,re,sys; m=re.search(r"<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"", sys.stdin.read()); print(html.unescape(m.group(1)) if m else "")')
  [ -n "$action" ] || fail "login form not found (is Keycloak reachable on 127.0.0.1:$kc_port?)"
  printf '%s' "$KEYCLOAK_CONSOLE_PASSWORD" > "$tmp/pw"
  loc=$(kc -b "$jar" -c "$jar" -o /dev/null -D - "$action" --data-urlencode "username=$KEYCLOAK_CONSOLE_USER" \
    --data-urlencode "password@$tmp/pw" | tr -d '\r' | sed -n 's/^[Ll]ocation: //p')
  code=$(printf '%s' "$loc" | python3 -I -c 'import sys,urllib.parse as u; q=u.parse_qs(u.urlparse(sys.stdin.read().strip()).query); assert q.get("state")==["'"$state"'"], "state mismatch"; print(q["code"][0])') \
    || fail "no authorization code in redirect (wrong credentials or redirect URI?)"
  kc "$issuer/protocol/openid-connect/token" -d grant_type=authorization_code -d client_id=archrag-admin-console \
    --data-urlencode "code=$code" --data-urlencode "redirect_uri=$redirect" --data-urlencode "code_verifier=$verifier" |
    python3 -I -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}
api() { # api <path> [token]
  if [ $# -gt 1 ]; then printf 'Authorization: Bearer %s\n' "$2" > "$tmp/h"; set -- "$1" -H "@$tmp/h"; else set -- "$1"; fi
  curl -s -o "$tmp/body" -w '%{http_code}' "$console$1" "${@:2}"
}

# --- 1. публичная конфигурация и вход ---
curl -fs "$console/console-config.json" | grep -q '"clientId":"archrag-admin-console"' || fail "console-config.json does not name the console client"
admin=$(login "openid architecture.admin")
[ -n "$admin" ] || fail "login with PKCE did not produce a token"
echo "ok 1: login (Authorization Code + PKCE S256) produced an access token"

# Прямой grant (пароль без браузера) у публичного клиента выключен.
code=$(kc -o /dev/null -w '%{http_code}' "$issuer/protocol/openid-connect/token" -d grant_type=password -d client_id=archrag-admin-console \
  -d "username=$KEYCLOAK_CONSOLE_USER" --data-urlencode "password=$KEYCLOAK_CONSOLE_PASSWORD")
[ "$code" = 400 ] || [ "$code" = 401 ] || fail "direct access grant must be disabled for the console client, got $code"

# --- 2. без токена -> 401 ---
for p in /api/graph/stats /api/sync/sources; do
  code=$(api "$p"); [ "$code" = 401 ] || fail "$p without token: expected 401, got $code"
done
echo "ok 2: API without token -> 401"

# --- 3. токен консоли без architecture.admin (aud консоли есть, scope нет) -> 403 ---
readonly_token=$(login "openid architecture.read")
code=$(api /api/graph/stats "$readonly_token"); [ "$code" = 403 ] || fail "console token with architecture.read only: expected 403, got $code"
# Агентский токен (client_credentials archrag-demo, aud MCP) консолью отвергается по audience.
agent=$(kc "$issuer/protocol/openid-connect/token" -d grant_type=client_credentials -d client_id=archrag-demo \
  --data-urlencode "client_secret=$KEYCLOAK_CLIENT_SECRET" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
code=$(api /api/graph/stats "$agent"); [ "$code" = 401 ] || fail "agent token (MCP audience) on console API: expected 401, got $code"
echo "ok 3: architecture.read-only console token -> 403, agent token -> 401"

# --- 4. architecture.admin -> 200; sync/sources даёт четыре источника ---
code=$(api /api/graph/stats "$admin"); [ "$code" = 200 ] || fail "/api/graph/stats with admin token: expected 200, got $code"
python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d, "empty stats"' "$tmp/body" || fail "/api/graph/stats: empty body"
code=$(api /api/sync/sources "$admin"); [ "$code" = 200 ] || fail "/api/sync/sources with admin token: expected 200, got $code"
python3 -I -c '
import json,sys
d=json.load(open(sys.argv[1])); items=d if isinstance(d,list) else d.get("items", d.get("sources", []))
names=sorted(str(i.get("source")) for i in items)
assert len(items)==4, "expected 4 sources, got %d: %s" % (len(items), names)
print("   sources:", ", ".join(names))' "$tmp/body" || fail "/api/sync/sources: expected four sources"
echo "ok 4: architecture.admin -> 200 on /api/graph/stats and /api/sync/sources (4 sources)"

# --- 5. токен консоли не проходит в записывающий admin REST ingestion (aud admin выдаёт scope, но azp чужой) ---
admin_claims=$(printf '%s' "$admin" | cut -d. -f2 | python3 -I -c 'import base64,json,sys; s=sys.stdin.read().strip(); print(json.dumps(json.loads(base64.urlsafe_b64decode(s+"="*(-len(s)%4)))))')
printf '%s' "$admin_claims" | python3 -I -c 'import json,sys; c=json.load(sys.stdin); aud=c["aud"] if isinstance(c["aud"],list) else [c["aud"]]; assert "urn:archrag:admin" in aud, "premise: token must carry the admin audience, aud=%s" % aud; assert c["azp"]=="archrag-admin-console"' \
  || fail "premise broken: console token has no admin audience / azp (the azp check would prove nothing)"
code=$(printf '%s' "$admin" | docker compose exec -T ingestion sh -c '
  umask 077; hdr=$(mktemp); trap "rm -f \"$hdr\"" EXIT
  printf "Authorization: Bearer %s\n" "$(cat)" > "$hdr"
  curl -s -o /dev/null -w "%{http_code}" -X POST http://127.0.0.1:8081/admin/replay -H @"$hdr" -H "Content-Type: application/json" \
    -d "{\"source\":\"urn:corp:eam\",\"receivedFrom\":\"1970-01-01T00:00:00Z\",\"receivedTo\":\"1970-01-02T00:00:00Z\"}"')
[ "$code" = 401 ] || fail "console token on ingestion admin REST: expected 401, got $code"
echo "ok 5: console token (with admin audience) on ingestion admin REST -> 401"

# --- 6. консоль и Keycloak недоступны по не-loopback адресу хоста ---
(exec 3<>"/dev/tcp/127.0.0.1/$console_port") 2>/dev/null || fail "control: console is not reachable on 127.0.0.1:$console_port"
ext=$(hostname -I 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$' | grep -v '^127\.' | head -1 || true)
if [ -n "$ext" ]; then
  for p in "$console_port" "$kc_port"; do
    if curl -s -o /dev/null --max-time 3 "http://$ext:$p/"; then fail "port $p answers on external address $ext"; fi
  done
  echo "ok 6: $ext:$console_port and $ext:$kc_port refuse connections (loopback only)"
else
  echo "skip 6: host has no non-loopback IPv4 address; published bindings: $(docker compose port admin-console 8080)" >&2
fi
docker compose port admin-console 8080 | grep -q '^127\.0\.0\.1:' || fail "admin-console is published not on 127.0.0.1: $(docker compose port admin-console 8080)"
docker compose port keycloak 8080 | grep -q '^127\.0\.0\.1:' || fail "keycloak is published not on 127.0.0.1: $(docker compose port keycloak 8080)"
# reverse-proxy консоль не видит (разные сети)
[ -n "$(docker compose exec -T reverse-proxy getent hosts mcp-server | tr -d '\r')" ] || fail "control: reverse-proxy cannot resolve mcp-server"
if [ -n "$(docker compose exec -T reverse-proxy getent hosts admin-console | tr -d '\r')" ]; then fail "reverse-proxy resolves admin-console"; fi

# --- 7. секреты и окружение контейнера консоли: только reader и роль archrag_console_ro ---
exec_c() { docker compose exec -T admin-console "$@"; }
secrets=$(exec_c ls /run/secrets | tr -d '\r' | sort | tr '\n' ' ')
[ "$secrets" = "ARCHRAG_CONSOLE_PG_PASSWORD ARCHRAG_NEO4J_READER_PASSWORD " ] || fail "admin-console /run/secrets must hold only reader and console_ro passwords, got: $secrets"
if exec_c env | grep -qE '^(ARCHRAG_PG_(USER|PASSWORD)=|NEO4J_PASSWORD=|ARCHRAG_NEO4J_PASSWORD=|ARCHRAG_NEO4J_USER=|ARCHRAG_S3_|ARCHRAG_WEBHOOK_SECRET=)'; then
  fail "admin-console environment contains writer/S3 settings"
fi
# Положительный контроль: neo4j и postgres достижимы (иначе отсутствие секретов ничего не доказывало бы).
exec_c timeout 3 bash -c 'exec 3<>/dev/tcp/neo4j/7687' 2>/dev/null || fail "control: admin-console cannot reach neo4j:7687"
exec_c timeout 3 bash -c 'exec 3<>/dev/tcp/postgres/5432' 2>/dev/null || fail "control: admin-console cannot reach postgres:5432"
echo "ok 7: admin-console holds only reader and archrag_console_ro secrets; no writer/S3 settings"

echo "console-smoke ok: PKCE login, 401/403/200, admin REST boundary, loopback only, secrets"
