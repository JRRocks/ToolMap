#!/usr/bin/env bash
# Scenario Z — zone download (docs/specs/performance.md §7): « IGN Cartes » over the fixed 5 km x 5 km square around
# Chambéry, tile zooms 12 to 16, the app's data emptied before each pass (`pm clear`). The download is started by the
# extras of the launch intent (fr.toolmap.perf.PerfIntent); the worker writes « zone completed ms=... » when done.
# Median over the passes (RUNS, default 3) of the duration and of the tiles per second.
set -u
. "$(dirname "$0")/lib.sh"

RUNS="${RUNS:-3}"
start_watch Z
durations=()
rates=()
for i in $(seq 1 "$RUNS"); do
    clear_app
    before=$(count_lines "zone [cir]")
    launch_fixed --es perf.zone "$ZONE" --es perf.zmin "$ZONE_ZMIN" --es perf.zmax "$ZONE_ZMAX" >/dev/null
    line=$(wait_line "zone [cir]" $((before + 1)) 900) || { log "passage $i : pas de fin de téléchargement en 15 min"; continue; }
    ms=$(field "$line" ms)
    downloaded=$(field "$line" downloaded)
    rate=$(awk -v d="$downloaded" -v ms="$ms" 'BEGIN { printf "%.1f\n", (ms > 0 ? d * 1000 / ms : 0) }')
    log "passage $i : $(echo "$line" | sed 's/.*ToolMapPerf: //') → $rate tuiles/s"
    durations+=("$ms")
    rates+=("$rate")
done
stop_watch
rate_median=$(printf '%s\n' "${rates[@]:-}" | grep . | sort -g | awk '{ v[NR] = $1 } END { if (NR == 0) print "-"; else if (NR % 2) print v[(NR + 1) / 2]; else printf "%.1f\n", (v[NR / 2] + v[NR / 2 + 1]) / 2 }')
echo "scenario=Z runs=$RUNS duration_median_ms=$(printf '%s\n' "${durations[@]:-}" | grep . | median) tiles_per_s_median=$rate_median last=$(echo "${line:-}" | sed 's/.*ToolMapPerf: //')"
