#!/usr/bin/env bash
# Emulator smoke test (§ real runtime verification, not just unit tests):
#   1. install the APK on the already-booted emulator (android-emulator-runner)
#   2. cold-launch MainActivity, wait for settle
#   3. FAIL if the process is dead or a FATAL EXCEPTION appears in logcat
#   4. capture a screenshot as evidence (uploaded as a workflow artifact)
# Works for BOTH debug and release APKs: the application id is auto-detected
# with aapt (debug builds carry a .debug applicationIdSuffix).
# $1 may be a glob (resolved with ls; first match wins).
set -euo pipefail

APK="$(ls ${1:?usage: emulator_smoke.sh <apk-path-or-glob>} 2>/dev/null | head -1 || true)"
test -n "$APK" && test -f "$APK" || { echo "::error::APK not found for pattern: ${1}"; ls -la . apks/ release/ 2>/dev/null || true; exit 1; }

adb wait-for-device
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

# --- auto-detect application id (aapt from the newest installed build-tools) --
AAPT="$(find "$ANDROID_HOME/build-tools" -name aapt -type f 2>/dev/null | sort | tail -1)"
test -n "$AAPT" || { echo "::error::aapt not found in ANDROID_HOME/build-tools"; exit 1; }
APP_ID="$("$AAPT" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
test -n "$APP_ID" || { echo "::error::could not read package name from $APK"; exit 1; }
# Launchable activity is the manifest class (com.openchat.android.MainActivity);
# am start needs <applicationId>/<class> — these differ on debug builds (.debug
# applicationIdSuffix). Build the full component name from aapt's badging.
MAIN_ACT="$("$AAPT" dump badging "$APK" | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" | head -1)"
test -n "$MAIN_ACT" || { echo "::error::could not read launchable activity from $APK"; exit 1; }
case "$MAIN_ACT" in
  .*) MAIN_ACT="$APP_ID$MAIN_ACT" ;;
esac
COMPONENT="$APP_ID/$MAIN_ACT"
echo "installing: $APK  (package: $APP_ID, activity: $MAIN_ACT)"

# --- install + cold launch ----------------------------------------------------
adb install -r "$APK"
adb shell am start -W -n "$COMPONENT" || { echo "::error::am start failed for $COMPONENT"; exit 1; }
echo "launched, settling 12s…"
sleep 12

# --- process alive? -----------------------------------------------------------
PID="$(adb shell pidof "$APP_ID" | tr -d '\r' || true)"
echo "pid: ${PID:-<none>}"
if [ -z "$PID" ]; then
  echo "::error::app process is NOT alive after launch"
  adb logcat -d | grep -A 30 -F "FATAL EXCEPTION" | head -50 || true
  adb logcat -d | grep -iE "AndroidRuntime|$APP_ID" | tail -60 || true
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
