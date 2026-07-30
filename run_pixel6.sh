#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$ROOT/build.sh"
"$ROOT/install_pixel6.sh"
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  SDK="$($ROOT/tools/find_android_sdk.sh)"
  ADB="$SDK/platform-tools/adb"
fi
SERIAL="$(ADB="$ADB" "$ROOT/tools/find_pixel6.sh")"
"$ADB" -s "$SERIAL" shell am force-stop com.n5nbd.mempuck.atsmini
"$ADB" -s "$SERIAL" shell monkey -p com.n5nbd.mempuck.atsmini 1 >/dev/null
