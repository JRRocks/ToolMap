#!/usr/bin/env bash
# Captures of the screens of the « Opération » mode (P39.8, ADR-130, docs/specs/group-hierarchy.md § 19), on one
# emulator or phone, ToolMap installed (an APK whose build type has this mode: debug) and started:
#
#   bash tools/emulator/operation-screens.sh [output folder]
#
# Asks the running app (`dumpsys activity … operation-screens`, testing tools only: debug) to build its demonstration
# (« Exercice Vallon », 22 phones simulated in the app, in a room in memory: nothing goes to the server), then shows
# each scene in turn and takes it: one PNG per scene in <output folder>/operation/, named after the maquette it is to be
# compared with (tour4-10-creation-A.png ↔ docs/previews/p39-groupes/tour4/img/10-creation.png, variant A), by day,
# and by night for a few (…-nuit.png); the texts of each screen go to the log (the job log of the CI can be read where
# its artifacts cannot). Fails when the demonstration is not built, a scene does not show, the app crashes (Java or
# native), freezes (ANR), shows its crash report or leaves the front. An APK that does not answer (a release) is
# skipped. Called by smoke.sh for the debug APK.
set -euo pipefail

out="${1:-emulator-check}"
pkg="fr.toolmap"
tag="ToolMapOperation"
dir="$out/operation"
mkdir -p "$dir"

fail() {
  echo "::error::$1"
  echo "FAILED: $1" > "$dir/result.txt"
  adb logcat -d > "$dir/logcat.txt" 2>/dev/null || true
  adb exec-out screencap -p > "$dir/failed.png" 2>/dev/null || true
  adb shell cmd statusbar collapse > /dev/null 2>&1 || true
  adb shell cmd connectivity airplane-mode disable > /dev/null 2>&1 || true
  adb shell cmd uimode night no > /dev/null 2>&1 || true
  exit 1
}

ask() {
  adb shell dumpsys activity "$pkg/.MainActivity" operation-screens "$@" 2>&1 | tr -d '\r'
}

# Nothing of the app went wrong since the log was last cleared, and it is still in front.
healthy() {
  local what="$1"
  adb logcat -d > "$dir/logcat-last.txt" 2>/dev/null || true
  if grep -A1 'FATAL EXCEPTION' "$dir/logcat-last.txt" | grep "Process: $pkg" > /dev/null; then
    cp "$dir/logcat-last.txt" "$dir/logcat.txt"
    fail "Java crash of $pkg on the screen $what (see operation/logcat.txt)"
  fi
  if grep ">>> $pkg" "$dir/logcat-last.txt" > /dev/null; then
    cp "$dir/logcat-last.txt" "$dir/logcat.txt"
    fail "native crash of $pkg on the screen $what (see operation/logcat.txt)"
  fi
  if grep "ANR in $pkg" "$dir/logcat-last.txt" > /dev/null; then
    cp "$dir/logcat-last.txt" "$dir/logcat.txt"
    fail "ANR of $pkg on the screen $what (see operation/logcat.txt)"
  fi
  local front
  front="$(adb shell dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' || true)"
  if [[ "$front" == *CrashActivity* ]]; then fail "the crash report screen is in front after $what"; fi
  if [[ "$front" != *"$pkg/"* ]]; then fail "ToolMap is not in front after $what: $front"; fi
}

