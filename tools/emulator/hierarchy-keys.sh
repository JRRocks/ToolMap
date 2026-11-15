#!/usr/bin/env bash
# Check of the Keystore keys of the « Opération » mode (P39.6, ADR-128, docs/specs/group-hierarchy.md § 9) on an
# emulator or a phone, ToolMap installed (an APK whose build type has the testing tools: debug) and started:
#
#   bash tools/emulator/hierarchy-keys.sh [output folder]
#
# Asks the running app twice (`dumpsys activity`, only adb and the system may): phase « create » makes the keys in the
# Keystore (agreement key in the Keystore from API 31, in a file sealed by the Keystore on every version), times a
# signature, a verification and an agreement, and stores a small operation; the process is then killed and the app
# started again; phase « reload » reads it all back in the new process, compares, times again, and wipes it. Fails when
# a phase fails or the app crashes; an APK that does not answer (a release) is skipped. Lines in hierarchy-keys.txt.
# Called by smoke.sh after its own check; run alone on the workstation: emulator started, APK installed and launched.
set -euo pipefail

out="${1:-emulator-check}"
pkg="fr.toolmap"
tag="ToolMapHierarchy"
mkdir -p "$out"
: > "$out/hierarchy-keys.txt"

fail() {
  echo "::error::$1"
  echo "FAILED: $1" > "$out/hierarchy-keys-result.txt"
  adb logcat -d > "$out/logcat-hierarchy.txt" 2>/dev/null || true
  # In the job's log too: a cloud session cannot open the artifact of the run.
  echo "Last lines of logcat about the app and its processes:"
  grep -E "AndroidRuntime|FATAL|am_proc|ActivityManager|$pkg" "$out/logcat-hierarchy.txt" 2>/dev/null | tail -n 30 || true
  exit 1
}

# Asks for one phase; sets ran to the phase the app ran (« create » or « reload »), empty when it does not answer.
# Not called in a $(…): a failure there would only end the subshell.
ran=""
ask() {
  local name="$1"
  ran=""
  adb logcat -c || true
  adb shell dumpsys activity "$pkg/.MainActivity" hierarchy-keys > "$out/hierarchy-keys-ask-$name.txt" 2>&1 || true
  grep 'hierarchy-keys started' "$out/hierarchy-keys-ask-$name.txt" > /dev/null || return 0
  for _ in $(seq 1 120); do
    if adb logcat -d -s "$tag:I" | grep 'hierarchy-keys done' > /dev/null; then break; fi
    sleep 1
  done
  adb logcat -d > "$out/logcat-hierarchy-$name.txt" || true
  if grep -A1 'FATAL EXCEPTION' "$out/logcat-hierarchy-$name.txt" | grep "Process: $pkg" > /dev/null; then
    fail "Java crash of $pkg during the check of the keys (see logcat-hierarchy-$name.txt)"
  fi
  if grep ">>> $pkg" "$out/logcat-hierarchy-$name.txt" > /dev/null; then
    fail "native crash of $pkg during the check of the keys (see logcat-hierarchy-$name.txt)"
  fi
  grep -oE "$tag *: hierarchy-keys .*" "$out/logcat-hierarchy-$name.txt" | sed -E "s/^$tag *: hierarchy-keys //" \
    >> "$out/hierarchy-keys.txt" || true
  grep 'hierarchy-keys done' "$out/logcat-hierarchy-$name.txt" > /dev/null ||
    fail "the check of the keys did not finish in time (phase $name)"
  ran="$(grep -oE 'hierarchy-keys phase=[a-z]+' "$out/logcat-hierarchy-$name.txt" | head -n 1 | sed 's/^.*phase=//' || true)"
}

# Stops the app and starts it again, then waits for its new process to settle. The process stopped can still be listed
# a moment after `am force-stop`, and the system starts the new one only once it is gone: one look right after the
# launch could see the old one, then nothing at all (run « CI » n° 209). So: wait for the old one to go, launch, then
# wait for a new process seen twice, a second apart.
restart() {
  local old new seen
  old="$(adb shell pidof "$pkg" | tr -d '\r' || true)"
  adb shell am force-stop "$pkg"
  for _ in $(seq 1 30); do
    [ -z "$(adb shell pidof "$pkg" | tr -d '\r' || true)" ] && break
    sleep 1
  done
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null || fail "the app does not start again"
  new=""
  for _ in $(seq 1 30); do
    seen="$(adb shell pidof "$pkg" | tr -d '\r' || true)"
    if [ -n "$seen" ] && [ "$seen" != "$old" ] && [ "$seen" = "$new" ]; then break; fi
    new="$seen"
    sleep 1
  done
  [ -n "$new" ] && [ "$new" != "$old" ] && [ "$(adb shell pidof "$pkg" | tr -d '\r' || true)" = "$new" ] ||
    fail "ToolMap is not running after the restart"
  sleep 8
}

passed() {
  grep "PASS phase=$1" "$out/hierarchy-keys.txt" > /dev/null
}

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
ask first
if [ -z "$ran" ]; then
  echo "The check of the keys of the « Opération » mode is not offered by this APK (no answer to dumpsys)."
  exit 0
fi
if [ "$ran" = "reload" ]; then
  # A run stopped halfway left its operation: that phase cleaned it up, the next one starts afresh.
  : > "$out/hierarchy-keys.txt"
  ask again
fi
[ "$ran" = "create" ] || fail "the check of the keys did not start with its first phase"
passed create || fail "phase create of the check of the keys failed: $(grep FAIL "$out/hierarchy-keys.txt" || true)"
restart
ask reload
[ "$ran" = "reload" ] || fail "the new process did not read back what the first one stored"
passed reload || fail "phase reload of the check of the keys failed: $(grep FAIL "$out/hierarchy-keys.txt" || true)"

echo "Keys of the « Opération » mode on this emulator (API $api): made in the Keystore, read back after a restart, wiped:"
sed 's/^/  /' "$out/hierarchy-keys.txt"
echo "OK: keys of the « Opération » mode (API $api)" | tee "$out/hierarchy-keys-result.txt"
