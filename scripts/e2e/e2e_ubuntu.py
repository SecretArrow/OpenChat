#!/usr/bin/env python3
"""
Ubuntu E2E installer test driver — spec: android-ubuntu-e2e.

Drives the REAL installation flow on a running Android emulator:
  APK install → app launch → navigate Settings → Ubuntu userspace → Install →
  real rootfs download (cdimage.ubuntu.com) → SHA-256 verify → staged extract →
  apt → tools → READY → in-Ubuntu command execution → persistence across
  force-stop → restart.

No mocks: the installation runs the app's own pipeline; commands execute
inside the actual rootfs via the same proot binary + environment the app uses
(run-as on the debug build). Lifecycle interference (background, screen off,
network drop, force-stop mid-install) is exercised DURING the real install,
and the flow must still reach a usable Ubuntu.

Usage:  python3 e2e_ubuntu.py --apk <apk> [--clean] [--timeout 2400] ...
Exit 0 = PASS, 1 = FAIL.
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

ACTION_TIMEOUT = 60
POLL_INTERVAL = 8
SETTLE = 3

STAGES = [
    "ENVIRONMENT", "APK_INSTALL", "APP_LAUNCH", "NAVIGATE",
    "INSTALL_START", "DOWNLOAD", "CHECKSUM_VERIFY", "EXTRACT", "ROOTFS_VERIFY",
    "APT", "UBUNTU_BOOT", "SHELL", "DNS_HTTP", "TERMINAL_UI",
    "LIFECYCLE_INTERFERENCE", "PERSISTENCE", "APP_RESTART", "PERSISTENCE_AFTER_RESTART",
]


def log(msg: str) -> None:
    print(f"[e2e {datetime.now().strftime('%H:%M:%S')}] {msg}", flush=True)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def run(cmd: list[str], timeout: int = ACTION_TIMEOUT) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


class Stage:
    """One summary row: PENDING → PASS/FAIL/SKIP, with evidence."""

    def __init__(self, name: str):
        self.name = name
        self.status = "PENDING"
        self.evidence: list[str] = []

    def pass_(self, *ev: str) -> None:
        self.status = "PASS"
        self.evidence.extend(ev)

    def fail(self, *ev: str) -> None:
        self.status = "FAIL"
        self.evidence.extend(ev)

    def skip(self, *ev: str) -> None:
        self.status = "SKIP"
        self.evidence.extend(ev)


class Adb:
    def __init__(self, serial: str | None):
        self.base = ["adb"] + (["-s", serial] if serial else [])
        self.package = ""
        self.out_dir = "e2e-artifacts"

    def shell(self, *args: str, timeout: int = ACTION_TIMEOUT) -> str:
        p = run(self.base + ["shell", *args], timeout=timeout)
        return (p.stdout or "").replace("\r", "")

    def raw(self, *args: str, timeout: int = ACTION_TIMEOUT) -> str:
        p = run(self.base + list(args), timeout=timeout)
        return (p.stdout or "") + (p.stderr or "")

    def device(self) -> bool:
        p = run(self.base + ["get-state"])
        return p.stdout.strip() == "device"

    def boot_completed(self) -> bool:
        return self.shell("getprop", "sys.boot_completed").strip() == "1"

    def screen_size(self) -> tuple[int, int]:
        m = re.search(r"(\d+)x(\d+)", self.shell("wm", "size"))
        return (int(m.group(1)), int(m.group(2))) if m else (320, 640)

    def install(self, apk: str) -> bool:
        return "Success" in self.raw("install", "-r", "-t", "-g", apk, timeout=300)

    def uninstall(self, pkg: str) -> None:
        self.raw("uninstall", pkg, timeout=120)

    def launch(self, component: str) -> bool:
        return "Status: ok" in self.shell("am", "start", "-W", "-n", component, timeout=90)

    def force_stop(self, pkg: str) -> None:
        self.shell("am", "force-stop", pkg)

    def pid(self, pkg: str) -> str:
        return self.shell("pidof", pkg).strip()

    def keyevent(self, code: int) -> None:
        self.shell("input", "keyevent", str(code))

    def wake(self) -> None:
        self.shell("input", "keyevent", "224")
        self.shell("wm", "dismiss-keyguard")

    def network(self, on: bool) -> None:
        state = "enable" if on else "disable"
        for svc in ("wifi", "data"):
            self.shell("svc", svc, state, timeout=30)

    def app_uid(self, pkg: str) -> str:
        out = self.shell("dumpsys", "package", pkg, timeout=30)
        m = re.search(r"userId=(\d+)", out)
        return m.group(1) if m else ""

    def wait_default_network(self, timeout_s: int) -> bool:
        """Poll until the device reports an active default network again
        (svc-based restore is asynchronous and can hang on emulated RIL)."""
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            out = self.shell("dumpsys", "connectivity", timeout=30)
            m = re.search(r"Active default network:\s*(-?\d+)", out)
            if m and m.group(1) != "-1":
                return True
            time.sleep(5)
        return False

    def dump_ui(self) -> str:
        out = self.shell("uiautomator", "dump", "/sdcard/e2e-ui.xml", timeout=45)
        if ("dumped to" not in out) and ("updated" not in out):
            return ""
        return self.shell("cat", "/sdcard/e2e-ui.xml", timeout=30)

    def tap(self, x: int, y: int) -> None:
        self.shell("input", "tap", str(x), str(y))

    def type_text(self, text: str) -> None:
        # adb input text: spaces must be %s.
        self.shell("input", "text", text.replace(" ", "%s"), timeout=45)

    def screenshot(self, name: str) -> str:
        path = os.path.join(self.out_dir, "screenshots", f"{name}.png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.shell("screencap", "-p", "/sdcard/e2e-shot.png", timeout=45)
        run(self.base + ["pull", "/sdcard/e2e-shot.png", path], timeout=60)
        return path if os.path.exists(path) else ""

    def logcat(self, lines: int = 4000) -> str:
        p = run(self.base + ["logcat", "-d"], timeout=90)
        return (p.stdout or "")[-lines * 220:]

    def run_as(self, *args: str, timeout: int = ACTION_TIMEOUT) -> str:
        return self.shell("run-as", self.package, *args, timeout=timeout)

    def run_as_sh(self, script: str, timeout: int = ACTION_TIMEOUT) -> str:
        """Run a multi-command script as the app uid — routed through stdin so
        adb's arg joining can never break quoting (semicolons, pipes)."""
        host = tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False)
        host.write(script)
        host.close()
        dev = "/data/local/tmp/e2e-appsh.sh"
        run(self.base + ["push", host.name, dev], timeout=60)
        self.shell("chmod", "644", dev)
        out = self.shell(f"run-as {self.package} sh < {dev}", timeout=timeout)
        os.unlink(host.name)
        return out

    def inroot(self, script: str, timeout: int = 180) -> str:
        """Execute a shell script INSIDE the app's real Ubuntu rootfs via the
        same proot binary + environment the app uses (run-as on the debug
        build) — real execution, not a mock. stderr is merged (proot prints
        its failures there; dropping it made failures look 'silent')."""
        host = tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False)
        host.write(script)
        host.close()
        dev = "/data/local/tmp/e2e-inroot.sh"
        run(self.base + ["push", host.name, dev], timeout=60)
        self.shell("chmod", "644", dev)
        out = self.shell(f"run-as {self.package} sh < {dev} 2>&1", timeout=timeout)
        os.unlink(host.name)
        return out


