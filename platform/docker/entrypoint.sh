#!/bin/sh
# Docker secrets -> переменные окружения процесса: каждый файл /run/secrets/<ИМЯ> становится переменной <ИМЯ>.
# Так секреты не попадают в `docker inspect` (Config.Env), а код и application.yml остаются прежними.
set -eu
if [ -d /run/secrets ]; then
  for f in /run/secrets/*; do
    [ -f "$f" ] || continue
    export "$(basename "$f")=$(cat "$f")"
  done
fi
exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar "$@"
