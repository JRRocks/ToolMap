#!/bin/bash
# Installs the simulator build of ToolMap, opens its screens through the debug launch arguments of AppModel and of the
# feature controllers, and saves a screenshot of each (P32.3; CLAUDE.md "aperçu validé", point 4). A screen after which
# the app is no longer running is a crash: its log is kept, the other screens are still taken, and the script fails at
# the end. Usage: screenshots.sh <simulator udid> <path/to/ToolMap.app> <output folder>
set -uo pipefail
udid="$1"
app="$2"
out="$3"
bundle=fr.toolmap.ios
mkdir -p "$out"
crashed=""

xcrun simctl boot "$udid" 2> /dev/null || true
xcrun simctl bootstatus "$udid" -b > /dev/null
xcrun simctl status_bar "$udid" override --time "9:41" --batteryState charged --batteryLevel 100 || true
xcrun simctl install "$udid" "$app"
xcrun simctl privacy "$udid" grant location "$bundle" || true
xcrun simctl privacy "$udid" grant photos "$bundle" || true
# A simulated position near Chambéry, the place of the Android screenshots.
xcrun simctl location "$udid" set 45.5646,5.9178 || true

# shot <name> <seconds to wait> <launch arguments…>
shot() {
  local name="$1"
  local wait="$2"
  shift 2
  xcrun simctl terminate "$udid" "$bundle" 2> /dev/null || true
  xcrun simctl launch "$udid" "$bundle" "$@" > /dev/null
  sleep "$wait"
  # Simulator apps are ordinary processes of the Mac.
  if ! pgrep -f "ToolMap.app/ToolMap" > /dev/null; then
    echo "::error::ToolMap is not running after the launch of $name ($*)"
    xcrun simctl spawn "$udid" log show --last 2m --style compact --predicate 'process == "ToolMap"' \
      | tail -n 120 > "$out/$name-crash.txt" || true
    tail -n 40 "$out/$name-crash.txt" || true
    # The crash report of the simulator app (stack of the crashed thread), the newest one.
    report="$(ls -t "$HOME"/Library/Logs/DiagnosticReports/ToolMap* 2> /dev/null | head -n 1)"
    if [ -n "$report" ]; then
      cp "$report" "$out/$name-crash-report.txt"
      grep -m 1 -A 40 '"exception"\|Crashed:' "$report" | head -n 60 || true
    fi
    crashed="$crashed $name"
    return 0
  fi
  xcrun simctl io "$udid" screenshot "$out/$name.png" > /dev/null
  echo "Screenshot $name"
}

map=20
page=8
centre=(-ToolMapCenter 45.5646,5.9178,13)

# The map, its panels and the main menu.
shot 01-carte "$map" "${centre[@]}"
shot 02-cartes-et-calques "$map" "${centre[@]}" -ToolMapOverlays ign-pentes -ToolMapPanel maps
shot 03-menu "$page" -ToolMapPanel menu
shot 04-position "$page" -ToolMapPanel position
shot 05-osm-nuit "$map" -ToolMapBase osm -ToolMapNight YES -ToolMapCenter 45.19,5.72,12
# Tracks and waypoints, with the demonstration library.
shot 06-traces-carte "$map" -ToolMapDemo YES -ToolMapCenter 45.5646,5.9178,14
shot 07-panneau-traces "$page" -ToolMapDemo YES -ToolMapPanel tracks
shot 08-bibliotheque "$page" -ToolMapDemo YES -ToolMapScreen library
shot 09-enregistrement "$map" -ToolMapDemo YES -ToolMapCenter 45.5646,5.9178,15
# Map tools.
shot 10-mesure "$map" -ToolMapTool measure -ToolMapDemo YES -ToolMapCenter 45.5646,5.9178,14
shot 11-recherche "$page" -ToolMapScreen search -ToolMapSearch "45.19, 5.72"
shot 12-menu-centre "$map" -ToolMapCenterMenu YES -ToolMapCenter 45.5646,5.9178,14
shot 13-grille-utm "$map" -ToolMapGrid UTM -ToolMapCenter 45.5646,5.9178,13
shot 14-itineraire "$map" -ToolMapTool route -ToolMapDemo YES -ToolMapCenter 45.5646,5.9178,14
# Offline maps.
shot 15-cartes-disponibles "$page" -ToolMapScreen available-maps
shot 16-zone "$map" -ToolMapTool zone "${centre[@]}"
shot 17-telechargements "$page" -ToolMapScreen downloads -ToolMapDemo YES
shot 18-stockage "$page" -ToolMapScreen storage
# Position sharing.
shot 19-partage "$page" -ToolMapScreen share
shot 20-partage-groupe "$page" -ToolMapScreen share -ToolMapDemo YES
# Settings, backup, help.
shot 21-reglages "$page" -ToolMapScreen settings
shot 22-sauvegarde "$page" -ToolMapScreen backup
shot 23-aide "$page" -ToolMapScreen about
shot 24-nouveautes "$page" -ToolMapScreen whats-new
shot 25-verrouille "$map" -ToolMapLocked YES "${centre[@]}"
# Print to scale; the last one exports a PDF at 1:50 000 (and keeps that scale in print/last.json).
shot 26-impression-feuille "$map" -ToolMapTool print "${centre[@]}"
shot 27-impression-cadre "$map" -ToolMapTool print -ToolMapPrintStage frame "${centre[@]}"
shot 28-impression-reglages "$map" -ToolMapTool print -ToolMapPrintStage confirm "${centre[@]}"
shot 29-impression-pdf 45 -ToolMapTool print -ToolMapPrintStage confirm -ToolMapPrintScale 50000 -ToolMapPrintGrid UTM \
  -ToolMapPrintExport YES "${centre[@]}"
container="$(xcrun simctl get_app_container "$udid" "$bundle" data 2> /dev/null || true)"
if [ -n "$container" ]; then
  cp "$container/Library/Application Support/prints/"*.pdf "$out/" 2> /dev/null || true
fi

if [ -n "$crashed" ]; then
  echo "::error::ToolMap stopped after:$crashed"
  exit 1
fi
