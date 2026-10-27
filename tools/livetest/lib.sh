# Shared helpers of the live-test scripts (docs/specs/live-test.md). Sourced, not run.
# Works from Git Bash on Windows (the user's machine) and from any POSIX shell with adb.

# Git Bash rewrites arguments that look like paths: adb must get them as they are.
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
RECEIVER=fr.toolmap/fr.toolmap.debugkit.DebugCommands
LIVE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$LIVE_DIR/out}"
mkdir -p "$OUT_DIR"

log() { echo "[$(date +%H:%M:%S)] $*" >&2; }

# Sends a command to the test tools of the app; prints their status line (the `data=` of the broadcast).
debug_cmd() {
    local action="$1"
    shift
    adb shell am broadcast -n "$RECEIVER" -a "fr.toolmap.debug.$action" "$@" | tr -d '\r' |
        sed -n 's/.*data="\(.*\)"$/\1/p'
}
