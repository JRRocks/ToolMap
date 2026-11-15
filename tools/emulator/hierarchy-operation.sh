#!/usr/bin/env bash
# Check of the « Opération » controller (P39.7, ADR-129, docs/specs/group-hierarchy.md § 17; P39.9, ADR-131, § 20) on
# one emulator or phone, without a server and without a screen, ToolMap installed (an APK whose build type has the
# testing tools: debug) and started:
#
#   bash tools/emulator/hierarchy-operation.sh [output folder]
#
# Asks the running app three times (`dumpsys activity`), the process killed and the app started again in between:
# phase « create » creates an operation over the real Keystore (a group, this phone its leader and leader of the
# operation, a waypoint, messages in two threads) and stores it; phase « reload » opens it again from the storage in
# the new process, compares (messages included), dissolves it and checks that only the trace of the dissolution is
# left (no folder and no key of the operation; one trace, which names nothing and holds no message); phase « trace »
# reads that trace back in another process, then wipes it and checks that nothing is left. Fails when a phase fails
# or the app crashes; an APK that does not answer (a release) is skipped. Lines in hierarchy-operation.txt. Called by
# smoke.sh after hierarchy-keys.sh.
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
  # In the job's log too: a cloud session cannot open the artifact of the run.
  echo "Last lines of logcat about the app and its processes:"
  grep -E "AndroidRuntime|FATAL|am_proc|ActivityManager|$pkg" "$out/logcat-hierarchy.txt" 2>/dev/null | tail -n 30 || true
  exit 1
}

# Asks for one phase; sets ran to the phase the app ran (« create », « reload » or « trace »), empty when it does not
# answer.
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
  grep "PASS phase=$1" "$out/hierarchy-operation.txt" > /dev/null
}

api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
ask first
if [ -z "$ran" ]; then
  echo "The check of the « Opération » controller is not offered by this APK (no answer to dumpsys)."
  exit 0
fi
for again in 1 2; do
  [ "$ran" = "create" ] && break
  # A run stopped halfway left its operation or its trace: that phase cleaned it up, the next one goes on afresh.
  : > "$out/hierarchy-operation.txt"
  ask "again-$again"
done
[ "$ran" = "create" ] || fail "the check of the « Opération » controller did not start with its first phase"
passed create || fail "phase create of the check of the « Opération » controller failed: $(grep FAIL "$out/hierarchy-operation.txt" || true)"
restart
ask reload
[ "$ran" = "reload" ] || fail "the new process did not read back what the first one stored"
passed reload || fail "phase reload of the check of the « Opération » controller failed: $(grep FAIL "$out/hierarchy-operation.txt" || true)"
restart
ask trace
[ "$ran" = "trace" ] || fail "the new process did not find the trace of the dissolution"
passed trace || fail "phase trace of the check of the « Opération » controller failed: $(grep FAIL "$out/hierarchy-operation.txt" || true)"

echo "« Opération » controller on this emulator (API $api): created, read back after a restart, dissolved, its trace read back then wiped:"
sed 's/^/  /' "$out/hierarchy-operation.txt"
echo "OK: « Opération » controller (API $api)" | tee "$out/hierarchy-operation-result.txt"
