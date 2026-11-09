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

echo "OK: ToolMap started, in front, no crash, no ANR (pid $pid)." | tee "$out/result.txt"
