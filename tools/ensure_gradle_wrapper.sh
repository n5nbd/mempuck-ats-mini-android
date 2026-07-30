#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_VERSION=8.13
CACHE="$HOME/.cache/mempuck/gradle-$GRADLE_VERSION"
ZIP="$CACHE/gradle-$GRADLE_VERSION-bin.zip"
DIST="$CACHE/gradle-$GRADLE_VERSION"

if [[ -f "$ROOT/gradle/wrapper/gradle-wrapper.jar" ]]; then
  exit 0
fi

mkdir -p "$CACHE"
if [[ ! -x "$DIST/bin/gradle" ]]; then
  echo "[gradle] downloading Gradle $GRADLE_VERSION"
  curl -fL --retry 3 \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" \
    -o "$ZIP"
  curl -fsSL \
    "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip.sha256" \
    -o "$ZIP.sha256"
  printf '%s  %s\n' "$(tr -d '[:space:]' < "$ZIP.sha256")" "$ZIP" | sha256sum -c -
  rm -rf "$DIST"
  unzip -q "$ZIP" -d "$CACHE"
fi

# Generate the wrapper in a tiny temporary Gradle project. This avoids resolving
# Android plugins or requiring an SDK merely to create gradlew.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
printf 'rootProject.name = "wrapper-bootstrap"\n' > "$TMP/settings.gradle"
"$DIST/bin/gradle" -p "$TMP" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin
mkdir -p "$ROOT/gradle/wrapper"
cp "$TMP/gradlew" "$TMP/gradlew.bat" "$ROOT/"
cp "$TMP/gradle/wrapper/gradle-wrapper.jar" "$TMP/gradle/wrapper/gradle-wrapper.properties" \
  "$ROOT/gradle/wrapper/"
chmod +x "$ROOT/gradlew"
