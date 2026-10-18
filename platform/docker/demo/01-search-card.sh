#!/usr/bin/env bash
# Сценарий 1 (DEMO.3): поиск актива и карточка. Точный поиск по eam_id/sourceId, поиск по имени и домену (полнотекст
# по description), карточка get_asset со связями, критичность вне словаря (К-10). Данные не меняет.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
mcp_initialize >/dev/null

scenario "1. Поиск и карточка актива"

step 01-search-eam-id search_assets '{"query":"EAM-1042"}' "точный поиск по eam_id" |
  check "найден ровно Payments Core, matchType=EXACT" '.items | length == 1 and .[0].name == "Payments Core" and .[0].matchType == "EXACT"'
step 01-search-name search_assets '{"query":"Card Gateway","types":["ITSystem"]}' "поиск по имени, фильтр types" |
  check "первая находка — ИТ-система Card Gateway" '.items[0].name == "Card Gateway" and .items[0].type == "ITSystem"'
step 01-search-domain search_assets '{"query":"Risk & Compliance","types":["ITSystem"],"limit":10}' "поиск по домену (текст description)" |
  check "в домене Риск и комплаенс найдены Fraud Screening и AML Monitoring" \
    '[.items[].name] | contains(["Fraud Screening", "AML Monitoring"])'

core=$(asset_gid ITSystem 5101)
step 01-card-system get_asset "$(jq -cn --arg g "$core" '{gid:$g}')" "карточка Payments Core со связями 1 уровня" |
  check "у системы есть владелец и сервисы" \
    '[.relations[].relationType] | index("OWNED_BY") != null and index("DECOMPOSED_INTO") != null'

fraud=$(asset_gid ITSystem 5201)
step 01-card-criticality get_asset "$(jq -cn --arg g "$fraud" '{gid:$g}')" "К-10: критичность вне словаря" |
  check "criticality не заполнена, исходное значение сохранено в criticalityRaw" \
    '.properties.criticalityRaw == "CRITICAL" and (.properties | has("criticality") | not)'
