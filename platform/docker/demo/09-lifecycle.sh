#!/usr/bin/env bash
# Сценарий 9 (DEMO.3): логическое удаление и reconcile (К-6). Сервис legacy-statements выводится из эксплуатации:
# записи исчезают из SCM, EAM и deploy map, snapshot трёх источников закрывает их. Узлы остаются с isCurrent=false,
# рёбра закрыты (validTo), поиск и обходы их больше не видят. Правило «удалять только после snapshot-complete» держит
# Reconciler. МУТИРУЕТ состояние: повтор только после seed.sh demo.
#
# Негативный случай (отказ SNAPSHOT_MASS_CLOSE_LIMIT, когда исчезло больше половины записей источника) на стенде не
# воспроизводится надёжно: polling адаптера раз в 30 с подбирает tombstone'ы удалённых записей раньше snapshot. Его
# проверяет ReconciliationIT.massCloseOverTheShareQuarantinesTheMarkerAndClosesNothing.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
. "$HERE/lib-stubs.sh"
mcp_initialize >/dev/null

scenario "9. Логическое удаление и reconcile"

svc=$(asset_gid Service svc-legacy-statements)
repo=$(asset_gid Repository repo-legacy-statements)
dep=$(asset_gid Deployment dep-legacy-statements-prod)
reporting=$(asset_gid Service svc-ledger-reporting)
vm=$(asset_gid ComputeInstance vm-legacy-app-02)
[ -n "$svc" ] && [ -n "$repo" ] && [ -n "$dep" ] || { echo "stand is not in the base state: rerun demo/seed.sh demo" >&2; exit 2; }

step 09-card-before get_asset "$(jq -cn --arg g "$svc" '{gid:$g}')" "до: legacy-statements действует" |
  check "isCurrent=true, есть открытые связи" '.isCurrent == true and (.relations | length) > 0'
step 09-impact-before trace_dependencies "$(jq -cn --arg g "$vm" '{gid:$g,mode:"impact"}')" "до: impact отказа vm-legacy-app-02" |
  check "затронуты sms-dispatcher и legacy-statements" \
    '[.nodes[] | select(.label == "Service") | .name] | contains(["sms-dispatcher", "legacy-statements"])'

log "вывод из эксплуатации: удаляем записи в SCM, EAM и deploy map"
stub_delete scm SERVICE svc-legacy-statements
stub_delete scm REPOSITORY repo-legacy-statements
stub_delete eam SERVICE_DEPENDENCY dep-legacy-statements-ledger-reporting
stub_delete deploymap DEPLOYMENT dep-legacy-statements-prod
for source in scm eam deploymap; do
  log "snapshot $source (reconcile)"
  run_reconcile "$source" >&2
done
wait_demo_queue 300

[ -z "$(asset_gid Service svc-legacy-statements)" ] || die "search_assets still finds svc-legacy-statements"
echo "  [ok] search_assets больше не находит svc-legacy-statements" >&2
step 09-search-after search_assets '{"query":"legacy-statements"}' "после: поиск по имени" |
  check "в результатах нет legacy-statements" '[.items[].name] | index("legacy-statements") == null'
step 09-card-after get_asset "$(jq -cn --arg g "$svc" '{gid:$g}')" "после: карточка закрытого актива по gid" |
  check "isCurrent=false, deletedAt задан, записи источников неактивны" \
    '.isCurrent == false and .deletedAt != null and ([.sources[].active] | all(. == false))'
step 09-card-deployment-after get_asset "$(jq -cn --arg g "$dep" '{gid:$g}')" "после: закрытое развёртывание" |
  check "Deployment закрыт" '.isCurrent == false and .deletedAt != null'
step 09-impact-after trace_dependencies "$(jq -cn --arg g "$vm" '{gid:$g,mode:"impact"}')" "после: impact отказа vm-legacy-app-02" |
  check "у ВМ остался только sms-dispatcher" \
    '[.nodes[] | select(.label == "Service") | .name] | index("sms-dispatcher") != null and index("legacy-statements") == null'
step 09-trace-after trace_dependencies "$(jq -cn --arg g "$reporting" '{gid:$g,direction:"upstream",relationTypes:["DEPENDS_ON"]}')" \
  "после: кто зависит от ledger-reporting" |
  check "legacy-statements среди зависимых нет" '[.nodes[].name] | index("legacy-statements") == null'

for pair in "Service:$svc" "Repository:$repo" "Deployment:$dep"; do
  [ "$(open_relations "${pair#*:}")" = 0 ] || die "${pair%%:*} legacy-statements still has open relations"
done
echo "  [ok] граф: у сервиса, репозитория и развёртывания нет открытых связей (validTo задан)" >&2

# Пороги и свежесть не сбиты: snapshot отдал сохранённый updatedAt устаревшей записи (К-7 сохранился).
notif=$(asset_gid ITSystem 5303)
mcp_call find_runtime_footprint "$(jq -cn --arg g "$notif" '{systemGid:$g}')" |
  check "К-7 сохранён: sms-dispatcher по-прежнему stale" '.warnings | map(select(startswith("stale: Service"))) | length == 1'
