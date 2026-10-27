#!/usr/bin/env bash
# Commands of the test tools on the emulator (or on the phone when it is on USB: ANDROID_SERIAL=<serial>).
#   cmd.sh status | snapshot | mark "texte" | collect on|off | test <minutes>|0 | awake <minutes>|0 [here]
# awake (P21.6) : sur le collecteur, les téléphones du groupe gardent leur écran allumé, appli en arrière-plan aussi ;
# « here » (ou sur un autre appareil) : l'écran de cet appareil.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
case "${1:-status}" in
    awake) debug_cmd AWAKE --ei minutes "${2:-30}" --ez here "$([ "${3:-}" = here ] && echo true || echo false)" ;;
    status) debug_cmd STATUS ;;
    snapshot) debug_cmd SNAPSHOT ;;
    mark) debug_cmd MARK --es text "\"${2:?texte}\"" ;;
    collect) debug_cmd COLLECT --ez on "$([ "${2:-on}" = off ] && echo false || echo true)" ;;
    test) debug_cmd TEST --ei minutes "${2:-30}" ;;
    *) echo "commande inconnue : $1" >&2; exit 2 ;;
esac
