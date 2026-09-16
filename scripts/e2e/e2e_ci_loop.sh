#!/usr/bin/env bash
# CI-side E2E repair loop (spec §17). Runs INSIDE the emulator-runner, which
# executes script inputs line-by-line — hence this file.
#
# Env: MAX_REPAIR (default 2), E2E_TIMEOUT (default 2700), E2E_CLEAN ("true"),
#      E2E_APK (glob), MAX_ATTEMPT_LOG for the rerun count.
# pipefail is MANDATORY: the driver's exit status rides a `| tee` pipeline —
# without it tee's RC (0) masks a failed attempt and the loop reports a false
# PASS without ever running a repair cycle.
set -uo pipefail
MAX="${MAX_REPAIR:-2}"
TIMEOUT="${E2E_TIMEOUT:-2700}"
APK="${E2E_APK:-app/build/outputs/apk/debug/OpenChat-x86_64-debug.apk}"
CLEAN=""
[ "${E2E_CLEAN:-true}" = "true" ] && CLEAN="--clean"

# tee -a target must exist from attempt 0 on, or the log (and autofix input)
# is silently lost.
mkdir -p e2e-artifacts/logs

attempt=0
ok=0
while [ "$attempt" -le "$MAX" ]; do
  echo "== Ubuntu E2E attempt $attempt of $MAX =="
  if bash scripts/e2e-ubuntu.sh --ci $CLEAN --apk "$APK" --timeout "$TIMEOUT" --collect-diagnostics 2>&1 | tee -a e2e-artifacts/logs/e2e-run.log; then
    ok=1
    echo "== Ubuntu E2E PASSED on attempt $attempt =="
    break
  fi
  attempt=$((attempt + 1))
  if [ "$attempt" -gt "$MAX" ]; then
    echo "== Ubuntu E2E still failing after $MAX repair cycles — honest stop =="
    break
  fi
  echo "== E2E attempt $((attempt - 1)) failed — bounded auto-fix =="
  # Deterministic repair first (root-cause patterns from the run log), then an
  # AI-assisted patch pass for the remaining classes of bug. Both are optional
  # by design: a repair that cannot identify a safe minimal fix leaves the
  # loop to the next attempt's genuine retest.
  python3 scripts/autofix.py e2e-artifacts/logs/e2e-run.log || true
  python3 scripts/autofix_ai.py e2e-artifacts/logs/e2e-run.log || true
  ./gradlew assembleDebug --no-daemon --stacktrace || { echo "rebuild failed"; break; }
done

if [ "$ok" != "1" ]; then
  echo "FINAL RESULT: FAIL — see e2e-artifacts/summary.txt and failure-report.md"
  exit 1
fi
