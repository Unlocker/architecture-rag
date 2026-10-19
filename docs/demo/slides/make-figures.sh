#!/usr/bin/env bash
# Скриншоты docs/demo/graph.html (офлайн, стенд не нужен) через headless Chrome/Chromium.
# graph.html не меняется: в build/ делается копия, в которую перед </body> вставлен скрипт,
# выбирающий сценарий (и раскладку) так же, как это делает пользователь в левой панели.
# Кадры консоли (S-15, S-16) и ответы агента здесь не снимаются: нужны стенд, вход через Keycloak и Claude Code.
set -euo pipefail
cd "$(dirname "$0")"
OUT=images/generated
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

shot s01-overview "window.dispatchEvent(new Event('resize'));"
shot sc1-search "$(pick 1)"
shot sc4-impact "$(pick 4)"
shot sc5-conflict "$(pick 5)"
shot sc6-stale "$(pick 6)"
shot sc9-lifecycle "$(pick 9)"
