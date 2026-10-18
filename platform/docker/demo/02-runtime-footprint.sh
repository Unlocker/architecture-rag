#!/usr/bin/env bash
# Сценарий 2 (DEMO.3): где развёрнута система. Окружения и commit'ы (К-3), общая ВМ нескольких систем (К-2),
# развёртывание без репозитория в SCM (К-8, repository=null). Данные не меняет.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
mcp_initialize >/dev/null

scenario "2. Где развёрнута система"

core=$(asset_gid ITSystem 5101)
step 02-footprint-all find_runtime_footprint "$(jq -cn --arg g "$core" '{systemGid:$g}')" "К-3: Payments Core по всем окружениям" |
  check "payments-api развёрнут в dev, test и prod с тремя разными commit" \
    '[.services[].deployments[] | select(.name == "payments-api") | {environment, sourceCommit}]
     | (map(.environment) | sort) == ["dev","prod","test"] and (map(.sourceCommit) | unique | length) == 3'
step 02-footprint-prod find_runtime_footprint "$(jq -cn --arg g "$core" '{systemGid:$g,environment:"prod"}')" "фильтр environment=prod" |
  check "остались только prod-развёртывания" '[.services[].deployments[].environment] | unique == ["prod"]'

# К-2: ВМ vm-legacy-app-01 видна в footprint четырёх разных систем.
for sys in 5102 5104 5202 5303; do
  gid=$(asset_gid ITSystem "$sys")
  step "02-footprint-shared-vm-$sys" find_runtime_footprint "$(jq -cn --arg g "$gid" '{systemGid:$g,environment:"prod"}')" "К-2: система $sys на общей ВМ" |
    check "система $sys работает на vm-legacy-app-01" \
      '[.services[].deployments[].compute[].hostname] | index("vm-legacy-app-01.prod.example.org") != null'
done

# К-8: у svc-partner-gateway нет системы (EAM-9999 в EAM не существует), поэтому find_runtime_footprint до него не
# дотянется; развёртывание ссылается на репозиторий, которого нет в SCM, — смотрим карточку Deployment.
pg=$(asset_gid Service svc-partner-gateway)
step 02-unresolved-service get_asset "$(jq -cn --arg g "$pg" '{gid:$g}')" "К-8: сервис без системы" |
  check "у svc-partner-gateway нет связи с системой" '[.relations[] | select(.relationType == "DECOMPOSED_INTO")] | length == 0'
dep=$(asset_gid Deployment dep-partner-gateway-prod)
step 02-unresolved-deployment get_asset "$(jq -cn --arg g "$dep" '{gid:$g}')" "К-8: развёртывание без репозитория" |
  check "repositoryUrl и commit есть на узле, связи DEPLOYED_FROM нет" \
    '.properties.repositoryUrl != null and .properties.sourceCommit != null
     and ([.relations[] | select(.relationType == "DEPLOYED_FROM")] | length == 0)'
