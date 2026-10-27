#!/bin/bash
# Installs the Android SDK pieces needed to build ToolMap into $ANDROID_HOME.
# Idempotent and non-interactive: safe to run from a SessionStart hook, from CI or by hand.
# Keep the versions below in sync with gradle/libs.versions.toml (compileSdk / buildTools).
set -euo pipefail

CMDLINE_TOOLS_BUILD="16111833"          # cmdline-tools;23.0
PLATFORM="platforms;android-37.0"       # compileSdk 37
BUILD_TOOLS="build-tools;36.0.0"        # AGP 9.4 default

SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"
SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "[sdk] Downloading Android command-line tools ($CMDLINE_TOOLS_BUILD)..."
  tmp="$(mktemp -d)"
  curl -fsSL --retry 3 -o "$tmp/cmdline-tools.zip" \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  mkdir -p "$SDK_DIR/cmdline-tools"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  rm -rf "$SDK_DIR/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
  rm -rf "$tmp"
fi

export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"

# Accept licenses (sdkmanager exits non-zero when 'yes' is closed by SIGPIPE: ignore it).
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

missing=()
[ -d "$SDK_DIR/platforms/android-37.0" ] || missing+=("$PLATFORM")
[ -d "$SDK_DIR/build-tools/36.0.0" ] || missing+=("$BUILD_TOOLS")
if [ "${#missing[@]}" -gt 0 ]; then
  echo "[sdk] Installing: ${missing[*]}"
  "$SDKMANAGER" "${missing[@]}"
fi

echo "[sdk] Android SDK ready in $SDK_DIR"
