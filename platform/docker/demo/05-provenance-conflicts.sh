#!/usr/bin/env bash
# Сценарий 5 (DEMO.3): происхождение данных и конфликты источников. explain_provenance: расхождение системы в Helm chart
# (К-4а, К-9), платформа системы и решения (К-4б), критичность вне словаря (К-10). Данные не меняет.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
mcp_initialize >/dev/null

scenario "5. Provenance и конфликты"

recon=$(asset_gid Deployment dep-settlement-recon-prod)
step 05-provenance-helm-system explain_provenance "$(jq -cn --arg g "$recon" '{gid:$g}')" \
  "К-4а/К-9: Helm chart называет систему 5102, SCM — 5104" |
  check "открытый конфликт по eamSystemId, запись deploy map — авторитетная" \
    '.conflictState == "OPEN" and (.records[0].conflictProperties | index("eamSystemId") != null) and .records[0].authoritative'
step 05-card-recon-deployment get_asset "$(jq -cn --arg g "$recon" '{gid:$g}')" "К-9: карточка развёртывания" |
  check "развёртывание привязано к сервису settlement-recon" \
    '[.relations[] | select(.relationType == "HAS_DEPLOYMENT") | .name] | index("settlement-recon") != null'
notif=$(asset_gid ITSystem 5303)
step 05-provenance-platform explain_provenance "$(jq -cn --arg g "$notif" '{gid:$g}')" \
  "К-4б: platform_id системы против решения" |
  check "открытый конфликт по platformId" '.conflictState == "OPEN" and (.records[0].conflictProperties | index("platformId") != null)'
fraud=$(asset_gid ITSystem 5201)
step 05-provenance-criticality explain_provenance "$(jq -cn --arg g "$fraud" '{gid:$g,property:"criticality"}')" \
  "К-10: критичность вне словаря, происхождение свойства" |
  check "запись EAM — мастер, конфликта нет" '.conflictState != "OPEN" and .records[0].source == "EAM"'
