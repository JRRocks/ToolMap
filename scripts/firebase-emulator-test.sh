#!/usr/bin/env bash
# Runs the relay tests against the real Firebase emulators (auth + realtime database) with firebase/database.rules.json.
# Needs Node (npx), Java 21 and network access the first time (the database emulator jar is downloaded).
set -euo pipefail
cd "$(dirname "$0")/.."
# The emulators talk to each other on localhost: never through a proxy.
unset https_proxy HTTPS_PROXY GLOBAL_AGENT_HTTPS_PROXY npm_config_https_proxy YARN_HTTPS_PROXY DOCKER_HTTPS_PROXY
export NO_PROXY=127.0.0.1,localhost,.localhost
exec npx --yes firebase-tools@latest emulators:exec --project demo-toolmap --config firebase/firebase.json \
  --only auth,database "FIREBASE_EMULATOR=1 ./gradlew :p2p:testDebugUnitTest --tests '*FirebaseRelayEmulatorTest*' --rerun-tasks -i"
