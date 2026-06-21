#!/usr/bin/env bash
#
# E2E orchestrator: build the harness, boot a dedicated server and one or two auto-connecting
# clients, drive a scenario, collect screenshots + report.json into e2e-out/.
#
# Usage:  bash e2e/run-e2e.sh [mc_version] [loader] [scenario]
#   mc_version  default 1.20.1
#   loader      default fabric        (fabric | forge)
#   scenario    default phase0-smoke  (phase0-smoke = 1 client; propagation = A+B clients)
#
# macOS: clients render to a native window (Xvfb is Linux/CI only). First run downloads JDK 17
# (foojay) + 1.20.1 deps and can take a while.

set -euo pipefail

MC_VERSION="${1:-1.20.1}"
LOADER="${2:-fabric}"
SCENARIO="${3:-phase0-smoke}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE="$REPO/gradlew"
GP=(-Pminecraft_version="$MC_VERSION" -Pe2e_scenario="$SCENARIO")

# Two clients (A subject + B observer) for the propagation scenarios; one client otherwise.
TWO_CLIENTS=false
[[ "$SCENARIO" == propagation* ]] && TWO_CLIENTS=true
# "Live" propagation: A changes its look WHILE B is already in-world watching. A blocks until B's
# entity loads, so we must launch B without first waiting for A's marker (else deadlock), and B is
# staggered until A has joined to avoid a concurrent 2nd-client login-handshake stall under load.
LIVE=false
[[ "$SCENARIO" == propagation-live* ]] && LIVE=true

SERVER_RUNDIR="$REPO/$LOADER/run/server-e2e"
CLIENTA_RUNDIR="$REPO/$LOADER/run/clientA"
CLIENTB_RUNDIR="$REPO/$LOADER/run/clientB"
OUT="$REPO/e2e-out/$MC_VERSION/$LOADER"
LOGDIR="$OUT/_logs"
MARKER_A="$CLIENTA_RUNDIR/e2e-report/done.marker"
MARKER_B="$CLIENTB_RUNDIR/e2e-report/done.marker"

mkdir -p "$OUT/client_a" "$LOGDIR" "$SERVER_RUNDIR"
$TWO_CLIENTS && mkdir -p "$OUT/client_b"

log() { echo "[orchestrator] $*"; }

SERVER_PID=""; CLIENTA_PID=""; CLIENTB_PID=""
cleanup() {
  log "teardown"
  pkill -f "quickskin.e2e.role=client_a" 2>/dev/null || true
  pkill -f "quickskin.e2e.role=client_b" 2>/dev/null || true
  pkill -f "quickskin.e2e.role=server"   2>/dev/null || true
  [[ -n "$CLIENTB_PID" ]] && kill "$CLIENTB_PID" 2>/dev/null || true
  [[ -n "$CLIENTA_PID" ]] && kill "$CLIENTA_PID" 2>/dev/null || true
  [[ -n "$SERVER_PID"  ]] && kill "$SERVER_PID"  2>/dev/null || true
}
trap cleanup EXIT

# Poll for a client's done.marker. Sets RESULT to the marker contents (pass/fail) or "timeout".
# Args: <marker-file> <client-pid> <label> [max_polls]
RESULT=""
wait_for_marker() {
  local marker="$1" pid="$2" label="$3" max="${4:-300}"
  RESULT="timeout"
  local i
  for ((i = 0; i < max; i++)); do
    if [[ -f "$marker" ]]; then RESULT="$(cat "$marker")"; log "$label reported: $RESULT"; return; fi
    if ! kill -0 "$pid" 2>/dev/null; then log "$label exited before writing marker"; return; fi
    sleep 2
  done
  log "$label timed out waiting for marker"
}

