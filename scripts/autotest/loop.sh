#!/usr/bin/env bash
# Autonomous loop: BUILD → INSTALL → TEST → DIAGNOSE → AUTO-FIX → REBUILD → RETEST.
# Runs INSIDE the android-emulator-runner script (emulator stays up across
# attempts; gradle rebuilds happen on the host between test rounds).
#
# usage: loop.sh <mode> <max_actions> <max_seconds> <seed> <max_repair_attempts> <abi>
#
# Repair policy (spec §21): MAX_REPAIR_ATTEMPTS bounds the loop — never infinite.
# A fix is only kept when it comes from the deterministic heuristics (scripts/
# autofix.py) or the optional AI patcher; the failure artifacts stay uploaded
# either way.
set -uo pipefail

MODE="${1:-STANDARD}"
MAX_ACTIONS="${2:-120}"
MAX_SECONDS="${3:-900}"
SEED="${4:-1337}"
MAX_REPAIR="${5:-2}"
ABI="${6:-x86_64}"
OUT="artifacts"
mkdir -p "$OUT/logs"

find_apk() {
  # per-ABI split first, then any signed release APK for the emulator ABI
  ls "release/OpenChat-${ABI}.apk" 2>/dev/null ||
    ls release/*"${ABI}"*.apk 2>/dev/null | head -1 ||
    ls app/build/outputs/apk/release/*"${ABI}"*.apk 2>/dev/null | head -1 || true
}

build_release() {
  local n="$1"
  echo "::group::BUILD release APK (build #$n)"
  ./gradlew --no-daemon --parallel --build-cache assembleRelease
  bash scripts/verify_apks.sh
  echo "::endgroup::"
}

build_release 1
APK="$(find_apk)"
if [ -z "$APK" ]; then
  echo "::error::no ${ABI} release APK found after build"
  exit 1
fi
echo "testing APK: $APK"

attempt=1
gate="FAIL"
max_rounds=$((MAX_REPAIR + 1))
while [ "$attempt" -le "$max_rounds" ]; do
  echo "::group::TEST attempt ${attempt}/${max_rounds} (mode=${MODE} seed=${SEED})"
  if bash scripts/autotest/run.sh "$APK" "$MODE" "$MAX_ACTIONS" "$MAX_SECONDS" "$SEED" "$OUT"; then
    gate="PASS"
    echo "::endgroup::"
    break
  fi
  echo "::endgroup::"

  if [ "$attempt" -gt "$MAX_REPAIR" ]; then
    echo "repair budget exhausted (${MAX_REPAIR})"
    break
  fi

  echo "::group::AUTO-FIX round ${attempt}"
  adb logcat -d > "$OUT/logs/failed-run.log" 2>/dev/null || true
  python3 scripts/autofix.py "$OUT/logs/failed-run.log" || echo "no deterministic fix applied"

  if [ -n "${AI_API_KEY:-}" ]; then
    python3 scripts/autofix_ai.py "$OUT/logs/failed-run.log" || echo "AI-assisted fix skipped/failed (non-fatal)"
  else
    echo "AI_API_KEY not set — heuristic fixes only"
  fi

  if git diff --quiet HEAD 2>/dev/null && [ -z "$(git status --porcelain)" ]; then
    echo "No fix available — stopping the loop."
    echo "::endgroup::"
    break
  fi
  git config user.name "openchat-autotest"
  git config user.email "autotest@users.noreply.github.com"
  git add -A
  git commit -m "fix: autonomous repair from universal-apk-test attempt ${attempt}" || true
  git push origin "HEAD:main" || echo "push failed (non-fatal — fixes kept locally)"
  build_release "$((attempt + 1))"
  APK="$(find_apk)"
  if [ -z "$APK" ]; then
    echo "::error::rebuild produced no APK"
    echo "::endgroup::"
    break
  fi
  attempt=$((attempt + 1))
  echo "::endgroup::"
done

GATE="$(cat "$OUT/reports/gate.txt" 2>/dev/null || echo FAIL)"
echo "FINAL GATE: $GATE"
[ "$gate" = "PASS" ] && [ "$GATE" = "PASS" ]
