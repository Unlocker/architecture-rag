#!/usr/bin/env bash
# Сквозной сценарий E5.4 (критерий 8): backup -> изменения в источнике -> restore -> replay => граф эквивалентен.
# Запуск: platform/docker/scripts/backup-restore-check.sh на поднятом стенде (нужен .env). Приёмка задачи.
#
#   F0 = fingerprint -> backup -> upsert в заглушке EAM (новый или изменённый объект) -> адаптер забирает их polling'ом
#   -> очередь пуста -> F1 (обязан отличаться от F0) -> restore (load + replay) -> очередь пуста -> F2 (обязан равняться F1).
set -euo pipefail
. "$(dirname "$0")/lib.sh"

label="check-$(utc_stamp)"
fp() { "$(dirname "$0")/graph-fingerprint.sh"; }
# Печатает sourceVersion, присвоенную заглушкой (состояние заглушки живёт между прогонами сценария).
upsert() { # <type> <id> <name>
  printf '{"type":"%s","id":"%s","payload":{"name":"%s","ownerTeam":"TEAM-PAY"}}' "$1" "$2" "$3" |
    dc exec -T stub-eam curl -sf -X POST http://127.0.0.1:8091/control/upsert -H 'Content-Type: application/json' --data-binary @- |
    sed -n 's/.*"sourceVersion":\([0-9]*\).*/\1/p'
}
# Адаптер забирает изменения polling'ом (по умолчанию раз в 30 с): ждём появления событий в inbox.
wait_event() { # <source_id> <source_version>
  local deadline=$((SECONDS + ${EVENT_WAIT_SECONDS:-300}))
  until [ "$(psql_q "SELECT count(*) FROM inbox_event WHERE source_id = '$1' AND source_version = '$2'")" -gt 0 ]; do
    [ "$SECONDS" -lt "$deadline" ] || die "event $1 v$2 did not reach the journal"
    sleep 3
  done
}

log "waiting for the stand to settle"
wait_queue_empty 300
F0=$(fp)
log "F0 taken"

"$(dirname "$0")/backup.sh" "$label" >/dev/null

log "changes in the EAM stub: new EAM-9001, changed EAM-1042"
probe="EAM-9001"
v_probe=$(upsert IT_SYSTEM "$probe" "Backup Probe $label")
v_core=$(upsert IT_SYSTEM EAM-1042 "Payments Core $label")
[ -n "$v_probe" ] && [ -n "$v_core" ] || die "stub /control/upsert did not return a version"
wait_event "$probe" "$v_probe"
wait_event EAM-1042 "$v_core"
wait_queue_empty 300
F1=$(fp)
log "F1 taken"
[ "$F0" != "$F1" ] || die "F1 equals F0: the changes did not reach the graph, the scenario checks nothing"

"$(dirname "$0")/restore.sh" "$label" >/dev/null
F2=$(fp)
log "F2 taken"

printf '== F0 (before backup) ==\n%s\n== F1 (after changes) ==\n%s\n== F2 (after restore + replay) ==\n%s\n' "$F0" "$F1" "$F2"
[ "$F2" = "$F1" ] || { diff <(echo "$F1") <(echo "$F2") >&2 || true; die "F2 differs from F1: graph after restore + replay is not equivalent"; }
echo "OK: restore + replay reproduced the graph (F2 == F1, F1 != F0)"
