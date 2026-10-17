#!/usr/bin/env bash
# Сценарий 8 (DEMO.3): смена commit через Helm. Передеплой dep-card-gateway-prod (chartVersion 4.0.2 -> 4.0.3, новый
# commit по правилу sha1(<репозиторий>@<версия>)) -> заглушка deploy map -> webhook -> журнал -> граф -> MCP. Прежнее
# ребро DEPLOYED_FROM закрыто, новое открыто (К-3, история). МУТИРУЕТ состояние: повтор только после seed.sh demo.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
. "$HERE/lib-stubs.sh"
mcp_initialize >/dev/null

scenario "8. Передеплой: смена commit через Helm"

DEPLOYMENT=dep-card-gateway-prod
NEW_VERSION=4.0.3
NEW_COMMIT=$(printf 'card-gateway@%s' "$NEW_VERSION" | sha1sum | cut -d' ' -f1)

system=$(asset_gid ITSystem 5103)
footprint_commit='[.services[].deployments[] | select(.name == "card-gateway" and .environment == "prod")][0]'
step 08-footprint-before find_runtime_footprint "$(jq -cn --arg g "$system" '{systemGid:$g,environment:"prod"}')" \
  "до: Card Gateway в prod" |
  check "card-gateway 4.0.2, deployedFrom.commit совпадает с sourceCommit" \
    "$footprint_commit | .version == \"4.0.2\" and .deployedFrom.commit == .sourceCommit"
old_commit=$(mcp_call find_runtime_footprint "$(jq -cn --arg g "$system" '{systemGid:$g,environment:"prod"}')" | jq -r "$footprint_commit.sourceCommit")

payload=$(jq -cn --arg c "$NEW_COMMIT" --arg v "$NEW_VERSION" '{
  format: "deploymap-poc-1", name: "card-gateway", service: "svc-card-gateway", environment: "prod",
  chart: "card-gateway", chartVersion: $v,
  chartMeta: {name: "card-gateway", version: $v, appVersion: $v, annotations: {
    "archrag.io/eam-system-id": "5103", "archrag.io/git-repo-url": "git@git.example.org:card/card-gateway.git",
    "archrag.io/git-commit": $c}},
  hosts: ["vm-card-01", "vm-card-02"]}')
log "helm upgrade: $DEPLOYMENT -> $NEW_VERSION, commit $NEW_COMMIT"
version=$(stub_control deploymap upsert "$(jq -cn --arg i "$DEPLOYMENT" --argjson p "$payload" '{type:"DEPLOYMENT",id:$i,payload:$p}')")
wait_event "$DEPLOYMENT" "$version"
wait_demo_queue 300

step 08-footprint-after find_runtime_footprint "$(jq -cn --arg g "$system" '{systemGid:$g,environment:"prod"}')" \
  "после: тот же запрос, новый commit" |
  check "card-gateway 4.0.3, sourceCommit и deployedFrom.commit — новый commit" \
    "$footprint_commit | .version == \"$NEW_VERSION\" and .sourceCommit == \"$NEW_COMMIT\" and .deployedFrom.commit == \"$NEW_COMMIT\""

# История в графе: ровно одно открытое ребро с новым commit и одно закрытое со старым.
counts=$(cypher --format plain <<CYPHER | tail -1 | tr -d ' '
MATCH (:Deployment {deploymentKey: '$DEPLOYMENT'})-[r:DEPLOYED_FROM]->(:Repository)
RETURN count(CASE WHEN r.validTo IS NULL THEN 1 END) AS open_total,
       count(CASE WHEN r.validTo IS NULL AND r.commit = '$NEW_COMMIT' THEN 1 END) AS open_new,
       count(CASE WHEN r.validTo IS NOT NULL AND r.commit = '$old_commit' THEN 1 END) AS closed_old;
CYPHER
)
echo "DEPLOYED_FROM (open_total,open_new,closed_old): $counts" >&2
[ "$counts" = "1,1,1" ] || die "expected one open edge with the new commit and one closed edge with the old one (1,1,1), got $counts"
echo "  [ok] граф: прежнее ребро DEPLOYED_FROM закрыто, новое открыто" >&2
