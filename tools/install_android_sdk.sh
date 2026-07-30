#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
TOOLS_REVISION="15859902"
TOOLS_ARCHIVE="commandlinetools-linux-${TOOLS_REVISION}_latest.zip"
TOOLS_URL="https://dl.google.com/android/repository/${TOOLS_ARCHIVE}"
TOOLS_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/mempuck/android-sdk"
ARCHIVE_PATH="$CACHE_DIR/$TOOLS_ARCHIVE"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

for command in curl unzip sha256sum java; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "ERROR: required command not found: $command" >&2
    exit 1
  fi
done

mkdir -p "$CACHE_DIR" "$SDK_ROOT/cmdline-tools"

if [[ ! -x "$SDKMANAGER" ]]; then
  echo "[android] downloading official command-line tools revision $TOOLS_REVISION"
  if [[ ! -f "$ARCHIVE_PATH" ]] || ! printf '%s  %s\n' "$TOOLS_SHA256" "$ARCHIVE_PATH" | sha256sum --check --status; then
    rm -f "$ARCHIVE_PATH"
    curl --fail --location --retry 3 --continue-at - \
      --output "$ARCHIVE_PATH" "$TOOLS_URL"
  fi
  printf '%s  %s\n' "$TOOLS_SHA256" "$ARCHIVE_PATH" | sha256sum --check

  STAGE="$(mktemp -d)"
  trap 'rm -rf "$STAGE"' EXIT
  unzip -q "$ARCHIVE_PATH" -d "$STAGE"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -a "$STAGE/cmdline-tools/." "$SDK_ROOT/cmdline-tools/latest/"
fi

if [[ ! -x "$SDKMANAGER" ]]; then
  echo "ERROR: sdkmanager installation failed: $SDKMANAGER" >&2
  exit 1
fi

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"

if [[ ! -d "$SDK_ROOT/licenses" ]]; then
  echo
  echo "Android SDK licenses must be accepted before Gradle can build."
  read -r -p "Accept the Android SDK licenses now? [y/N] " answer
  case "$answer" in
    y|Y|yes|YES)
      yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null
      ;;
    *)
      echo "ERROR: Android SDK licenses were not accepted." >&2
      exit 1
      ;;
  esac
fi

PACKAGES=(
  "platform-tools"
  "platforms;android-36"
  "build-tools;36.0.0"
)

echo "[android] installing SDK packages for API 36"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" --install "${PACKAGES[@]}"

printf '%s\n' "$SDK_ROOT"
