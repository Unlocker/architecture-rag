#!/usr/bin/env bash
# Отпечаток графа Neo4j: эталон эквивалентности для сценария backup -> изменения -> restore -> replay (E5.4).
# Запуск из любого каталога: platform/docker/scripts/graph-fingerprint.sh. Только чтение (константные запросы).
#
# Выводит:
#   nodes <метка> <число>, rels <тип> <число>
#   sha256 узлов (ключи), связей (начало, тип, конец), свойств узлов и связей.
# Ключ узла: gid, иначе runId (SyncRun), code (SourceSystem), иначе (source, sourceType, sourceId) у SourceRecord.
#
# Свойства, которые проектор ставит временем обработки, а не данными источника, в отпечаток не входят: replay
# проходит заново и даёт другое время (fetchedAt и lastSeenAt берутся у события, но SyncRun.startedAt/status и
# отметки наблюдения зависят от порядка обработки). Список ниже — единственное место, где это зафиксировано.
# `status`, `validFrom`, `validTo`, `deletedAt` — данные графа и сравниваются; исключается только SyncRun.status (процессное).
set -euo pipefail
. "$(dirname "$0")/lib.sh"

EXCLUDED="['fetchedAt','lastSeenAt','firstSeenAt','observedAt','startedAt','finishedAt','_lock']"

out=$(cypher --format plain <<CYPHER
:param excluded => $EXCLUDED;
MATCH (n) UNWIND labels(n) AS l RETURN 'NODELABEL' AS kind, l AS a, count(*) AS b ORDER BY l;
MATCH ()-[r]->() RETURN 'RELTYPE' AS kind, type(r) AS a, count(*) AS b ORDER BY a;
MATCH (n)
WITH n, coalesce(n.gid, n.runId, n.code, n.source + '/' + n.sourceType + '/' + n.sourceId) AS k
RETURN 'NODE' AS kind, k AS a, '' AS b ORDER BY k;
MATCH (s)-[r]->(e)
WITH coalesce(s.gid, s.runId, s.code, s.source + '/' + s.sourceType + '/' + s.sourceId) AS sk, type(r) AS t,
     coalesce(e.gid, e.runId, e.code, e.source + '/' + e.sourceType + '/' + e.sourceId) AS ek
RETURN 'REL' AS kind, sk + ' ' + t + ' ' + ek AS a, '' AS b ORDER BY a;
MATCH (n)
WITH n, coalesce(n.gid, n.runId, n.code, n.source + '/' + n.sourceType + '/' + n.sourceId) AS k
UNWIND keys(n) AS p
WITH k, p, n[p] AS v, n WHERE NOT p IN \$excluded AND NOT (p = 'status' AND 'SyncRun' IN labels(n))
WITH k, p, CASE WHEN v IS :: LIST<ANY> THEN reduce(s = '', x IN v | s + toString(x) + ',') ELSE toString(v) END AS sv
ORDER BY k, p
RETURN 'NODEPROP' AS kind, k + '.' + p AS a, sv AS b;
MATCH (s)-[r]->(e)
WITH coalesce(s.gid, s.runId, s.code, s.source + '/' + s.sourceType + '/' + s.sourceId) AS sk, type(r) AS t,
     coalesce(e.gid, e.runId, e.code, e.source + '/' + e.sourceType + '/' + e.sourceId) AS ek, r
UNWIND keys(r) AS p
WITH sk, t, ek, p, r[p] AS v WHERE NOT p IN \$excluded
WITH sk, t, ek, p, CASE WHEN v IS :: LIST<ANY> THEN reduce(s = '', x IN v | s + toString(x) + ',') ELSE toString(v) END AS sv
ORDER BY sk, t, ek, p
RETURN 'RELPROP' AS kind, sk + ' ' + t + ' ' + ek + '.' + p AS a, sv AS b;
CYPHER
) || die "cypher-shell failed"
# cypher-shell печатает строковые значения в кавычках и заголовок на каждый запрос.
out=$(printf '%s\n' "$out" | tr -d '\r' | grep -v '^kind, a, b$' | sed -E 's/^"([A-Z]+)", /\1, /')

for kind in NODELABEL RELTYPE; do
  printf '%s\n' "$out" | grep "^$kind, " | sed -E "s/^$kind, \"?([^\",]*)\"?, ([0-9]+)$/${kind,,} \1 \2/" || true
done
for kind in NODE REL NODEPROP RELPROP; do
  printf '%-8s %s\n' "$kind" "$(printf '%s\n' "$out" | grep "^$kind, " | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
done
printf '%-8s %s\n' TOTAL "$(printf '%s\n' "$out" | LC_ALL=C sort | sha256sum | cut -d' ' -f1)"
