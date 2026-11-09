#!/usr/bin/env bash
# Scenario E — the native engine against the Kotlin code (ADR-119, docs/specs/native-engine.md § 4). Usage: E-engine.sh
# Asks the app, started here, to time each computation of a drawn tile (blend modes, tint, colour matrix, elevation
# mask, path simplification), the PNG of a tile, then whole tiles, with the Kotlin code and with the native engine, on
# tiles drawn by the app itself (no network, no stored map). Prints the lines of the measure.
# Works on the APK handed out by the CI (debug build type, not debuggable since P36.7) and on a benchmark APK: the
# measure is asked for by `dumpsys activity`, which only adb and the system may use. A release does not listen.
set -u
. "$(dirname "$0")/lib.sh"

ENGINE_TAG=ToolMapEngine
TIMEOUT="${TIMEOUT:-240}"

adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY" >/dev/null
# The engine is loaded and checked off the main thread just after the start.
sleep 5
adb logcat -c
if ! adb shell dumpsys activity "$ACTIVITY" engine-bench | tr -d '\r' | grep 'engine-bench started' >/dev/null; then
    log "this APK does not offer the measure (a release, or the app is not in front)"
    exit 1
fi

end=$(($(date +%s) + TIMEOUT))
while [ "$(date +%s)" -lt "$end" ]; do
    if adb logcat -d -s "$ENGINE_TAG:I" | grep -E 'bench (done|skipped)' >/dev/null; then break; fi
    sleep 2
done
out="$OUT_DIR/E-engine-$(date +%Y%m%d-%H%M%S).log"
adb logcat -d -s "$ENGINE_TAG:I" | tr -d '\r' | grep -oE "$ENGINE_TAG *: .*" | sed -E "s/^$ENGINE_TAG *: //" > "$out"
cat "$out"
grep 'bench done' "$out" >/dev/null || { log "the measure did not finish in ${TIMEOUT}s (see $out)"; exit 1; }
log "kept in $out"
