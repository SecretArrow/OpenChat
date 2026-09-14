#!/usr/bin/env bash
# Ubuntu E2E installer test — local + CI entry point (spec §24, §25).
#
#   ./scripts/e2e-ubuntu.sh                # needs a running emulator + built APK
#   ./scripts/e2e-ubuntu.sh --build        # also runs ./gradlew assembleDebug
#   ./scripts/e2e-ubuntu.sh --ci           # CI mode: strict checks, globs APK
#   ./scripts/e2e-ubuntu.sh --clean        # uninstall first (fresh install state)
#
# Flags: --ci --clean --build --timeout <sec> --keep-data --verbose
#        --collect-diagnostics --apk <path|glob> --serial <adb-serial> --out <dir>
set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$HERE")"
APK="app/build/outputs/apk/debug/OpenChat-*-debug.apk"
TIMEOUT=2400
EXTRA=()
BUILD=0
CI=0

while [ $# -gt 0 ]; do
  case "$1" in
    --ci)                  CI=1; shift ;;
    --clean)               EXTRA+=("--clean"); shift ;;
    --build)               BUILD=1; shift ;;
    --timeout)             TIMEOUT="$2"; shift 2 ;;
    --keep-data)           shift ;;   # default behaviour: data kept unless --clean
    --verbose)             EXTRA+=("--verbose"); shift ;;
    --collect-diagnostics) EXTRA+=("--collect-diagnostics"); shift ;;
    --apk)                 APK="$2"; shift 2 ;;
    --serial)              EXTRA+=(--serial "$2"); shift 2 ;;
    --out)                 EXTRA+=(--out "$2"); shift 2 ;;
    *) echo "unknown flag: $1" >&2; exit 2 ;;
  esac
done

if [ "$BUILD" = "1" ]; then
  echo "== building debug APK (x86_64 emulator target) =="
  (cd "$REPO" && ./gradlew assembleDebug --no-daemon) || { echo "gradle assembleDebug failed"; exit 2; }
fi

# ---- environment guards (spec §25: clear local errors, not CI surprises) ----
if ! command -v adb >/dev/null 2>&1 && [ -z "${ANDROID_HOME:-}" ]; then
  echo "ERROR: adb not found and ANDROID_HOME is unset — install the Android SDK or export ANDROID_HOME." >&2
  exit 2
fi
ADB_BIN="${ANDROID_HOME:-}/platform-tools/adb"
command -v adb >/dev/null 2>&1 && ADB_BIN=adb

STATE=$("$ADB_BIN" get-state 2>/dev/null | tr -d '[:space:]')
if [ "$STATE" != "device" ]; then
  echo "ERROR: no emulator/device in 'device' state (adb get-state → '${STATE:-none}')." >&2
  if [ "$CI" = "1" ]; then
    echo "The CI workflow must boot the emulator BEFORE calling this script." >&2
  else
    echo "Start one locally, e.g.:" >&2
    echo "  \$ANDROID_HOME/emulator/emulator -avd <name> -no-window -no-audio -gpu swiftshader_indirect &" >&2
    echo "  \$ANDROID_HOME/platform-tools/adb wait-for-device" >&2
  fi
  exit 2
fi

# Wait for full boot (CI emulators are slow right after launch).
BOOT=$("$ADB_BIN" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')
if [ "$BOOT" != "1" ]; then
  echo "== waiting for sys.boot_completed =="
  for _ in $(seq 1 60); do
    BOOT=$("$ADB_BIN" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')
    [ "$BOOT" = "1" ] && break
    sleep 5
  done
  [ "$BOOT" = "1" ] || { echo "ERROR: emulator did not finish booting."; exit 2; }
fi

"$ADB_BIN" shell input keyevent 224 >/dev/null 2>&1 || true   # WAKEUP
"$ADB_BIN" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB_BIN" shell svc power stayon true >/dev/null 2>&1 || true

echo "== running the Ubuntu E2E driver =="
python3 "$HERE/e2e/e2e_ubuntu.py" --apk "$APK" --timeout "$TIMEOUT" "${EXTRA[@]+"${EXTRA[@]}"}"
