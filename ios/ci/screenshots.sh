#!/bin/bash
# Installs the simulator build of ToolMap, opens a few screens through the debug launch arguments of AppModel and saves a
# screenshot of each (P32.3; CLAUDE.md "aperçu validé", point 4). Fails when the app is not running after its launch.
# Usage: screenshots.sh <simulator udid> <path/to/ToolMap.app> <output folder>
set -euo pipefail
udid="$1"
app="$2"
out="$3"
bundle=fr.toolmap.ios
mkdir -p "$out"

xcrun simctl boot "$udid" 2> /dev/null || true
xcrun simctl bootstatus "$udid" -b > /dev/null
xcrun simctl status_bar "$udid" override --time "9:41" --batteryState charged --batteryLevel 100 || true
xcrun simctl install "$udid" "$app"
xcrun simctl privacy "$udid" grant location "$bundle" || true
# A simulated position near Chambéry, the place of the Android screenshots.
xcrun simctl location "$udid" set 45.5646,5.9178 || true

shot() {
  local name="$1"
  shift
  xcrun simctl terminate "$udid" "$bundle" 2> /dev/null || true
  xcrun simctl launch "$udid" "$bundle" "$@" > /dev/null
  sleep "${WAIT:-20}"
  # Simulator apps are ordinary processes of the Mac.
  if ! pgrep -f "ToolMap.app/ToolMap" > /dev/null; then
    echo "ToolMap is not running after the launch of $name"
    xcrun simctl spawn "$udid" log show --last 3m --style compact --predicate 'process == "ToolMap"' | tail -n 80 || true
    exit 1
  fi
  xcrun simctl io "$udid" screenshot "$out/$name.png" > /dev/null
  echo "Screenshot $name"
}

shot 01-carte -ToolMapCenter 45.5646,5.9178,13
shot 02-cartes-et-calques -ToolMapCenter 45.5646,5.9178,13 -ToolMapOverlays ign-pentes -ToolMapPanel maps
shot 03-menu -ToolMapPanel menu
shot 04-position -ToolMapPanel position
shot 05-osm-nuit -ToolMapBase osm -ToolMapNight YES -ToolMapCenter 45.19,5.72,12
