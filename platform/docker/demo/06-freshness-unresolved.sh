#!/usr/bin/env bash
# Сценарий 6 (DEMO.3): свежесть и неразрешённые ссылки. Устаревшие записи (К-7): stale в карточке, поиске, footprint и
# warnings. Сервис с ссылкой на несуществующую систему и развёртывание без репозитория (К-8). Данные не меняет.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
mcp_initialize >/dev/null

scenario "6. Свежесть и неразрешённые ссылки"

notif=$(asset_gid ITSystem 5303)
step 06-footprint-stale find_runtime_footprint "$(jq -cn --arg g "$notif" '{systemGid:$g}')" \
  "К-7: Notification Center, устаревшие sms-dispatcher / dep-sms-dispatcher-dev / vm-dev-legacy-01" |
  check "три предупреждения stale, окружение dev помечено stale, порог staleAfter=PT168H" \
    '(.warnings | length) == 3 and .staleAfter == "PT168H"
     and ([.services[].deployments[] | select(.environment == "dev") | .stale] == [true])'
step 06-search-stale search_assets '{"query":"vm-dev-legacy-01"}' "К-7: поиск устаревшей ВМ" |
  check "ВМ найдена точным совпадением" '.items[0].matchType == "EXACT"'
sms=$(asset_gid Service svc-sms-dispatcher)
step 06-card-stale get_asset "$(jq -cn --arg g "$sms" '{gid:$g}')" "К-7: карточка устаревшего сервиса" |
  check "lastSeenAt старше порога, но актив действующий" '.isCurrent == true and .deletedAt == null'

pg=$(asset_gid Service svc-partner-gateway)
step 06-unresolved-service get_asset "$(jq -cn --arg g "$pg" '{gid:$g}')" "К-8: сервис со ссылкой на EAM-9999" |
  check "связи с системой нет, ссылка отложена" '[.relations[] | select(.relationType == "DECOMPOSED_INTO")] | length == 0'
