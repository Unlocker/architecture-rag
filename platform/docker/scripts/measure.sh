#!/usr/bin/env bash
# Замеры PoC на поднятом стенде (E5.5, критерий 9): p95 tools, задержка «источник → граф», throughput, sync lag,
# DLQ/error rate, поведение trace_dependencies по глубине. Запуск: platform/docker/scripts/measure.sh (нужен .env,
# стенд с профилем observability: ARCHRAG_OBSERVABILITY=true). На хосте нужны только Docker, bash и python3 (stdlib).
#
# Параметры (env): N=20 замеров «источник → граф», M=200 событий burst, K=100 вызовов каждого tool,
# P=4 параллельных потока, E2E_PATH=webhook|poll|both (по умолчанию both) путь «источник → граф», E2E_JITTER_MAX=
# <интервал polling> задержка перед upsert в режиме poll (равномерная выборка фазы polling).
# Сырые данные: platform/docker/measurements/<UTC-штамп>/ (в .gitignore); итоговая markdown-таблица: stdout.
# Код возврата 1, если p95 «источник → граф» по webhook > 30 с или по polling > интервал polling + 5 с, вырос DLQ/QUARANTINED или любой tool вернул ошибку.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

command -v python3 >/dev/null || die "python3 is required on the host"
out="$DOCKER_DIR/measurements/$(utc_stamp)"
mkdir -p "$out"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
dc cp certs:/certs/ca.crt "$tmp/ca.crt" >/dev/null 2>&1
poll=$(dc exec -T adapter-eam printenv ARCHRAG_POLL_INTERVAL_SECONDS 2>/dev/null | tr -d '\r' || true)
log "raw data: $out"

export M_OUT="$out" M_CA="$tmp/ca.crt" M_BASE="https://localhost:${ARCHRAG_PUBLIC_PORT:-8443}"
export M_POLL="${poll:-30}" M_N="${N:-20}" M_M="${M:-200}" M_K="${K:-100}" M_P="${P:-4}"
e2e_path="${E2E_PATH:-both}"
case "$e2e_path" in webhook|poll|both) ;; *) die "E2E_PATH must be webhook, poll or both" ;; esac
export M_E2E_PATH="$e2e_path" M_JITTER="${E2E_JITTER_MAX:-${poll:-30}}" M_DOCKER_DIR="$DOCKER_DIR"
export M_PG_USER="$POSTGRES_USER" M_PG_DB="$POSTGRES_DB" M_SECRET="$KEYCLOAK_CLIENT_SECRET"

python3 - <<'PY'
import concurrent.futures as cf
import json, math, os, random, re, ssl, statistics, subprocess, sys, time, urllib.parse, urllib.request, uuid

E = os.environ
OUT, BASE = E["M_OUT"], E["M_BASE"]
N, M, K, P = int(E["M_N"]), int(E["M_M"]), int(E["M_K"]), int(E["M_P"])
POLL, JITTER = int(E["M_POLL"]), float(E["M_JITTER"])
E2E_PATHS = ["webhook", "poll"] if E["M_E2E_PATH"] == "both" else [E["M_E2E_PATH"]]
# Гейт критерия 2 — webhook, 30 с; polling — страховка: интервал + 5 с.
E2E_LIMITS = {"webhook": 30.0, "poll": POLL + 5.0}
CTX = ssl.create_default_context(cafile=E["M_CA"])
# Демо-CA выпущен без keyUsage; строгий режим X.509 (Python 3.13+) такой CA отвергает. Цепочка и имя хоста по-прежнему проверяются.
CTX.verify_flags &= ~ssl.VERIFY_X509_STRICT
failures = []
raw = {}


def log(msg):
    print(f"[{time.strftime('%H:%M:%S', time.gmtime())}] {msg}", file=sys.stderr, flush=True)


def fail(msg):
    failures.append(msg)
    log(f"FAIL: {msg}")


def dc(*args, stdin=None):
    return subprocess.run(["docker", "compose", *args], cwd=E["M_DOCKER_DIR"], input=stdin, text=True,
                          capture_output=True, check=True).stdout


