#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  SDK="$($ROOT/tools/find_android_sdk.sh)" || {
    echo "ERROR: adb not found and Android SDK could not be located" >&2
    exit 1
  }
  ADB="$SDK/platform-tools/adb"
fi
SERIAL="$(ADB="$ADB" "$ROOT/tools/find_pixel6.sh")"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$APK" ]] || "$ROOT/build.sh"
echo "[android] installing on $SERIAL"
"$ADB" -s "$SERIAL" install -r "$APK"
