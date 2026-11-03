#!/usr/bin/env bash
# Scenario B — blend modes (P26.0, docs/specs/performance.md §7). Usage: B-blend.sh
# Three maps (BASE under, then OVERLAYS, the last one blended with MODE) opened cold at two fixed points, without
# `pm clear` (the user's data stays):
# - online: the first opening of point P1 with the blend, of point P2 without it (each tile comes from the network);
# - stored: RUNS cold openings of P1 with the blend, then without it (every tile in MBTiles);
# - offline: the same in airplane mode.
# Prints the time until the map is fully drawn (`rendered`, median) and, per blended tile, the median and 90th
# percentile of the lines `blend` (tiles read) and `blended` (decode, blend, PNG encode) of the interceptor.
set -u
. "$(dirname "$0")/lib.sh"

RUNS="${RUNS:-3}"
BASE="${BASE:-opentopomap}"
OVERLAYS="${OVERLAYS:-ign-pentes,ign-plan}"
MODE="${MODE:-multiply}"
# Two points of the Maurienne, a few kilometres apart, with the same kind of maps. Online, a point is "first visit"
# only once: pass other points (P1, P2) to measure the network again.
P1="${P1:-45.4300 6.4500}"
P2="${P2:-45.4050 6.5050}"
ZOOM="${ZOOM:-13}"
SOURCE="$BASE"

# Opens the app cold at "<lat> <lon>" with blend mode <mode>; prints the `rendered` line.
open_at() {
    local point="$1" mode="$2" before line
    LAT="${point% *}"
    LON="${point#* }"
    before=$(count_lines "rendered ")
    launch_fixed --es perf.overlay "$OVERLAYS" --es perf.blend "$mode" >/dev/null
    line=$(wait_line "rendered " $((before + 1)) 90) || { echo "timeout"; return 1; }
    sleep 2
    echo "$line"
}

# Median and 90th percentile of key=value over the lines matching a pattern since line n of the watch file.
stats_since() {
    local from="$1" pattern="$2" key="$3" values
    values=$(tail -n +"$from" "$WATCH_FILE" | grep -- "$pattern" | tr ' ' '\n' | awk -F= -v k="$key" '$1 == k { print $2 }')
    [ -z "$values" ] && { echo "-"; return; }
    echo "$(echo "$values" | median)/p90 $(echo "$values" | sort -n | awk '{ v[NR] = $1 } END { print v[int(NR * 0.9 + 0.5)] }') (n=$(echo "$values" | wc -l | tr -d ' '))"
}

# One measured case: <label> <point> <mode> <passes>.
measure() {
    local label="$1" point="$2" mode="$3" passes="$4" from line ms=""
    from=$(($(wc -l <"$WATCH_FILE") + 1))
    for _ in $(seq 1 "$passes"); do
        line=$(open_at "$point" "$mode")
        ms="$ms $(field "$line" ms)"
    done
    echo "$label : ouverture ms =$ms (médiane $(echo "$ms" | tr ' ' '\n' | grep . | median))" \
        "| tuile fetch_ms $(stats_since "$from" "blend z=" fetch_ms)" \
        "| decode_ms $(stats_since "$from" "blended " decode_ms)" \
        "| blend_ms $(stats_since "$from" "blended " blend_ms)" \
        "| encode_ms $(stats_since "$from" "blended " encode_ms)" \
        "| total_ms $(stats_since "$from" "blend z=" total_ms)" \
        "| tuiles $(field "$line" store)s/$(field "$line" network)n/$(field "$line" composed)c"
}

start_watch B
measure "en ligne, $MODE (P1)" "$P1" "$MODE" 1
measure "en ligne, normal (P2)" "$P2" normal 1
measure "stocké, $MODE (P1)" "$P1" "$MODE" "$RUNS"
measure "stocké, normal (P1)" "$P1" normal "$RUNS"
adb shell cmd connectivity airplane-mode enable >/dev/null
sleep 3
measure "mode avion, $MODE (P1)" "$P1" "$MODE" "$RUNS"
measure "mode avion, normal (P1)" "$P1" normal "$RUNS"
adb shell cmd connectivity airplane-mode disable >/dev/null
stop_watch
