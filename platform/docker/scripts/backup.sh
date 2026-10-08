#!/usr/bin/env bash
# Резервная копия стенда (E5.4): холодный dump Neo4j + pg_dump PostgreSQL -> volume backups -> S3 (SeaweedFS).
# Использование (из любого каталога): platform/docker/scripts/backup.sh [<метка>]; метка по умолчанию — UTC-штамп.
#
# Neo4j Community не умеет online backup, поэтому на время dump останавливаются neo4j, ingestion и mcp-server.
# Адаптеры продолжают принимать события в inbox: после restore их догоняет replay (restore.sh).
# Результат: s3://$BACKUP_BUCKET/<метка>/{neo4j.dump,postgres.dump,manifest.json}.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

label="${1:-$(utc_stamp)}"
check_label "$label"

started_at=$(utc_iso)   # до остановки: от этого времени restore считает окно replay
log "backup '$label' started at $started_at"

# Каталог в volume; пустой, чтобы старые файлы одноимённой метки не попали в копию.
vol_sh 'rm -rf "/backups/$1" && mkdir -p "/backups/$1" && chown neo4j:neo4j "/backups/$1"' "$label"

# Если что-то упадёт после остановки, граф должен подняться обратно.
trap 'log "restoring services after failure"; start_graph_services >/dev/null || true' ERR

neo4j_ver=$(neo4j_version)
log "stopping ingestion, mcp-server, neo4j (Neo4j $neo4j_ver)"
stop_graph_services
log "neo4j-admin database dump"
neo4j_admin database dump neo4j --to-path="/backups/$label" >&2
trap - ERR
log "restarting graph services"
start_graph_services

log "pg_dump"
dc exec -T postgres pg_dump -Fc -U "$POSTGRES_USER" "$POSTGRES_DB" |
  vol_sh 'cat > "/backups/$1/postgres.dump"' "$label"

log "manifest"
vol_sh '
  cd "/backups/$1"
  printf "{\n  \"label\": \"%s\",\n  \"backup_started_at\": \"%s\",\n  \"neo4j_version\": \"%s\",\n  \"neo4j_dump_sha256\": \"%s\",\n  \"postgres_dump_sha256\": \"%s\"\n}\n" \
    "$1" "$2" "$3" "$(sha256sum neo4j.dump | cut -d" " -f1)" "$(sha256sum postgres.dump | cut -d" " -f1)" > manifest.json
  chown -R neo4j:neo4j .' "$label" "$started_at" "$neo4j_ver"

log "upload to s3://$BACKUP_BUCKET/$label/"
aws_s3 s3api head-bucket --bucket "$BACKUP_BUCKET" >/dev/null 2>&1 || aws_s3 s3 mb "s3://$BACKUP_BUCKET" >&2
aws_s3 s3 cp --only-show-errors --recursive "/backups/$label" "s3://$BACKUP_BUCKET/$label/" >&2

log "backup '$label' done"
echo "$label"
