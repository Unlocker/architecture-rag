#!/bin/bash
# Задаёт пароль read-only роли archrag_console_ro (создаётся миграцией V6 ingestion без пароля). Идемпотентен.
# Пароль не попадает ни в argv, ни в переменные окружения psql: он читается из Docker secret метакомандой psql.
set -euo pipefail
PGPASSWORD=$(cat /run/secrets/pg_password)
export PGPASSWORD
psql -X -v ON_ERROR_STOP=1 -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null <<'SQL'
\set p `cat /run/secrets/console_pg_password`
ALTER ROLE archrag_console_ro PASSWORD :'p';
SQL
echo "console-pg-init: archrag_console_ro ready"
