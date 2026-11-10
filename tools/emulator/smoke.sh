#!/usr/bin/env bash
# Check of a ToolMap APK on an Android emulator before any publication (CLAUDE.md « Aucune version publiée sans
# passage sur un émulateur », ADR-117). Used by the CI (cloud sessions have no emulator of their own) and on the
# Windows workstation (Git Bash, emulator « Medium_Phone » started):
#
#   bash tools/emulator/smoke.sh <APK file or folder> [output folder]
#
# Installs the APK (permissions granted), starts it from the launcher, waits for the map screen, and fails when the app
# crashed (Java or native), froze (ANR), shows its crash report screen, or is not in front. Leaves in the output folder
# a screenshot, the window dump and the log of the app, for the person who checks.
#
# An APK that carries the native compute engine (Rust, ADR-119) must also have it in charge, checked against the Kotlin
# code by its self-test; the app is then asked to time the engine against the Kotlin code on this emulator
# (`dumpsys activity`, docs/specs/native-engine.md § 4), and a crash during that measure fails the check too.
# The APK is not debuggable since P36.7 (ADR-120): nothing here needs `run-as`.
set -euo pipefail

apk="${1:?APK file or folder}"
out="${2:-emulator-check}"
pkg="fr.toolmap"
mkdir -p "$out"

if [ -d "$apk" ]; then
  apk="$(ls "$apk"/*.apk | head -n 1)"
fi
echo "APK: $apk"

fail() {
  echo "::error::$1"
  echo "FAILED: $1" > "$out/result.txt"
  adb logcat -d > "$out/logcat.txt" 2>/dev/null || true
  adb exec-out screencap -p > "$out/screen.png" 2>/dev/null || true
  exit 1
}

adb wait-for-device
for _ in $(seq 1 120); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || fail "the emulator did not finish booting"
adb shell getprop ro.build.version.release | tr -d '\r' | sed 's/^/Android /'

adb install -r -g "$apk" || fail "the APK does not install"
adb logcat -c || true
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null || fail "the app does not start"

# The map screen: MainActivity in front, then time for the map and its first tiles.
front=""
for _ in $(seq 1 60); do
  front="$(adb shell dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' || true)"
  [[ "$front" == *"$pkg/"* ]] && break
  sleep 1
done
sleep 20
front="$(adb shell dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' || true)"
echo "$front"

adb exec-out screencap -p > "$out/screen.png" || true
adb shell uiautomator dump /sdcard/toolmap-ui.xml > /dev/null 2>&1 && adb pull /sdcard/toolmap-ui.xml "$out/ui.xml" > /dev/null 2>&1 || true
adb logcat -d > "$out/logcat.txt" || true

# What the screen shows, in words: the job log can be read where the artifact cannot (a cloud session's network does
# not reach GitHub's artifact storage). Under pipefail, `|| true`: head closing the pipe early is not a failure.
if [ -s "$out/ui.xml" ]; then
  echo "Texts on the screen:"
  grep -oE '(text|content-desc)="[^"]+"' "$out/ui.xml" | sed -E 's/^[a-z-]+="//; s/"$//' | awk '!seen[$0]++' |
    head -n 80 | sed 's/^/  /' || true
fi
echo "Screenshot: $(wc -c < "$out/screen.png" 2>/dev/null || echo 0) bytes"

# No `grep -q` at the end of a pipe: under pipefail, the first grep killed by SIGPIPE would hide a match.
if grep -A1 'FATAL EXCEPTION' "$out/logcat.txt" | grep "Process: $pkg" > /dev/null; then fail "Java crash of $pkg (see logcat.txt)"; fi
if grep -q ">>> $pkg" "$out/logcat.txt"; then fail "native crash of $pkg (see logcat.txt)"; fi
if grep -q "ANR in $pkg" "$out/logcat.txt"; then fail "ANR of $pkg (see logcat.txt)"; fi
if [[ "$front" == *CrashActivity* ]]; then fail "the crash report screen is in front"; fi
if [[ "$front" != *"$pkg/"* ]]; then fail "ToolMap is not in front: $front"; fi
pid="$(adb shell pidof "$pkg" | tr -d '\r' || true)"
[ -n "$pid" ] || fail "ToolMap is not running"

# The native engine: in charge wherever the APK carries it, a release included since P38.6 (BuildConfig.NATIVE_ENGINE).
engine_note=""
if unzip -l "$apk" 2>/dev/null | grep 'libtoolmap_engine.so' > /dev/null; then
  engine="$(grep -E 'ToolMapEngine *: engine ' "$out/logcat.txt" | tail -n 1 | sed -E 's/^.*ToolMapEngine *: //' || true)"
  echo "Native engine: ${engine:-no line in the log}"
  [[ "$engine" == *"native=1 state=active"* ]] ||
    fail "the APK carries the native engine but it is not in charge: ${engine:-no line in the log}"
  engine_note=", native engine in charge"
else
  echo "Native engine: not in this APK (built without Rust), the Kotlin code does all the work."
fi

echo "OK: ToolMap started, in front, no crash, no ANR (pid $pid)$engine_note." | tee "$out/result.txt"

# The engine against the Kotlin code on this emulator, asked of the running app by `dumpsys` (only adb and the system
# may make an activity dump; a release does not listen). A measure, not a check: only a crash while it runs fails.
if [[ "$engine_note" == *"in charge"* ]]; then
  adb logcat -c || true
  adb shell dumpsys activity "$pkg/.MainActivity" engine-bench > "$out/engine-bench-ask.txt" 2>&1 || true
  if grep 'engine-bench started' "$out/engine-bench-ask.txt" > /dev/null; then
    for _ in $(seq 1 120); do
      if adb logcat -d -s ToolMapEngine:I | grep -E 'bench (done|skipped)' > /dev/null; then break; fi
      sleep 2
    done
    adb logcat -d > "$out/logcat-bench.txt" || true
    echo "Native engine against the Kotlin code on this emulator (median times of one 256 px tile):"
    grep -oE 'ToolMapEngine *: bench .*' "$out/logcat-bench.txt" | sed -E 's/^ToolMapEngine *: bench /  /' |
      tee "$out/engine-bench.txt" || true
    if grep -A1 'FATAL EXCEPTION' "$out/logcat-bench.txt" | grep "Process: $pkg" > /dev/null; then
      fail "Java crash of $pkg during the engine measure (see logcat-bench.txt)"
    fi
    if grep ">>> $pkg" "$out/logcat-bench.txt" > /dev/null; then
      fail "native crash of $pkg during the engine measure (see logcat-bench.txt)"
    fi
    grep 'bench done' "$out/logcat-bench.txt" > /dev/null || echo "::warning::the engine measure did not finish in time"
  else
    echo "The engine measure is not offered by this APK (no answer to dumpsys)."
  fi
fi
