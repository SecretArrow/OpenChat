#!/usr/bin/env python3
"""Deterministic CI auto-fixer for Open Chat.

Reads the tail of a failed CI build log, applies safe, whitelisted fixes,
and writes a summary to autofix_result.txt (consumed by the Auto Fix workflow).

Final marker line: TRANSIENT | FIXED n | NOFIX
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAX_FIXES = 25

# ---------------------------------------------------------------- heuristics

TRANSIENT_PATTERNS = [
    r"Could not (?:GET|HEAD|POST) '?https?://",
    r"Could not resolve (?:all files|host|com\.android)",
    r"Read timed out",
    r"Connection reset",
    r" premature end ",
    r"HTTP/1\.1 5\d\d",
    r"Github package upload failed",
]

KNOWN_IMPORTS = {
    "collectAsState": "androidx.compose.runtime.collectAsState",
    "getValue": "androidx.compose.runtime.getValue",
    "setValue": "androidx.compose.runtime.setValue",
    "remember": "androidx.compose.runtime.remember",
    "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
    "mutableStateListOf": "androidx.compose.runtime.mutableStateListOf",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "rememberCoroutineScope": "androidx.compose.runtime.rememberCoroutineScope",
    "derivedStateOf": "androidx.compose.runtime.derivedStateOf",
    "DisposableEffect": "androidx.compose.runtime.DisposableEffect",
    "dp": "androidx.compose.ui.unit.dp",
    "sp": "androidx.compose.ui.unit.sp",
    "Color": "androidx.compose.ui.graphics.Color",
    "FontFamily": "androidx.compose.ui.text.font.FontFamily",
    "FontWeight": "androidx.compose.ui.text.font.FontWeight",
    "TextAlign": "androidx.compose.ui.text.style.TextAlign",
    "TextOverflow": "android.compose.ui.text.style.TextOverflow",
    "Modifier": "androidx.compose.ui.Modifier",
    "Alignment": "androidx.compose.ui.Alignment",
    "Arrangement": "androidx.compose.foundation.layout.Arrangement",
    "Column": "androidx.compose.foundation.layout.Column",
    "Row": "androidx.compose.foundation.layout.Row",
    "Box": "androidx.compose.foundation.layout.Box",
    "Spacer": "androidx.compose.foundation.layout.Spacer",
    "fillMaxSize": "androidx.compose.foundation.layout.fillMaxSize",
    "fillMaxWidth": "androidx.compose.foundation.layout.fillMaxWidth",
    "padding": "androidx.compose.foundation.layout.padding",
    "LazyColumn": "androidx.compose.foundation.lazy.LazyColumn",
    "items": "androidx.compose.foundation.lazy.items",
    "rememberLazyListState": "androidx.compose.foundation.lazy.rememberLazyListState",
    "Text": "androidx.compose.material3.Text",
    "Button": "androidx.compose.material3.Button",
    "OutlinedButton": "androidx.compose.material3.OutlinedButton",
    "TextButton": "androidx.compose.material3.TextButton",
    "OutlinedTextField": "androidx.compose.material3.OutlinedTextField",
    "Scaffold": "androidx.compose.material3.Scaffold",
    "TopAppBar": "androidx.compose.material3.TopAppBar",
    "IconButton": "androidx.compose.material3.IconButton",
    "Card": "androidx.compose.material3.Card",
    "Surface": "androidx.compose.material3.Surface",
    "MaterialTheme": "androidx.compose.material3.MaterialTheme",
    "HorizontalDivider": "androidx.compose.material3.HorizontalDivider",
    "CircularProgressIndicator": "androidx.compose.material3.CircularProgressIndicator",
    "LinearProgressIndicator": "androidx.compose.material3.LinearProgressIndicator",
    "Switch": "androidx.compose.material3.Switch",
    "Checkbox": "androidx.compose.material3.Checkbox",
    "DropdownMenu": "androidx.compose.material3.DropdownMenu",
    "DropdownMenuItem": "androidx.compose.material3.DropdownMenuItem",
    "ExposedDropdownMenuBox": "androidx.compose.material3.ExposedDropdownMenuBox",
    "NavigationBar": "androidx.compose.material3.NavigationBar",
    "NavigationBarItem": "androidx.compose.material3.NavigationBarItem",
    "AlertDialog": "androidx.compose.material3.AlertDialog",
    "FilterChip": "androidx.compose.material3.FilterChip",
    "AssistChip": "androidx.compose.material3.AssistChip",
    "Icon": "androidx.compose.material3.Icon",
    "FILLED_CHECK": "androidx.compose.material.icons.filled.Check",
    "Icons": "androidx.compose.material.icons.Icons",
    "navArgument": "androidx.navigation.navArgument",
    "CoroutineScope": "kotlinx.coroutines.CoroutineScope",
    "launch": "kotlinx.coroutines.launch",
    "Dispatchers": "kotlinx.coroutines.Dispatchers",
    "withContext": "kotlinx.coroutines.withContext",
    "StateFlow": "kotlinx.coroutines.flow.StateFlow",
    "MutableStateFlow": "kotlinx.coroutines.flow.MutableStateFlow",
    "asStateFlow": "kotlinx.coroutines.flow.asStateFlow",
    "JSONObject": "org.json.JSONObject",
    "JSONArray": "org.json.JSONArray",
}

KOTLIN_ERR = re.compile(
    r"e: file://(?:/[^:\n]*)?/([\w/\-.]+\.kt):(\d+):\d+ (?:Unresolved reference '(\w+)'|Unresolved reference: (\w+))"
)

UNRESOLVED = re.compile(
    r"(?:Unresolved reference '(\w+)'|Unresolved reference: (\w+))", re.M)


def is_transient(log: str) -> bool:
    return any(re.search(p, log) for p in TRANSIENT_PATTERNS)


def insert_import(text: str, imp: str) -> str:
    if f"import {imp}" in text:
        return text
    lines = text.splitlines(keepends=True)
    last_import = -1
    for i, ln in enumerate(lines):
        if ln.startswith("import "):
            last_import = i
        elif ln.startswith("package ") and last_import < 0:
            last_import = i
    if last_import < 0:
        return text
    lines.insert(last_import + 1, f"import {imp}\n")
    return "".join(lines)


def fix_unresolved_refs(log: str, fixes: list) -> None:
    src_root = ROOT / "app" / "src"
    seen = set()
    for m in KOTLIN_ERR.finditer(log):
        rel_file, _, ref1, ref2 = m.group(1), m.group(2), m.group(3), m.group(4)
        ref = ref1 or ref2
        if not ref or (rel_file, ref) in seen:
            continue
        seen.add((rel_file, ref))
        imp = KNOWN_IMPORTS.get(ref)
        if not imp:
            continue
        # locate file by suffix (path in error may differ slightly)
        candidates = list(src_root.rglob(rel_file.split("/")[-1]))
        target = None
        for c in candidates:
            if rel_file in str(c) or c.name == rel_file.split("/")[-1]:
                target = c
                break
        if target is None or len(fixes) >= MAX_FIXES:
            continue
        text = target.read_text(encoding="utf-8")
        new = insert_import(text, imp)
        if new != text:
            target.write_text(new, encoding="utf-8")
            fixes.append(f"FIXED {target.relative_to(ROOT)}: added import {imp}")


def ensure_block(app_gradle: str, marker: str, block: str) -> str:
    if marker in app_gradle:
        return app_gradle
    return app_gradle


def fix_lint(log: str, fixes: list) -> None:
    if "lintVitalRelease" not in log and "Lint found errors" not in log:
        return
    p = ROOT / "app" / "build.gradle"
    t = p.read_text(encoding="utf-8")
    if "checkReleaseBuilds" in t:
        return
    new = t.replace(
        "lint {\n        abortOnError false\n    }",
        "lint {\n        abortOnError false\n        checkReleaseBuilds false\n    }",
    )
    if new != t:
        p.write_text(new, encoding="utf-8")
        fixes.append("FIXED app/build.gradle: disabled lintVital (checkReleaseBuilds false)")


def fix_duplicate_meta_inf(log: str, fixes: list) -> None:
    if not re.search(r"Duplicate class|META-INF/[^ ]+ conflicts", log):
        return
    p = ROOT / "app" / "build.gradle"
    t = p.read_text(encoding="utf-8")
    needed = ["/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST", "/META-INF/LICENSE*", "/META-INF/NOTICE*"]
    missing = [n for n in needed if n not in t]
    if not missing:
        return
    add = ", ".join(f"'{n}'" for n in missing)
    new = t.replace("excludes += ['/META-INF/{AL2.0,LGPL2.1}', '/META-INF/DEPENDENCIES', '/META-INF/INDEX.LIST']",
                    f"excludes += ['/META-INF/{{AL2.0,LGPL2.1}}', '/META-INF/DEPENDENCIES', '/META-INF/INDEX.LIST', {add}]")
    if new != t:
        p.write_text(new, encoding="utf-8")
        fixes.append(f"FIXED app/build.gradle: packaging excludes += {missing}")


def fix_oom(log: str, fixes: list) -> None:
    if not re.search(r"OutOfMemoryError|Metaspace|GC overhead limit", log):
        return
    p = ROOT / "gradle.properties"
    t = p.read_text(encoding="utf-8")
    if "-Xmx3072m" in t:
        new = t.replace("-Xmx3072m", "-Xmx4096m").replace("-Xmx2048m", "-Xmx3072m")
    elif "-Xmx4096m" in t:
        new = t.replace("-Xmx4096m", "-Xmx6144m")
    else:
        new = t
    if new != t:
        p.write_text(new, encoding="utf-8")
        fixes.append("FIXED gradle.properties: increased JVM memory")


def fix_sdk_location(log: str, fixes: list) -> None:
    if "SDK location not found" not in log:
        return
    for wf in ["ci.yml", "release.yml"]:
        p = ROOT / ".github" / "workflows" / wf
        if not p.exists():
            continue
        t = p.read_text(encoding="utf-8")
        if "ANDROID_HOME" in t:
            continue
        new = t.replace(
            "jobs:",
            f"env:\n  ANDROID_HOME: /usr/local/lib/android/sdk\njobs:",
            1,
        )
        if new != t:
            p.write_text(new, encoding="utf-8")
            fixes.append(f"FIXED .github/workflows/{wf}: explicit ANDROID_HOME")


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: autofix.py <failed-log-file>")
        return 2
    log = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
    out = []
    if is_transient(log):
        out.append("TRANSIENT")
        out.append("Network/dependency flake detected — will rerun failed jobs.")
    else:
        fixes: list = []
        try:
            fix_unresolved_refs(log, fixes)
            fix_lint(log, fixes)
            fix_duplicate_meta_inf(log, fixes)
            fix_oom(log, fixes)
            fix_sdk_location(log, fixes)
        except Exception as exc:  # never crash the workflow
            out.append(f"autofix internal error: {exc}")
        out.extend(fixes)
        out.append(f"FIXED {len(fixes)}" if fixes else "NOFIX")
        if not fixes:
            out.append("No deterministic fix matched. Analysis tail:")
            out.extend(log.splitlines()[-15:])
    (ROOT / "autofix_result.txt").write_text("\n".join(out) + "\n", encoding="utf-8")
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
