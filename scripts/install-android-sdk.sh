#!/bin/bash
# Installs the Android SDK pieces needed to build ToolMap into $ANDROID_HOME.
# Idempotent and non-interactive: safe to run from a SessionStart hook, from CI or by hand.
# Keep the versions below in sync with gradle/libs.versions.toml (compileSdk / buildTools).
set -euo pipefail

CMDLINE_TOOLS_BUILD="16111833"          # cmdline-tools;23.0
PLATFORM="platforms;android-37.0"       # compileSdk 37
BUILD_TOOLS="build-tools;36.0.0"        # AGP 9.4 default
# The NDK whose linker builds the native engine (Rust, ADR-119): keep in sync with pinnedNdk in engine/build.gradle.kts.
# TOOLMAP_NDK=optional (the cloud session hook) goes on without it when the download fails: the build then leaves
# the engine out and says so. TOOLMAP_NDK=skip does not install it.
NDK_VERSION="28.2.13676358"

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

if [ "${TOOLMAP_NDK:-required}" != "skip" ] && [ ! -d "$SDK_DIR/ndk/$NDK_VERSION" ]; then
  echo "[sdk] Installing: ndk;$NDK_VERSION (about 700 MB to download)"
  if ! "$SDKMANAGER" "ndk;$NDK_VERSION" > /dev/null; then
    if [ "${TOOLMAP_NDK:-required}" = "optional" ]; then
      echo "[sdk] NDK not installed: the native engine is left out of local builds (docs/specs/native-engine.md)."
    else
      echo "[sdk] NDK $NDK_VERSION could not be installed." >&2
      exit 1
    fi
  fi
fi

echo "[sdk] Android SDK ready in $SDK_DIR"
