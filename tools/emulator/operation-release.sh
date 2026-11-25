#!/usr/bin/env bash
# The « Opération » mode is in the APK (P39.19, ADR-146), checked without the testing tools, so on a release too:
#
#   bash tools/emulator/operation-release.sh [output folder]
#
# ToolMap installed and started. Checks that an invitation link `toolmap://operation?d=…` is taken by ToolMap, that
# the service of an operation is declared, and that the screen « Partage de position », out of a group, shows the card
# « Opération » and its button « Créer une opération ». The screen is opened by a pairing link that carries no valid
# code (it opens the screen of the sharing, joins nothing, and its window « Code refusé » is closed by Back). Leaves
# operation-release.png and its window dump in the output folder, and the texts of the screen in the log. Fails on any
# of these, or when the app crashes (Java or native), freezes (ANR) or leaves the front. Called by smoke.sh for every
# APK.
set -euo pipefail

out="${1:-emulator-check}"
pkg="fr.toolmap"
mkdir -p "$out"
export MSYS_NO_PATHCONV=1

fail() {
  echo "::error::$1"
  adb logcat -d > "$out/logcat-operation-release.txt" 2>/dev/null || true
  adb exec-out screencap -p > "$out/operation-release-failed.png" 2>/dev/null || true
  exit 1
}

resolved="$(adb shell pm resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE \
  -d 'toolmap://operation?d=0' | tr -d '\r' | tail -n 1)"
echo "toolmap://operation is taken by: $resolved"
[[ "$resolved" == "$pkg/"*MainActivity ]] || fail "the link toolmap://operation is not taken by ToolMap ($resolved)"

# A service without an intent filter is not in `dumpsys package`: asked by its name instead.
adb shell pm query-services -n "$pkg/.operation.OperationService" | tr -d '\r' |
  grep "name=$pkg.operation.OperationService" > /dev/null || fail "the service of an operation is not declared in the APK"
echo "The service of an operation is declared."

adb logcat -c || true
adb shell am start -W -a android.intent.action.VIEW -d 'toolmap://pair?d=0' "$pkg" > /dev/null ||
  fail "the screen of the sharing does not open"
found=""
for _ in $(seq 1 20); do
  sleep 1
  adb shell uiautomator dump /sdcard/toolmap-opr.xml > /dev/null 2>&1 || continue
  adb pull /sdcard/toolmap-opr.xml "$out/operation-release.xml" > /dev/null 2>&1 || continue
  if grep -q 'text="Créer une opération"' "$out/operation-release.xml" &&
    grep -q 'text="Opération"' "$out/operation-release.xml"; then
    found="yes"
    break
  fi
  # The pairing link carries no valid code: its window « Code refusé » goes away with Back, over the screen.
  if grep -q 'text="Code refusé"' "$out/operation-release.xml"; then adb shell input keyevent KEYCODE_BACK; fi
done
adb exec-out screencap -p > "$out/operation-release.png" || true
echo "Texts on the screen of the sharing:"
grep -oE '(text|content-desc)="[^"]+"' "$out/operation-release.xml" 2>/dev/null | sed -E 's/^[a-z-]+="//; s/"$//' |
  awk '!seen[$0]++' | head -n 40 | sed 's/^/  /' || true
[ -n "$found" ] || fail "the card « Opération » is not on the screen of the sharing"

adb logcat -d > "$out/logcat-operation-release.txt" || true
if grep -A1 'FATAL EXCEPTION' "$out/logcat-operation-release.txt" | grep "Process: $pkg" > /dev/null; then
  fail "Java crash of $pkg while the screen of the sharing opened"
fi
if grep -q ">>> $pkg" "$out/logcat-operation-release.txt"; then fail "native crash of $pkg"; fi
if grep -q "ANR in $pkg" "$out/logcat-operation-release.txt"; then fail "ANR of $pkg"; fi
front="$(adb shell dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' || true)"
[[ "$front" == *"$pkg/"* ]] || fail "ToolMap is not in front: $front"
echo "The « Opération » mode is in this APK: link, service and card."
adb shell input keyevent KEYCODE_BACK
