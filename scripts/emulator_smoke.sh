#!/usr/bin/env bash
# Emulator smoke test — deep feature sweep (§ real runtime verification):
#   1. install the APK on the already-booted emulator (android-emulator-runner)
#   2. cold-launch MainActivity, wait for settle
#   3. FAIL if the process is dead or a FATAL EXCEPTION appears in logcat
#   4. DEEP SWEEP: open every main destination (Chat, Terminal, Files) and
#      every Settings sub-screen (Providers, Models, Ollama, Local models,
#      OpenCode, Ubuntu userspace, Terminal settings, Workspace, Background
#      processes, Security, Storage, About) via uiautomator-driven taps —
#      the process is crash-checked after EVERY screen, with per-screen
#      screenshot evidence
#   5. capture a final screenshot (uploaded as a workflow artifact)
# Works for BOTH debug and release APKs: the application id is auto-detected
# with aapt (debug builds carry a .debug applicationIdSuffix).
# $1 may be a glob (resolved with ls; first match wins).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

APK="$(ls ${1:?usage: emulator_smoke.sh <apk-path-or-glob>} 2>/dev/null | head -1 || true)"
test -n "$APK" && test -f "$APK" || { echo "::error::APK not found for pattern: ${1}"; ls -la . apks/ release/ 2>/dev/null || true; exit 1; }

adb wait-for-device
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') (API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"

# --- real screen size (emulator default may be as small as 320x640) ----------
SIZE_OUT="$(adb shell wm size | tr -d '\r' || true)"
SCR_W="$(printf '%s' "$SIZE_OUT" | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\1/p' | head -1)"
SCR_H="$(printf '%s' "$SIZE_OUT" | sed -n 's/.*: \([0-9][0-9]*\)x\([0-9][0-9]*\).*/\2/p' | head -1)"
case "${SCR_W:-0}:${SCR_H:-0}" in
  0:0|0:|:0) SCR_W=320; SCR_H=640 ;;   # sane fallback
esac
SWIPE_X=$((SCR_W / 2))
SWIPE_Y1=$((SCR_H * 70 / 100))
SWIPE_Y2=$((SCR_H * 25 / 100))
echo "screen: ${SCR_W}x${SCR_H} (scroll swipe: $SWIPE_X,$SWIPE_Y1 -> $SWIPE_X,$SWIPE_Y2)"

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
adb logcat -c
# keep the screen awake + dismiss any keyguard — uiautomator dump returns the
# keyguard (or nothing) when the display sleeps, which would silently break
# every subsequent UI-driven tap
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell am start -W -n "$COMPONENT" || { echo "::error::am start failed for $COMPONENT"; exit 1; }
echo "launched, settling 12s…"
sleep 12

# --- stabilize: kill animations so uiautomator stays deterministic ------------
adb shell settings put global window_animation_scale 0.0 || true
adb shell settings put global transition_animation_scale 0.0 || true
adb shell settings put global animator_duration_scale 0.0 || true

fail_with_logs() { # $1 = reason
  echo "::error::$1"
  adb exec-out screencap -p > smoke-screen.png 2>/dev/null || true
  adb logcat -d | grep -A 40 -F "FATAL EXCEPTION" | head -60 || true
  adb logcat -d | grep -iE "AndroidRuntime|$APP_ID" | tail -60 || true
  exit 1
}

check_alive() { # $1 = context label
  local PID
  PID="$(adb shell pidof "$APP_ID" | tr -d '\r' || true)"
  if [ -z "$PID" ]; then
    fail_with_logs "app process is NOT alive after: $1"
  fi
  echo "  alive (pid $PID): $1"
}

check_no_fatal() {
  if adb logcat -d | grep -qF "FATAL EXCEPTION"; then
    fail_with_logs "FATAL EXCEPTION found in logcat"
  fi
}

# --- uiautomator helpers ------------------------------------------------------
ui_dump() {
  local out
  out="$(adb shell uiautomator dump /sdcard/window_dump.xml 2>&1 | tr -d '\r' || true)"
  if ! printf '%s' "$out" | grep -q "dumped to"; then
    echo "  [dump] uiautomator did not report success: $out"
    sleep 2
  fi
}

