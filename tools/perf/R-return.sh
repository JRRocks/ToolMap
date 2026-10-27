#!/usr/bin/env bash
# Scenario R — back to the map (docs/specs/performance.md §7): map → « Explorer les traces et waypoints » → back,
# 10 times. Per return: the timing line of the map shown again (trigger=return, ms from the moment it is uncovered; since
# P15.4 the map is kept alive under the screen) and the time from the Back key to that line (device clock). Slow frames over
# the whole run. Before P15.4 the map was rebuilt on each return: the numbers in the journal are of step 0 of the new map.
set -u
. "$(dirname "$0")/lib.sh"

RUNS="${RUNS:-10}"
start_watch "$(basename "$0" .sh)"
launch_fixed >/dev/null
wait_line "rendered step=0 " 1 60 >/dev/null || { log "carte non dessinée au lancement"; exit 1; }
sleep 1
gfx_reset
drawn=()
fromback=()
for i in $(seq 1 "$RUNS"); do
    tap_element 'content-desc="Traces et waypoints"' || exit 1
    sleep 0.7
    tap_element 'Explorer les traces et waypoints' || exit 1
    sleep 1.5
    before=$(count_lines "trigger=return ")
    back_ms=$(device_ms)
    adb shell input keyevent KEYCODE_BACK
    line=$(wait_line "trigger=return " $((before + 1)) 30) || { log "passage $i : carte non dessinée"; continue; }
    ms=$(field "$line" ms)
    total=$(($(line_ms "$line") - back_ms))
    log "passage $i : carte dessinée ${ms} ms après sa ré-apparition, ${total} ms après la touche Retour"
    drawn+=("$ms")
    fromback+=("$total")
    sleep 1
done
gfx=$(gfx_summary)
stop_watch
read -r frames janky p90 p95 <<<"$gfx"
echo "scenario=R runs=$RUNS drawn_median_ms=$(printf '%s\n' "${drawn[@]:-}" | grep . | median) from_back_median_ms=$(printf '%s\n' "${fromback[@]:-}" | grep . | median) frames=$frames janky=$janky p90=$p90 p95=$p95"
