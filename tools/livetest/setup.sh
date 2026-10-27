#!/usr/bin/env bash
# Prepares the emulator for a live test of the position sharing (docs/specs/live-test.md §2):
# installs the debug APK, makes the emulator the collector, puts it at a fixed place and opens the app.
#   bash tools/livetest/setup.sh app/build/outputs/apk/debug/ToolMap-debug-<version>.apk ["toolmap://join?d=..."]
# The invitation link (sent by the phone that created the group) is optional: it can also be opened later with
#   bash tools/livetest/join.sh "toolmap://join?d=..."
set -euo pipefail
. "$(dirname "$0")/lib.sh"

APK="${1:?chemin de l APK debug}"
LINK="${2:-}"
# The emulator's place: Chambéry, like the perf scenarios (LAT / LON to change it).
LAT="${LAT:-45.5646}"
LON="${LON:-5.9178}"

log "Installation de $APK"
adb install -r -g "$APK" >/dev/null
# The sharing service notifies: allowed without the dialog.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
adb shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true

log "Position de l'émulateur : $LAT, $LON"
adb emu geo fix "$LON" "$LAT" >/dev/null

log "Démarrage de l application"
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 3
log "Rôle collecteur"
debug_cmd COLLECT --ez on true

if [ -n "$LINK" ]; then
    bash "$(dirname "$0")/join.sh" "$LINK"
fi
log "Prêt. Suivi en direct : node tools/livetest/live.mjs"
