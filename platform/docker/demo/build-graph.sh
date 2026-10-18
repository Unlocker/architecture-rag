#!/usr/bin/env bash
# Сборка docs/demo/graph.html (DEMO.5): свежая выгрузка стенда (export-graph.sh) + шаблон + Cytoscape.js + сценарии в один
# офлайн-файл без внешних запросов. Нужны bash, jq и поднятый демо-стенд в базовом состоянии (demo/seed.sh demo).
#   build-graph.sh                 пишет ../../../docs/demo/graph.html
#   build-graph.sh --from <json>   берёт готовую выгрузку (export-graph.sh > файл) и стенд не трогает
# Данные идут в <script type="application/json">; `<` экранируется как <, поэтому `</script>` в текстах источников
# страницу не ломает. Тексты на странице выводятся только через textContent.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
GRAPH_DIR="$HERE/graph"
OUT="$HERE/../../../docs/demo/graph.html"

if [ "${1:-}" = --from ]; then
  [ -f "${2:-}" ] || { echo "usage: $0 [--from <export.json>]" >&2; exit 2; }
  export_json=$(cat "$2")
else
  export_json=$("$HERE/export-graph.sh")
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$export_json" | jq -c '.' | sed 's/</\\u003c/g' > "$work/data.json"
jq -c '.' "$GRAPH_DIR/scenarios.json" | sed 's/</\\u003c/g' > "$work/scenarios.json"
jq -e '.meta.counts.nodes > 0 and .meta.counts.edges > 0' < "$work/data.json" >/dev/null || { echo "FAILED: export is empty" >&2; exit 1; }
# Cytoscape не должен содержать закрывающего тега script: иначе встраивание сломает страницу.
! grep -q '</script' "$GRAPH_DIR/vendor/cytoscape.min.js" || { echo "FAILED: cytoscape.min.js contains </script" >&2; exit 1; }

sed -e "/^@@GRAPH_DATA@@\$/{r $work/data.json" -e 'd}' \
    -e "/^@@SCENARIOS@@\$/{r $work/scenarios.json" -e 'd}' \
    -e "/^@@CYTOSCAPE@@\$/{r $GRAPH_DIR/vendor/cytoscape.min.js" -e 'd}' \
    "$GRAPH_DIR/graph.template.html" > "$work/graph.html"
! grep -q '^@@' "$work/graph.html" || { echo "FAILED: unreplaced marker in template" >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
mv "$work/graph.html" "$OUT"
echo "OK: $(cd "$(dirname "$OUT")" && pwd)/graph.html ($(wc -c < "$OUT") bytes, $(jq -r '"\(.meta.counts.nodes) nodes, \(.meta.counts.edges) edges"' < "$work/data.json"))" >&2
