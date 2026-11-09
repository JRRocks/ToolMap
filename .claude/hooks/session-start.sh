#!/bin/bash
# SessionStart hook for Claude Code on the web: makes `./gradlew testDebugUnitTest` work out of the box.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(pwd)}"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# 0. Maven Central mirror for this sandbox (avoids HTTP 429 from repo.maven.apache.org).
mkdir -p "$HOME/.gradle/init.d"
cp ./scripts/gradle-init/cloud-mirror.init.gradle "$HOME/.gradle/init.d/cloud-mirror.init.gradle"

# 1. Android SDK (cached by the container snapshot after the first run). The NDK serves the native engine (Rust,
#    ADR-119): without it, or without Rust, a local build leaves the engine out and the Kotlin code does the work.
TOOLMAP_NDK=optional ./scripts/install-android-sdk.sh

# 2. Tell Gradle where the SDK is (local.properties is git-ignored).
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 3. Persist the environment for the session.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export ANDROID_HOME=\"$ANDROID_HOME\""
    echo "export ANDROID_SDK_ROOT=\"$ANDROID_HOME\""
  } >> "$CLAUDE_ENV_FILE"
fi

# 4. Warm the Gradle cache (wrapper distribution + dependencies) once the wrapper exists.
if [ -x ./gradlew ]; then
  ./gradlew --version >/dev/null
fi

# 4b. The Rust of the native engine: rustup installs the pinned toolchain and the Android targets named in
#     engine/src/main/rust/rust-toolchain.toml, cargo fetches the crates. Never fatal: the engine is optional here.
if command -v cargo >/dev/null 2>&1 && [ -f engine/src/main/rust/Cargo.toml ]; then
  (cd engine/src/main/rust && cargo fetch --locked >/dev/null 2>&1) ||
    echo "[rust] The Rust toolchain of the native engine could not be prepared: local builds leave the engine out."
fi

# 5. Remind the model of the project protocol: show the latest journal entry.
if [ -f docs/JOURNAL.md ]; then
  echo "=== ToolMap : lire CLAUDE.md, docs/PLAN.md et docs/JOURNAL.md avant de travailler ==="
  awk '/^## 20/{n++} n==1' docs/JOURNAL.md | head -30
fi
