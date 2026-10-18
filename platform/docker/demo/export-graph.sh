#!/usr/bin/env bash
# Выгрузка демо-графа для интерактивной визуализации (DEMO.5): текущие узлы, открытые связи и provenance в JSON на stdout.
#   export-graph.sh > graph.json     формат: {meta:{generatedAt, staleAfter, counts:{nodes, edges}}, nodes, edges}
# Только чтение: фиксированный Cypher под учёткой archrag_reader (не writer `neo4j` из scripts/lib.sh `cypher()`), одна
# транзакция. Секрет reader есть только в neo4j-init, поэтому запрос идёт из одноразового контейнера этого сервиса по
# внутренней сети compose; пароль берётся из /run/secrets и попадает только в окружение процесса cypher-shell.
#
# Почему не HTTP Query API: в образе neo4j:5-community нет curl, а cypher-shell JSON без APOC не отдаёт. Поэтому
# `cypher-shell --format plain` с фиксированными колонками. Он не экранирует `\` и перевод строки, поэтому каждое
# значение кодируется в самом Cypher (`~`, `\`, `"`, `,`, \n, \r, \t -> `~t ~b ~q ~c ~n ~r ~T`, null -> `~z`), а jq
# разбирает строки и декодирует. Тексты источников остаются данными: они только кодируются и декодируются.
#
# Узлы: девять меток слоёв Governance/Delivery/Runtime с isCurrent. SourceRecord/SourceSystem в узлы не попадают, а
# идут в sources карточки; отложенные связи (PendingRelation) в pending. Рёбра: связи между этими узлами с validTo IS NULL.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"

# Порог «устарел»: тот же ARCHRAG_QUERY_STALE_AFTER, что у mcp-server и admin-console (ISO-8601 Duration).
STALE_AFTER="${ARCHRAG_QUERY_STALE_AFTER:-P7D}"
[[ "$STALE_AFTER" =~ ^P[0-9A-Z.,:T-]+$ ]] || die "ARCHRAG_QUERY_STALE_AFTER is not an ISO-8601 duration: $STALE_AFTER"

# Фиксированные списки меток и типов связей (константы скрипта, не ввод).
NODE_LABELS="['Team','ITSystem','Solution','Platform','Service','Repository','Deployment','Environment','ComputeInstance']"
REL_TYPES="['DECOMPOSED_INTO','IMPLEMENTED_IN','HAS_DEPLOYMENT','IN_ENVIRONMENT','DEPLOYED_FROM','RUNS_ON','DEPENDS_ON','OWNED_BY','USES_SOLUTION','USES_PLATFORM']"

# Кодирование строкового выражения; null -> ~z.
enc() {
  printf "CASE WHEN %s IS NULL THEN '~z' ELSE replace(replace(replace(replace(replace(replace(replace(toString(%s), '~', '~t'), '\\\\\\\\', '~b'), '\"', '~q'), ',', '~c'), '\\\\n', '~n'), '\\\\r', '~r'), '\\\\t', '~T') END" "$1" "$1"
}
# Значение свойства (в том числе список строк) как строка.
val() {
  printf "CASE WHEN %s IS :: LIST<ANY> THEN reduce(s = '', x IN %s | s + CASE WHEN s = '' THEN '' ELSE ';' END + toString(x)) ELSE toString(%s) END" "$1" "$1" "$1"
}

read -r -d '' QUERY <<CYPHER || true
:begin
MATCH (n) WHERE n.isCurrent = true AND any(l IN labels(n) WHERE l IN $NODE_LABELS)
WITH n, [l IN labels(n) WHERE l IN $NODE_LABELS][0] AS label,
     [l IN labels(n) WHERE l IN ['VirtualMachine','PhysicalServer']] AS subtypes,
     (n.lastSeenAt IS NULL OR n.lastSeenAt < datetime() - duration(\$staleAfter)
       OR NOT EXISTS { MATCH (r:SourceRecord {active: true})-[:ASSERTS]->(n) }) AS stale
RETURN 'N' AS t, $(enc 'n.gid') AS a, $(enc 'label') AS b, $(enc "reduce(s = '', x IN subtypes | s + x)") AS c, toString(stale) AS d
ORDER BY n.gid;
MATCH (n) WHERE n.isCurrent = true AND any(l IN labels(n) WHERE l IN $NODE_LABELS)
UNWIND keys(n) AS k
WITH n, k, n[k] AS v WHERE k <> 'gid'
RETURN 'P' AS t, $(enc 'n.gid') AS a, $(enc 'k') AS b, $(enc "$(val v)") AS c
ORDER BY n.gid, k;
MATCH (n) WHERE n.isCurrent = true AND any(l IN labels(n) WHERE l IN $NODE_LABELS)
MATCH (r:SourceRecord)-[a:ASSERTS]->(n)
RETURN 'S' AS t, $(enc 'n.gid') AS a, $(enc 'r.source') AS b, $(enc 'r.sourceType') AS c, $(enc 'r.sourceId') AS d,
       $(enc 'r.sourceVersion') AS e, $(enc 'r.fetchedAt') AS f, $(enc 'r.active') AS g, $(enc 'r.deletedAt') AS h,
       $(enc 'a.authority') AS i, $(enc 'a.confidence') AS j, $(enc "$(val a.conflicts)") AS k
