#!/usr/bin/env bash
set -euo pipefail

candidates=(
  "${ANDROID_SDK_ROOT:-}"
  "${ANDROID_HOME:-}"
  "$HOME/Android/Sdk"
  "$HOME/Android/sdk"
)

for candidate in "${candidates[@]}"; do
  if [[ -n "$candidate" && -d "$candidate" ]]; then
    printf '%s\n' "$candidate"
    exit 0
  fi
done

exit 1
