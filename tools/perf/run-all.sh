#!/usr/bin/env bash
# All the measure scenarios of docs/specs/performance.md §7, in order: 0, R, A, T2, T1, Z.
# Usage: run-all.sh [apk]  — with an APK, installs it first (adb install -r -g).
# The summary lines go to the screen and to OUT_DIR/summary-<date>.txt; the raw timing lines stay in OUT_DIR.
set -u
. "$(dirname "$0")/lib.sh"

if [ $# -ge 1 ]; then
    log "installation de $1"
    adb install -r -g "$1" >/dev/null || exit 1
fi
SUMMARY="$OUT_DIR/summary-$(date +%Y%m%d-%H%M%S).txt"
{
    echo "# $(date '+%Y-%m-%d %H:%M') — $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
    adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 -o 'versionName=.*'
    adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 'pkgFlags=' | sed 's/^ *//'
} | tee "$SUMMARY"
dir="$(dirname "$0")"
for scenario in "0-startup.sh" "R-return.sh" "A-pan.sh" "T-tiles.sh online" "T-tiles.sh stored" "Z-zone.sh"; do
    log "scénario $scenario"
    # shellcheck disable=SC2086
    bash "$dir"/$scenario | tee -a "$SUMMARY"
done
# Memory at the end (§7): the map open on the fixed view.
launch_fixed >/dev/null
sleep 5
pss=$(adb shell dumpsys meminfo "$PKG" | tr -d '\r' | awk '/TOTAL PSS:/ { print $3; exit } /^ *TOTAL / { print $2; exit }')
echo "memory total_pss_kb=$pss" | tee -a "$SUMMARY"
log "résumé : $SUMMARY"
