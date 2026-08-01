#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$APK" ]]; then
  echo "APK not found: $APK" >&2
  echo "Run ./build.sh once before using ./pushapk.sh." >&2
  exit 1
fi

if [[ -x "$ROOT_DIR/tools/find_pixel6.sh" ]]; then
  DEVICE="$($ROOT_DIR/tools/find_pixel6.sh)"
else
  DEVICE="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [[ -z "${DEVICE:-}" ]]; then
  echo "No ready Android device found. Reconnect the Pixel 6 and try again." >&2
  exit 1
fi

echo "Installing existing APK to $DEVICE (no build)..."
adb -s "$DEVICE" install -r "$APK"
echo "Installed: $APK"
