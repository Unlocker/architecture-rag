#!/bin/bash
# Создаёт (или обновляет пароль) пользователя archrag_reader в Neo4j. Идемпотентен.
# В Community нет ролей: reader всего лишь отдельный креденшел, а read-only обеспечивает приложение (E4.3).
set -euo pipefail
writer_pw=$(cat /run/secrets/neo4j_password)
reader_pw=$(cat /run/secrets/neo4j_reader_password)
case "$reader_pw" in
  *[\"\\]*) echo "NEO4J_READER_PASSWORD не должен содержать \" и \\" >&2; exit 1 ;;
esac

shell() { cypher-shell -a bolt+ssc://neo4j:7687 "$@"; }

shell -u neo4j -p "$writer_pw" -d system --param "p => \"$reader_pw\"" \
  "CREATE USER archrag_reader IF NOT EXISTS SET PASSWORD \$p CHANGE NOT REQUIRED;" >/dev/null

# Пароль в .env мог смениться после первого запуска: ALTER только если reader с текущим паролем не входит
# (ALTER на тот же пароль Neo4j отвергает).
if ! shell -u archrag_reader -p "$reader_pw" "RETURN 1;" >/dev/null 2>&1; then
  shell -u neo4j -p "$writer_pw" -d system --param "p => \"$reader_pw\"" \
    "ALTER USER archrag_reader SET PASSWORD \$p CHANGE NOT REQUIRED;" >/dev/null
fi
echo "neo4j-init: archrag_reader ready"
