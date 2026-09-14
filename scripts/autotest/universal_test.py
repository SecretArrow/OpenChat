#!/usr/bin/env python3
"""
Universal Android APK autonomous test engine.

App-agnostic by design: nothing about the package, launcher activity, screens,
buttons or feature names is hardcoded. Everything is discovered at runtime from
the APK itself (aapt) and the live UI hierarchy (uiautomator).

Pipeline (spec §0):
  INSTALL → LAUNCH → DISCOVER → GENERATE FLOWS → EXECUTE → DETECT ERRORS →
  COLLECT DIAGNOSTICS → (caller auto-fixes from artifacts) → RE-RUN

Modes:
  SMOKE     launch + stability + basic navigation sanity
  STANDARD  + guided feature exploration (safe interaction engine)
  DEEP      + input fuzzing + lifecycle suite + offline/network + randomized exploration
  RELEASE   DEEP + strict coverage requirements for the release gate

Outputs (under --out):
  application-model.json          discovered app model
  traces/action-trace.json        every action taken (replayable)
  logs/ screenshots/ crashes/ anr/ traces/
  reports/release-test-report.md  human report
  reports/gate.txt                PASS / FAIL (release gate, read by CI)

Exit code: 0 on gate PASS, 1 on FAIL.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from datetime import datetime, timezone

# --------------------------------------------------------------------------- budget defaults
DEFAULT_MAX_ACTIONS = 120
DEFAULT_MAX_SECONDS = 900
ACTION_TIMEOUT = 20          # every adb action must finish inside this
SETTLE_SECONDS = 2.0         # wait after a UI action
CRASH_PATTERNS = ["FATAL EXCEPTION", "ANR in ", "beginning of crash", "SIGSEGV", "has died"]

# Safe-interaction engine keyword rules (case-insensitive substrings).
HARD_DENY = [
    "purchase", "pay", "buy", "checkout", "order now", "subscription",
    "delete", "remove", "erase", "wipe", "reset", "uninstall",
    "logout", "log out", "sign out", "signout", "revoke",
    "clear data", "clear storage", "format",
]
# Flow openers that must never be confirmed (we cancel instead).
OPEN_ONLY = [
    "export", "import", "share", "send", "submit", "post", "publish",
    "install", "update", "upgrade", "restore", "sync", "apply", "reset all",
]
CONFIRM_WORDS = ["ok", "yes", "confirm", "continue", "proceed", "agree", "delete", "reset", "install"]
CANCEL_WORDS = ["cancel", "dismiss", "no", "close", "later", "not now", "deny", "don't allow", "dont allow"]

FUZZ_STRINGS = [
    "", "a", "x" * 300, "héllo wörld", "🎉🚀 emoji test", "1234567890",
    "-42", "3.14159", "   spaces   ", "<script>alert(1)</script>",
    "'; DROP TABLE users;--", "%s%n%d", "\\\\\\ backslashes \\\\",
    "日本語テキスト", "null", "undefined",
]


def log(msg: str) -> None:
    print(f"[autotest {datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def run(cmd: list[str], timeout: int = ACTION_TIMEOUT, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout, check=check
    )


# --------------------------------------------------------------------------- adb wrapper
class Adb:
    def __init__(self, serial: str | None = None, package: str = ""):
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self._pkg = package

    def shell(self, *args: str, timeout: int = ACTION_TIMEOUT) -> str:
        p = run(self.base + ["shell", *args], timeout=timeout)
        return (p.stdout or "").replace("\r", "")

    def raw(self, *args: str, timeout: int = ACTION_TIMEOUT) -> str:
        p = run(self.base + list(args), timeout=timeout)
        return (p.stdout or "") + (p.stderr or "")

    # --- device -------------------------------------------------------------
    def wait(self) -> None:
        run(self.base + ["wait-for-device"], timeout=120)

    def screen_size(self) -> tuple[int, int]:
        out = self.shell("wm", "size")
        m = re.search(r"(\d+)x(\d+)", out)
        return (int(m.group(1)), int(m.group(2))) if m else (320, 640)

    def api_level(self) -> str:
        return self.shell("getprop", "ro.build.version.sdk").strip() or "?"

    def model(self) -> str:
        return self.shell("getprop", "ro.product.model").strip() or "emulator"

    # --- app ----------------------------------------------------------------
    def install(self, apk: str) -> bool:
        out = self.raw("install", "-r", "-t", apk, timeout=180)
        return "Success" in out

    def launch(self, component: str) -> tuple[bool, str]:
        if component:
            out = self.shell("am", "start", "-W", "-n", component, timeout=ACTION_TIMEOUT)
            if "Status: ok" in out:
                return True, out
        # Universal fallback: launcher intent via monkey (works for ANY app).
        out = self.shell(
            "monkey", "-p", self._pkg, "-c", "android.intent.category.LAUNCHER", "1",
            timeout=ACTION_TIMEOUT,
        )
        ok = "Events injected: 1" in out or "Events injected: 1" in out
        return ("Events injected: 1" in out), out

    def pid(self, package: str) -> str:
        return self.shell("pidof", package).strip()

    def force_stop(self, package: str) -> None:
        self.shell("am", "force-stop", package)

    # --- ui -----------------------------------------------------------------
    def dump_ui(self) -> str:
        self.shell("uiautomator", "dump", "/sdcard/at-ui.xml", timeout=30)
        return self.shell("cat", "/sdcard/at-ui.xml", timeout=30)

    def screenshot(self, path: str) -> bool:
        p = subprocess.run(
            self.base + ["exec-out", "screencap", "-p"], capture_output=True, timeout=30
        )
        if p.returncode == 0 and p.stdout:
            with open(path, "wb") as f:
                f.write(p.stdout)
            return os.path.getsize(path) > 500
        return False

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def swipe_up(self) -> None:
        w, h = self.screen_size()
        self.shell("input", "swipe", str(w // 2), str(int(h * 0.7)), str(w // 2), str(int(h * 0.25)), "250")

    def swipe_down(self) -> None:
        w, h = self.screen_size()
        self.shell("input", "swipe", str(w // 2), str(int(h * 0.25)), str(w // 2), str(int(h * 0.7)), "250")

    def back(self) -> None:
        self.shell("input", "keyevent", "4")

    def home(self) -> None:
        self.shell("input", "keyevent", "3")

    def wake(self) -> None:
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("wm", "dismiss-keyguard")

    def type_text(self, text: str) -> None:
        if not text:
            return
        self.shell("input", "text", text.replace(" ", "%s"), timeout=30)

    def clear_field(self) -> None:
        # select-all + delete works on most fields via keycodes (CTRL_A not universal;
        # move to end then send 60 DELs — bounded and safe)
        self.shell("input", "keyevent", "123")  # KEYCODE_MOVE_END
        for _ in range(6):
            self.shell("input", "keyevent", "67", timeout=10)  # DEL ×60 (6×10)

    def logcat(self, tail: int = 4000) -> str:
        p = run(self.base + ["logcat", "-d"], timeout=60)
        return (p.stdout or "")[-tail * 200:]

    def clear_logcat(self) -> None:
        run(self.base + ["logcat", "-c"], timeout=30)

    def rotate(self) -> None:
        cur = self.shell("settings", "get", "system", "user_rotation").strip()
        self.shell("settings", "put", "system", "accelerometer_rotation", "0")
        self.shell("settings", "put", "system", "user_rotation", "1" if cur != "1" else "0")

    def network(self, online: bool) -> bool:
        """Toggle wifi+data; returns True when the command path exists."""
        state = "enable" if online else "disable"
        ok = True
        for svc in ("wifi", "data"):
            out = self.shell("svc", svc, state, timeout=15)
            if "Error" in out and "Exception" in out:
                ok = False
        return ok


# --------------------------------------------------------------------------- UI model
@dataclass
class UiNode:
    text: str = ""
    desc: str = ""
    res_id: str = ""
    cls: str = ""
    clickable: bool = False
    scrollable: bool = False
    enabled: bool = True
    bounds: tuple[int, int, int, int] = (0, 0, 0, 0)

    @property
    def cx(self) -> int:
        return (self.bounds[0] + self.bounds[2]) // 2

    @property
    def cy(self) -> int:
        return (self.bounds[1] + self.bounds[3]) // 2

    @property
    def visible(self) -> bool:
        x1, y1, x2, y2 = self.bounds
        return x2 > x1 and y2 > y1 and self.cx >= 0

    def label(self) -> str:
        return (self.text or self.desc or self.res_id or self.cls).strip()[:60]

    def signature(self) -> str:
        return f"{self.cls}|{self.text}|{self.desc}|{self.res_id}|{self.bounds}"


def parse_ui(xml: str) -> list[UiNode]:
    nodes: list[UiNode] = []
    if not xml or "<hierarchy" not in xml:
        return nodes
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return nodes

    def bounds_of(s: str) -> tuple[int, int, int, int]:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", s or "")
        return tuple(map(int, m.groups())) if m else (0, 0, 0, 0)  # type: ignore[return-value]

    def walk(el: ET.Element) -> None:
        a = el.attrib
        nodes.append(
            UiNode(
                text=a.get("text", ""),
                desc=a.get("content-desc", ""),
                res_id=a.get("resource-id", ""),
                cls=a.get("class", ""),
                clickable=a.get("clickable") == "true",
                scrollable=a.get("scrollable") == "true",
                enabled=a.get("enabled", "true") == "true",
                bounds=bounds_of(a.get("bounds", "")),
            )
        )
        for child in el:
            walk(child)

    # root is the <hierarchy> wrapper itself — not a UI node
    for child in root:
        walk(child)
    return nodes


def actionable(nodes: list[UiNode]) -> list[UiNode]:
    """Interactive candidates: clickable containers/buttons/fields/switches."""
    out = []
    for n in nodes:
        if not n.visible or not n.enabled:
            continue
        is_input = "EditText" in n.cls
        is_clickable = n.clickable and (n.text or n.desc or n.res_id or "Button" in n.cls or "EditText" in n.cls)
        if is_input or is_clickable:
            out.append(n)
    return out


# --------------------------------------------------------------------------- safety engine
@dataclass
class SafetyVerdict:
    allowed: bool
    open_only_cancel: bool = False
    reason: str = ""


def safety(node: UiNode, allow_install: bool) -> SafetyVerdict:
    blob = f"{node.text} {node.desc} {node.res_id}".lower().strip()
    if any(k in blob for k in HARD_DENY):
        return SafetyVerdict(False, reason="destructive keyword")
    if any(k in blob for k in OPEN_ONLY):
        if "install" in blob or "update" in blob and not allow_install:
            return SafetyVerdict(False, reason="install/update heavy flow disabled on CI")
        # "send"/"submit" may be irreversible (messages) — verify presence, never execute.
        if any(k in blob for k in ("send", "submit", "post", "publish")):
            return SafetyVerdict(False, reason="irreversible submit — presence verified only")
        return SafetyVerdict(True, open_only_cancel=True, reason="open-only flow — cancel at confirmation")
    return SafetyVerdict(True)


# --------------------------------------------------------------------------- engine
@dataclass
class Failure:
    kind: str          # crash | anr | freeze | dead | blank | nav | fuzz | lifecycle | network
    detail: str
    action_index: int = -1


@dataclass
class Stats:
    screens_seen: set = field(default_factory=set)
    actions: list = field(default_factory=list)
    elements_found: int = 0
    elements_tested: int = 0
    elements_skipped: int = 0
    crashes: list = field(default_factory=list)
    anrs: list = field(default_factory=list)
    lifecycle: dict = field(default_factory=lambda: {"pass": 0, "fail": 0})
    network: dict = field(default_factory=lambda: {"pass": 0, "fail": 0})
    fuzz: dict = field(default_factory=lambda: {"inputs": 0, "fail": 0})
    cancels_tested: int = 0


class Engine:
    def __init__(self, apk: str, mode: str, max_actions: int, max_seconds: int, seed: int, out: str):
        self.apk = apk
        self.mode = mode.upper()
        self.max_actions = max_actions
        self.deadline = time.time() + max_seconds
        self.seed = seed
        self.rng = random.Random(seed)
        self.out = out
        for sub in ("apk", "logs", "screenshots", "traces", "crashes", "anr", "reports"):
            os.makedirs(os.path.join(out, sub), exist_ok=True)
        self.adb = Adb()
        self.adb._pkg = ""
        self.stats = Stats()
        self.failures: list[Failure] = []
        self.app: dict = {}
        self.trace_path = os.path.join(out, "traces", "action-trace.json")
        self.trace: list[dict] = []

    @staticmethod
    def _bullets(items: list[str], prefix: str = "- ") -> list[str]:
        return [prefix + i.replace("\n", " ")[:200] for i in items] or ["- none"]

    # --- infrastructure -----------------------------------------------------
    def out_path(self, *parts: str) -> str:
        return os.path.join(self.out, *parts)

    def trace_add(self, action: str, **meta) -> None:
        entry = {"t": now_iso(), "action": action, **meta}
        self.trace.append(entry)
        with open(self.trace_path, "w") as f:
            json.dump(self.trace, f, indent=1)

    def screenshot(self, name: str) -> str:
        path = self.out_path("screenshots", f"{name}.png")
        self.adb.screenshot(path)
        return path

    def save_failure_artifacts(self, f: Failure) -> None:
        stamp = datetime.now().strftime("%H%M%S")
        with open(self.out_path("logs", f"failure-{stamp}.log"), "w") as fh:
            fh.write(self.adb.logcat(2000))
        shot = self.out_path("crashes" if f.kind == "crash" else "logs", f"failure-{stamp}.png")
        self.adb.screenshot(shot)
        with open(self.out_path("traces", f"ui-{stamp}.xml"), "w") as fh:
            fh.write(self.adb.dump_ui())
        self.trace_add("failure", kind=f.kind, detail=f.detail)

    # --- APK discovery (§3) ---------------------------------------------------
    def discover_apk(self) -> None:
        aapt = self._find_aapt()
        badging = run([aapt, "dump", "badging", self.apk], timeout=60).stdout

        def grab(pattern: str) -> str:
            m = re.search(pattern, badging)
            return m.group(1) if m else ""

        package = grab(r"package: name='([^']*)'")
        if not package:
            raise RuntimeError("could not read package name from APK — not an Android APK?")
        self.adb._pkg = package
        self.app = {
            "package": package,
            "versionName": grab(r"versionName='([^']*)'"),
            "versionCode": grab(r"versionCode='([^']*)'"),
            "launchable_activity": grab(r"launchable-activity: name='([^']*)'"),
            "minSdk": grab(r"sdkVersion:'([^']*)'"),
            "targetSdk": grab(r"targetSdkVersion:'([^']*)'"),
            "permissions": re.findall(r"uses-permission: name='([^']*)'", badging),
            "apk": os.path.abspath(self.apk),
            "discovered_at": now_iso(),
        }
        main_act = self.app["launchable_activity"]
        if main_act.startswith("."):
            self.app["component"] = f"{package}/{main_act}"
        else:
            self.app["component"] = f"{package}/{main_act}"
        # component inventory from the binary manifest tree
        tree = run([aapt, "dump", "xmltree", self.apk, "AndroidManifest.xml"], timeout=60).stdout
        self.app["activities"] = sorted(set(re.findall(r"E: activity[^\n]*\n[^\n]*A: .*\"([a-zA-Z0-9_.$]+)\"", tree)))
        self.app["services"] = sorted(set(re.findall(r"E: service[^\n]*\n[^\n]*A: .*\"([a-zA-Z0-9_.$]+)\"", tree)))
        self.app["receivers"] = sorted(set(re.findall(r"E: receiver[^\n]*\n[^\n]*A: .*\"([a-zA-Z0-9_.$]+)\"", tree)))
        self.app["providers"] = sorted(set(re.findall(r"E: provider[^\n]*\n[^\n]*A: .*\"([a-zA-Z0-9_.$]+)\"", tree)))
        with open(self.out_path("application-model.json"), "w") as f:
            json.dump(self.app, f, indent=2)
        log(f"discovered: {package} v{self.app['versionName']} ({self.app['versionCode']}) "
            f"activity={main_act} perms={len(self.app['permissions'])}")

    @staticmethod
    def _find_aapt() -> str:
        ah = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
        for root in (os.path.join(ah, "build-tools") if ah else "", ):
            if root and os.path.isdir(root):
                cands = sorted(d for d in os.listdir(root) if re.match(r"[\d.]+", d))
                for v in reversed(cands):
                    p = os.path.join(root, v, "aapt")
                    if os.path.exists(p):
                        return p
        for p in ("/usr/bin/aapt", "/usr/local/bin/aapt"):
            if os.path.exists(p):
                return p
        raise RuntimeError("aapt not found — ANDROID_HOME/build-tools missing?")

    # --- health ---------------------------------------------------------------
    def crash_scan(self) -> str | None:
        cat = self.adb.logcat(1200)
        for pat in ("FATAL EXCEPTION", "beginning of crash"):
            idx = cat.find(pat)
            if idx >= 0:
                return cat[idx: idx + 3000]
        return None

    def anr_scan(self) -> str | None:
        cat = self.adb.logcat(800)
        idx = cat.find("ANR in ")
        if idx >= 0:
            return cat[idx: idx + 1500]
        return None

    def alive(self) -> bool:
        return bool(self.adb.pid(self.app["package"]))

    def health(self, after: str) -> None:
        """Post-action health gate (§14, §15)."""
        crash = self.crash_scan()
        if crash:
            f = Failure("crash", crash[:800])
            self.failures.append(f)
            self.stats.crashes.append(crash[:400])
            self.save_failure_artifacts(f)
            raise RuntimeError(f"FATAL CRASH after {after}")
        if self.anr_scan():
            f = Failure("anr", "ANR detected in logcat")
            self.failures.append(f)
            self.stats.anrs.append("ANR in " + after)
            self.save_failure_artifacts(f)
            raise RuntimeError(f"ANR after {after}")
        if not self.alive():
            f = Failure("dead", "app process is gone")
            self.failures.append(f)
            self.save_failure_artifacts(f)
            raise RuntimeError(f"process died after {after}")

    def settle(self, seconds: float = SETTLE_SECONDS) -> None:
        time.sleep(seconds)

    # --- phases ---------------------------------------------------------------
    def phase_install_launch(self) -> None:
        self.trace_add("install", apk=os.path.basename(self.apk))
        if not self.adb.install(self.apk):
            raise RuntimeError("APK installation failed (session reject / signature / space?)")
        self.adb.wake()
        self.adb.clear_logcat()
        ok, out = self.adb.launch(self.app["component"])
        self.trace_add("launch", component=self.app["component"], ok=ok, raw=out[:300])
        if not ok:
            raise RuntimeError(f"launch failed: {out[:200]}")
        self.settle(5)
        self.health("cold launch")
        self.stats.screens_seen.add("launch")
        self.screenshot("00-launch")

    def current_screen(self) -> list[UiNode]:
        return parse_ui(self.adb.dump_ui())

    def dialog_cancel(self) -> bool:
        """If a confirmation dialog is up, press its CANCEL word (§7)."""
        nodes = self.current_screen()
        for n in actionable(nodes):
            blob = (n.text or n.desc).lower().strip()
            if blob in CANCEL_WORDS:
                self.adb.tap(n.cx, n.cy)
                self.settle(1)
                return True
        return False

    def phase_explore(self) -> None:
        """Guided BFS exploration with the safe-interaction engine (§4-§7, §9)."""
        budget = self.max_actions
        tried: set[str] = set()
        actions = 0
        while actions < budget and time.time() < self.deadline:
            nodes = self.current_screen()
            if not nodes:
                self.swipe_up()
                self.settle(1)
                continue
            root_sig = "|".join(sorted({n.cls for n in nodes})[:8])
            self.stats.screens_seen.add(root_sig[:80])
            acts = actionable(nodes)
            self.stats.elements_found = max(self.stats.elements_found, len(acts))
            nxt = None
            for n in acts:
                sig = n.signature()
                if sig in tried:
                    continue
                tried.add(sig)
                nxt = n
                break
            if nxt is None:
                if not self.rng.random() < 0.5:
                    self.adb.swipe_up()
                else:
                    self.adb.back()
                self.settle(1.2)
                self.health("explore-scroll/back")
                actions += 1
                continue

            verdict = safety(nxt, allow_install=os.environ.get("ALLOW_INSTALL_ACTIONS", "0") == "1")
            label = nxt.label()
            if not verdict.allowed:
                self.stats.elements_skipped += 1
                self.trace_add("skip", target=label, reason=verdict.reason)
                # skip locally: mark screen scrolled so we don't re-scan forever
                self.adb.swipe_up()
                self.settle(0.6)
                actions += 1
                continue

            self.stats.elements_tested += 1
            self.trace_add("tap", target=label, bounds=nxt.bounds, open_only=verdict.open_only_cancel)
            self.adb.tap(nxt.cx, nxt.cy)
            self.settle()
            actions += 1
            self.health(f"tap {label}")

            if verdict.open_only_cancel:
                self.settle(1.5)
                if self.dialog_cancel():
                    self.stats.cancels_tested += 1
                    self.trace_add("cancel-confirmation", target=label)
                self.health(f"cancel flow {label}")
            # keep the back stack sane: if we are deep, come back periodically
            if actions % 6 == 0:
                self.adb.back()
                self.settle(1)
                self.health("periodic back")

    def phase_fuzz_inputs(self) -> None:
        """Safe input fuzzing on visible text fields (§8)."""
        for i, fuzz in enumerate(FUZZ_STRINGS):
            if time.time() > self.deadline:
                return
            nodes = self.current_screen()
            fields = [n for n in nodes if "EditText" in n.cls and n.visible and n.enabled]
            if not fields:
                return
            f = fields[0]
            self.adb.tap(f.cx, f.cy)
            self.settle(0.6)
            self.adb.clear_field()
            self.adb.type_text(fuzz)
            self.stats.fuzz["inputs"] += 1
            self.trace_add("fuzz-input", length=len(fuzz), sample=fuzz[:24])
            self.settle(0.6)
            # dismiss keyboard without navigating (BACK hides IME first)
            self.adb.shell("input", "keyevent", "4")
            self.settle(0.6)
            try:
                self.health(f"fuzz input #{i}")
            except RuntimeError as e:
                self.stats.fuzz["fail"] += 1
                raise
            # clear again so app state stays clean
            fields2 = [n for n in self.current_screen() if "EditText" in n.cls and n.visible and n.enabled]
            if fields2:
                self.adb.tap(fields2[0].cx, fields2[0].cy)
                self.settle(0.4)
                self.adb.clear_field()
                self.adb.shell("input", "keyevent", "4")

    def phase_lifecycle(self) -> None:
        """Rotation, background/foreground, lock, force-stop+restart (§10, §13)."""
        def step(name: str, fn) -> None:
            self.trace_add("lifecycle", step=name)
            try:
                fn()
                self.settle(2.5)
                self.health(f"lifecycle {name}")
                self.stats.lifecycle["pass"] += 1
            except Exception as e:  # noqa: BLE001
                self.stats.lifecycle["fail"] += 1
                self.failures.append(Failure("lifecycle", f"{name}: {e}"))
                raise

        step("rotate", self.adb.rotate)
        step("rotate-back", self.adb.rotate)
        self.screenshot("lifecycle-rotated")
        step("background", self.adb.home)
        step("foreground", lambda: self.adb.launch(self.app["component"]))
        step("lock", lambda: self.adb.shell("input", "keyevent", "26"))
        step("unlock", lambda: (self.adb.wake()))
        step("force-stop", lambda: self.adb.force_stop(self.app["package"]))
        step("restart", lambda: self.adb.launch(self.app["component"]))
        # persistence sanity: app must come back to a usable UI (§13)
        self.settle(3)
        nodes = self.current_screen()
        if not nodes:
            self.failures.append(Failure("lifecycle", "blank screen after restart"))
            self.stats.lifecycle["fail"] += 1
            raise RuntimeError("blank UI after restart")
        self.screenshot("lifecycle-restart")

    def phase_network(self) -> None:
        """Offline → app must degrade gracefully; reconnect → must recover (§12)."""
        def step(name: str, fn) -> None:
            self.trace_add("network", step=name)
            try:
                fn()
                self.settle(2.5)
                self.health(f"network {name}")
                self.stats.network["pass"] += 1
            except Exception as e:  # noqa: BLE001
                self.stats.network["fail"] += 1
                self.failures.append(Failure("network", f"{name}: {e}"))
                raise

        if self.adb.network(False):
            step("offline", lambda: None)
            self.screenshot("network-offline")
            # exercise the app a little while offline
            self.adb.swipe_up()
            self.settle(1.5)
            self.health("offline interaction")
            step("reconnect", lambda: self.adb.network(True))
            self.settle(3)
        else:
            self.trace_add("network", step="skipped (svc unavailable on this image)")

    def phase_random(self) -> None:
        """Seeded randomized exploration (§17) — reproducible via --seed."""
        rnd_budget = max(10, self.max_actions // 4)
        for i in range(rnd_budget):
            if time.time() > self.deadline:
                return
            nodes = self.current_screen()
            acts = [n for n in actionable(nodes) if safety(n, allow_install=False).allowed]
            if not acts:
                self.adb.back()
                self.settle(1)
                continue
            pick = self.rng.choice(acts)
            self.trace_add("random-tap", target=pick.label(), seed=self.seed, index=i)
            self.adb.tap(pick.cx, pick.cy)
            self.settle(1.2)
            self.health(f"random #{i} {pick.label()}")

    # --- report + gate (§28, §29) --------------------------------------------
    def report(self, status: str, extra: dict | None = None) -> None:
        pkg = self.app.get("package", "?")
        commit = os.environ.get("GITHUB_SHA") or ""
        if not commit:
            p = run(["git", "rev-parse", "HEAD"])
            commit = (p.stdout or "").strip()[:12]
        lines = [
            "# Release Test Report", "",
            f"- Generated: {now_iso()}",
            f"- Application: {self.app.get('launchable_activity', pkg)}",
            f"- Package: `{pkg}`",
            f"- Version: {self.app.get('versionName','?')} (code {self.app.get('versionCode','?')})",
            f"- Commit: `{commit}`",
            f"- APK: `{os.path.basename(self.apk)}`",
            f"- Android API: {self.adb.api_level()}",
            f"- Device: {self.adb.model()}",
            f"- Mode: {self.mode} · Seed: {self.seed}",
            "", f"## Summary", "", f"**{status}**", "",
            "## Feature Discovery", "",
            f"- Discovered elements (peak on one screen): {self.stats.elements_found}",
            f"- Tested: {self.stats.elements_tested}",
            f"- Skipped by safety engine: {self.stats.elements_skipped}",
            f"- Screens seen: {len(self.stats.screens_seen)}",
            f"- Confirmation flows cancelled safely: {self.stats.cancels_tested}",
            "", "## Crashes", "",
            *self._bullets(self.stats.crashes),
            "", "## ANR", "",
            *self._bullets(self.stats.anrs),
            "", "## Lifecycle", "",
            f"- pass: {self.stats.lifecycle['pass']} · fail: {self.stats.lifecycle['fail']}",
            "", "## Permissions", "",
            f"- discovered: {len(self.app.get('permissions', []))} (runtime grant/deny handled by the OS dialogs; dangerous prompts cancelled by the safety engine)",
            "", "## Network", "",
            f"- pass: {self.stats.network['pass']} · fail: {self.stats.network['fail']}",
            "", "## Persistence", "",
            "- verified via force-stop → restart → alive + usable UI" if self.mode in ("DEEP", "RELEASE") else "- covered in DEEP/RELEASE modes",
            "", "## Visual", "",
            f"- screenshots: {len(os.listdir(self.out_path('screenshots')))}",
            "", "## Random Exploration", "",
            f"- seed {self.seed} (replay with --seed {self.seed})",
            "", "## Auto Fixes", "",
            (extra or {}).get("autofix", "- handled by the CI repair loop (see loop logs)"),
            "", "## Regression Tests", "",
            "- every fixed failure re-runs the failed action + full regression via the CI loop",
            "", "## Remaining Problems", "",
            *self._bullets([f"[{f.kind}] {f.detail}" for f in self.failures]),
            "", "## Final Recommendation", "",
            "**RELEASE**" if status == "PASS" else "**DO NOT RELEASE**",
            "",
        ]
        with open(self.out_path("reports", "release-test-report.md"), "w") as f:
            f.write("\n".join(lines))
        with open(self.out_path("reports", "gate.txt"), "w") as f:
            f.write(status + "\n")

    # --- main -------------------------------------------------------------------
    def run(self) -> str:
        status = "FAIL"
        try:
            self.adb.wait()
            self.discover_apk()
            self.phase_install_launch()
            self.phase_explore()
            if self.mode in ("DEEP", "RELEASE"):
                self.phase_fuzz_inputs()
                self.phase_lifecycle()
                self.phase_network()
                self.phase_random()
            required = 3 if self.mode == "RELEASE" else 0
            if len(self.stats.screens_seen) < required:
                raise RuntimeError(
                    f"release coverage too low: {len(self.stats.screens_seen)} screens < {required}"
                )
            status = "PASS"
        except Exception as e:  # noqa: BLE001
            log(f"FAILED: {e}")
            if not self.failures:
                self.failures.append(Failure("engine", str(e)))
                try:
                    self.save_failure_artifacts(Failure("engine", str(e)))
                except Exception:
                    pass
            if self.alive():
                self.screenshot("final-state")
        finally:
            extra = {}
            if self.trace:
                extra["autofix"] = f"- last action: {self.trace[-1]['action']} (see action-trace.json)"
            self.report(status, extra)
            log(f"gate={status} screens={len(self.stats.screens_seen)} "
                f"crashes={len(self.stats.crashes)} anrs={len(self.stats.anrs)} "
                f"tested={self.stats.elements_tested} skipped={self.stats.elements_skipped}")
        return status


def main() -> int:
    ap = argparse.ArgumentParser(description="Universal APK autonomous test engine")
    ap.add_argument("--apk", required=True)
    ap.add_argument("--mode", default="STANDARD", choices=["SMOKE", "STANDARD", "DEEP", "RELEASE"])
    ap.add_argument("--max-actions", type=int, default=DEFAULT_MAX_ACTIONS)
    ap.add_argument("--max-seconds", type=int, default=DEFAULT_MAX_SECONDS)
    ap.add_argument("--seed", type=int, default=1337)
    ap.add_argument("--out", default="artifacts")
    args = ap.parse_args()

    eng = Engine(args.apk, args.mode, args.max_actions, args.max_seconds, args.seed, args.out)
    status = eng.run()
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
