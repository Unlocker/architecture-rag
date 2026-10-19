#!/usr/bin/env bash
# Скриншоты docs/demo/graph.html (офлайн, стенд не нужен) через headless Chrome/Chromium.
# graph.html не меняется: в build/ делается копия, в которую перед </body> вставлен скрипт,
# выбирающий сценарий (и раскладку) так же, как это делает пользователь в левой панели.
# S-15, S-16 (консоль http://127.0.0.1:8090 после сценариев 8–10) и S-06…S-14 (Claude Code, терминал, Inspector) здесь не снимаются:
# нужны поднятый стенд, вход через Keycloak и ручная съёмка. Для них в слайдах стоит \\IfFileExists с запасным листингом.
set -euo pipefail
cd "$(dirname "$0")"
OUT=figures
TMP=build/figures
mkdir -p "$OUT" "$TMP"

CHROME="${CHROME:-}"
if [ -z "$CHROME" ]; then
  for c in google-chrome google-chrome-stable chromium chromium-browser; do
    if command -v "$c" >/dev/null 2>&1 && "$c" --version >/dev/null 2>&1; then CHROME="$c"; break; fi
  done
fi
[ -n "$CHROME" ] || { echo "нужен Chrome/Chromium (переменная CHROME)" >&2; exit 1; }

SRC="$(cd ../ && pwd)/graph.html"

# shot <имя> <js, выполняемый после загрузки страницы>
shot() {
  local name="$1" js="$2" page="$TMP/$1.html"
  awk -v js="$js" '{ if (index($0, "</body>")) print "<script>window.addEventListener(\"load\",function(){setTimeout(function(){" js "},400);});</script>"; print }' \
    "$SRC" | sed "s/duration: 250/duration: 0/" >"$page"
  "$CHROME" --headless=new --no-sandbox --disable-gpu --hide-scrollbars \
    --window-size=1600,1010 --virtual-time-budget=6000 \
    --screenshot="$OUT/$name.png" "file://$(pwd)/$page" >/dev/null 2>&1
  [ -s "$OUT/$name.png" ] || { echo "не получился кадр $name" >&2; exit 1; }
  echo "figure: $OUT/$name.png"
}

pick() { echo "window.dispatchEvent(new Event('resize'));setTimeout(function(){var s=document.getElementById('scenario');s.value='$1';s.dispatchEvent(new Event('change'));},300);"; }

RS="window.dispatchEvent(new Event('resize'));"
# S-01..S-05 — по presentation-plan.md, п. 5; X-* — дополнительные кадры сценариев для слайдов.
shot S-01-graph-layers "$RS"
shot S-02-graph-envs "$RS var v=document.getElementById('view');v.value='envs';v.dispatchEvent(new Event('change'));"
shot S-03-card-payments-core "$RS var q=document.getElementById('search');q.value='Payments Core';q.dispatchEvent(new Event('input'));setTimeout(function(){var r=document.querySelector('#results li');if(r){r.click();}},300);"
shot S-04-conflict "$(pick 5)"
shot S-05-stale-closed "$(pick 6)"
shot X-sc1-search "$(pick 1)"
shot X-sc4-impact "$(pick 4)"
shot X-sc9-closed "$(pick 9)"
