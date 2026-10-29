#!/usr/bin/env bash
# Installs BRouter and its routing data on the emulator (docs/specs/routing.md §10), from a cache kept outside the
# repository so that nothing is downloaded twice:
#   bash tools/emulator/brouter-setup.sh [E5_N45 ...]
# Cache: $TOOLMAP_BROUTER_CACHE, by default ~/.cache/toolmap/brouter (btools.routingapp_<code>.apk, segments4/*.rd5).
# Only what is missing in the cache is downloaded (F-Droid for the app, brouter.de for the 5° x 5° cells).
# An emulator started with -no-snapshot-save forgets the install at its next start: save the state afterwards with
#   adb emu avd snapshot save default_boot
set -euo pipefail

CACHE="${TOOLMAP_BROUTER_CACHE:-$HOME/.cache/toolmap/brouter}"
CODE="${BROUTER_VERSION_CODE:-56}" # BRouter 1.7.9 on F-Droid
APK="$CACHE/btools.routingapp_$CODE.apk"
PKG=btools.routingapp
BASE=/sdcard/Android/media/$PKG/brouter
CELLS=("${@:-E5_N45}")

# Git Bash: adb.exe needs Windows paths for local files and no path rewriting for the device ones.
export MSYS_NO_PATHCONV=1
if ! command -v adb >/dev/null 2>&1; then
    for sdk in "${ANDROID_HOME:-}" "${LOCALAPPDATA:-}/Android/Sdk" "$HOME/AppData/Local/Android/Sdk" "$HOME/android-sdk"; do
        if [ -n "$sdk" ] && { [ -x "$sdk/platform-tools/adb" ] || [ -x "$sdk/platform-tools/adb.exe" ]; }; then
            export PATH="$PATH:$sdk/platform-tools"
            break
        fi
    done
fi
command -v adb >/dev/null 2>&1 || { echo "adb introuvable (ANDROID_HOME ?)" >&2; exit 1; }
winpath() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
log() { echo "[brouter] $*"; }

mkdir -p "$CACHE/segments4"
if [ ! -s "$APK" ]; then
    log "Téléchargement de BRouter ($CODE) depuis F-Droid"
    curl -sSfL -o "$APK" "https://f-droid.org/repo/${PKG}_$CODE.apk"
fi
for cell in "${CELLS[@]}"; do
    if [ ! -s "$CACHE/segments4/$cell.rd5" ]; then
        log "Téléchargement de $cell.rd5 depuis brouter.de"
        curl -sSfL -o "$CACHE/segments4/$cell.rd5" "https://brouter.de/brouter/segments4/$cell.rd5"
    fi
done

if ! adb shell pm list packages "$PKG" | grep -q "package:$PKG\$"; then
    log "Installation de BRouter"
    adb install -r "$(winpath "$APK")" >/dev/null
fi

# The first start asks for the data folder: the internal one (selected) is accepted with OK.
if ! adb shell ls "$BASE/segments4" >/dev/null 2>&1; then
    log "Premier démarrage de BRouter (dossier de données)"
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 4
    adb shell uiautomator dump /sdcard/brouter-ui.xml >/dev/null 2>&1
    ok=$(adb exec-out cat /sdcard/brouter-ui.xml | tr '>' '\n' | grep 'text="OK"' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' || true)
    if [ -n "$ok" ]; then
        read -r x1 y1 x2 y2 <<<"$(echo "$ok" | grep -oE '[0-9]+' | tr '\n' ' ')"
        adb shell input tap $(((x1 + x2) / 2)) $(((y1 + y2) / 2))
    fi
    for _ in $(seq 20); do adb shell ls "$BASE/segments4" >/dev/null 2>&1 && break; sleep 1; done
    adb shell am force-stop "$PKG"
fi

for cell in "${CELLS[@]}"; do
    size=$(stat -c %s "$CACHE/segments4/$cell.rd5")
    if [ "$(adb shell stat -c %s "$BASE/segments4/$cell.rd5" 2>/dev/null | tr -d '\r')" != "$size" ]; then
        log "Copie de $cell.rd5 sur l'émulateur"
        adb push "$(winpath "$CACHE/segments4/$cell.rd5")" "$BASE/segments4/$cell.rd5" >/dev/null
    fi
done
log "Prêt : $(adb shell ls "$BASE/segments4" | tr -d '\r' | grep '\.rd5$' | tr '\n' ' ')"
