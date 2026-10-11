#!/usr/bin/env bash
# Сценарий H.4 (UNLOCKER-252): смена commit развёртывания проходит от заглушки deploy map до MCP, старая связь закрыта.
# Запуск: platform/docker/scripts/deployment-commit-check.sh на поднятом стенде (нужен .env; на хосте нужны Docker и python3).
#
#   очередь пуста -> find_runtime_footprint (commit D0) -> upsert DEPLOYMENT с другим commit в заглушке deploy map (webhook
#   или polling) -> событие в inbox -> очередь пуста -> find_runtime_footprint (commit D1 != D0, deployedFrom.commit == D1)
#   -> Neo4j: ровно одна открытая DEPLOYED_FROM с D1, ребро с D0 закрыто (validTo не null).
#
# Идемпотентен: если seed уже сменён, commit переключается обратно (проверки симметричны). Код выхода != 0 при любом расхождении.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

DEPLOYMENT_ID="dep-payments-api-prod"
COMMIT_A="9fceb02d0ae598e95dc970b74767f19372d61af8"  # StubSources.PAYMENTS_API_COMMIT
COMMIT_B="1b4f0e9851971998e732078544c96b36c3d01ced"  # StubSources.PAYMENTS_API_NEW_COMMIT
REPO_SSH_URL="git@git.example.org:pay/payments-api.git"  # StubSources.PAYMENTS_API_SSH_URL
SYSTEM_ID="5101"                                         # EamApiSeed.SYSTEM_PAYMENTS_CORE

port="${ARCHRAG_PUBLIC_PORT:-8443}"
base="https://localhost:$port"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
dc cp certs:/certs/ca.crt "$tmp/ca.crt" >/dev/null

# Токен client_credentials со scope по умолчанию (architecture.read); секрет клиента не попадает в argv вывода и лог.
token=$(curl -sf --cacert "$tmp/ca.crt" -X POST "$base/realms/archrag/protocol/openid-connect/token" \
  -d grant_type=client_credentials -d client_id=archrag-demo --data-urlencode "client_secret=$KEYCLOAK_CLIENT_SECRET" |
  python3 -I -c 'import json,sys; print(json.load(sys.stdin)["access_token"])') || die "cannot get an access token"

# tools/call <name> <arguments-json> -> текст result.content[0].text (JSON ответа tool). Ошибка tool (isError) — короткий
# текст без значений запроса в stderr и код 1; три попытки: сразу после старта стенда индексы Neo4j могут быть не готовы.
mcp() {
  local out
  for _ in 1 2 3; do
    if out=$(curl -sf --cacert "$tmp/ca.crt" -X POST "$base/mcp" -H 'Content-Type: application/json'         -H 'Accept: application/json, text/event-stream' -H "Authorization: Bearer $token"         -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" |
        python3 -I -c '
import json, sys
r = json.load(sys.stdin)["result"]
text = r["content"][0]["text"]
if r.get("isError"):
    print("tool error: " + text[:200], file=sys.stderr)
    sys.exit(1)
print(text)'); then
      printf '%s\n' "$out"
      return 0
    fi
    sleep 5
  done
  return 1
}

# Печатает через табуляцию: sourceCommit, deployedFrom.commit, deployedFrom.validFrom, repository.url, repositoryUrl.
footprint() { # <systemGid>
  mcp find_runtime_footprint "{\"systemGid\":\"$1\",\"environment\":\"prod\"}" | python3 -I -c '
import json, sys
data = json.load(sys.stdin)
for svc in data["services"]:
    for d in svc["deployments"]:
        if any(s["sourceId"] == sys.argv[1] for s in d["sources"]):
            df, repo = d.get("deployedFrom") or {}, d.get("repository") or {}
            print("\t".join(str(v or "") for v in (d.get("sourceCommit"), df.get("commit"), df.get("validFrom"),
                                                   repo.get("url"), d.get("repositoryUrl"))))
            sys.exit(0)
sys.exit("deployment %s not found in the footprint" % sys.argv[1])' "$DEPLOYMENT_ID"
}

# Адаптер забирает изменения webhook'ом или polling'ом (по умолчанию раз в 30 с): ждём события в inbox.
wait_event() { # <source_id> <source_version>
  local deadline=$((SECONDS + ${EVENT_WAIT_SECONDS:-300}))
  until [ "$(psql_q "SELECT count(*) FROM inbox_event WHERE source_id = '$1' AND source_version = '$2'")" -gt 0 ]; do
    [ "$SECONDS" -lt "$deadline" ] || die "event $1 v$2 did not reach the journal"
    sleep 3
  done
}

log "waiting for the stand to settle"
# После `docker compose up` адаптеры ещё могут не успеть положить seed в журнал: ждём проекции самого развёртывания.
until [ "$(psql_q "SELECT count(*) FROM inbox_event WHERE source_id = '$DEPLOYMENT_ID' AND status = 'PROJECTED'")" -gt 0 ]; do
  [ "$SECONDS" -lt 300 ] || die "seed deployment $DEPLOYMENT_ID was not projected"
  sleep 3
done
wait_queue_empty 300

system_gid=$(mcp search_assets '{"query":"Payments Core","types":["ITSystem"],"limit":5}' |
  python3 -I -c '
import json, sys
for i in json.load(sys.stdin)["items"]:
    if i["name"] == "Payments Core":
        print(i["gid"]); sys.exit(0)
sys.exit(1)') || die "ITSystem Payments Core not found"

before=$(footprint "$system_gid") || die "before: find_runtime_footprint failed"
IFS=$'\t' read -r before_commit before_from before_valid_from _ _ <<<"$before"
[ -n "$before_commit" ] && [ "$before_from" = "$before_commit" ] || die "before: sourceCommit '$before_commit' and deployedFrom.commit '$before_from' must be equal and set"
[ -n "$before_valid_from" ] || die "before: deployedFrom.validFrom is empty"
case "$before_commit" in
  "$COMMIT_A") new_commit=$COMMIT_B; new_version=1.4.3 ;;
  "$COMMIT_B") new_commit=$COMMIT_A; new_version=1.4.2 ;;
  *) die "before: unexpected commit $before_commit (the stand seed was changed by something else)" ;;