ui_tap() { # $1=human label; remaining args = attr value pairs tried in order
  local label="$1"; shift
  local PAIRS=("$@")
  local tries=0 c="" XML=""
  while [ "$tries" -lt 8 ]; do
    ui_dump
    XML="$(adb shell cat /sdcard/window_dump.xml 2>/dev/null | tr -d '\r' || true)"
    if [ -z "$XML" ]; then
      echo "  [dump] empty XML (attempt $tries)"
    else
      local i=0
      while [ "$i" -lt "${#PAIRS[@]}" ]; do
        c="$(printf '%s' "$XML" | python3 "$ROOT/scripts/emu_ui.py" "${PAIRS[$i]}" "${PAIRS[$((i + 1))]}" || true)"
        if [ -n "$c" ]; then
          # shellcheck disable=SC2086
          adb shell input tap $c
          echo "  tapped [$label] via ${PAIRS[$i]}='${PAIRS[$((i + 1))]} at ($c)"
          return 0
        fi
        i=$((i + 2))
      done
      if [ "$tries" -eq 0 ]; then
        echo "  [dump] XML size ${#XML}, no candidate matched for [$label]; XML head:"
        printf '%s\n' "$XML" | head -c 1500
        echo " …"
      fi
    fi
    if [ "$tries" -ge 5 ]; then
      adb shell input swipe "$SWIPE_X" "$SWIPE_Y2" "$SWIPE_X" "$SWIPE_Y1" 250   # scroll back up
    else
      adb shell input swipe "$SWIPE_X" "$SWIPE_Y1" "$SWIPE_X" "$SWIPE_Y2" 250 # scroll down
    fi
    sleep 2
    tries=$((tries + 1))
  done
  fail_with_logs "UI element not found after scroll retries: $label"
}

shot() { # $1 = slug
  mkdir -p smoke-shots
  adb exec-out screencap -p > "smoke-shots/$1.png" 2>/dev/null || true
}

# Make sure we are on the Settings root screen. The Ubuntu status card sits at
# the very top of that screen, so an Install/Reinstall button in the dump is a
# reliable marker. Recovery path: re-launch the activity (returns to Chat, the
# start destination) and tap the bottom-nav Settings item.
ensure_settings_root() {
  local tries=0 XML
  while [ "$tries" -lt 3 ]; do
    ui_dump
    XML="$(adb shell cat /sdcard/window_dump.xml 2>/dev/null | tr -d '\r' || true)"
    if printf '%s' "$XML" | grep -Eq 'text="(Re)?install"'; then
      return 0
    fi
    echo "  [nav] not on Settings root (attempt $tries) — tapping bottom-nav Settings"
    ui_tap "bottom-nav Settings" content-desc Settings text Settings
    sleep 1.5
    tries=$((tries + 1))
  done
  echo "  [nav] recovery: re-launching MainActivity"
  adb shell am start -W -n "$COMPONENT" >/dev/null 2>&1 || true
  sleep 3
  ui_tap "bottom-nav Settings (recovery)" content-desc Settings text Settings
  sleep 1.5
}

# Visit a Settings sub-screen by its exact row label and return to Settings.
visit_subscreen() { # $1 = row label, $2 = slug
  ensure_settings_root
  sleep 1
  ui_tap "settings row: $1" text "$1" content-desc "$1"
  sleep 2.5
  check_alive "screen: $1"
  check_no_fatal
  shot "$2"
  adb shell input keyevent 4          # back to Settings
  sleep 1
}

# --- cold launch checks -------------------------------------------------------
check_alive "cold launch"
check_no_fatal
shot "01-chat-cold"

# --- main destinations via bottom navigation ----------------------------------
adb shell input keyevent KEYCODE_WAKEUP || true   # screen must be on for taps
ui_tap "bottom-nav Terminal" content-desc Terminal text Terminal
sleep 2.5
check_alive "screen: Terminal"
check_no_fatal
shot "02-terminal"

ui_tap "bottom-nav Files" content-desc Files text Files
sleep 2.5
check_alive "screen: Files"
check_no_fatal
shot "03-files"

ui_tap "bottom-nav Settings" content-desc Settings text Settings
sleep 2
check_alive "screen: Settings"
check_no_fatal
shot "04-settings"

# --- every Settings sub-screen (in list order; scroll handled by ui_tap) ------
visit_subscreen "Providers"                 "05-providers"
visit_subscreen "Models"                    "06-models"
visit_subscreen "Ollama"                    "07-ollama"
visit_subscreen "Local models (on-device)"  "08-local-models"
visit_subscreen "OpenCode"                  "09-opencode"
visit_subscreen "Ubuntu userspace"          "10-ubuntu"
visit_subscreen "Terminal"                  "11-terminal-settings"
visit_subscreen "Workspace"                 "12-workspace"
visit_subscreen "Background processes"      "13-processes"
visit_subscreen "Security"                  "14-security"
visit_subscreen "Storage"                   "15-storage"
visit_subscreen "About"                     "16-about"

# --- back to start destination, final evidence --------------------------------
ui_tap "bottom-nav Chat" content-desc Chat text Chat
sleep 2
check_alive "screen: Chat (final)"
check_no_fatal
adb exec-out screencap -p > smoke-screen.png
ls -l smoke-screen.png smoke-shots/ | head -25
echo "SMOKE OK: $APP_ID survived the full feature sweep (16 screens), no FATAL EXCEPTION"
