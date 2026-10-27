#!/usr/bin/env bash
# Scenario 0 — cold start (docs/specs/performance.md §7): `am start -W` 5 times, median of TotalTime.
# Also prints the median time until the map is fully drawn (timing line of the opening, from the process start).
set -u
. "$(dirname "$0")/lib.sh"

RUNS="${RUNS:-5}"
start_watch "$(basename "$0" .sh)"
launch_fixed >/dev/null # first launch: puts the fixed view in place, not counted
wait_line "rendered step=0 " 1 60 >/dev/null || log "carte non dessinée au premier lancement"
totals=()
drawn=()
for i in $(seq 1 "$RUNS"); do
    before=$(count_lines "rendered step=0 ")
    total=$(launch_fixed)
    line=$(wait_line "rendered step=0 " $((before + 1)) 60) || line=""
    since=$(field "$line" since_process_ms)
    log "passage $i : TotalTime=${total} ms, carte dessinée ${since:-?} ms après le début du processus"
    totals+=("$total")
    [ -n "$since" ] && drawn+=("$since")
done
stop_watch
echo "scenario=0 runs=$RUNS total_time_median_ms=$(printf '%s\n' "${totals[@]}" | median) map_drawn_median_ms=$(printf '%s\n' "${drawn[@]:-}" | grep . | median)"
