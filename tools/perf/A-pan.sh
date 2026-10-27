#!/usr/bin/env bash
# Scenario A — moving the map (docs/specs/performance.md §7): a fixed series of swipes for 1 minute, then the slow
# frames of the app's UI (gfxinfo: frames, janky %, 90th and 95th percentile). The series comes back to its start
# every 4 swipes, so the tiles come from the network only in the first round.
set -u
. "$(dirname "$0")/lib.sh"

DURATION="${DURATION:-60}"
start_watch "$(basename "$0" .sh)"
launch_fixed >/dev/null
wait_line "rendered step=0 " 1 60 >/dev/null || { log "carte non dessinée au lancement"; exit 1; }
sleep 1
gfx_reset
swipes=0
end=$((SECONDS + DURATION))
while [ "$SECONDS" -lt "$end" ]; do
    for move in "300 1500 800 1500" "540 900 540 1600" "800 1500 300 1500" "540 1600 540 900"; do
        # shellcheck disable=SC2086
        adb shell input swipe $move 250
        swipes=$((swipes + 1))
        sleep 0.6
    done
done
gfx=$(gfx_summary)
stop_watch
read -r frames janky p90 p95 <<<"$gfx"
echo "scenario=A seconds=$DURATION swipes=$swipes frames=$frames janky=$janky p90=$p90 p95=$p95"