ORDER BY n.gid, r.source, r.sourceId;
MATCH (n) WHERE n.isCurrent = true AND any(l IN labels(n) WHERE l IN $NODE_LABELS)
MATCH (:SourceRecord)-[:ASSERTS]->(n)
WITH DISTINCT n
MATCH (r:SourceRecord)-[:ASSERTS]->(n)
MATCH (r)-[:DEFERS]->(p:PendingRelation)
RETURN 'D' AS t, $(enc 'n.gid') AS a, $(enc 'p.type') AS b, $(enc 'p.fromSource') AS c, $(enc 'p.fromType') AS d,
       $(enc 'p.fromId') AS e, $(enc 'p.toSource') AS f, $(enc 'p.toType') AS g, $(enc 'p.toId') AS h
ORDER BY n.gid, p.key;
MATCH (src)-[r]->(dst)
WHERE type(r) IN $REL_TYPES AND r.validTo IS NULL
  AND src.isCurrent = true AND any(l IN labels(src) WHERE l IN $NODE_LABELS)
  AND dst.isCurrent = true AND any(l IN labels(dst) WHERE l IN $NODE_LABELS)
RETURN 'E' AS t, $(enc 'elementId(r)') AS a, $(enc 'type(r)') AS b, $(enc 'src.gid') AS c, $(enc 'dst.gid') AS d
ORDER BY type(r), src.gid, dst.gid, elementId(r);
MATCH (src)-[r]->(dst)
WHERE type(r) IN $REL_TYPES AND r.validTo IS NULL
  AND src.isCurrent = true AND any(l IN labels(src) WHERE l IN $NODE_LABELS)
  AND dst.isCurrent = true AND any(l IN labels(dst) WHERE l IN $NODE_LABELS)
UNWIND keys(r) AS k
WITH r, k, r[k] AS v
RETURN 'Q' AS t, $(enc 'elementId(r)') AS a, $(enc 'k') AS b, $(enc "$(val v)") AS c
ORDER BY elementId(r), k;
:commit
CYPHER

# Выполняется в neo4j-init; запрос идёт со stdin. Предупреждения compose (stderr) оставляем: это единственный журнал.
rows=$(printf '%s\n' "$QUERY" | dc run --rm --no-deps -T --entrypoint bash neo4j-init -c '
  set -euo pipefail
  export NEO4J_USERNAME=archrag_reader NEO4J_PASSWORD="$(cat /run/secrets/neo4j_reader_password)"
  exec cypher-shell -a bolt+ssc://neo4j:7687 --format plain --param "staleAfter => \"$1\"" --non-interactive' export-graph "$STALE_AFTER") \
  || die "graph export failed (is the stand up? run demo/seed.sh demo)"

generated_at=$(utc_iso)
printf '%s\n' "$rows" | jq -R -s --arg generatedAt "$generated_at" --arg staleAfter "$STALE_AFTER" '
  def dec: if . == "~z" then null
           else gsub("~(?<c>[tbqcnrTz])"; {t:"~", b:"\\", q:"\"", c:",", n:"\n", r:"\r", T:"\t", z:"~"}[.c]) end;
  def row: select(startswith("\"")) | .[1:-1] | split("\", \"") | map(dec);
  [split("\n")[] | row] as $rows
  | ($rows | map(select(.[0] == "N")))  as $n
  | ($rows | map(select(.[0] == "P")))  as $p
  | ($rows | map(select(.[0] == "S")))  as $s
  | ($rows | map(select(.[0] == "D")))  as $d
  | ($rows | map(select(.[0] == "E")))  as $e
  | ($rows | map(select(.[0] == "Q")))  as $q
  | def coerce: if . == "true" then true elif . == "false" then false else . end;
    {
      meta: {generatedAt: $generatedAt, staleAfter: $staleAfter,
             counts: {nodes: ($n | length), edges: ($e | length)}},
      nodes: ($n | map(. as $r | {
        id: $r[1], label: $r[2],
        subtypes: (if $r[3] == "" then [] else [$r[3]] end),
        stale: ($r[4] == "true"),
        props: ($p | map(select(.[1] == $r[1])) | map({key: .[2], value: (.[3] | coerce)}) | from_entries),
        sources: ($s | map(select(.[1] == $r[1])) | map({
          source: .[2], sourceType: .[3], sourceId: .[4], sourceVersion: .[5], fetchedAt: .[6],
          active: (.[7] == "true"), deletedAt: .[8], authority: .[9], confidence: (.[10] | tonumber? // .[10]),
          conflicts: (if .[11] == null or .[11] == "" then [] else .[11] | split(";") end)})),
        pending: ($d | map(select(.[1] == $r[1])) | map({
          type: .[2], from: {source: .[3], type: .[4], id: .[5]}, to: {source: .[6], type: .[7], id: .[8]}}))
      })),
      edges: ($e | map(. as $r | {
        id: $r[1], type: $r[2], source: $r[3], target: $r[4],
        props: ($q | map(select(.[1] == $r[1])) | map({key: .[2], value: (.[3] | coerce)}) | from_entries)
      }))
    }'
