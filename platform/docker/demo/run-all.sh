#!/usr/bin/env bash
# Все сценарии демо подряд (DEMO.3): читающие 01–07, затем мутирующие 08–10 (docs/demo/dataset.md, раздел 6).
#   run-all.sh [NN ...]   без аргументов — все; с номерами — только они (например, `run-all.sh 01 04`)
#
# Работает только на базовом состоянии после `seed.sh demo`: мутации не откатываются, повторный прогон делается через
# новый seed. На изменённом стенде — выход с кодом 2 и подсказкой. Ответы шагов складываются в $DEMO_OUT (demo-check.sh).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
. "$HERE/lib-stubs.sh"

mcp_initialize >/dev/null || die "MCP is not reachable: is the stand up? run demo/seed.sh demo"
require_base_state

scenarios=()
if [ "$#" -gt 0 ]; then
  for n in "$@"; do
    f=$(compgen -G "$HERE/$n-*.sh" | head -1) || true
    [ -n "$f" ] || { echo "no scenario $n" >&2; exit 2; }
    scenarios+=("$f")
  done
else
  for f in "$HERE"/[0-9][0-9]-*.sh; do scenarios+=("$f"); done
fi
for f in "${scenarios[@]}"; do
  log "=== $(basename "$f")"
  bash "$f" || die "scenario $(basename "$f") failed"
done
echo "OK: ${#scenarios[@]} scenarios" >&2
