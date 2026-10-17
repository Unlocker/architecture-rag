#!/usr/bin/env bash
# Сценарий 10 (DEMO.3): администрирование. Кандидаты identity: К-5а подтверждается оператором (общий gid), К-5б остаётся
# открытым (автослияния нет). Карантин: К-11, исправленная запись и возврат события из DLQ. Затем шаги для админ-консоли.
# МУТИРУЕТ состояние: повтор только после seed.sh demo. Admin REST публикуется только внутри сети стенда, поэтому вызовы
# идут изнутри контейнера ingestion (токен со scope architecture.admin).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
. "$HERE/lib-stubs.sh"
mcp_initialize >/dev/null

scenario "10. Администрирование: identity и карантин"

# --- К-5а: подтверждение кандидата ---
vm=$(asset_gid ComputeInstance vm-pay-01)
dup=$(asset_gid ComputeInstance cmdb-ci-88014)
[ -n "$vm" ] && [ -n "$dup" ] && [ "$vm" != "$dup" ] || { echo "stand is not in the base state: rerun demo/seed.sh demo" >&2; exit 2; }
log "К-5а до: два узла, кандидат: $(psql_q "SELECT status || ' score=' || score FROM identity_candidate WHERE left_id = 'cmdb-ci-88014' OR right_id = 'cmdb-ci-88014'")"
approve='{"left":{"source":"CMDB","sourceType":"COMPUTE_INSTANCE","sourceId":"vm-pay-01"},
          "right":{"source":"CMDB","sourceType":"COMPUTE_INSTANCE","sourceId":"cmdb-ci-88014"},
          "survivor":"LEFT","reason":"demo: same host registered twice in CMDB"}'
result=$(admin_post /admin/identity/candidates/approve "$approve")
echo "POST /admin/identity/candidates/approve -> ${result:0:200}" >&2
[ "${result%% *}" = 200 ] || die "approve candidate failed: HTTP ${result%% *}"
wait_demo_queue 120
[ "$(asset_gid ComputeInstance cmdb-ci-88014)" = "$vm" ] || die "after approve both CMDB records must resolve to the gid of vm-pay-01"
step 10-card-merged get_asset "$(jq -cn --arg g "$vm" '{gid:$g}')" "К-5а после: одна карточка, две записи источника" |
  check "у узла две записи CMDB: vm-pay-01 и cmdb-ci-88014" \
    '[.sources[].sourceId] | contains(["vm-pay-01", "cmdb-ci-88014"])'

# --- К-5б: кандидат остаётся открытым ---
open=$(psql_q "SELECT string_agg(left_id || ' ~ ' || right_id || ' score=' || score, ';') FROM identity_candidate WHERE status = 'OPEN'")
log "К-5б открытые кандидаты: $open"
[ "$open" = "repo-payments-api ~ repo-payments-api-legacy score=0.80" ] || die "expected exactly the repository candidate to stay OPEN, got '$open'"
[ "$(asset_gid Repository repo-payments-api)" != "$(asset_gid Repository repo-payments-api-legacy)" ] ||
  die "К-5б: repositories must stay two separate nodes"
echo "  [ok] К-5б: кандидат OPEN, в графе два узла Repository (автослияния нет)" >&2

# --- К-11: карантин, исправление, возврат из DLQ ---
BAD=dep-card-tokenizer-test-badcommit
log "К-11 до: $(psql_q "SELECT count(*) FROM inbox_event WHERE source_id = '$BAD' AND status = 'QUARANTINED'") событий в QUARANTINED (INVALID_PAYLOAD), узла в графе нет: '$(asset_gid Deployment "$BAD")'"
[ -z "$(asset_gid Deployment "$BAD")" ] || die "$BAD must not be in the graph before the fix"
commit=$(printf 'card-tokenizer@1.2.1' | sha1sum | cut -d' ' -f1)
payload=$(jq -cn --arg c "$commit" '{
  format: "deploymap-poc-1", name: "card-tokenizer", service: "svc-card-tokenizer", environment: "test",
  chart: "card-tokenizer", chartVersion: "1.2.1",
  chartMeta: {name: "card-tokenizer", version: "1.2.1", appVersion: "1.2.1", annotations: {
    "archrag.io/eam-system-id": "5103", "archrag.io/git-repo-url": "https://git.example.org/card/card-tokenizer.git",
    "archrag.io/git-commit": $c}},
  hosts: ["vm-card-test-01"]}')
version=$(stub_control deploymap upsert "$(jq -cn --arg i "$BAD" --argjson p "$payload" '{type:"DEPLOYMENT",id:$i,payload:$p}')")
wait_event "$BAD" "$version"
wait_demo_queue 300
step 10-card-fixed get_asset "$(jq -cn --arg g "$(asset_gid Deployment "$BAD")" '{gid:$g}')" "К-11 после: исправленное развёртывание в графе" |
  check "узел действует, commit корректный" '.isCurrent == true and (.properties.sourceCommit | test("^[0-9a-f]{40}$"))'

# Возврат из карантина повторяет обработку сохранённого payload: он по-прежнему невалиден, поэтому событие снова уходит
# в QUARANTINED с тем же кодом. Исправляет такую запись только новая версия в источнике (выше), а не release.
event_id=$(psql_q "SELECT event_id FROM inbox_event WHERE source_id = '$BAD' AND status = 'QUARANTINED' ORDER BY event_id LIMIT 1")
[ -n "$event_id" ] || die "К-11: no quarantined event of $BAD"
result=$(admin_post /admin/quarantine/release "$(jq -cn --arg e "$event_id" '{source:"urn:corp:deploymap",eventId:$e}')")
echo "POST /admin/quarantine/release -> ${result:0:200}" >&2
[ "${result%% *}" = 200 ] || die "release failed: HTTP ${result%% *}"
deadline=$((SECONDS + 120))
until [ "$(psql_q "SELECT status || ' ' || error_code FROM inbox_event WHERE event_id = '$event_id'")" = "QUARANTINED $DEMO_QUARANTINE_CODE" ]; do
  [ "$SECONDS" -lt "$deadline" ] || die "К-11: released event did not return to QUARANTINED $DEMO_QUARANTINE_CODE"
  sleep 2
done
echo "  [ok] К-11: release повторил обработку невалидного payload, событие снова в QUARANTINED $DEMO_QUARANTINE_CODE; исправление пришло новой версией" >&2
log "К-11 журнал: $(psql_q "SELECT string_agg(status || ' v' || source_version, ', ' ORDER BY source_version, status) FROM inbox_event WHERE source_id = '$BAD' AND status <> 'DUPLICATE'")"

cat >&2 <<CONSOLE

Админ-консоль (только чтение): http://127.0.0.1:${ARCHRAG_CONSOLE_PORT:-8090}
  вход: Keycloak, пользователь ${KEYCLOAK_CONSOLE_USER:-console-admin}, пароль KEYCLOAK_CONSOLE_PASSWORD из .env
  граф: разделы «Граф» и «Статистика»; синхронизация: журнал событий, DLQ, кандидаты identity, конфликты
  что искать: общий gid у vm-pay-01 и cmdb-ci-88014 (К-5а), открытый кандидат по репозиторию (К-5б), конфликты
  settlement-recon и Notification Center (К-4), обработанная запись $BAD (К-11)
CONSOLE