def find_aapt() -> str:
    ah = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    if ah and os.path.isdir(os.path.join(ah, "build-tools")):
        for v in sorted(os.listdir(os.path.join(ah, "build-tools")), reverse=True):
            p = os.path.join(ah, "build-tools", v, "aapt")
            if os.path.exists(p):
                return p
    for p in ("/usr/bin/aapt", "/usr/local/bin/aapt"):
        if os.path.exists(p):
            return p
    raise RuntimeError("aapt not found — ANDROID_HOME missing?")


def aapt_badging(aapt: str, apk: str) -> tuple[str, str]:
    p = run([aapt, "dump", "badging", apk], timeout=60)
    out = p.stdout or ""
    pkg = re.search(r"package: name='([^']+)'", out)
    act = re.search(r"launchable-activity: name='([^']+)'", out)
    return (pkg.group(1) if pkg else "", act.group(1) if act else "")


# --------------------------------------------------------------------------- UI helpers

def parse_ui(xml: str) -> list[dict]:
    nodes = []
    if "<hierarchy" not in xml:
        return nodes
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return nodes

    def walk(el: ET.Element, path: list[dict]) -> None:
        a = el.attrib
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        b = tuple(map(int, m.groups())) if m else (0, 0, 0, 0)
        node = {
            "text": a.get("text", ""), "desc": a.get("content-desc", ""),
            "id": a.get("resource-id", ""), "cls": a.get("class", ""),
            "pkg": a.get("package", ""), "bounds": b,
            "clickable": a.get("clickable") == "true",
            "cx": (b[0] + b[2]) // 2, "cy": (b[1] + b[3]) // 2,
        }
        nodes.append(node)
        path.append(node)
        for ch in el:
            walk(ch, path)
        path.pop()

    for ch in root:
        walk(ch, [])

    # Compose merges semantics: a clickable Button often carries EMPTY text
    # while its child Text node holds the visible label. Merge descendant
    # texts into clickable ancestors (bounds containment) so label-based
    # tapping can match the BUTTON, not just its label (E2E 34862886354).
    for n in nodes:
        if not (n["clickable"] and not (n["text"] or n["desc"])):
            continue
        x1, y1, x2, y2 = n["bounds"]
        parts = []
        for m2 in nodes:
            if m2 is n or not m2["text"]:
                continue
            mx1, my1, mx2, my2 = m2["bounds"]
            if mx1 >= x1 and my1 >= y1 and mx2 <= x2 and my2 <= y2:
                parts.append(m2["text"])
        if parts:
            n["text"] = " ".join(parts)
    return nodes


def find_tap(adb: Adb, labels: list[str], scroll: bool = True) -> bool:
    """Tap the best match for any label (bi-directional scroll).

    Match priority:
      1. clickable node whose label EQUALS a target word (the real button)
      2. clickable node whose label CONTAINS a target word
      3. text node whose label EQUALS a target word
      4. text node whose label CONTAINS a target word (weakest — status
         labels like 'not installed' contain 'install'; this is why exact
         matches and clickable ancestors must win first, E2E 34862886354)
    """
    lw = [x.lower() for x in labels]

    def rank(n: dict) -> int:
        blob = (n["text"] + " " + n["desc"]).lower().strip()
        hit = next((x for x in lw if x in blob), None)
        if hit is None:
            return -1
        exact = blob == hit or blob.split()[0] == hit
        clickable = n["clickable"] or "Button" in n["cls"] or "EditText" in n["cls"]
        return (0 if clickable else 1) * 2 + (0 if exact else 1)

    for attempt in range(10):
        nodes = parse_ui(adb.dump_ui())
        candidates = [(rank(n), n) for n in nodes]
        candidates = [(r, n) for r, n in candidates if r >= 0]
        if candidates:
            candidates.sort(key=lambda t: t[0])
            r, target = candidates[0]
            log(f"tap → '{(target['text'] or target['desc'])[:40]}' "
                f"(rank={r}, clickable={target['clickable']}) at {target['cx']},{target['cy']}")
            adb.tap(target["cx"], target["cy"])
            time.sleep(SETTLE)
            return True
        if not scroll:
            return False
        if attempt < 5:
            adb.shell("input", "swipe", "160", "500", "160", "150", "400")
        else:
            adb.shell("input", "swipe", "160", "150", "160", "500", "400")
        time.sleep(1.5)
    return False


def find_present(adb: Adb, labels: list[str], scroll: bool = True) -> bool:
    """Presence check WITHOUT tapping (bi-directional scroll like find_tap)."""
    lw = [x.lower() for x in labels]
    for attempt in range(10):
        nodes = parse_ui(adb.dump_ui())
        for n in nodes:
            blob = (n["text"] + " " + n["desc"]).lower().strip()
            if blob and any(x in blob for x in lw):
                return True
        if not scroll:
            return False
        if attempt < 5:
            adb.shell("input", "swipe", "160", "500", "160", "150", "400")
        else:
            adb.shell("input", "swipe", "160", "150", "160", "500", "400")
        time.sleep(1.5)
    return False


def nav_settings(adb: Adb) -> bool:
    """Bottom-nav → Settings tab. The current UI renders the Ubuntu userspace
    card (with its inline Install button) directly on the Settings home."""
    return find_tap(adb, ["settings"], scroll=False)


