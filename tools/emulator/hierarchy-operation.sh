#!/usr/bin/env bash
# Check of the « Opération » controller (P39.7, ADR-129, docs/specs/group-hierarchy.md § 17) on one emulator or phone,
# without a server and without a screen, ToolMap installed (an APK whose build type has the testing tools: debug) and
# started:
#
#   bash tools/emulator/hierarchy-operation.sh [output folder]
#
# Asks the running app twice (`dumpsys activity`): phase « create » creates an operation over the real Keystore (a
# group, this phone its leader and leader of the operation, a waypoint) and stores it; the process is then killed and
# the app started again; phase « reload » opens it again from the storage in the new process, compares, dissolves it
# and checks that no folder and no key is left. Fails when a phase fails or the app crashes; an APK that does not
# answer (a release) is skipped. Lines in hierarchy-operation.txt. Called by smoke.sh after hierarchy-keys.sh.
set -euo pipefail

out="${1:-emulator-check}"
pkg="fr.toolmap"
tag="ToolMapHierarchy"
mkdir -p "$out"
: > "$out/hierarchy-operation.txt"

fail() {
  echo "::error::$1"
  echo "FAILED: $1" > "$out/hierarchy-operation-result.txt"
  adb logcat -d > "$out/logcat-hierarchy.txt" 2>/dev/null || true
  exit 1
}

# Asks for one phase; sets ran to the phase the app ran (« create » or « reload »), empty when it does not answer.
# Not called in a $(…): a failure there would only end the subshell.
ran=""
ask() {
  local name="$1"
  ran=""
  adb logcat -c || true
  adb shell dumpsys activity "$pkg/.MainActivity" hierarchy-operation > "$out/hierarchy-operation-ask-$name.txt" 2>&1 || true
  grep 'hierarchy-operation started' "$out/hierarchy-operation-ask-$name.txt" > /dev/null || return 0
  for _ in $(seq 1 120); do
    if adb logcat -d -s "$tag:I" | grep 'hierarchy-operation done' > /dev/null; then break; fi
    sleep 1
  done
  adb logcat -d > "$out/logcat-operation-$name.txt" || true
  if grep -A1 'FATAL EXCEPTION' "$out/logcat-operation-$name.txt" | grep "Process: $pkg" > /dev/null; then
    fail "Java crash of $pkg during the check of the « Opération » controller (see logcat-operation-$name.txt)"
  fi
  if grep ">>> $pkg" "$out/logcat-operation-$name.txt" > /dev/null; then
    fail "native crash of $pkg during the check of the « Opération » controller (see logcat-operation-$name.txt)"
  fi
  grep -oE "$tag *: hierarchy-operation .*" "$out/logcat-operation-$name.txt" | sed -E "s/^$tag *: hierarchy-operation //" \
    >> "$out/hierarchy-operation.txt" || true
  grep 'hierarchy-operation done' "$out/logcat-operation-$name.txt" > /dev/null ||
    fail "the check of the « Opération » controller did not finish in time (phase $name)"
  ran="$(grep -oE 'hierarchy-operation phase=[a-z]+' "$out/logcat-operation-$name.txt" | head -n 1 | sed 's/^.*phase=//' || true)"
}

restart() {
  adb shell am force-stop "$pkg"
  adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null || fail "the app does not start again"
  for _ in $(seq 1 30); do
    [ -n "$(adb shell pidof "$pkg" | tr -d '\r' || true)" ] && break
    sleep 1
  done
  [ -n "$(adb shell pidof "$pkg" | tr -d '\r' || true)" ] || fail "ToolMap is not running after the restart"
  sleep 8
}

passed() {
  grep "PASS phase=$1" "$out/hierarchy-operation.txt" > /dev/null
}

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
ask first
if [ -z "$ran" ]; then
  echo "The check of the « Opération » controller is not offered by this APK (no answer to dumpsys)."
  exit 0
fi
if [ "$ran" = "reload" ]; then
  # A run stopped halfway left its operation: that phase cleaned it up, the next one starts afresh.
  : > "$out/hierarchy-operation.txt"
  ask again
fi
[ "$ran" = "create" ] || fail "the check of the « Opération » controller did not start with its first phase"
passed create || fail "phase create of the check of the « Opération » controller failed: $(grep FAIL "$out/hierarchy-operation.txt" || true)"
restart
ask reload
[ "$ran" = "reload" ] || fail "the new process did not read back what the first one stored"
passed reload || fail "phase reload of the check of the « Opération » controller failed: $(grep FAIL "$out/hierarchy-operation.txt" || true)"

echo "« Opération » controller on this emulator (API $api): created, read back after a restart, dissolved:"
sed 's/^/  /' "$out/hierarchy-operation.txt"
echo "OK: « Opération » controller (API $api)" | tee "$out/hierarchy-operation-result.txt"
