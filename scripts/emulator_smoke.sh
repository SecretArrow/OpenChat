#!/usr/bin/env bash
# Emulator smoke test (§ real runtime verification, not just unit tests):
#   1. install the APK on the already-booted emulator (android-emulator-runner)
#   2. cold-launch MainActivity, wait for settle
#   3. FAIL if the process is dead or a FATAL EXCEPTION appears in logcat
#   4. capture a screenshot as evidence (uploaded as a workflow artifact)
# Works for BOTH debug and release APKs: the application id is auto-detected
# with aapt (debug builds carry a .debug applicationIdSuffix).
set -euo pipefail

APK="${1:?usage: emulator_smoke.sh <apk-path>}"
test -f "$APK" || { echo "::error::APK not found: $APK"; exit 1; }

adb wait-for-device
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

# --- auto-detect application id (aapt from the newest installed build-tools) --
AAPT="$(find "$ANDROID_HOME/build-tools" -name aapt -type f 2>/dev/null | sort | tail -1)"
test -n "$AAPT" || { echo "::error::aapt not found in ANDROID_HOME/build-tools"; exit 1; }
APP_ID="$("$AAPT" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
test -n "$APP_ID" || { echo "::error::could not read package name from $APK"; exit 1; }
echo "installing: $APK  (package: $APP_ID)"

# --- install + cold launch ----------------------------------------------------
adb install -r "$APK"
adb shell am start -W -n "$APP_ID/.MainActivity" || { echo "::error::am start failed"; exit 1; }
echo "launched, settling 12s…"
sleep 12

# --- process alive? -----------------------------------------------------------
PID="$(adb shell pidof "$APP_ID" | tr -d '\r' || true)"
echo "pid: ${PID:-<none>}"
if [ -z "$PID" ]; then
  echo "::error::app process is NOT alive after launch"
  adb logcat -d | tail -300 || true
  exit 1
fi

# --- crash check --------------------------------------------------------------
if adb logcat -d | grep -qF "FATAL EXCEPTION"; then
  echo "::error::FATAL EXCEPTION found in logcat"
  adb logcat -d | grep -A 40 -F "FATAL EXCEPTION" | head -60 || true
  exit 1
fi

# --- UI focus sanity + screenshot evidence ------------------------------------
adb shell dumpsys window 2>/dev/null | grep -i mCurrentFocus || true
adb exec-out screencap -p > smoke-screen.png
ls -l smoke-screen.png
echo "SMOKE OK: $APP_ID alive (pid $PID), no FATAL EXCEPTION"
