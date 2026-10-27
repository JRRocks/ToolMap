#!/usr/bin/env bash
# Opens an invitation link of the position sharing on the emulator (toolmap://join?d=...).
set -euo pipefail
. "$(dirname "$0")/lib.sh"
LINK="${1:?lien toolmap://join?d=...}"
adb shell am start -a android.intent.action.VIEW -d "\"$LINK\"" "$PKG" >/dev/null
log "Lien ouvert ; écran affiché :"
adb shell uiautomator dump /sdcard/livetest-ui.xml >/dev/null 2>&1 || true
adb shell cat /sdcard/livetest-ui.xml 2>/dev/null | tr '>' '\n' | sed -n 's/.*text="\([^"]\+\)".*/  \1/p' | head -30
