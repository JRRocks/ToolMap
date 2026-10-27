#!/usr/bin/env bash
# Scenarios T1 and T2 — tiles (docs/specs/performance.md §7). Usage: T-tiles.sh stored|online
# Fixed path on « IGN Cartes » at Chambéry: opening, 5 swipes, 2 taps on « Zoomer ». Each step waits for its timing
# line (time until the map is fully drawn, tiles by origin) before the next one.
# - online (T2): the app's data is emptied before each pass (`pm clear`: maps, settings, everything), so every tile
#   comes from the network;
# - stored (T1): one unmeasured pass after `pm clear` stores the tiles of the path, then the measured passes find them
#   all in MBTiles.
# Prints each pass, then the median of each step and of the total over the passes (RUNS, default 3).
set -u
. "$(dirname "$0")/lib.sh"

MODE="${1:-}"
case "$MODE" in
stored) NAME=T1 ;;
online) NAME=T2 ;;
*)
    echo "usage : $0 stored|online" >&2
    exit 2
    ;;
esac
RUNS="${RUNS:-3}"
STEPS="open swipe1 swipe2 swipe3 swipe4 swipe5 zoom1 zoom2"

# The numbers of a timing line, in the order of run_path.
step_fields() { for key in ms idle_ms store network composed failed; do printf " %s" "$(field "$1" "$key")"; done; }

# One pass of the path; prints "<step> <ms> <idle_ms> <store> <network> <composed> <failed>" per step.
run_path() {
    local before line xy zoom_xy step
    before=$(count_lines "rendered ")
    launch_fixed >/dev/null
    line=$(wait_line "rendered " $((before + 1)) 60) || { echo "open timeout"; return 1; }
    echo "open$(step_fields "$line")"
    sleep 1
    zoom_xy=$(find_element 'content-desc="Zoomer"')
    for step in swipe1 swipe2 swipe3 swipe4 swipe5 zoom1 zoom2; do
        before=$(count_lines "rendered ")
        case "$step" in
        swipe1 | swipe3) xy="800 1300 300 1300" ;; # map moves left: new tiles on the right
        swipe2 | swipe4) xy="540 1600 540 1000" ;; # map moves up: new tiles below
        swipe5) xy="300 1000 800 1000" ;;
        *) xy="" ;;
        esac
        if [ -n "$xy" ]; then
            # shellcheck disable=SC2086
            adb shell input swipe $xy 600
        else
            # shellcheck disable=SC2086
            adb shell input tap $zoom_xy
        fi
        line=$(wait_line "rendered " $((before + 1)) 30) || { echo "$step timeout"; continue; }
        echo "$step$(step_fields "$line")"
        sleep 1
    done
}

start_watch "$NAME"
if [ "$MODE" = stored ]; then
    log "$NAME : passage de préparation (tuiles stockées)"
    clear_app
    run_path >/dev/null
fi
RESULTS="$OUT_DIR/$NAME-steps-$(date +%Y%m%d-%H%M%S).txt"
: >"$RESULTS"
for i in $(seq 1 "$RUNS"); do
    [ "$MODE" = online ] && clear_app
    run_path | sed "s/^/$i /" | tee -a "$RESULTS" >&2
done
stop_watch

# Per step: median of ms and of idle_ms (the wait after the camera stopped), and the tiles of the first pass;
# totals = sums of the step medians.
summary=""
sum=0
sum_idle=0
for step in $STEPS; do
    med=$(awk -v s="$step" '$2 == s && $3 ~ /^[0-9]+$/ { print $3 }' "$RESULTS" | median)
    idle=$(awk -v s="$step" '$2 == s && $4 ~ /^[0-9]+$/ { print $4 }' "$RESULTS" | median)
    tiles=$(awk -v s="$step" '$2 == s && $3 ~ /^[0-9]+$/ { print "s" $5 "/n" $6 "/c" $7 "/f" $8; exit }' "$RESULTS")
    summary="$summary $step=${med}/${idle}ms(${tiles:-?})"
    [ "$med" != "-" ] && sum=$((sum + med))
    [ "$idle" != "-" ] && sum_idle=$((sum_idle + idle))
done
echo "scenario=$NAME runs=$RUNS total_median_ms=$sum total_idle_median_ms=$sum_idle steps(ms/idle_ms)=$summary"
