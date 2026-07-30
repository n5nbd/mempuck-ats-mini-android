#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$ROOT/tools/ensure_gradle_wrapper.sh"
"$ROOT/tools/prepare_android_sdk.sh"
cd "$ROOT"
./gradlew --no-daemon testDebugUnitTest assembleDebug
printf '\nAPK: %s\n' "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
