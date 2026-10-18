#!/usr/bin/env bash
# Сценарий 4 (DEMO.3): impact отказа ВМ (trace_dependencies, mode=impact) с объяснимыми путями (К-1, К-2). Фильтр
# environment. Данные не меняет.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
mcp_initialize >/dev/null

scenario "4. Impact отказа ВМ"

# Имена затронутых узлов (affected) в виде «Метка:имя» для проверок.
affected='. as $r | [$r.nodes[] | select(.gid as $g | $r.affected | index($g)) | "\(.label):\(.name)"]'

risk=$(asset_gid ComputeInstance vm-risk-02)
step 04-impact-risk-vm trace_dependencies "$(jq -cn --arg g "$risk" '{gid:$g,mode:"impact",direction:"upstream"}')" \
  "К-1: отказ vm-risk-02 (prod)" |
  check "затронуты fraud-rules, цепочка вверх до payments-router и системы Fraud Screening, Card Gateway, Payments Core" \
    "$affected | contains([\"Service:fraud-rules\", \"Service:payments-router\", \"ITSystem:Fraud Screening\", \"ITSystem:Card Gateway\", \"ITSystem:Payments Core\"])"
step 04-impact-risk-vm-test trace_dependencies "$(jq -cn --arg g "$risk" '{gid:$g,mode:"impact",environment:"test"}')" \
  "К-1: тот же отказ, но фильтр environment=test" |
  check "prod-ВМ не влияет на окружение test: затронутых нет" '.affected == [] and (.paths | length) == 0'

legacy=$(asset_gid ComputeInstance vm-legacy-app-01)
step 04-impact-shared-vm trace_dependencies "$(jq -cn --arg g "$legacy" '{gid:$g,mode:"impact"}')" \
  "К-2: отказ общей ВМ vm-legacy-app-01" |
  check "затронуты четыре системы: Ledger, Settlement Hub, AML Monitoring, Notification Center" \
    "$affected | contains([\"ITSystem:Ledger\", \"ITSystem:Settlement Hub\", \"ITSystem:AML Monitoring\", \"ITSystem:Notification Center\"])"
