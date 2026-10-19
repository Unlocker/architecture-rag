#!/usr/bin/env bash
# Сокращает эталоны platform/docker/demo/expected/*.json до ключевых полей для слайдов.
# Исходные файлы не меняются; результат кладётся в listings/ (коммитится: изменение эталона видно в диффе) и подключается через minted. Запуск вручную при смене expected/.
set -euo pipefail
cd "$(dirname "$0")"
EXP=../../../platform/docker/demo/expected
OUT=listings
mkdir -p "$OUT"
command -v jq >/dev/null || { echo "нужен jq" >&2; exit 1; }

jq -c '{items: [.items[] | {name, type, matchType}], truncated}' "$EXP/01-search-eam-id.json" >"$OUT/search.json"
jq '{type, properties: (.properties | {name, criticalityRaw})}' \
  "$EXP/01-card-criticality.json" >"$OUT/card.json"
jq '{mode, direction, truncated, affected: (.affected[:6] + ["..."])}' "$EXP/04-impact-risk-vm.json" >"$OUT/impact.json"
jq '{conflictState, type, record: (.records[0] | {source, sourceId, authority, conflictProperties})}' \
  "$EXP/05-provenance-helm-system.json" >"$OUT/provenance.json"
jq '.' "$EXP/07-limit-max-depth.json" >"$OUT/error-depth.json"
jq -c '.[] | {name, readOnly, destructive}' "$EXP/tools-list.json" >"$OUT/tools-list.json"
jq -c '{before: (.services[] | select(.name == "card-gateway") | .deployments[0].deployedFrom.commit)}' \
  "$EXP/08-footprint-before.json" >"$OUT/redeploy-before.json"
jq -c '{after: (.services[] | select(.name == "card-gateway") | .deployments[0].deployedFrom.commit)}' \
  "$EXP/08-footprint-after.json" >"$OUT/redeploy-after.json"
jq -c '{type, isCurrent, name: .properties.name}' "$EXP/09-card-after.json" >"$OUT/lifecycle-after.json"
echo "snippets: $(ls "$OUT" | wc -l) файлов в $OUT"
