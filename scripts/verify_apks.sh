#!/usr/bin/env bash
# Verify release APKs (spec §21): exactly 3 per-ABI APKs, signed, correct ABI
# payload (libpty.so), then produce SHA256 checksums.txt.
set -euo pipefail

SRC="app/build/outputs/apk/release"
OUT="release"
mkdir -p "$OUT"

EXPECTED=(
  "OpenChat-arm64-v8a.apk"
  "OpenChat-armeabi-v7a.apk"
  "OpenChat-x86_64.apk"
)

echo "=== Collecting APKs ==="
for f in "${EXPECTED[@]}"; do
  if [ ! -f "$SRC/$f" ]; then
    echo "MISSING expected split APK: $SRC/$f" >&2
    echo "--- actual outputs ---" >&2
    ls -R "$SRC" >&2 || true
    exit 1
  fi
  cp "$SRC/$f" "$OUT/$f"
  echo "  ok: $f"
done

# Reject unexpected/universal APKs
EXTRA=$(ls "$SRC" 2>/dev/null | grep -v -F -f <(printf '%s\n' "${EXPECTED[@]}") || true)
if [ -n "$EXTRA" ]; then
  echo "WARNING: unexpected extra outputs (ignored): $EXTRA"
fi

APKSIGNER="$(ls -d "${ANDROID_HOME:-/usr/local/lib/android/sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"
if [ -z "$APKSIGNER" ]; then
  echo "apksigner not found" >&2
  exit 1
fi

echo "=== Verifying signatures ==="
for f in "${EXPECTED[@]}"; do
  "$APKSIGNER" verify "$OUT/$f"
  echo "  signature ok: $f"
done

echo "=== Verifying ABI payload (libpty.so) ==="
declare -A ABI_DIR=(
  ["OpenChat-arm64-v8a.apk"]="lib/arm64-v8a"
  ["OpenChat-armeabi-v7a.apk"]="lib/armeabi-v7a"
  ["OpenChat-x86_64.apk"]="lib/x86_64"
)
for f in "${EXPECTED[@]}"; do
  lib="${ABI_DIR[$f]}"
  if ! unzip -l "$OUT/$f" | grep -q "$lib/libpty.so"; then
    echo "FAIL: $f does not contain $lib/libpty.so" >&2
    exit 1
  fi
  # Ensure no foreign ABI lib leaked into this APK (spec §18)
  if unzip -l "$OUT/$f" | grep -qE "lib/(arm64-v8a|armeabi-v7a|x86_64)/libpty.so" && \
     [ "$(unzip -l "$OUT/$f" | grep -cE "lib/(arm64-v8a|armeabi-v7a|x86_64)/libpty.so")" != "1" ]; then
    echo "FAIL: $f contains multiple ABI libpty.so" >&2
    exit 1
  fi
  echo "  abi payload ok: $f ($lib)"
done

echo "=== SHA256 ==="
(
  cd "$OUT"
  sha256sum OpenChat-arm64-v8a.apk OpenChat-armeabi-v7a.apk OpenChat-x86_64.apk > checksums.txt
  cat checksums.txt
)

echo "=== Release artifacts ready ==="
ls -la "$OUT"
