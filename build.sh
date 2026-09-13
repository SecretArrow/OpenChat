#!/usr/bin/env bash
# Open Chat — full build pipeline (spec §21).
# Usage: ./build.sh [debug|release]
#   Clean → Validate environment → Validate dependencies → Validate signing
#         → Build ABI APKs → Verify APK → Generate SHA256
set -euo pipefail
cd "$(dirname "$0")"

BUILD_TYPE="${1:-release}"
case "$BUILD_TYPE" in
  debug|release) ;;
  *) echo "usage: ./build.sh [debug|release]"; exit 1 ;;
esac

say() { printf '\n\033[1;36m[openchat-build]\033[0m %s\n' "$*"; }

# --- 1) Clean ---------------------------------------------------------------
say "1/7 Clean"
./gradlew clean >/dev/null

# --- 2) Validate environment -------------------------------------------------
say "2/7 Validate environment"
command -v java >/dev/null || { echo "Java not found"; exit 1; }
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
  for d in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" /usr/local/lib/android/sdk; do
    [ -d "$d" ] && export ANDROID_HOME="$d" && break
  done
fi
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] || {
  echo "Android SDK not found (set ANDROID_HOME)"; exit 1; }
echo "  java: $(java -version 2>&1 | head -1)"
echo "  sdk:  $ANDROID_HOME"

# --- 3) Validate dependencies -------------------------------------------------
say "3/7 Validate dependencies"
test -f gradle/wrapper/gradle-wrapper.jar || { echo "gradle-wrapper.jar missing"; exit 1; }
./gradlew --version >/dev/null
echo "  gradle wrapper OK"

# --- 4) Validate signing ------------------------------------------------------
say "4/7 Validate signing"
KS="${ANDROID_KEYSTORE_PATH:-}"
if [ "$BUILD_TYPE" = "release" ]; then
  if [ -z "$KS" ] && [ -f keystore.properties ]; then
    # shellcheck disable=SC1091
    ANDROID_KEYSTORE_PATH="$(grep '^ANDROID_KEYSTORE_PATH=' keystore.properties | cut -d= -f2-)"
    export ANDROID_KEYSTORE_PATH
    KS="$ANDROID_KEYSTORE_PATH"
  fi
  if [ -n "$KS" ] && [ -f "$KS" ]; then
    echo "  persistent keystore: $KS"
  else
    echo "  WARNING: no release keystore configured — release APK will be signed"
    echo "  with the debug key (installable but NOT updatable from real releases)."
    echo "  See docs/RELEASE_SIGNING.md."
  fi
fi

# --- 5) Build ABI APKs ---------------------------------------------------------
say "5/7 Build $BUILD_TYPE APKs (ABI split)"
if [ "$BUILD_TYPE" = "release" ]; then
  ./gradlew --parallel --build-cache assembleRelease
else
  ./gradlew --parallel --build-cache assembleDebug
fi

# --- 6) Verify APK ---------------------------------------------------------------
say "6/7 Verify APKs"
SRC="app/build/outputs/apk/$BUILD_TYPE"
mkdir -p release
if [ "$BUILD_TYPE" = "release" ]; then
  bash scripts/verify_apks.sh
else
  ls -la "$SRC"/OpenChat-*.apk
  cp "$SRC"/OpenChat-*.apk release/
fi

# --- 7) SHA256 ---------------------------------------------------------------------
say "7/7 SHA256"
if [ "$BUILD_TYPE" = "release" ]; then
  echo "checksums already generated in release/checksums.txt"
else
  (cd release && sha256sum OpenChat-*-debug.apk > checksums.txt && cat checksums.txt)
fi

say "Done. Artifacts in ./release/"