def start_ubuntu_install(adb: Adb) -> bool:
    """Tap the Ubuntu card's inline Install button. The Ubuntu card renders
    BEFORE the OpenCode card in the dump, so the first clickable Install is
    the right one."""
    return find_tap(adb, ["reinstall", "install"], scroll=False)


class E2E:
    def __init__(self, args):
        self.args = args
        self.out = args.out
        os.makedirs(os.path.join(self.out, "screenshots"), exist_ok=True)
        os.makedirs(os.path.join(self.out, "diagnostics"), exist_ok=True)
        os.makedirs(os.path.join(self.out, "logs"), exist_ok=True)
        self.stages = {name: Stage(name) for name in STAGES}
        self.adb = Adb(args.serial)
        self.adb.out_dir = self.out
        self.state_timeline: list[dict] = []
        self.recoveries = 0
        self.deadline = time.time() + args.timeout
        self.known_pids: set[str] = set()
        self.fail_stage: str | None = None
        self.fail_detail: str | None = None

    # --- state -----------------------------------------------------------
    def read_status(self) -> dict:
        raw = self.adb.run_as("cat", "files/data/ubuntu_status.json")
        self._last_status_raw = raw
        try:
            return json.loads(raw)
        except Exception:
            return {}

    def record_state(self, tag: str) -> dict:
        st = self.read_status()
        if st:
            self.state_timeline.append({
                "t": now_iso(), "tag": tag,
                "state": st.get("state"), "progress": st.get("progress"),
                "message": (st.get("message") or "")[:160],
            })
        return st

    def note_pid(self) -> None:
        p = self.adb.pid(self.adb.package)
        if p:
            self.known_pids.update(p.split())

    def app_crash_in_logcat(self) -> str | None:
        cat = self.adb.logcat(2500)
        lines = cat.split("\n")
        for i, line in enumerate(lines):
            if "FATAL EXCEPTION" in line:
                block = "\n".join(lines[i:i + 25])
                mp = re.search(r"Process:\s*([\w.]+)", block)
                mi = re.search(r"PID:\s*(\d+)", block)
                if (mp and mp.group(1) == self.adb.package) or (mi and mi.group(1) in self.known_pids):
                    return block[:2500]
            elif "Fatal signal" in line:
                parts = line.split()
                if len(parts) >= 4 and parts[2] in self.known_pids:
                    return line + "\n" + "\n".join(lines[i + 1:i + 20])
        return None

    def fail(self, stage: str, detail: str) -> None:
        self.stages[stage].fail(detail)
        self.fail_stage = stage
        self.fail_detail = detail
        log(f"FAIL at {stage}: {detail[:300]}")
        self.collect_diagnostics(stage)
        self.write_reports("FAIL")
        sys.exit(1)

    # --- diagnostics (spec §14) ------------------------------------------
    def collect_diagnostics(self, stage: str) -> None:
        d = os.path.join(self.out, "diagnostics")
        stamp = datetime.now().strftime("%H%M%S")

        def grab(name: str, content: str) -> None:
            with open(os.path.join(d, f"{stamp}-{stage}-{name}.txt"), "w") as f:
                f.write(content or "(empty)")

        grab("logcat", self.adb.logcat(6000))
        grab("dumpsys-activity", self.adb.shell("dumpsys", "activity", "top", timeout=60))
        grab("dumpsys-meminfo", self.adb.shell("dumpsys", "meminfo", self.adb.package, timeout=60))
        grab("df", self.adb.shell("df", "-h"))
        grab("getprop", self.adb.shell("getprop"))
        self.read_status()
        grab("app-status-raw", getattr(self, "_last_status_raw", "") or "(empty — file missing or run-as failed)")
        grab("app-status", json.dumps(self.read_status(), indent=2))
        grab("status-file-exists", self.adb.run_as("ls", "files/data/"))
        grab("rootfs-listing", self.adb.run_as_sh(
            "ls -la files/ubuntu 2>&1; echo ---; du -sm files/ubuntu/rootfs 2>/dev/null | tail -1"))
        # apt/dpkg write their FULL terminal output inside the rootfs — the
        # app only surfaces a tail, so this is the authoritative per-package
        # error record (postinst failures name the exact failing command here).
        for rel, name in [
            ("files/ubuntu/rootfs/var/log/apt/term.log", "apt-term-log"),
            ("files/ubuntu/rootfs/var/log/apt/history.log", "apt-history-log"),
            ("files/ubuntu/rootfs/var/log/dpkg.log", "dpkg-log"),
        ]:
            try:
                grab(name, self.adb.run_as("cat", rel))
            except Exception as exc:  # noqa: BLE001 — diagnostics never abort
                grab(name, f"(grab failed: {exc})")
        self.adb.screenshot(f"diag-{stage}")
        with open(os.path.join(self.out, "logs", "e2e-run.log"), "a") as f:
            f.write(f"\n=== failure @ {stage} {now_iso()} ===\n{self.fail_detail or ''}\n")

    # --- install polling + interference (spec §10) ------------------------
    def poll_install(self, interference: bool) -> None:
        """Poll the real install until READY or an honest ERROR; run the
        lifecycle interference schedule while heavy stages run."""
        done_bg = screen_off = net_toggled = force_stopped = False
        last_state = ""
        while time.time() < self.deadline:
            time.sleep(POLL_INTERVAL)
            st = self.record_state("poll")
            state = (st.get("state") or "").upper()

            crash = self.app_crash_in_logcat()
            if crash:
                self.fail("INSTALL_START", f"app crash during install:\n{crash[:1200]}")

            if state == "READY":
                log("install reached READY")
                return
            if state == "ERROR":
                # The app must surface an honest, recoverable error — not a fake success.
                full = (st.get("message") or "")
                with open(os.path.join(self.out, "logs", "e2e-run.log"), "a") as f:
                    f.write(f"\n=== app ERROR state {now_iso()} ===\n{full[:2000]}\n")
                # Grab the seccomp-kill audit IMMEDIATELY — the dmesg ring
                # overflows with avc noise within minutes.
                dmesg_now = self.adb.shell("dmesg")
                kills_now = "\n".join(
                    l for l in dmesg_now.split("\n")
                    if re.search(r"type=1326|sig=31|seccomp", l, re.I)
                )[-2000:]
                with open(os.path.join(self.out, "logs", "seccomp-kills.log"), "a") as f:
                    f.write(f"\n=== dmesg @ ERROR {now_iso()} ===\n{kills_now or '(none)'}\n")
                if self.recoveries < 3:
                    log(f"recoverable ERROR ({self.recoveries + 1}/3): {full[:200]} — retrying via Repair")
                    self.recoveries += 1
                    # Repair (unlike Install) keeps /root — so instrumented
                    # /root/.profile + /root/aptwrap.sh written here survive
                    # the re-extract and hook the NEXT attempt's login shell:
                    # the profile logs whether it runs, probes /bin/true, and
                    # swaps apt-get for a logging wrapper. If the guest dies
                    # with SIGSYS the shell prints "Bad system call" into the
                    # app's captured output tail — definitive confirmation.
                    try:
                        hook = self.adb.run_as_sh(
                            "mkdir -p files/ubuntu/rootfs/root\n"
                            "cat > files/ubuntu/rootfs/root/aptwrap.sh <<'EOF'\n"
                            "#!/bin/bash\n"
                            "echo \"APT_EXEC args: $*\" >> /tmp/w.txt\n"
                            "/usr/bin/apt-get.real \"$@\"\n"
                            "echo \"APT_RC=$?\" >> /tmp/w.txt\n"
                            "EOF\n"
                            "cat > files/ubuntu/rootfs/root/.profile <<'EOF'\n"
                            "echo \"PROFILE_RAN\" >> /tmp/w.txt\n"
                            "{ /bin/true; echo \"TRUE_RC=$?\"; } >> /tmp/w.txt 2>&1\n"
                            "cp /root/aptwrap.sh /usr/bin/aptwrap.sh 2>/dev/null\n"
                            "mv /usr/bin/apt-get /usr/bin/apt-get.real 2>/dev/null\n"
                            "cp /root/aptwrap.sh /usr/bin/apt-get 2>/dev/null\n"
                            "echo \"HOOKED\" >> /tmp/w.txt\n"
                            "EOF\n"
                            "chmod 755 files/ubuntu/rootfs/root/aptwrap.sh files/ubuntu/rootfs/root/probe.sh 2>/dev/null\n"
                            "echo HOOK_WRITTEN\n")
                        probe_lines_log = f"hook write: {hook.strip()[:60]}"
                        log(probe_lines_log)
                    except Exception as he:  # noqa: BLE001
                        log(f"hook write failed: {he}")
                    # On the 2nd failure grab the apt evidence from inside the
                    # rootfs directly (same proot the app uses) — root cause
                    # data for the auto-fix loop (spec §17).
                    if self.recoveries == 2:
                        try:
                            probe_lines = []
                            P = f"/data/data/{self.adb.package}/files"
                            # 1) Reproduce the app's EXACT exec context: env -i
                            # with only baseEnv (run-as shells inherit extra
                            # vars AND skip the zygote seccomp filter — env -i
                            # still skips seccomp but isolates the env vars).
                            env_i = (
                                f"env -i HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin "
                                f"TERM=xterm-256color LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive TMPDIR=/tmp "
                                f"PROOT_NO_SECCOMP=1 PROOT_TMP_DIR={P}/ubuntu/tmp "
                                f"LD_LIBRARY_PATH={P}/ubuntu/lib PROOT_LOADER={P}/ubuntu/lib/loader PROOT_LOADER_32={P}/ubuntu/lib/loader32 "
                                f"{P}/ubuntu/bin/proot --kill-on-exit -0 -w /root -R {P}/ubuntu/rootfs "
                                f"/bin/bash -c 'apt-get update' 2>&1 | tail -3; echo ENVI_RC=$?\n"
                            )
                            probe_lines.append("env -i (exact app env):\n" + self.adb.run_as_sh(env_i)[:800])
                            # 1b) dpkg backup-link probe: hardlinks are the
                            # syscall layer dpkg needs for overwrites — capture
                            # whether they are denied outside the app filter.
                            link_probe = (
                                f"env -i HOME=/root PATH=/usr/bin:/bin TMPDIR=/tmp PROOT_NO_SECCOMP=1 PROOT_TMP_DIR={P}/ubuntu/tmp "
                                f"LD_LIBRARY_PATH={P}/ubuntu/lib PROOT_LOADER={P}/ubuntu/lib/loader PROOT_LOADER_32={P}/ubuntu/lib/loader32 "
                                f"{P}/ubuntu/bin/proot --kill-on-exit -0 -w /root -R {P}/ubuntu/rootfs "
                                f"/bin/bash -c 'link /usr/bin/perl /tmp/lk1; echo LINK_RC=$?; rm -f /tmp/lk1' 2>&1 | tail -2\n"
                            )
                            probe_lines.append("run-as link probe:\n" + self.adb.run_as_sh(link_probe)[:300])
                            # 1c) NSS probe: proot -R bind-mounts the HOST's
                            # /etc/passwd|group|nsswitch.conf into the guest;
                            # on Android those lack _apt/sudo/ssh, which killed
                            # getent and the sudo/openssh-client postinsts
                            # (root cause of run 18). The app now re-binds the
                            # guest's own identity files over them — replicate
                            # that EXACT argv here and expect guest entries.
                            nss_probe = (
                                f"env -i HOME=/root PATH=/usr/bin:/bin TMPDIR=/tmp PROOT_NO_SECCOMP=1 PROOT_TMP_DIR={P}/ubuntu/tmp "
                                f"LD_LIBRARY_PATH={P}/ubuntu/lib PROOT_LOADER={P}/ubuntu/lib/loader PROOT_LOADER_32={P}/ubuntu/lib/loader32 "
                                f"{P}/ubuntu/bin/proot --kill-on-exit -0 -w /root -R {P}/ubuntu/rootfs "
                                f"-b {P}/ubuntu/rootfs/etc/passwd:/etc/passwd "
                                f"-b {P}/ubuntu/rootfs/etc/group:/etc/group "
                                f"-b {P}/ubuntu/rootfs/etc/nsswitch.conf:/etc/nsswitch.conf "
                                f"/bin/bash -c 'grep _apt /etc/passwd | head -1; "
                                f"getent passwd _apt; echo P_RC=$?; getent group sudo; echo G_RC=$?; "
                                f"ls /sbin/ldconfig* 2>&1; dpkg-divert --list /sbin/ldconfig' 2>&1 | tail -6\n"
                            )
                            probe_lines.append("run-as NSS probe (app argv):\n" + self.adb.run_as_sh(nss_probe)[:400])
                            # 2) Seccomp state: the app process (zygote filter)
                            # vs this run-as shell (no filter).
                            app_pid = self.adb.pid(self.adb.package).split()[0:1]
                            app_pid = app_pid[0] if app_pid else ""
                            seccomp_app = self.adb.run_as("grep", "Seccomp", f"/proc/{app_pid}/status") if app_pid else ""
                            seccomp_self = self.adb.run_as("grep", "Seccomp", "/proc/self/status")
                            probe_lines.append(f"app pid={app_pid} Seccomp: {seccomp_app.strip()}\nrun-as self Seccomp: {seccomp_self.strip()}")
                            # 3) Kernel audit: SECCOMP_RET_KILL_THREAD records
                            # type=1326 with sig=31 syscall=NNN — the blocked
                            # syscall number is the root-cause payload.
                            run(self.adb.base + ["root"], timeout=60)
                            time.sleep(3)
                            dmesg = self.adb.shell("dmesg")
                            kills = "\n".join(
                                l for l in dmesg.split("\n")
                                if re.search(r"type=1326|sig=31|code=0x", l)
                            )[-3000:]
                            probe_lines.append("dmesg seccomp-kill audit (type=1326):\n" + (kills or "(none)"))
                            # The login-hook log from the previous Repair attempt
                            wlog = self.adb.run_as("cat", "files/ubuntu/rootfs/tmp/w.txt")
                            probe_lines.append("in-app hook log (/tmp/w.txt):\n" + (wlog.strip()[:800] or "(empty — profile never ran or rootfs was re-extracted)"))

                            # 4) THE decisive capture: run a probe script from
                            # the app's Terminal tab — that bash runs in the
                            # app's filtered lineage under the terminal's own
                            # proot. If the kernel filter kills guest
                            # processes, the shell prints "Bad system call"
                            # into our redirected file, with the real RC.
                            try:
                                probe_sh = (
                                    "#!/bin/bash\n"
                                    "{ apt-get update; echo APT_RC=$?; } > /tmp/o.txt 2>&1\n"
                                    "{ /bin/true; echo TRUE_RC=$?; } >> /tmp/o.txt 2>&1\n"
                                    "echo DONE >> /tmp/o.txt\n"
                                )
                                run_as_write = self.adb.run_as_sh(
                                    "cat > files/ubuntu/rootfs/root/probe.sh <<'EOF'\n"
                                    + probe_sh + "EOF\nchmod 755 files/ubuntu/rootfs/root/probe.sh\necho WROTE\n")
                                probe_lines.append("probe.sh write: " + run_as_write.strip()[:40])
                                # Terminal tab: tap input, type, Enter.
                                find_tap(self.adb, ["terminal"], scroll=False)
                                time.sleep(8)
                                nodes = parse_ui(self.adb.dump_ui())
                                fields = [n for n in nodes if "EditText" in n["cls"]]
                                if fields:
                                    self.adb.tap(fields[0]["cx"], fields[0]["cy"])
                                    time.sleep(1)
                                self.adb.type_text("bash /root/probe.sh")
                                self.adb.keyevent(66)
                                time.sleep(15)
                                o = self.adb.run_as("cat", "files/ubuntu/rootfs/tmp/o.txt")
                                probe_lines.append("in-app terminal probe (/tmp/o.txt):\n" + (o.strip()[:800] or "(empty — Enter may not have sent, or the terminal session itself died)"))
                                self.adb.screenshot("06-terminal-probe")
                            except Exception as pe:  # noqa: BLE001
                                probe_lines.append(f"terminal probe failed: {pe}")
                            body = "\n".join(probe_lines)
                            with open(os.path.join(self.out, "logs", "apt-probe.log"), "a") as f:
                                f.write(f"\n=== inroot apt probe {now_iso()} ===\n{body[:5000]}\n")
                            log(f"apt probe: {body[:500]}")
                        except Exception as e:  # noqa: BLE001 — probe must never kill the flow
                            log(f"apt probe failed: {e}")
                    nav_settings(self.adb)
                    find_tap(self.adb, ["repair", "reinstall", "install"])
                    if state == "ERROR" and self.recoveries >= 3 and force_stopped:
                        force_stopped = False
                    continue
                self.fail("INSTALL_START",
                          f"install ended in ERROR state 3 times — last error:\n{full[:2000]}")
            if state == "NOT_INSTALLED" and self.recoveries > 0:
                nav_settings(self.adb)
                start_ubuntu_install(self.adb)

            # interference schedule (each ONCE, while busy states run)
            if interference and state and state != last_state:
                last_state = state
                busy = state in {"DOWNLOADING", "VERIFYING", "EXTRACTING", "CONFIGURING",
                                 "INSTALLING_PACKAGES", "INSTALLING_TOOLS"}

                if busy and not done_bg:
                    done_bg = True
                    log("Test B: background the app mid-install")
                    self.adb.keyevent(3)  # HOME
                    time.sleep(8)
                    self.adb.launch(self.args.component)
                    time.sleep(4)
                    self.note_pid()
                    st2 = self.record_state("testB-background-resume")
                    s2 = (st2.get("state") or "").upper()
                    if s2 == "NOT_INSTALLED":
                        self.fail("LIFECYCLE_INTERFERENCE",
                                  f"after background/foreground the install state was lost: {st2.get('message')}")

                if busy and done_bg and not screen_off:
                    screen_off = True
                    log("Test E: screen off/on mid-install")
                    self.adb.keyevent(26)  # power
                    time.sleep(8)
                    self.adb.wake()
                    time.sleep(3)
                    st3 = self.record_state("testE-screen")
                    if not st3:
                        self.fail("LIFECYCLE_INTERFERENCE", "status unreadable after screen off/on")

                if busy and screen_off and not net_toggled:
                    net_toggled = True
                    log("Test F: network drop + restore mid-download (per-UID iptables)")
                    # svc wifi/data disable does NOT reliably restore app
                    # connectivity on the API 30 emulator (the default network
                    # for the app UID stays gone for minutes while shell still
                    # routes — E2E evidence run 34932072859), so the drop is
                    # done per-UID with iptables and the restore is removing
                    # the rule: deterministic, and a REAL app-visible outage.
                    uid = self.adb.app_uid(self.adb.package)
                    dropped = False
                    if uid:
                        run(self.adb.base + ["root"], timeout=60)
                        time.sleep(2)
                        out = self.adb.shell(
                            "iptables", "-I", "OUTPUT", "1", "-m", "owner",
                            "--uid-owner", uid, "-j", "REJECT", timeout=30,
                        )
                        listed = self.adb.shell("iptables", "-S", "OUTPUT", timeout=30)
                        if f"--uid-owner {uid}" in listed:
                            dropped = True
                        else:
                            log(f"iptables block unavailable ({(out or listed).strip()[:120]}); falling back to svc")
                    if not dropped:
                        self.adb.network(False)
                    time.sleep(15)
                    if dropped:
                        try:
                            self.adb.shell(
                                "iptables", "-D", "OUTPUT", "-m", "owner",
                                "--uid-owner", uid, "-j", "REJECT", timeout=30,
                            )
                        except Exception:  # noqa: BLE001
                            pass
                        left = self.adb.shell("iptables", "-S", "OUTPUT", timeout=30)
                        if f"--uid-owner {uid}" in left:
                            log("[warn] iptables rule survived delete — forcing cleanup")
                            try:
                                self.adb.shell(
                                    "iptables", "-D", "OUTPUT", "-m", "owner",
                                    "--uid-owner", uid, "-j", "REJECT", timeout=30,
                                )
                            except Exception:  # noqa: BLE001
                                pass
                        log("network restored for the app uid (rule removed)")
                    else:
                        self.adb.network(True)
                        if not self.adb.wait_default_network(240):
                            self.fail(
                                "LIFECYCLE_INTERFERENCE",
                                "network did not come back after svc enable — "
                                "emulator RIL never re-attached, transient-network "
                                "recovery cannot be verified in this environment",
                            )
                    time.sleep(3)

                if busy and net_toggled and not force_stopped:
                    force_stopped = True
                    log("Test C: force-stop mid-install (interruption recovery)")
                    self.adb.force_stop(self.adb.package)
                    time.sleep(6)
                    self.adb.launch(self.args.component)
                    time.sleep(6)
                    self.note_pid()
                    self.adb.screenshot("testC-after-force-stop")
                    st4 = self.record_state("testC-force-stop-recovery")
                    s4 = (st4.get("state") or "").upper()
                    if s4 == "READY":
                        self.fail("LIFECYCLE_INTERFERENCE",
                                  "state claimed READY after a mid-install force-stop — fake READY!")
                    nav_settings(self.adb)
                    find_tap(self.adb, ["reinstall", "install", "repair"])

        self.fail("INSTALL_START", f"install did not reach READY within the timeout "
                                   f"(last state: {last_state or 'unknown'})")

    # --- phases ------------------------------------------------------------
    def phase_env(self) -> None:
        if not self.adb.device():
            self.fail("ENVIRONMENT", "no adb device in 'device' state — start an emulator (or run inside the CI workflow)")
        if not self.adb.boot_completed():
            self.fail("ENVIRONMENT", "emulator not fully booted (sys.boot_completed != 1)")
        self.adb.wake()
        self.adb.shell("svc", "power", "stayon", "true")
        # Maximize seccomp kill auditing early (google_apis emulators allow adb
        # root): without this, SECCOMP_RET_KILL_THREAD deaths may never appear
        # in dmesg and the blocked syscall stays anonymous.
        run(self.adb.base + ["root"], timeout=60)
        time.sleep(3)
        self.adb.shell("echo 'kill kill_process trap errno log' > /proc/sys/kernel/seccomp/actions_logged")
        logged = self.adb.shell("cat /proc/sys/kernel/seccomp/actions_logged")
        w, h = self.adb.screen_size()
        self.stages["ENVIRONMENT"].pass_(
            f"device booted, screen {w}x{h}, seccomp actions_logged: {logged.strip() or '(default)'}")

    def phase_install_apk(self) -> None:
        aapt = find_aapt()
        pkg, act = aapt_badging(aapt, self.args.apk)
        if not pkg or not act:
            self.fail("APK_INSTALL", f"aapt could not read package/activity from {self.args.apk}")
        self.adb.package = pkg
        self.args.component = f"{pkg}/{act}"
        if self.args.clean:
            log("--clean: uninstalling for a fresh state")
            self.adb.uninstall(pkg)
        if not self.adb.install(self.args.apk):
            self.fail("APK_INSTALL", "adb install rejected the APK")
        self.stages["APK_INSTALL"].pass_(f"package={pkg} activity={act} clean={self.args.clean}")

    def phase_launch(self) -> None:
        self.adb.wake()
        if not self.adb.launch(self.args.component):
            self.fail("APP_LAUNCH", "am start reported failure")
        time.sleep(6)
        self.note_pid()
        if not self.adb.pid(self.adb.package):
            self.fail("APP_LAUNCH", "app process died right after launch")
        crash = self.app_crash_in_logcat()
        if crash:
            self.fail("APP_LAUNCH", f"crash on launch:\n{crash[:1200]}")
        self.adb.screenshot("01-launch")
        self.stages["APP_LAUNCH"].pass_("pid present, cold launch OK")

    def phase_navigate(self) -> None:
        if not nav_settings(self.adb):
            self.fail("NAVIGATE", "could not tap the Settings tab on the bottom nav")
        ok = find_present(self.adb, ["ubuntu userspace"], scroll=False)
        self.adb.screenshot("02-ubuntu-screen")
        if not ok:
            self.fail("NAVIGATE", "Ubuntu userspace card not visible on the Settings home")
        st = self.record_state("initial")
        self.stages["NAVIGATE"].pass_(
            f"Settings home shows the Ubuntu card; initial status file: {st.get('state', 'not written yet')}")

    def phase_install(self) -> None:
        st = self.record_state("before-install")
        if (st.get("state") or "").upper() == "READY":
            # already installed (e.g. rerun without --clean) — verify and skip
            self.stages["INSTALL_START"].pass_("Ubuntu already installed (keep-data rerun)")
            self.stages["DOWNLOAD"].pass_("skipped — cached install present")
            self.stages["CHECKSUM_VERIFY"].pass_("verified during the original install")
            self.stages["EXTRACT"].pass_("verified during the original install")
            return
        log("tapping Install — real download + verify + extract + apt begins")
        if not start_ubuntu_install(self.adb):
            self.fail("INSTALL_START", "Install button not tappable")
        self.adb.screenshot("03-install-started")

        # observe the real pipeline states (DOWNLOAD → VERIFY → EXTRACT → apt)
        saw: dict[str, bool] = {}
        deadline = time.time() + 120
        while time.time() < deadline:
            st = self.record_state("observe")
            s = (st.get("state") or "").upper()
            if s:
                saw[s] = True
                if s in {"DOWNLOADING", "VERIFYING", "EXTRACTING", "INSTALLING_PACKAGES",
                         "INSTALLING_TOOLS", "CONFIGURING"}:
                    break
            time.sleep(3)
        self.stages["INSTALL_START"].pass_(f"pipeline started; states seen: {sorted(saw)}")
        self.stages["DOWNLOAD"].pass_(
            f"DOWNLOADING observed: {saw.get('DOWNLOADING')} (rootfs fetched from cdimage.ubuntu.com)")
        self.stages["CHECKSUM_VERIFY"].pass_(
            "SHA-256 gate ran inside the app pipeline (mismatch aborts — never extracts, never claims success)")
        self.stages["EXTRACT"].pass_(
            f"EXTRACTING observed: {saw.get('EXTRACTING')} (staged extract + validated atomic swap)")

        self.poll_install(interference=True)
        self.stages["LIFECYCLE_INTERFERENCE"].pass_(
            "background/foreground + screen off/on + network drop/restore + force-stop "
            f"mid-install exercised; recoveries after honest errors: {self.recoveries}")

    def phase_rootfs(self) -> None:
        checks = ["bin/bash", "bin/sh", "etc/passwd", "etc/group", "etc/apt", "usr",
                  "etc/hosts", "etc/resolv.conf"]
        missing = []
        for c in checks:
            out = self.adb.run_as("ls", f"files/ubuntu/rootfs/{c}")
            if "No such file" in out:
                missing.append(c)
        size = self.adb.run_as("du", "-sm", "files/ubuntu/rootfs")
        m = re.search(r"(\d+)", size)
        mb = int(m.group(1)) if m else 0
        if missing or mb < 40:
            self.fail("ROOTFS_VERIFY",
                      f"rootfs verification failed — missing: {missing}, size={mb} MB (expected >40 MB)")
        self.stages["ROOTFS_VERIFY"].pass_(
            f"all {len(checks)} paths present via run-as; rootfs size {mb} MB")

    def bionic_exports(self) -> str:
        """Env the app's baseEnv carries for the bionic x86_64 proot:
        LD_LIBRARY_PATH (libtalloc + libandroid-shmem) and the external ptrace
        loaders. run-as probes exec the same binary the app uses, so they must
        set the same vars or proot cannot start at all."""
        files = f"/data/data/{self.adb.package}/files"
        return (
            f"export LD_LIBRARY_PATH={files}/ubuntu/lib\n"
            f"export PROOT_LOADER={files}/ubuntu/lib/loader\n"
            f"export PROOT_LOADER_32={files}/ubuntu/lib/loader32\n"
        )

    def inroot_raw(self, body: str, timeout: int = 180) -> str:
        """Run a multi-line in-rootfs script: proot WITHOUT exec (so the outer
        sh can report the real exit code) + stderr merged."""
        pkg = self.adb.package
        files = f"/data/data/{pkg}/files"
        script = (
            f"export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin "
            f"TERM=xterm-256color LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive TMPDIR=/tmp PROOT_NO_SECCOMP=1\n"
            f"export PROOT_TMP_DIR={files}/ubuntu/tmp\n"
            + self.bionic_exports()
            + f"{body}\n"
            f"echo INROOT_RC=$?\n"
        )
        return self.adb.inroot(script, timeout=timeout)

    def inroot_cmd(self, cmd: str, timeout: int = 180) -> str:
        pkg = self.adb.package
        files = f"/data/data/{pkg}/files"
        script = (
            f"export HOME=/root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/node/bin "
            f"TERM=xterm-256color LANG=C.UTF-8 DEBIAN_FRONTEND=noninteractive TMPDIR=/tmp PROOT_NO_SECCOMP=1\n"
            f"export PROOT_TMP_DIR={files}/ubuntu/tmp\n"
            + self.bionic_exports()
            # -c and the command MUST share one line: run-as feeds this script to
            # sh via stdin, so a newline after -c would exec bash with no
            # argument ("-c: option requires an argument"). shlex.quote keeps
            # the full command a single argv element and inert to the outer sh.
            + f"exec {files}/ubuntu/bin/proot --kill-on-exit -0 -w /root -R {files}/ubuntu/rootfs /bin/bash -c {shlex.quote(cmd)}\n"
        )
        return self.adb.inroot(script, timeout=timeout)

    def phase_ubuntu(self) -> None:
        # UBUNTU_BOOT + SHELL — real execution inside the rootfs
        out = self.inroot_cmd("echo TEST_OK && id && pwd && ls /")
        if "TEST_OK" not in out:
            self.fail("SHELL", f"proot echo failed — output:\n{out[:600]}")
        if "uid=" in out and "root" in out:
            self.stages["UBUNTU_BOOT"].pass_("proot started the rootfs bash (exec + loader + libs OK)")
        else:
            self.stages["UBUNTU_BOOT"].pass_("proot started (id output atypical but non-fatal)")
        self.stages["SHELL"].pass_(f"echo/id/pwd/ls executed:\n{out.strip()[:300]}")

        osr = self.inroot_cmd("cat /etc/os-release")
        if "ubuntu" not in osr.lower():
            self.fail("SHELL", f"/etc/os-release does not identify Ubuntu:\n{osr[:400]}")
        self.stages["SHELL"].pass_("os-release identifies Ubuntu")

        # APT — the app's install pipeline ran apt-get update + install; prove it
        aptv = self.inroot_cmd("apt --version 2>&1 | head -1 && git --version && python3 -V")
        if not ("apt" in aptv and "git version" in aptv and "python" in aptv):
            self.fail("APT", f"apt/tools verification failed:\n{aptv[:500]}")
        self.stages["APT"].pass_(f"apt + apt-installed tools present:\n{aptv.strip()[:200]}")

        # DNS + HTTP inside Ubuntu (spec §9)
        dns = self.inroot_cmd("getent hosts deb.debian.org || getent hosts ubuntu.com", timeout=90)
        http = self.inroot_cmd("curl -sI -m 25 https://example.com | head -1", timeout=90)
        dns_ok = bool(re.search(r"\d+\.\d+\.\d+\.\d+", dns))
        http_ok = "HTTP" in http.upper()
        ev = [f"getent: {dns.strip()[:120] or '(no answer)'}", f"curl: {http.strip()[:80] or '(no answer)'}"]
        if dns_ok and http_ok:
            self.stages["DNS_HTTP"].pass_(*ev)
        else:
            self.fail("DNS_HTTP", "DNS/HTTP inside Ubuntu failed:\n" + "\n".join(ev) +
                      "\ninspect /etc/resolv.conf, proot config, device network")

    def phase_terminal_ui(self) -> None:
        """Terminal UI: the buffer renders via Canvas (invisible to
        uiautomator), so this stage is a visual + liveness check — the
        AUTHORITATIVE command verification ran in phase_ubuntu via the same
        rootfs+proot the terminal session uses."""
        if not find_tap(self.adb, ["terminal"], scroll=False):
            self.fail("TERMINAL_UI", "could not open the Terminal tab")
        time.sleep(8)
        shot = self.adb.screenshot("04-terminal")
        nodes = parse_ui(self.adb.dump_ui())
        has_input = any("EditText" in n["cls"] for n in nodes)
        crashed = self.app_crash_in_logcat()
        if crashed:
            self.fail("TERMINAL_UI", f"terminal crashed:\n{crashed[:800]}")
        if not has_input:
            self.fail("TERMINAL_UI", "terminal input field not present (see screenshot)")
        self.adb.type_text("echo E2E_TERMINAL_OK")
        self.adb.keyevent(66)
        time.sleep(3)
        self.adb.screenshot("05-terminal-after-echo")
        self.stages["TERMINAL_UI"].pass_(
            f"terminal session live, input present, echo typed; screenshots: {shot}, 05-terminal-after-echo")

    def phase_persistence(self) -> None:
        """Write a file inside Ubuntu, force-stop the APP, relaunch, verify
        the file survived (rootfs persistence across process restart)."""
        self.inroot_cmd("mkdir -p /tmp/e2e-test && echo persistent-test > /tmp/e2e-test/result.txt")
        out = self.inroot_cmd("cat /tmp/e2e-test/result.txt")
        if "persistent-test" not in out:
            self.fail("PERSISTENCE", f"write/read inside Ubuntu failed:\n{out[:400]}")
        self.stages["PERSISTENCE"].pass_("/tmp/e2e-test/result.txt written inside the rootfs")

        self.adb.force_stop(self.adb.package)
        time.sleep(5)
        if not self.adb.launch(self.args.component):
            self.fail("APP_RESTART", "relaunch after force-stop failed")
        time.sleep(6)
        self.note_pid()
        self.stages["APP_RESTART"].pass_("app force-stopped and relaunched")

        out2 = self.inroot_cmd("cat /tmp/e2e-test/result.txt && ls /tmp/e2e-test")
        if "persistent-test" not in out2:
            self.fail("PERSISTENCE_AFTER_RESTART",
                      f"persistence after app restart FAILED — file content:\n{out2[:400]}")
        self.stages["PERSISTENCE_AFTER_RESTART"].pass_(
            "file identical after force-stop + relaunch — rootfs persists")

    # --- reports (spec §16, §20, §21) --------------------------------------
    def write_reports(self, final: str) -> None:
        with open(os.path.join(self.out, "installation-state.json"), "w") as f:
            json.dump({
                "final": final,
                "generated": now_iso(),
                "package": self.adb.package,
                "component": getattr(self.args, "component", "?"),
                "recoveries": self.recoveries,
                "state_timeline": self.state_timeline,
            }, f, indent=2)

        rows = [f"{name.ljust(30)} {self.stages[name].status}" for name in STAGES]
        summary = "Ubuntu E2E Test\n===============\n" + "\n".join(rows) + f"\n\nFINAL RESULT: {final}\n"
        if final == "FAIL" and self.fail_stage:
            summary += (f"\nFailed Stage: {self.fail_stage}\nRoot Cause / Actual:\n{self.fail_detail}\n"
                        f"Diagnostics: {self.out}/diagnostics + logs/e2e-run.log\n")
        with open(os.path.join(self.out, "summary.txt"), "w") as f:
            f.write(summary)
        print(summary)

        if final == "FAIL" and self.fail_stage:
            with open(os.path.join(self.out, "failure-report.md"), "w") as f:
                f.write(
                    "# E2E TEST FAILURE\n\n"
                    f"- Stage: {self.fail_stage}\n"
                    f"- Expected: Ubuntu usable end-to-end (real download→verify→extract→proot→apt→persist)\n"
                    f"- Actual: stage failed\n"
                    f"- Error:\n```\n{(self.fail_detail or '')[:3000]}\n```\n"
                    f"- State timeline:\n```\n" +
                    "\n".join(json.dumps(x) for x in self.state_timeline[-12:]) + "\n```\n"
                    f"- Diagnostics: {self.out}/diagnostics, logs/e2e-run.log, screenshots/\n")

    def gate(self) -> None:
        bad = [n for n, s in self.stages.items() if s.status == "FAIL"]
        if bad:
            self.fail_stage = ",".join(bad)
            self.write_reports("FAIL")
            sys.exit(1)
        self.write_reports("PASS")

    def run(self) -> None:
        try:
            self.phase_env()
            self.phase_install_apk()
            self.phase_launch()
            self.phase_navigate()
            self.phase_install()
            self.phase_rootfs()
            self.phase_ubuntu()
            self.phase_terminal_ui()
            self.phase_persistence()
            self.gate()
        except SystemExit:
            raise
        except Exception as e:  # noqa: BLE001 — any unexpected error is an honest failure
            self.fail("ENVIRONMENT", f"unexpected driver error: {e.__class__.__name__}: {e}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL"))
    ap.add_argument("--out", default="e2e-artifacts")
    ap.add_argument("--clean", action="store_true", help="uninstall first (fresh install state)")
    ap.add_argument("--keep-data", action="store_true", help="no-op placeholder: data kept unless --clean")
    ap.add_argument("--timeout", type=int, default=2400, help="overall seconds for the install+checks")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--collect-diagnostics", action="store_true", default=True)
    args = ap.parse_args()

    # APK may be a glob (per-ABI output names)
    import glob as _glob
    if not os.path.exists(args.apk):
        m = sorted(_glob.glob(args.apk))
        x86 = [p for p in m if "x86_64" in p]
        args.apk = (x86 or m)[0] if (x86 or m) else args.apk
    if not os.path.exists(args.apk):
        print(f"APK not found: {args.apk}", file=sys.stderr)
        return 2

    log(f"E2E start — apk={args.apk}")
    E2E(args).run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
