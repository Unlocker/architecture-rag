#!/usr/bin/env bash
# Проверка демо-стенда (DEMO.3): число узлов и рёбер базового набора, домены систем, затем все сценарии и сверка их
# ответов с эталонами expected/*.json.
#   demo-check.sh           сверка; расхождение -> diff -u и код 1
#   demo-check.sh --update  переписывает expected/ ответами текущего прогона (перед коммитом сверьте эталоны глазами с
#                           docs/demo/dataset.md, раздел 5: кейс должен быть виден в ответе)
#   demo-check.sh --counts  только печатает число узлов и рёбер (для обновления констант ниже)
# Работает на базовом состоянии после `seed.sh demo` (иначе код 2). Прогон мутирует стенд: повтор — через новый seed.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
. "$HERE/lib-mcp.sh"
. "$HERE/lib-stubs.sh"

# Узлы и рёбра базового состояния (docs/demo/dataset.md, п. 7.4). Строки «метка/тип, число». Служебные SyncRun и PROCESSED
# не сверяются: их число зависит от количества запусков snapshot при старте адаптеров. DEPLOYED_FROM = 41 развёртывание
# минус два без репозитория в SCM (aml-case-mgmt и partner-gateway, К-8); SourceRecord = 180 записей источников минус
# одна карантинная (К-11).
EXPECTED_NODES='
ComputeInstance,27
Deployment,41
Environment,3
ITSystem,10
PendingRelation,3
Platform,3
Repository,26
Service,27
Solution,8
SourceRecord,179
SourceSystem,4
Team,5
'
EXPECTED_RELATIONS='
ASSERTS,150
DECOMPOSED_INTO,26
DEFERS,3
DEPENDS_ON,29
DEPLOYED_FROM,39
HAS_DEPLOYMENT,41
IMPLEMENTED_IN,26
IN_ENVIRONMENT,41
OWNED_BY,10
OWNS_RECORD,179
RUNS_ON,52
USES_PLATFORM,8
USES_SOLUTION,10
'
EXPECTED_CANDIDATES=2   # К-5а и К-5б (dataset.md, 4.3)
EXPECTED_CONFLICTS=2    # К-4а и К-4б

graph_counts() { # nodes|relations
  if [ "$1" = nodes ]; then
    cypher --format plain <<'CYPHER' | tail -n +2 | tr -d ' "' | grep -v '^SyncRun,'
MATCH (n) RETURN labels(n)[0] AS label, count(*) AS n ORDER BY label;
CYPHER
  else
    cypher --format plain <<'CYPHER' | tail -n +2 | tr -d ' "' | grep -v '^PROCESSED,'
MATCH ()-[r]->() RETURN type(r) AS type, count(*) AS n ORDER BY type;
CYPHER
  fi
}

if [ "${1:-}" = --counts ]; then
  echo "nodes:"; graph_counts nodes
  echo "relations:"; graph_counts relations
  exit 0
fi
mode=compare
case "${1:-}" in
  "") ;;
  --update) mode=update ;;
  *) echo "usage: $0 [--update|--counts]" >&2; exit 2 ;;
esac

mcp_initialize >/dev/null || die "MCP is not reachable: is the stand up? run demo/seed.sh demo"
require_base_state

diff_counts() { # <метка> <ожидаемое> <фактическое>
  diff -u <(printf '%s\n' "$2" | sed '/^$/d') <(printf '%s\n' "$3") >&2 || die "$1: graph counts differ from dataset.md (diff above)"
  echo "  [ok] $1 совпадают с dataset.md" >&2
}
diff_counts "узлы" "$EXPECTED_NODES" "$(graph_counts nodes)"
diff_counts "рёбра" "$EXPECTED_RELATIONS" "$(graph_counts relations)"
candidates=$(psql_q "SELECT count(*) FROM identity_candidate WHERE status = 'OPEN'")
[ "$candidates" = "$EXPECTED_CANDIDATES" ] || die "open identity candidates: expected $EXPECTED_CANDIDATES, got $candidates"
conflicts=$(psql_q "SELECT count(*) FROM source_conflict")
[ "$conflicts" = "$EXPECTED_CONFLICTS" ] || die "source conflicts: expected $EXPECTED_CONFLICTS, got $conflicts"
echo "  [ok] кандидатов identity: $candidates, конфликтов: $conflicts" >&2
# dataset.md, 8.2: домен выводится из description системы; у всех 10 систем он должен разбираться.
domains=$(cypher --format plain <<'CYPHER' | tail -1 | tr -d ' '
MATCH (s:ITSystem) WHERE s.isCurrent AND s.description =~ '^Domain: (Payments|Risk & Compliance|Customer Channels)\\..*'
RETURN count(s);
CYPHER
)
[ "$domains" = 10 ] || die "domain is parsed for $domains of 10 ITSystem nodes (expected 10)"
echo "  [ok] домен разобран у 10 из 10 систем" >&2

# docs/demo/graph.html (DEMO.5): число узлов и рёбер вшитой выгрузки должно совпасть со стендом. Свежая выгрузка идёт под
# reader-учёткой во временный файл; расхождение — граф или профиль изменились, а страницу не пересобрали.
GRAPH_HTML="$HERE/../../../docs/demo/graph.html"
[ -f "$GRAPH_HTML" ] || die "docs/demo/graph.html not found: run demo/build-graph.sh"
fresh_json=$(mktemp)
"$HERE/export-graph.sh" > "$fresh_json" || { rm -f "$fresh_json"; die "graph export failed"; }
fresh_counts=$(jq -c '.meta.counts' "$fresh_json")
rm -f "$fresh_json"
embedded_counts=$(sed -n '/<script type="application\/json" id="graph-data">/,/<\/script>/p' "$GRAPH_HTML" | sed '1d;$d' | jq -c '.meta.counts') \
  || die "docs/demo/graph.html has no embedded graph data: run demo/build-graph.sh"
[ "$fresh_counts" = "$embedded_counts" ] || die "docs/demo/graph.html is out of date (stand $fresh_counts, page $embedded_counts): rebuild with demo/build-graph.sh"
echo "  [ok] docs/demo/graph.html совпадает со стендом: $fresh_counts" >&2

work=$(mktemp -d)
trap 'rm -rf "$work"; mcp_cleanup' EXIT
DEMO_OUT="$work/actual" "$HERE/run-all.sh"

if [ "$mode" = update ]; then
  rm -f "$HERE"/expected/*.json
  mkdir -p "$HERE/expected"
  cp "$work"/actual/*.json "$HERE/expected/"
  echo "OK: expected/ updated ($(find "$HERE/expected" -name '*.json' | wc -l) files); review with git diff" >&2
  exit 0
fi
if diff -ru "$HERE/expected" "$work/actual" >&2; then
  echo "OK: demo-check passed ($(find "$work/actual" -name '*.json' | wc -l) responses match expected/)" >&2
else
  die "responses differ from expected/ (diff above); an intended change -> demo-check.sh --update"
fi