# Shows the scene $1 and takes it as $2.png, with its texts in the log.
captured=0
capture() {
  local scene="$1" file="$2" result="" shade=""
  # The notification of the operation (P39.14.5, L4): pulled down for the capture; « hors réseau », the network cut
  # (airplane mode) while it is shown, and given back right after.
  [[ "$scene" == *notification* || "$scene" == *hors-reseau* ]] && shade=1
  if [[ "$scene" == *hors-reseau* ]]; then
    adb shell cmd connectivity airplane-mode enable > /dev/null 2>&1 || fail "the emulator does not go into airplane mode"
  fi
  adb logcat -c || true
  ask show "$scene" > "$dir/ask.txt"
  grep "show $scene started" "$dir/ask.txt" > /dev/null || fail "the app does not know the scene $scene: $(cat "$dir/ask.txt")"
  for _ in $(seq 1 90); do
    result="$(adb logcat -d -s "$tag:I" | grep -oE "operation-screens (shown|failed) $scene( |$)" | head -n 1 || true)"
    [ -n "$result" ] && break
    sleep 0.5
  done
  [ -n "$result" ] || fail "the scene $scene did not show in time"
  [[ "$result" == *shown* ]] || fail "the scene $scene could not be shown"
  sleep 1.5
  if [ -n "$shade" ]; then
    adb shell cmd statusbar expand-notifications > /dev/null 2>&1 || true
    sleep 2
  fi
  adb exec-out screencap -p > "$dir/$file.png" || fail "no screenshot of $scene"
  rm -f "$dir/ui.xml"
  adb shell uiautomator dump /sdcard/toolmap-op.xml > /dev/null 2>&1 && adb pull /sdcard/toolmap-op.xml "$dir/ui.xml" > /dev/null 2>&1 || true
  echo "== $file.png"
  if [ -s "$dir/ui.xml" ]; then
    grep -oE '(text|content-desc)="[^"]+"' "$dir/ui.xml" | sed -E 's/^[a-z-]+="//; s/"$//' | awk '!seen[$0]++' |
      head -n 60 | sed 's/^/  /' || true
  fi
  if [ -n "$shade" ]; then
    adb shell cmd statusbar collapse > /dev/null 2>&1 || true
    sleep 1
  fi
  if [[ "$scene" == *hors-reseau* ]]; then
    adb shell cmd connectivity airplane-mode disable > /dev/null 2>&1 || true
    sleep 3
  fi
  healthy "$scene"
  captured=$((captured + 1))
}

answer="$(ask list || true)"
if ! grep 'operation-screens scene ' > /dev/null <<< "$answer"; then
  echo "The screens of the « Opération » mode are not offered by this APK (no answer to dumpsys)."
  exit 0
fi
mapfile -t stable < <(grep -oE 'operation-screens scene [^ ]+$' <<< "$answer" | sed 's/^operation-screens scene //')
mapfile -t changing < <(grep -oE 'operation-screens scene [^ ]+ changes$' <<< "$answer" | sed 's/^operation-screens scene //; s/ changes$//')
echo "Screens of the « Opération » mode: ${#stable[@]} scenes, then ${#changing[@]} that change the demonstration."

# The app in front, by day (the theme follows the system by default).
adb shell cmd uimode night no > /dev/null 2>&1 || true
adb shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null || fail "the app does not start"
sleep 4

adb logcat -c || true
ask demo > "$dir/ask.txt"
grep 'demo started' "$dir/ask.txt" > /dev/null || fail "the demonstration does not start: $(cat "$dir/ask.txt")"
built=""
for _ in $(seq 1 240); do
  built="$(adb logcat -d -s "$tag:I" | grep -oE 'operation-screens demo (ready|failed)' | head -n 1 || true)"
  [ -n "$built" ] && break
  sleep 1
done
adb logcat -d -s "$tag:I" | grep -oE 'operation-screens demo .*' | sed 's/^/  /' || true
[ "$built" = "operation-screens demo ready" ] || fail "the demonstration was not built (${built:-no answer in 240 s})"
healthy "demo"

for scene in "${stable[@]}"; do capture "$scene" "$scene"; done

# By night: the main screens of each part.
adb shell cmd uimode night yes > /dev/null 2>&1 || fail "the emulator does not switch to the night theme"
sleep 5
for scene in tour2-01-entree-A tour4-10-creation-A tour3-08-inviter-A tour4-01-attente-A tour3-04-entree-nom-etats \
  tour2-04-admin-sommaire tour2-04-admin-groupe tour4-09-glissiere tour3-07-remise-attente-adjoint-confirmation; do
  capture "$scene" "$scene-nuit"
done
adb shell cmd uimode night no > /dev/null 2>&1 || true
sleep 5

for scene in "${changing[@]}"; do capture "$scene" "$scene"; done

echo "OK: $captured screens of the « Opération » mode taken in operation/." | tee "$dir/result.txt"
rm -f "$dir/ask.txt" "$dir/ui.xml" "$dir/logcat-last.txt"
