#!/usr/bin/env python3
"""Optional AI-assisted CI fixer.

Requires secrets: AI_API_KEY (+ optional AI_BASE_URL, AI_MODEL).
Sends the failing-log tail to an OpenAI-compatible chat endpoint (e.g. GLM),
asks for a minimal unified diff, validates and applies it with `git apply`.

Safety: refuses diffs > 40KB, refuses paths outside the repo, never touches
.github/workflows/** from AI output.
"""
import json
import os
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAX_DIFF = 40_000
MAX_LOG = 30_000

FORBIDDEN_PREFIXES = (".github/", ".git/", "scripts/")


def main() -> int:
    api_key = os.environ.get("AI_API_KEY", "")
    base = os.environ.get("AI_BASE_URL", "https://api.z.ai/api/paas/v4").rstrip("/")
    model = os.environ.get("AI_MODEL", "glm-4.6")
    if not api_key:
        print("AI_API_KEY not set; skipping.")
        return 0

    log = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")[-MAX_LOG:]

    prompt = f"""You are a senior Android build engineer. The GitHub Actions build for the
'Open Chat' Android app (Kotlin 2.0.20, AGP 8.5.2, Compose BOM 2024.09.03, minSdk 29,
targetSdk 28, compileSdk 34, package com.openchat.android) FAILED.

Respond with ONLY a unified diff (git format) that fixes the build. Rules:
- Minimal, targeted changes. No reformatting, no TODOs, no placeholders.
- Never modify .github/**, scripts/**, or add binary files.
- If the error cannot be fixed by a small diff, respond with NOFIX.

Failed build log (tail):
---
{log}
---
"""

    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content": "You output only valid unified diffs or NOFIX."},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.1,
        "max_tokens": 4096,
    }).encode()

    req = urllib.request.Request(
        f"{base}/chat/completions",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = json.loads(resp.read().decode())
        content = data["choices"][0]["message"]["content"]
    except Exception as exc:
        print(f"AI request failed: {exc}")
        return 0

    m = re.search(r"```(?:diff)?\s*\n(.*?)```", content, re.S)
    diff = m.group(1) if m else content
    diff = diff.strip()
    if "NOFIX" in content and "diff --git" not in diff:
        print("AI responded NOFIX.")
        return 0
    if len(diff) > MAX_DIFF or "diff --git" not in diff:
        print("AI diff rejected (too large or not a diff).")
        return 0

    # safety: check touched paths
    for path_line in re.findall(r"^\+\+\+ b/(.+)$", diff, re.M):
        if any(path_line.startswith(p) for p in FORBIDDEN_PREFIXES):
            print(f"AI diff touches forbidden path: {path_line} — rejected.")
            return 0

    p = ROOT / "autofix_ai.diff"
    p.write_text(diff + "\n", encoding="utf-8")

    check = subprocess.run(["git", "apply", "--check", str(p)], cwd=ROOT, capture_output=True, text=True)
    if check.returncode != 0:
        print(f"AI diff does not apply cleanly:\n{check.stderr[:2000]}")
        p.unlink(missing_ok=True)
        return 0
    apply = subprocess.run(["git", "apply", str(p)], cwd=ROOT, capture_output=True, text=True)
    if apply.returncode == 0:
        print("AI diff applied successfully.")
        (ROOT / "autofix_result.txt").open("a").write("AI_PATCH_APPLIED\n")
    else:
        print(f"git apply failed: {apply.stderr[:2000]}")
    p.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
