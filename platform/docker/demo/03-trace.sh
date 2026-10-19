#!/usr/bin/env bash
# Сценарий 3 (DEMO.3): зависимости системы (trace_dependencies, mode=trace). Цепочка DEPENDS_ON глубиной 7 и граница
# maxDepth=6 (К-1), фильтр relationTypes, truncated на широком обходе, маркер conflicts на узле (К-4). Данные не меняет.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
mcp_initialize >/dev/null

scenario "3. Зависимости: trace"

ibweb=$(asset_gid Service svc-ib-web)
step 03-trace-chain trace_dependencies "$(jq -cn --arg g "$ibweb" '{gid:$g,relationTypes:["DEPENDS_ON"],maxDepth:6}')" \
  "К-1: цепочка DEPENDS_ON от ib-web, maxDepth=6" |
  check "достигнут fraud-scoring (6 рёбер), fraud-rules (7 рёбер) за границей глубины" \
    '([.nodes[].name] | index("fraud-scoring") != null and index("fraud-rules") == null) and .truncated == false'

# Широкий обход: сервис → развёртывание → хост ветвится, число путей упирается в лимит max-paths.
# Эталон хранит только признак и число путей: какие именно 50 путей вошли в ответ, от запуска к запуску не гарантируется.
STEP_JQ='{truncated, paths: (.paths | length)}' step 03-trace-wide trace_dependencies "$(jq -cn --arg g "$ibweb" '{gid:$g}')" \
  "К-1: широкий обход без relationTypes" |
  check "ответ усечён лимитом путей: truncated=true" '.truncated == true and (.paths | length) == 50'

recon=$(asset_gid Deployment dep-settlement-recon-prod)
step 03-trace-conflict-deployment trace_dependencies "$(jq -cn --arg g "$recon" '{gid:$g,maxDepth:1}')" \
  "К-4а: маркер conflicts у развёртывания settlement-recon" |
  check "у узла Deployment есть маркер конфликта по eamSystemId" \
    '[.nodes[] | select(.label == "Deployment") | .conflicts[].properties[]] | index("eamSystemId") != null'
notif=$(asset_gid ITSystem 5303)
step 03-trace-conflict-system trace_dependencies "$(jq -cn --arg g "$notif" '{gid:$g,maxDepth:1,relationTypes:["DECOMPOSED_INTO"]}')" \
  "К-4б: маркер conflicts у Notification Center" |
  check "у системы есть маркер конфликта по platformId; sms-dispatcher помечен stale" \
    '([.nodes[] | select(.label == "ITSystem") | .conflicts[].properties[]] | index("platformId") != null)
     and ([.nodes[] | select(.name == "sms-dispatcher") | .stale] == [true])'
