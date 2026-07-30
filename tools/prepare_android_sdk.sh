#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find_sdk() {
  "$ROOT/tools/find_android_sdk.sh" 2>/dev/null || true
}

SDK="$(find_sdk)"
if [[ -z "$SDK" || ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
  SDK="$($ROOT/tools/install_android_sdk.sh | tail -n 1)"
fi

required_paths=(
  "$SDK/platform-tools/adb"
  "$SDK/platforms/android-36/android.jar"
  "$SDK/build-tools/36.0.0/aapt2"
)

missing=0
for path in "${required_paths[@]}"; do
  if [[ ! -e "$path" ]]; then
    missing=1
    break
  fi
done

if (( missing )); then
  SDK="$($ROOT/tools/install_android_sdk.sh | tail -n 1)"
fi

escaped_sdk="${SDK//\\/\\\\}"
printf 'sdk.dir=%s\n' "$escaped_sdk" > "$ROOT/local.properties"

echo "[android] using SDK: $SDK"
