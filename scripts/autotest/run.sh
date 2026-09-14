#!/usr/bin/env bash
# Universal APK test entrypoint — runs INSIDE the booted-emulator step.
# App-agnostic: nothing here knows what app the APK contains.
# usage: run.sh <apk> [mode] [max_actions] [max_seconds] [seed] [out_dir]
set -euo pipefail

APK="${1:?usage: run.sh <apk> [mode] [max_actions] [max_seconds] [seed] [out_dir]}"
MODE="${2:-STANDARD}"
MAX_ACTIONS="${3:-120}"
MAX_SECONDS="${4:-900}"
SEED="${5:-1337}"
OUT="${6:-artifacts}"

adb wait-for-device
# Deterministic, awake, unlocked device:
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell settings put global window_animation_scale 0.0 || true
adb shell settings put global transition_animation_scale 0.0 || true
adb shell settings put global animator_duration_scale 0.0 || true

mkdir -p "$OUT/apk"
cp -f "$APK" "$OUT/apk/" 2>/dev/null || true

python3 "$(cd "$(dirname "$0")" && pwd)/universal_test.py" \
  --apk "$APK" \
  --mode "$MODE" \
  --max-actions "$MAX_ACTIONS" \
  --max-seconds "$MAX_SECONDS" \
  --seed "$SEED" \
  --out "$OUT"
