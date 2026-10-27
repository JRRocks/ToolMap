# Shared helpers of the measure scripts (docs/specs/performance.md §7). Sourced, not run.
# Works from Git Bash on Windows (the user's machine) and from any POSIX shell with adb.

# Git Bash rewrites arguments that look like paths (/sdcard/...) into Windows paths: adb must get them as they are.
export MSYS_NO_PATHCONV=1

if ! command -v adb >/dev/null 2>&1; then
    for sdk in "${ANDROID_HOME:-}" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" "$HOME/android-sdk"; do
        if [ -n "$sdk" ] && { [ -x "$sdk/platform-tools/adb" ] || [ -x "$sdk/platform-tools/adb.exe" ]; }; then
            export PATH="$PATH:$sdk/platform-tools"
            break
        fi
    done
fi
command -v adb >/dev/null 2>&1 || { echo "adb introuvable (ANDROID_HOME ?)" >&2; exit 1; }

PKG=fr.toolmap
ACTIVITY=fr.toolmap/.MainActivity
TAG=ToolMapPerf
PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$PERF_DIR/out}"
mkdir -p "$OUT_DIR"

# The fixed view of the scenarios: Chambéry, map « IGN Cartes », camera zoom 14 (tile zoom 16 with the default
# sharpness of ADR-032).
SOURCE="${SOURCE:-ign-maps}"
LAT=45.5646
LON=5.9178
ZOOM=14
# The 5 km x 5 km square around Chambéry of scenario Z (west,south,east,north), tile zooms 12 to 16.
ZONE="5.8857,45.5421,5.9499,45.5871"
ZONE_ZMIN=12
ZONE_ZMAX=16

log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

# Device clock in milliseconds (the clock of `logcat -v epoch`).
device_ms() { adb shell 'echo $EPOCHREALTIME' | tr -d '\r' | awk '{ printf "%d\n", $1 * 1000 }'; }

# Stops the app, then starts it cold on the fixed view; prints the TotalTime of `am start -W` (ms).
# Extra arguments are passed to `am start` (e.g. the zone of scenario Z).
launch_fixed() {
    adb shell am force-stop "$PKG"
    adb shell am start -W -n "$ACTIVITY" \
        --es perf.source "$SOURCE" --es perf.lat "$LAT" --es perf.lon "$LON" --es perf.zoom "$ZOOM" "$@" |
        tr -d '\r' | awk -F': ' '/^TotalTime/ { print $2 }'
}

# Empties everything the app stored (maps included) and gives back the permissions `adb install -g` granted.
clear_app() {
    adb shell am force-stop "$PKG"
    adb shell pm clear "$PKG" >/dev/null
    adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
}

# Follows the timing lines in a file while a scenario runs: start_watch <name>, then wait_line, then stop_watch.
# The file stays in OUT_DIR (<name>-<date>.log) as the raw record of the run.
WATCH_FILE=""
WATCH_PID=""
start_watch() {
    WATCH_FILE="$OUT_DIR/${1:-watch}-$(date +%Y%m%d-%H%M%S).log"
    : >"$WATCH_FILE"
    adb logcat -c
    adb logcat -v epoch -s "$TAG:I" >"$WATCH_FILE" 2>/dev/null &
    WATCH_PID=$!
    trap stop_watch EXIT
}
stop_watch() {
    [ -n "$WATCH_PID" ] && kill "$WATCH_PID" 2>/dev/null
    WATCH_PID=""
}

# Number of timing lines matching a pattern so far.
count_lines() { grep -c -- "$1" "$WATCH_FILE" 2>/dev/null || true; }

# Waits until the n-th line matching a pattern exists (timeout in seconds, default 30); prints that line, or
# returns 1 on timeout.
wait_line() {
    local pattern="$1" n="$2" timeout="${3:-30}" waited=0
    while [ "$(count_lines "$pattern")" -lt "$n" ]; do
        sleep 0.2
        waited=$((waited + 1))
        [ "$waited" -ge $((timeout * 5)) ] && return 1
    done
    grep -- "$pattern" "$WATCH_FILE" | sed -n "${n}p"
}

# Value of key=value in a timing line.
field() { echo "$1" | tr ' ' '\n' | awk -F= -v k="$2" '$1 == k { print $2 }'; }

# Logcat epoch timestamp of a timing line, in ms.
line_ms() { echo "$1" | awk '{ printf "%d\n", $1 * 1000 }'; }

# Centre of the first on-screen element whose text or description contains the given text ("x y").
find_element() {
    adb shell uiautomator dump /sdcard/toolmap-perf.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/toolmap-perf.xml | tr '>' '\n' | grep -F -- "$1" | head -1 |
        sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
        awk 'NF == 4 { print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}

tap_element() {
    local xy
    xy=$(find_element "$1")
    [ -n "$xy" ] || { log "élément introuvable : $1"; return 1; }
    # shellcheck disable=SC2086
    adb shell input tap $xy
}

# Median of the numbers on stdin (one per line); "-" when there are none.
median() {
    sort -n | awk '{ v[NR] = $1 } END { if (NR == 0) print "-"; else if (NR % 2) print v[(NR + 1) / 2]; else print int((v[NR / 2] + v[NR / 2 + 1]) / 2) }'
}

# Frames drawn by the app's UI since the last reset: "frames janky% p90 p95".
gfx_reset() { adb shell dumpsys gfxinfo "$PKG" reset >/dev/null; }
gfx_summary() {
    adb shell dumpsys gfxinfo "$PKG" | tr -d '\r' | awk '
        /^Total frames rendered:/ && !f { f = $4 }
        /^Janky frames:/ && !j { j = $4; gsub(/[()]/, "", j) }
        /^90th percentile:/ && !p90 { p90 = $3 }
        /^95th percentile:/ && !p95 { p95 = $3 }
        END { print f, j, p90, p95 }'
}
