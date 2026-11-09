#!/usr/bin/env bash
# Scenario E — the native engine against the Kotlin code (ADR-119, docs/specs/native-engine.md § 4). Usage: E-engine.sh
# Starts the app with the measure asked for and prints its lines: each computation of a drawn tile (blend modes, tint,
# colour matrix, elevation mask, path simplification), the PNG of a tile, then whole tiles, each timed with the Kotlin
# code and with the native engine on tiles drawn by the app itself (no network, no stored map).
# Works on a benchmark APK (`perf.engine=bench`, the figures to quote) and on a debug APK (a marker file left by adb;
# Kotlin is slower there than in an APK handed out without the debugger's support).
set -u
. "$(dirname "$0")/lib.sh"

ENGINE_TAG=ToolMapEngine
TIMEOUT="${TIMEOUT:-240}"

adb logcat -c
adb shell am force-stop "$PKG"
if adb shell run-as "$PKG" true >/dev/null 2>&1; then
    log "debug APK: measure asked for by a marker file"
    adb shell "run-as $PKG sh -c 'mkdir -p files && touch files/engine-bench'"
    adb shell am start -n "$ACTIVITY" >/dev/null
else
    log "benchmark APK: measure asked for by perf.engine=bench"
    adb shell am start -n "$ACTIVITY" --es perf.engine bench >/dev/null
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
