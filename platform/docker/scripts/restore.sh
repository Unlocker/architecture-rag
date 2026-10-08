#!/usr/bin/env bash
# Восстановление Neo4j из резервной копии и догон графа replay'ем журнала (E5.4).
# Использование (из любого каталога): platform/docker/scripts/restore.sh <метка>
#
# 1. Копия скачивается из S3 в volume backups, sha256 сверяются с manifest.json, версия Neo4j — с образом;
#    при расхождении выход с ошибкой до остановки чего-либо.
# 2. neo4j, ingestion, mcp-server останавливаются, `neo4j-admin database load`, сервисы поднимаются.
# 3. POST /admin/replay по каждому источнику за [backup_started_at - 5 мин, сейчас + 1 мин): события, пришедшие
#    после dump, применяются заново, уже применённые отсекает проверка версий.
# PostgreSQL не восстанавливается: журнал — источник replay, его откат потерял бы изменения (ручной pg_restore — в README).
# Известный пробел: удаления, сделанные reconciliation после backup, replay не повторяет; после restore нужен
# POST /admin/reconcile/{source} или новый snapshot.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

[ $# -eq 1 ] || die "usage: restore.sh <label>"
label=$1
check_label "$label"

log "download s3://$BACKUP_BUCKET/$label/"
vol_sh 'rm -rf "/backups/$1" && mkdir -p "/backups/$1" && chown neo4j:neo4j "/backups/$1"' "$label"
aws_s3 s3 cp --only-show-errors --recursive "s3://$BACKUP_BUCKET/$label/" "/backups/$label" >&2

log "verify checksums and Neo4j version"
current_ver=$(neo4j_version)
vol_sh '
  cd "/backups/$1" || exit 1
  [ -f manifest.json ] && [ -f neo4j.dump ] || { echo "backup incomplete" >&2; exit 1; }
  field() { sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" manifest.json; }
  [ "$(sha256sum neo4j.dump | cut -d" " -f1)" = "$(field neo4j_dump_sha256)" ] || { echo "neo4j.dump checksum mismatch" >&2; exit 1; }
  if [ -f postgres.dump ]; then
    [ "$(sha256sum postgres.dump | cut -d" " -f1)" = "$(field postgres_dump_sha256)" ] || { echo "postgres.dump checksum mismatch" >&2; exit 1; }
  fi
  [ "$(field neo4j_version)" = "$2" ] || { echo "Neo4j version mismatch: backup $(field neo4j_version), image $2" >&2; exit 1; }' \
  "$label" "$current_ver" || die "backup '$label' failed verification"
backup_started_at=$(vol_sh 'sed -n "s/.*\"backup_started_at\": *\"\([^\"]*\)\".*/\1/p" "/backups/$1/manifest.json"' "$label" | tr -d '\r')
[[ "$backup_started_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:]{8}Z$ ]] || die "bad backup_started_at in manifest"

trap 'log "restoring services after failure"; start_graph_services >/dev/null || true' ERR
log "stopping ingestion, mcp-server, neo4j"
stop_graph_services
log "neo4j-admin database load"
neo4j_admin database load neo4j --from-path="/backups/$label" --overwrite-destination=true >&2
trap - ERR
log "starting graph services"
start_graph_services

from=$(utc_iso "$backup_started_at - 5 minutes")
to=$(utc_iso "now + 1 minute")
log "replay [$from, $to)"
run_replay "$from" "$to"
log "waiting for the journal queue to drain"
wait_queue_empty 600
log "restore '$label' done"