# Collect report.json + screenshots from a client run dir into an output dir.
# Args: <client-rundir> <out-subdir>
collect() {
  local rundir="$1" outdir="$2"
  mkdir -p "$outdir"
  [[ -f "$rundir/e2e-report/report.json" ]] && cp "$rundir/e2e-report/report.json" "$outdir/" || true
  if [[ -d "$rundir/screenshots" ]]; then
    cp "$rundir/screenshots/"*.png "$outdir/" 2>/dev/null || true
    # Tripwire: a healthy capture is full-window; a tiny PNG means a broken/transient grab slipped past
    # the harness FLUSH re-grab. Warn loudly (per file) so a regression surfaces instead of silently
    # shipping a broken gallery image. Reads the PNG IHDR width with stdlib python (no PIL/sips dep).
    local png w
    for png in "$outdir"/*.png; do
      [[ -e "$png" ]] || continue
      w=$(python3 -c "import struct,sys;f=open(sys.argv[1],'rb');f.read(16);print(struct.unpack('>I',f.read(4))[0])" "$png" 2>/dev/null) || true
      if [[ -n "$w" && "$w" -lt 640 ]]; then
        log "WARNING: undersized screenshot (${w}px wide, expected full-window): $png"
      fi
    done
  fi
}

# 0. Build the harness source set first — classes AND resources (the dev-mod manifest). Using
# `e2eClasses` (not just `compileE2eJava`) guarantees processE2eResources runs so the loader manifest
# (fabric.mod.json / mods.toml / neoforge.mods.toml) exists in the mod folder before any run; the
# server run references the e2e mod via the global mods{} block, and NeoForge's FML fatals on a
# manifest-less mod folder. Still provisions the JDK and fails fast before launching JVMs.
log "building harness ($MC_VERSION/$LOADER, scenario=$SCENARIO)..."
"$GRADLE" "${GP[@]}" ":$LOADER:e2eClasses" 2>&1 | tee "$LOGDIR/compile.log"

# 1. Pre-seed the server run dir (eula + deterministic server.properties).
cp "$REPO/e2e/server-template/eula.txt"          "$SERVER_RUNDIR/eula.txt"
cp "$REPO/e2e/server-template/server.properties" "$SERVER_RUNDIR/server.properties"

# Regenerate a fresh world each run with the time-pin datapack present at generation (guaranteed
# enabled). Its minecraft:load function sets time=day + stops the daylight/weather cycle, so
# screenshots are deterministic (Forge otherwise spawns into night, hiding the observed player).
rm -rf "$SERVER_RUNDIR/world" 2>/dev/null || true
mkdir -p "$SERVER_RUNDIR/world/datapacks/qs_e2e_time"
cp -R "$REPO/e2e/server-template/datapack/." "$SERVER_RUNDIR/world/datapacks/qs_e2e_time/"

# Reset each client run dir to deterministic state: clear generated artifacts (screenshots, prior
# report, imported skins/capes + saved appearance) and re-seed fixed options. Starting clean keeps
# hashes/screenshots reproducible across runs and stops the mod re-applying a leftover saved skin.
reset_client_dir() {
  local d="$1"
  rm -rf "$d/screenshots" "$d/e2e-report" "$d/quickskin" "$d/quickskin_cache" 2>/dev/null || true
  mkdir -p "$d"
  cp "$REPO/e2e/options.txt.template" "$d/options.txt"
  # NeoForge: disable the early-display GLFW window (no system-property equivalent; it's an FMLConfig
  # value). Avoids the macOS glfwGetPrimaryMonitor crash when the second client window opens.
  if [[ "$LOADER" == neoforge ]]; then
    mkdir -p "$d/config"
    cp "$REPO/e2e/fml.toml.neoforge" "$d/config/fml.toml"
  fi
}
reset_client_dir "$CLIENTA_RUNDIR"
$TWO_CLIENTS && reset_client_dir "$CLIENTB_RUNDIR"

# 2. Launch the dedicated server in the background.
log "starting server..."
( "$GRADLE" "${GP[@]}" ":$LOADER:runServerE2E" > "$LOGDIR/server.log" 2>&1 ) &
SERVER_PID=$!

# 3. Wait for "Done (" readiness (up to ~20 min on a cold first build).
log "waiting for server ready..."
for _ in $(seq 1 600); do
  if grep -q 'Done (' "$LOGDIR/server.log" 2>/dev/null; then log "server ready"; break; fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then log "server process died:"; tail -n 60 "$LOGDIR/server.log"; exit 1; fi
  sleep 2
done
grep -q 'Done (' "$LOGDIR/server.log" || { log "server never became ready"; tail -n 60 "$LOGDIR/server.log"; exit 1; }

# 4. Launch client A (subject).
rm -f "$MARKER_A" 2>/dev/null || true
log "starting client A..."
( "$GRADLE" "${GP[@]}" ":$LOADER:runClientAE2E" > "$LOGDIR/clientA.log" 2>&1 ) &
CLIENTA_PID=$!

RESULT_B="n/a"
if $LIVE; then
  # Live ordering: A blocks until B joins, so launch B before A's marker. Stagger B until A is in-world
  # (poll the server log for Alice's join) so the two logins don't contend on the FML handshake.
  log "LIVE: waiting for A to join before launching B..."
  for _ in $(seq 1 150); do
    grep -q 'Alice joined the game' "$LOGDIR/server.log" 2>/dev/null && { log "A joined"; break; }
    if ! kill -0 "$CLIENTA_PID" 2>/dev/null; then log "client A died before joining"; break; fi
    sleep 2
  done
  rm -f "$MARKER_B" 2>/dev/null || true
  log "starting client B (observer; A will change its look while B watches)..."
  ( "$GRADLE" "${GP[@]}" ":$LOADER:runClientBE2E" > "$LOGDIR/clientB.log" 2>&1 ) &
  CLIENTB_PID=$!

  log "waiting for client A done.marker (A applies once B is present)..."
  wait_for_marker "$MARKER_A" "$CLIENTA_PID" "client A" 300
  RESULT_A="$RESULT"
  log "waiting for client B done.marker..."
  wait_for_marker "$MARKER_B" "$CLIENTB_PID" "client B" 300
  RESULT_B="$RESULT"
else
  # 5. Wait for client A's sentinel (A applied + report written; for propagation A stays connected).
  log "waiting for client A done.marker..."
  wait_for_marker "$MARKER_A" "$CLIENTA_PID" "client A" 300
  RESULT_A="$RESULT"

  if $TWO_CLIENTS; then
    # 6. With A applied and still connected, launch client B (observer). B sends a C2S to get confirmed,
    #    receives A's relayed appearance+textures, and asserts the render-truthful propagation.
    if ! kill -0 "$CLIENTA_PID" 2>/dev/null; then
      log "WARNING: client A is no longer running; B may not observe A"
    fi
    rm -f "$MARKER_B" 2>/dev/null || true
    log "starting client B..."
    ( "$GRADLE" "${GP[@]}" ":$LOADER:runClientBE2E" > "$LOGDIR/clientB.log" 2>&1 ) &
    CLIENTB_PID=$!

    log "waiting for client B done.marker..."
    wait_for_marker "$MARKER_B" "$CLIENTB_PID" "client B" 300
    RESULT_B="$RESULT"
  fi
fi

# 7. Collect artifacts.
log "collecting artifacts -> $OUT"
collect "$CLIENTA_RUNDIR" "$OUT/client_a"
$TWO_CLIENTS && collect "$CLIENTB_RUNDIR" "$OUT/client_b"

# 8. Result. For propagation the observer (B) carries the key assertion; A must also pass.
if $TWO_CLIENTS; then
  log "RESULT: A=$RESULT_A B=$RESULT_B  (artifacts in $OUT)"
  [[ "$RESULT_A" == "pass" && "$RESULT_B" == "pass" ]]
else
  log "RESULT: A=$RESULT_A  (artifacts in $OUT/client_a)"
  [[ "$RESULT_A" == "pass" ]]
fi
