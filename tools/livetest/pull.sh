#!/usr/bin/env bash
# Copies the logs of the emulator (the collected logs of the phones, and its own) to tools/livetest/out/<date>/.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
DEST="$OUT_DIR/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$DEST"
for f in remote.previous.log remote.log previous.log current.log; do
    adb exec-out run-as "$PKG" cat "files/eventlog/$f" > "$DEST/$f" 2>/dev/null || true
    # A missing file comes back as the error of cat, on stdout (exec-out).
    if [ ! -s "$DEST/$f" ] || head -c 4 "$DEST/$f" | grep -q '^cat:'; then rm -f "$DEST/$f"; fi
done
ls -la "$DEST" >&2
echo "$DEST"
