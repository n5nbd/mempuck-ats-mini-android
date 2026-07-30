#!/usr/bin/env bash
set -euo pipefail

ADB="${ADB:-adb}"
mapfile -t devices < <("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')

if (( ${#devices[@]} == 0 )); then
  echo "ERROR: no authorized Android device found by adb" >&2
  exit 1
fi

for serial in "${devices[@]}"; do
  model="$($ADB -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  if [[ "$model" == "Pixel 6" ]]; then
    printf '%s\n' "$serial"
    exit 0
  fi
done

if (( ${#devices[@]} == 1 )); then
  printf '%s\n' "${devices[0]}"
  exit 0
fi

echo "ERROR: multiple Android devices are connected and none identifies as Pixel 6" >&2
exit 1