def psql(sql):
    return dc("exec", "-T", "postgres", "psql", "-X", "-qAt", "-U", E["M_PG_USER"], "-d", E["M_PG_DB"], "-c", sql).strip()


def prom(query):
    body = dc("exec", "-T", "prometheus", "wget", "-qO-", f"--post-data=query={urllib.parse.quote(query)}",
              "http://localhost:9090/api/v1/query")
    return json.loads(body)["data"]["result"]


def prom_scalar(query, default=0.0):
    r = prom(query)
    return float(r[0]["value"][1]) if r else default


# --- токен и MCP ---------------------------------------------------------------------------------------------
_tok = {"v": None, "exp": 0.0}


def token():
    # Токен живёт ~5 минут: обновляем заранее.
    if time.time() > _tok["exp"]:
        data = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": "archrag-demo",
                                       "client_secret": E["M_SECRET"]}).encode()
        with urllib.request.urlopen(f"{BASE}/realms/archrag/protocol/openid-connect/token", data, context=CTX) as r:
            _tok["v"] = json.load(r)["access_token"]
        _tok["exp"] = time.time() + 120
    return _tok["v"]


def rpc(method, params=None, timeout=60):
    req = urllib.request.Request(f"{BASE}/mcp", method="POST", data=json.dumps(
        {"jsonrpc": "2.0", "id": 1, "method": method, "params": params or {}}).encode(),
        headers={"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                 "Authorization": f"Bearer {token()}"})
    t0 = time.perf_counter()
    with urllib.request.urlopen(req, context=CTX, timeout=timeout) as r:
        body = json.load(r)
    return (time.perf_counter() - t0) * 1000, body


def call(name, args):
    """Возвращает (мс, ошибка|None, разобранный JSON результата|None). Ошибка = JSON-RPC error или isError."""
    try:
        ms, body = rpc("tools/call", {"name": name, "arguments": args})
    except Exception as e:  # сетевая ошибка тоже считается ошибкой вызова, а не глушится
        return 0.0, f"{type(e).__name__}: {e}", None
    if "error" in body:
        return ms, f"JSON-RPC {body['error']}", None
    res = body["result"]
    text = res["content"][0]["text"] if res.get("content") else ""
    if res.get("isError"):
        return ms, text.splitlines()[0][:200] if text else "isError", None
    try:
        return ms, None, json.loads(text)
    except ValueError:
        return ms, None, None


def pct(values, q):
    if not values:
        return float("nan")
    s = sorted(values)
    return s[min(len(s) - 1, math.ceil(q * len(s)) - 1)]


def stats(values):
    return {"n": len(values), "p50": pct(values, .5), "p95": pct(values, .95), "max": max(values) if values else float("nan")}


def fmt(x, unit="ms"):
    return "—" if x != x else (f"{x:.0f} {unit}" if unit == "ms" else f"{x:.1f} {unit}")


# --- фикстуры ------------------------------------------------------------------------------------------------
SEED_SOURCE_ID = "EAM-1042"
_, body = rpc("tools/list")
tools = [t["name"] for t in body["result"]["tools"]]
log(f"tools: {tools}")
ms, err, found = call("search_assets", {"query": SEED_SOURCE_ID})
for _ in range(3):  # первый вызов после холодного старта может упереться в таймаут запроса: это прогрев, не замер
    if err is None:
        break
    ms, err, found = call("search_assets", {"query": SEED_SOURCE_ID})
if err or not found.get("items"):
    sys.exit(f"seed {SEED_SOURCE_ID} is not found in the graph: {err}")
GID = found["items"][0]["gid"]
FIXTURES = {
    "ping": {},
    "search_assets": {"query": SEED_SOURCE_ID},
    "get_asset": {"gid": GID},
    "find_runtime_footprint": {"systemGid": GID},
    "trace_dependencies": {"gid": GID},
    "explain_provenance": {"gid": GID},
}
raw["fixtures"] = {"systemGid": GID, "sourceId": SEED_SOURCE_ID}
dlq0 = prom_scalar("sum(archrag_ingestion_dlq_open)")
quar0 = int(psql("SELECT count(*) FROM inbox_event WHERE status = 'QUARANTINED'"))
t_run0 = time.time()

# --- A. MCP tools --------------------------------------------------------------------------------------------
log(f"A: MCP tools, K={K}, P={P}")
tool_rows, tool_errors = {}, 0
for name in tools:
    if name not in FIXTURES:
        tool_rows[name] = None
        continue
    for _ in range(3):  # прогрев (JIT, кэш планов); не входит в статистику
        call(name, FIXTURES[name])
    seq = [call(name, FIXTURES[name]) for _ in range(K)]
    t0 = time.perf_counter()
    with cf.ThreadPoolExecutor(P) as ex:
        par = list(ex.map(lambda _: call(name, FIXTURES[name]), range(K)))
    wall = time.perf_counter() - t0
    errs = [r[1] for r in seq + par if r[1]]
    tool_errors += len(errs)
    if errs:
        fail(f"tool {name}: {len(errs)} of {2 * K} calls failed, e.g. {errs[0]}")
    tool_rows[name] = {"seq": stats([r[0] for r in seq if not r[1]]), "par": stats([r[0] for r in par if not r[1]]),
                       "rps_par": K / wall, "errors": len(errs), "calls": 2 * K}
raw["tools"] = tool_rows

# --- B. источник → граф --------------------------------------------------------------------------------------
log(f"B: source -> graph latency, N={N}, paths={E2E_PATHS}, poll jitter up to {JITTER:.0f}s")


def upsert(src_type, sid, name, notify, stub="stub-eam"):
    body = json.dumps({"type": src_type, "id": sid, "notify": notify,
                       "payload": {"name": name, "ownerTeam": "TEAM-PAY"}})
    out = dc("exec", "-T", stub, "curl", "-sf", "-X", "POST", "http://127.0.0.1:8091/control/upsert",
             "-H", "Content-Type: application/json", "--data-binary", "@-", stdin=body)
    version = int(re.search(r'"sourceVersion":(\d+)', out).group(1))
    wh = re.search(r'"webhookStatus":(\d+)', out)
    return version, int(wh.group(1)) if wh else None


e2e_by_path, e2e_stats = {}, {}
for path in E2E_PATHS:
    notify = path == "webhook"
    times = []
    for i in range(N):
        if not notify:
            time.sleep(random.uniform(0, JITTER))  # равномерная выборка фазы polling; webhook идёт без задержки
        name = f"measure-{path}-{uuid.uuid4().hex[:8]}"
        version, wh = upsert("IT_SYSTEM", SEED_SOURCE_ID, name, notify)
        if notify and not (wh and 200 <= wh < 300):
            fail(f"webhook was not accepted by the adapter (webhookStatus={wh}) for {name}")
        t0 = time.perf_counter()
        deadline = t0 + 300
        while True:
            ms_, err, asset = call("get_asset", {"gid": GID, "includeRelations": False})
            if err is None and asset and asset["properties"].get("name") == name:
                break
            if time.perf_counter() > deadline:
                fail(f"{path}: change {name} (v{version}) did not reach the graph in 300 s")
                t0 = None
                break
            time.sleep(0.25)
        if t0 is not None:
            times.append(time.perf_counter() - t0)
            log(f"  {path} {i + 1}/{N}: {times[-1]:.1f} s")
    e2e_by_path[path] = times
    e2e_stats[path] = stats(times)
    if times and e2e_stats[path]["p95"] > E2E_LIMITS[path]:
        fail(f"{path}: p95 source -> graph {e2e_stats[path]['p95']:.1f} s > {E2E_LIMITS[path]:.0f} s")
    if not times:
        fail(f"{path}: no change reached the graph")
raw["e2e_seconds"] = e2e_by_path

# --- C. throughput ingestion ---------------------------------------------------------------------------------
log(f"C: ingestion burst, M={M}")
run_id = uuid.uuid4().hex[:8]
prefix = f"EAM-LOAD-{run_id}"
lines = "".join(f"{prefix}-{i:05d}\n" for i in range(M))
# Burst идёт без webhook (notify=false): сценарий сопоставим с замером #59, дубли webhook + polling не искажают throughput.
script = ('while read -r id; do curl -sf -o /dev/null -X POST http://127.0.0.1:8091/control/upsert '
          '-H "Content-Type: application/json" -d "{\\"type\\":\\"IT_SYSTEM\\",\\"id\\":\\"$id\\",\\"notify\\":false,'
          '\\"payload\\":{\\"name\\":\\"load $id\\",\\"ownerTeam\\":\\"TEAM-PAY\\"}}" || exit 1; done')
t_burst0 = time.time()
dc("exec", "-T", "stub-eam", "sh", "-c", script, stdin=lines)
t_burst_sent = time.time()
lag_max = 0.0
deadline = time.time() + 900
while True:
    done = int(psql(f"SELECT count(*) FROM inbox_event WHERE source_id LIKE '{prefix}-%' AND status = 'PROJECTED'"))
    lag_max = max(lag_max, prom_scalar("max(archrag_ingestion_sync_lag_seconds)"))
    if done >= M:
        break
    if time.time() > deadline:
        fail(f"burst: only {done} of {M} events PROJECTED in 900 s")
        break
    time.sleep(2)
window = psql(f"SELECT extract(epoch FROM min(received_at)), extract(epoch FROM max(updated_at)), count(*) "
              f"FROM inbox_event WHERE source_id LIKE '{prefix}-%' AND status = 'PROJECTED'").split("|")
first_in, last_done, projected = float(window[0]), float(window[1]), int(window[2])
span = max(last_done - first_in, 1e-9)
burst = {"events": M, "projected": projected, "send_seconds": t_burst_sent - t_burst0,
         "ingest_window_seconds": span, "events_per_second": projected / span if projected else 0.0,
         "sync_lag_max_seconds_sampled": lag_max}
raw["burst"] = burst

# --- D. глубина traversal ------------------------------------------------------------------------------------
log("D: trace_dependencies by depth")
depth_rows = []
for d in [1, 2, 3, 4, 5, 6, 7, 0, None]:
    args = {"gid": GID} if d is None else {"gid": GID, "maxDepth": d}
    times, last = [], None
    for _ in range(5):
        ms_, err, resp = call("trace_dependencies", args)
        if err is None:
            times.append(ms_)
            last = resp
    out_of_range = d in (0, 7)
    if out_of_range:
        # Ожидаемое поведение — отказ с понятным текстом; фиксируем его, а не считаем сбоем tool.
        depth_rows.append({"depth": d, "behavior": "rejected" if err else "accepted", "message": err})
        if err is None:
            fail(f"maxDepth={d} was accepted, expected a rejection")
        continue
    if err:
        fail(f"trace_dependencies maxDepth={d}: {err}")
        continue
    depth_rows.append({"depth": "default" if d is None else d, "p50_ms": statistics.median(times),
                       "nodes": len(last["nodes"]), "paths": len(last["paths"]),
                       "relations": len(last["relations"]), "truncated": last["truncated"]})
raw["depth"] = depth_rows

# --- Prometheus ----------------------------------------------------------------------------------------------
log("Prometheus: waiting for the OTLP export")
time.sleep(25)
w = int(time.time() - t_run0) + 30
prom_p95 = {r["metric"]["tool"]: float(r["value"][1]) for r in prom(
    f"histogram_quantile(0.95, sum by (le, tool) (increase(archrag_mcp_tool_call_milliseconds_bucket[{w}s])))")}
events = {r["metric"]["status"]: float(r["value"][1]) for r in prom(
    f"sum by (status) (increase(archrag_ingestion_events_total[{w}s]))")}
dlq1 = prom_scalar("sum(archrag_ingestion_dlq_open)")
quar1 = int(psql("SELECT count(*) FROM inbox_event WHERE status = 'QUARANTINED'"))
lag_now = prom_scalar("max(archrag_ingestion_sync_lag_seconds)")
lag_window = prom_scalar(f"max(max_over_time(archrag_ingestion_sync_lag_seconds[{w}s]))")
total_events = sum(events.values())
bad_events = sum(v for k, v in events.items() if k != "PROJECTED")
raw["prometheus"] = {"tool_p95_ms": prom_p95, "events_by_status": events, "dlq_open": [dlq0, dlq1],
                     "quarantined": [quar0, quar1], "sync_lag_now": lag_now, "sync_lag_max_window": lag_window}
if dlq1 > dlq0 or quar1 > quar0:
    fail(f"DLQ grew during the run: dlq_open {dlq0:.0f} -> {dlq1:.0f}, QUARANTINED {quar0} -> {quar1}")

with open(os.path.join(OUT, "raw.json"), "w") as f:
    json.dump(raw, f, indent=1, default=str)

# --- отчёт ---------------------------------------------------------------------------------------------------
print(f"## Замеры PoC ({time.strftime('%Y-%m-%d %H:%M:%SZ', time.gmtime())})\n")
print(f"Параметры: N={N}, M={M}, K={K}, P={P}, интервал polling адаптеров {POLL} с.\n")
print("### MCP tools\n")
print("| tool | p50 посл. | p95 посл. | max посл. | p95 при P потоках | вызовов/с при P | p95 Prometheus | ошибки |")
print("|---|---|---|---|---|---|---|---|")
for name in tools:
    r = tool_rows[name]
    if r is None:
        print(f"| `{name}` | — | — | — | — | — | — | фикстура не определена |")
        continue
    pp = prom_p95.get(name)
    print(f"| `{name}` | {fmt(r['seq']['p50'])} | {fmt(r['seq']['p95'])} | {fmt(r['seq']['max'])} | {fmt(r['par']['p95'])} "
          f"| {r['rps_par']:.1f} | {fmt(pp) if pp is not None else '—'} | {r['errors']} из {r['calls']} |")
print("\n### Источник → граф (изменение в заглушке EAM → новое значение в `get_asset`)\n")
print("| путь | замеров | p50 | p95 | max | порог p95 | результат |\n|---|---|---|---|---|---|---|")
for path in E2E_PATHS:
    st, lim = e2e_stats[path], E2E_LIMITS[path]
    verdict = "OK" if e2e_by_path[path] and st["p95"] <= lim else "FAIL"
    label = "webhook" if path == "webhook" else f"polling (интервал {POLL} с)"
    print(f"| {label} | {st['n']} | {st['p50']:.1f} с | {st['p95']:.1f} с | {st['max']:.1f} с | {lim:.0f} с | {verdict} |")
print("\n### Ingestion\n")
print("| показатель | значение |\n|---|---|")
print(f"| burst | {burst['projected']} из {M} событий PROJECTED |")
print(f"| throughput (от первого приёма до последнего PROJECTED, {burst['ingest_window_seconds']:.1f} с) | "
      f"{burst['events_per_second']:.1f} событий/с |")
print(f"| sync lag, максимум за прогон (Prometheus) | {lag_window:.1f} с |")
print(f"| sync lag по завершении | {lag_now:.1f} с |")
print(f"| события за прогон по исходам | {', '.join(f'{k}={v:.0f}' for k, v in sorted(events.items())) or '—'} |")
print(f"| доля не-PROJECTED | {(bad_events / total_events * 100) if total_events else 0:.1f}% |")
print(f"| `archrag_ingestion_dlq_open` до / после | {dlq0:.0f} / {dlq1:.0f} |")
print(f"| QUARANTINED до / после | {quar0} / {quar1} |")
print(f"| ошибки tools (клиент) | {tool_errors} |")
print("\n### `trace_dependencies` по глубине (старт: `EAM-1042`)\n")
print("| maxDepth | p50 | узлов | путей | связей | усечён | поведение |\n|---|---|---|---|---|---|---|")
for r in depth_rows:
    if "behavior" in r:
        print(f"| {r['depth']} | — | — | — | — | — | {r['behavior']}: {r['message']} |")
    else:
        print(f"| {r['depth']} | {fmt(r['p50_ms'])} | {r['nodes']} | {r['paths']} | {r['relations']} | "
              f"{'да' if r['truncated'] else 'нет'} | |")

if failures:
    print("\n### Провалы\n")
    for f in failures:
        print(f"- {f}")
    sys.exit(1)
PY
