#!/usr/bin/env bash
# Autonomous loop: BUILD → INSTALL → TEST → DIAGNOSE → AUTO-FIX → REBUILD → RETEST.
# Runs INSIDE the android-emulator-runner script (emulator stays up across
# attempts; gradle rebuilds happen on the host between test rounds).
#
# usage: loop.sh <mode> <max_actions> <max_seconds> <seed> <max_repair_attempts> <abi>
#
# Repair policy (spec §21, §22): MAX_REPAIR_ATTEMPTS bounds the loop — never
# infinite. BOTH failure classes feed the repair step: compile/build failures
# (gradle log) and runtime test failures (logcat + action trace). A round only
# continues when the repair step actually changed the source tree; fixes are
# committed and pushed so CI re-validates them independently.
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
  local logf="$OUT/logs/build-$n.log"
  echo "::group::BUILD release APK (build #$n)"
  if ./gradlew --no-daemon --parallel --build-cache assembleRelease >"$logf" 2>&1; then
    bash scripts/verify_apks.sh
    echo "::endgroup::"
    return 0
  fi
  tail -n 60 "$logf" || true
  echo "::endgroup::"
  return 1
}

# Feed a failure log to the repair agents; succeeds (0) only when the source tree changed.
repair_from() {
  local logf="$1"
  python3 scripts/autofix.py "$logf" || echo "no deterministic fix applied"
  if [ -n "${AI_API_KEY:-}" ]; then
    python3 scripts/autofix_ai.py "$logf" || echo "AI-assisted fix skipped/failed (non-fatal)"
  else
    echo "AI_API_KEY not set — heuristic fixes only"
  fi
  if git diff --quiet HEAD 2>/dev/null && [ -z "$(git status --porcelain)" ]; then
    echo "repair produced no source change"
    return 1
  fi
  git config user.name "openchat-autotest"
  git config user.email "autotest@users.noreply.github.com"
  git add -A
  git commit -m "fix: autonomous repair from universal-apk-test (${1##*/})" || true
  git push origin "HEAD:main" || echo "push failed (non-fatal — fixes kept locally)"
  return 0
}

gate="FAIL"
attempt=1
max_rounds=$((MAX_REPAIR + 1))
while [ "$attempt" -le "$max_rounds" ]; do
  echo "::group::ROUND ${attempt}/${max_rounds} (mode=${MODE} seed=${SEED})"

  if ! build_release "$attempt"; then
    echo "build failed — entering repair"
    echo "::endgroup::"
    if [ "$attempt" -gt "$MAX_REPAIR" ] || ! repair_from "$OUT/logs/build-$attempt.log"; then
      break
    fi
    attempt=$((attempt + 1))
    continue
  fi

  APK="$(find_apk)"
  if [ -z "$APK" ]; then
    echo "::error::no ${ABI} release APK found after build"
    echo "::endgroup::"
    break
  fi
  echo "testing APK: $APK"

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
  echo "::group::DIAGNOSE + REPAIR from runtime failure"
  adb logcat -d > "$OUT/logs/failed-run.log" 2>/dev/null || true
  ok=0
  repair_from "$OUT/logs/failed-run.log" && ok=1
  echo "::endgroup::"
  if [ "$ok" != "1" ]; then
    break
  fi
  attempt=$((attempt + 1))
done

GATE="$(cat "$OUT/reports/gate.txt" 2>/dev/null || echo FAIL)"
echo "FINAL GATE: $GATE"
[ "$gate" = "PASS" ] && [ "$GATE" = "PASS" ]