esac
log "before: commit $before_commit, deployedFrom.validFrom $before_valid_from; switching to $new_commit"

payload=$(printf '{"type":"DEPLOYMENT","id":"%s","payload":{"format":"deploymap-poc-1","name":"payments-api","service":"svc-payments-api","environment":"prod","chart":"payments-api","chartVersion":"%s","chartMeta":{"name":"payments-api","version":"%s","appVersion":"%s","annotations":{"archrag.io/eam-system-id":"%s","archrag.io/git-repo-url":"%s","archrag.io/git-commit":"%s"}},"hosts":["vm-pay-01"]}}' \
  "$DEPLOYMENT_ID" "$new_version" "$new_version" "$new_version" "$SYSTEM_ID" "$REPO_SSH_URL" "$new_commit")
version=$(printf '%s' "$payload" |
  dc exec -T stub-deploymap curl -sf -X POST http://127.0.0.1:8091/control/upsert -H 'Content-Type: application/json' --data-binary @- |
  sed -n 's/.*"sourceVersion":\([0-9]*\).*/\1/p')
[ -n "$version" ] || die "stub /control/upsert did not return a version"
wait_event "$DEPLOYMENT_ID" "$version"
wait_queue_empty 300

after=$(footprint "$system_gid") || die "after: find_runtime_footprint failed"
IFS=$'\t' read -r after_commit after_from after_valid_from repo_url repo_url_prop <<<"$after"
log "after: commit $after_commit, deployedFrom.validFrom $after_valid_from, repository $repo_url"
[ "$after_commit" = "$new_commit" ] || die "after: sourceCommit is '$after_commit', expected $new_commit"
[ "$after_from" = "$new_commit" ] || die "after: deployedFrom.commit is '$after_from', expected $new_commit"
[ -n "$after_valid_from" ] || die "after: deployedFrom.validFrom is empty"
[ "$after_valid_from" != "$before_valid_from" ] || die "after: deployedFrom.validFrom did not change, the new edge was not opened"
[ "$repo_url" = "$repo_url_prop" ] && [ -n "$repo_url" ] || die "after: repository.url '$repo_url' and repositoryUrl '$repo_url_prop' differ or are empty"

# Граф напрямую: ровно одно открытое ребро (новый commit), ребро старого commit закрыто. Commit — константы выше (40 hex).
counts=$(cypher --format plain <<CYPHER | tail -1 | tr -d ' '
MATCH (:Deployment {deploymentKey: '$DEPLOYMENT_ID'})-[r:DEPLOYED_FROM]->(:Repository)
RETURN count(CASE WHEN r.validTo IS NULL THEN 1 END) AS open_total,
       count(CASE WHEN r.validTo IS NULL AND r.commit = '$new_commit' THEN 1 END) AS open_new,
       count(CASE WHEN r.validTo IS NOT NULL AND r.commit = '$before_commit' THEN 1 END) AS closed_old;
CYPHER
)
echo "DEPLOYED_FROM edges (open_total,open_new,closed_old): $counts"
[ "$counts" = "1,1,1" ] || die "expected exactly one open edge with the new commit and one closed edge with the old one (1,1,1), got $counts"

echo "OK: commit $before_commit -> $new_commit; MCP returns the new commit, the old DEPLOYED_FROM is closed"
