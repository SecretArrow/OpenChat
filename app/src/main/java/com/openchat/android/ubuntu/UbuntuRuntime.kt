package com.openchat.android.ubuntu

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.RepairAction
import com.openchat.android.core.model.UbuntuState
import com.openchat.android.core.model.UbuntuStatus
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.storage.SettingsStore
import com.openchat.android.core.util.Errors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The argv/cwd/environment triple a PTY session or exec runs with (spec §4).
 * Built by [ProotRunner.buildSessionSpec]; consumed by TerminalManager and by
 * [UbuntuRuntime.exec]/[UbuntuRuntime.execStream] through ProcessBuilder.
 */
data class SessionSpec(
    val argv: List<String>,
    val cwd: String,
    val env: Map<String, String>,
)

/**
 * Failure carrying a structured, actionable [ErrorInfo] (spec §24). Runtime
 * internals throw this so the outermost `runCatching`-style boundary can map it
 * 1:1 onto the UI error card instead of a bare exception message.
 */
class ErrorInfoException(val info: ErrorInfo) : Exception(info.detail)

/**
 * Owns the Ubuntu userspace lifecycle (spec §3–4):
 *
 *  - [install]: pinned rootfs download → SHA-256 verify → hardened extract →
 *    configure (DNS/APT/hosts) → apt update → smoke test → READY. The CORE
 *    install is deliberately minimal (v0.1.15: users reported "the install
 *    runs too many things, not just the basic Ubuntu") — developer tools
 *    (git, python3, node, …) are opt-in via [installDevTools].
 *  - [installDevTools]: opt-in apt tools + Node.js 20 (idempotent, needs READY).
 *  - [repair]: re-verify/re-extract over the existing rootfs (keeps /root),
 *    `apt-get -f install`, smoke test.
 *  - [update]: apt update + upgrade.
 *  - [export]: pack the whole rootfs into a user-picked .tar.gz backup.
 *  - [import]: restore a .tar.gz backup into a fresh, validated rootfs.
 *  - [reset]: delete the rootfs and cached tarballs.
 *  - [exec]/[execStream]: run real commands inside the rootfs via proot.
 *  - [sessionCommand]: the SessionSpec used for every interactive PTY session.
 *
 * Status is persisted to `ubuntu_status.json` on every transition; busy states
 * found at app start are coerced to ERROR ("interrupted") — the app never
 * pretends a killed download is still running. The same coercion is available
 * at runtime via [clearStaleOperation] (called on every Ubuntu-screen entry):
 * a CANCELLED operation (user navigates away mid-export — v0.1.14 report:
 * after Export, Repair/Install were dead) previously left the busy state
 * persisted forever, silently disabling every button. Every step streams into
 * [log] (tail 300). UI only talks to this class through AppGraph (spec §17).
 */
class UbuntuRuntime(
    private val context: Context,
    private val json: JsonStore,
    private val settings: SettingsStore,
    private val proot: ProotRunner,
    private val installer: UbuntuInstaller,
) {

    private val pipelineRunning = AtomicBoolean(false)

    private val _status: MutableStateFlow<UbuntuStatus> = MutableStateFlow(loadStatus())
    val status: StateFlow<UbuntuStatus> = _status

    private val logLock = Any()
    private val _log: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    val log: StateFlow<List<String>> = _log

    /**
     * Output tail (last lines) of the command that most recently failed —
     * surfaced by the Ubuntu error card so the REAL reason is visible without
     * digging through the log (users reported a bare "exit 255" with the
     * actual cause hidden).
     */
    private val _failureTail = MutableStateFlow<List<String>>(emptyList())
    val failureTail: StateFlow<List<String>> = _failureTail

    /**
     * Optional post-install continuation, wired by AppGraph (currently NOT
     * wired — v0.1.15 made the core install minimal: OpenCode/dev tools are
     * installed on demand from their own screens). A hook failure keeps
     * Ubuntu READY — it must never corrupt the runtime state.
     */
    var postInstallHook: (suspend () -> Unit)? = null

    /**
     * Optional reset hook (wired by AppGraph to TerminalManager.closeAll +
     * process cleanup) invoked before the rootfs is deleted.
     */
    var onReset: (() -> Unit)? = null

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Coerce busy states from a killed app run into an honest ERROR.
        clearStaleOperation(
            "The previous operation was interrupted when the app was killed — use Repair or Reset.",
        )
    }

    /**
     * Heals a persisted busy state that no live pipeline backs (pure decision
     * in [healState], JVM-tested). v0.1.14 bug this fixes: navigating away
     * during Export/Import/Install cancelled the composing scope mid-op and
     * left the busy state persisted — every button on the Ubuntu screen was
     * silently disabled ("after export I can't repair or install anymore").
     * Called on every Ubuntu-screen entry; a running operation is never touched.
     */
    fun clearStaleOperation(reason: String = "The previous operation was interrupted — use Repair or Reset.") {
        val st = _status.value
        val healed = healState(st.state, pipelineRunning.get())
        if (healed != null) setState(healed, reason, 0)
    }

    /** The device's primary ABI (drives rootfs/proot/Node downloads). */
    fun abi(): String = Build.SUPPORTED_ABIS[0]

    // --------------------------------------------------------------- readiness

    /**
     * True only when the status is READY **and** the rootfs bash + proot binary
     * actually exist on disk — a persisted READY without files is not "ready".
     */
    fun isReady(): Boolean =
        _status.value.state == UbuntuState.READY &&
            File(rootfsDir(), "bin/bash").exists() &&
            UbuntuFileSystem.prootComplete(context)

    /**
     * True when a usable rootfs exists on disk regardless of the persisted
     * state — the gate for Export (a broken install can still be backed up).
     */
    fun hasRootfs(): Boolean = UbuntuFileSystem.hasRootfs(context)

    /**
     * Fast readiness gate for every consumer action (terminal, exec, OpenCode…).
     * Failure is [Errors.ubuntuNotReady], or a repair-required ubuntuFailure when
     * the status claims READY but files went missing.
     */
    suspend fun ensureReady(): Result<Unit> {
        val st = _status.value
        return when {
            st.state == UbuntuState.READY && isReady() -> Result.success(Unit)
            st.state == UbuntuState.READY -> {
                setState(UbuntuState.ERROR, "Rootfs or proot is missing — repair required", 0)
                Result.failure(
                    ErrorInfoException(
                        Errors.ubuntuFailure("Ubuntu is marked ready but the rootfs or proot binary is missing"),
                    ),
                )
            }
            else -> Result.failure(ErrorInfoException(Errors.ubuntuNotReady()))
        }
    }

    // ----------------------------------------------------------------- install

    /**
     * Full §3–4 install chain. Fails honestly when an operation is already
     * running or Ubuntu is already installed (use [repair]/[update]).
     */
    suspend fun install(): Result<Unit> {
        if (_status.value.state.busy || !pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        try {
            if (_status.value.state == UbuntuState.READY) {
                return Result.failure(
                    ErrorInfoException(
                        Errors.ubuntuFailure("Ubuntu is already installed — use Repair or Update instead"),
                    ),
                )
            }
            return runPipeline(repair = false)
        } finally {
            pipelineRunning.set(false)
        }
    }

    /**
     * Repairs a broken installation: reuses the cached tarball when its hash
     * matches (re-downloads otherwise), re-extracts OVER the existing rootfs
     * (preserving /root home data), runs `apt-get -f install` and the smoke test.
     */
    suspend fun repair(): Result<Unit> {
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        try {
            return runPipeline(repair = true)
        } finally {
            pipelineRunning.set(false)
        }
    }

    private suspend fun runPipeline(repair: Boolean): Result<Unit> = try {
        runPipelineInner(repair)
    } catch (e: CancellationException) {
        // v0.1.15 cancel-safety: a cancelled pipeline must not leave a busy
        // state persisted (it disabled every button until the app restart).
        setState(UbuntuState.ERROR, "The operation was interrupted — use Repair or Reset.", 0)
        throw e
    }

    private suspend fun runPipelineInner(repair: Boolean): Result<Unit> {
        val arch = try {
            RootfsCatalog.ubuntuArchForAbi(abi())
        } catch (e: IllegalArgumentException) {
            return failStep("Unsupported device ABI", e)
        }

        // ---- pre-flight: honest storage check before any download starts ----
        val free = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (_: Exception) {
            -1L
        }
        if (free in 0 until MIN_FREE_BYTES) {
            return failStep(
                "Not enough free storage",
                IllegalStateException(
                    "${free / MB} MB available — the install needs at least ${MIN_FREE_BYTES / MB} MB",
                ),
            )
        }
        if (free >= 0 && free < WARN_FREE_BYTES) {
            addLog("[warn] Storage is low (${free / MB} MB free) — download + extraction need roughly ${WARN_FREE_BYTES / MB} MB")
        }

        // ---- resolve variant (pinned catalog or advanced override) ----
        val overrideUrl = settings.settings.value.rootfsUrlOverride?.trim().orEmpty()
        val variant: RootfsVariant
        val effectiveUrl: String
        val effectiveSha: String
        if (overrideUrl.isNotEmpty()) {
            addLog("rootfs URL override active — SHA-256 verification skipped: $overrideUrl")
            effectiveUrl = overrideUrl
            effectiveSha = ""
            variant = RootfsVariant(
                codename = "jammy",
                ubuntuVersion = "22.04",
                url = overrideUrl,
                sha256 = "",
            )
        } else {
            variant = try {
                RootfsCatalog.forAbi(abi())
            } catch (e: IllegalArgumentException) {
                return failStep("No pinned rootfs for this ABI", e)
            }
            effectiveUrl = variant.url
            effectiveSha = variant.sha256
        }

        // ---- DOWNLOADING (reuses a hash-verified cached tarball) ----
        if (repair) {
            addLog("Repair: the cached tarball is reused when its SHA-256 matches, re-downloaded otherwise")
        }
        setState(UbuntuState.DOWNLOADING, "Downloading Ubuntu base rootfs (${variant.ubuntuVersion} $arch)…", 2)
        val tarball = installer.downloadAndVerify(effectiveUrl, effectiveSha) { done, total ->
            if (total > 0) {
                val pct = ((done * 100L) / total).toInt().coerceIn(0, 100)
                updateProgress(pct * 45 / 100, "Downloading Ubuntu base rootfs… $pct%")
            } else {
                updateProgress(20, "Downloading Ubuntu base rootfs… ${done / (1024 * 1024)} MB")
            }
        }.getOrElse { return failStep("Download failed", it) }

        // ---- VERIFYING ----
        setState(UbuntuState.VERIFYING, "Verifying SHA-256…", 48)
        addLog("Tarball: ${tarball.absolutePath} (${tarball.length() / (1024 * 1024)} MB)")
        if (effectiveSha.isNotBlank()) {
            addLog("SHA-256 verified: $effectiveSha")
        }

        // ---- EXTRACTING ----------------------------------------------------
        // Fresh install: extract into a STAGING directory, validate, then swap
        // atomically (spec §28) — a crash mid-extraction can never leave a
        // partial rootfs in place or a fake READY behind. Repair keeps its
        // in-place over-extract semantics (preserves the user's /root data).
        val rootfs = rootfsDir()
        val staging = UbuntuFileSystem.importStagingDir(context)
        if (!repair) {
            addLog("Clearing any previous rootfs…")
            withContext(Dispatchers.IO) { rootfs.deleteRecursively() }
            setState(UbuntuState.EXTRACTING, "Extracting rootfs (staging)…", 55)
            withContext(Dispatchers.IO) {
                staging.deleteRecursively()
                staging.mkdirs()
            }
            installer.extract(tarball, staging).getOrElse { return failStep("Extraction failed", it) }
            setState(UbuntuState.EXTRACTING, "Validating the staged rootfs…", 62)
            val stagedBash = File(staging, "bin/bash").isFile
            if (!stagedBash) {
                withContext(Dispatchers.IO) { staging.deleteRecursively() }
                return failStep(
                    "Extraction validation failed",
                    IllegalStateException(
                        "staged rootfs has no bin/bash — the archive is incomplete or corrupt; nothing was installed",
                    ),
                )
            }
            setState(UbuntuState.EXTRACTING, "Swapping the verified rootfs into place…", 65)
            withContext(Dispatchers.IO) {
                if (rootfs.exists()) rootfs.deleteRecursively()
                if (!staging.renameTo(rootfs)) {
                    staging.copyRecursively(rootfs, overwrite = true)
                    staging.deleteRecursively()
                }
            }
        } else {
            setState(UbuntuState.EXTRACTING, "Extracting rootfs (repair keeps /root)…", 55)
            installer.extract(tarball, rootfs).getOrElse { return failStep("Extraction failed", it) }
        }
        setState(UbuntuState.EXTRACTING, "Rootfs extracted (${rootfs.absolutePath})", 65)

        // ---- CONFIGURING ----
        setState(UbuntuState.CONFIGURING, "Configuring DNS + APT sources…", 72)
        installer.configure(rootfs, variant).getOrElse { return failStep("Configuration failed", it) }

        // ---- proot binary (needed for every exec below) ----
        setState(UbuntuState.CONFIGURING, "Ensuring the proot binary…", 76)
        proot.ensureProot().getOrElse { return failStep("proot download failed", it) }

        // ---- INSTALLING_PACKAGES (apt-get update, streamed to the log) ----
        // v0.1.15: the CORE install deliberately stops here. Installing the
        // tool chain (curl/git/python3/node, plus the OpenCode npm chain that
        // used to follow) made every install take 10+ minutes and look like
        // "way more than the basic Ubuntu" (user report). Dev tools are now
        // opt-in via [installDevTools]; OpenCode installs from its own screen.
        setState(UbuntuState.INSTALLING_PACKAGES, "Refreshing package lists (apt-get update)…", 80)
        if (abi() == "x86_64") {
            divertLdconfig().getOrElse { return failStep("ldconfig bootstrap failed", it) }
        }
        runAptUpdate().getOrElse { return failStep("apt-get update failed", it) }
        if (repair) {
            // Fix any broken partial state left over in the previous install.
            runChecked("DEBIAN_FRONTEND=noninteractive apt-get -f install -y")
                .getOrElse { return failStep("apt-get -f install failed", it) }
        }

        // ---- smoke test (bash is the whole contract of the base rootfs) ----
        setState(UbuntuState.INSTALLING_TOOLS, "Running smoke test…", 95)
        val smoke = runCommand("bash --version && uname -m")
        val smokeOut = smoke.getOrElse { return failStep("Smoke test failed", it) }
        val smokeOk = smokeOut.contains("bash", ignoreCase = true)
        if (!smokeOk) {
            return failStep(
                "Smoke test failed — unexpected tool output: ${smokeOut.lineSequence().firstOrNull()?.take(200) ?: "(empty)"}",
                IllegalStateException("smoke mismatch"),
            )
        }

        // ---- READY ----
        setState(
            UbuntuState.READY,
            "Ubuntu ready (basic userspace) — use “Install dev tools” for git/python/node",
            100,
        ) { current ->
            current.copy(
                installed = true,
                version = variant.codename,
                arch = arch,
                rootfsPath = rootfs.absolutePath,
                lastUpdated = System.currentTimeMillis(),
            )
        }
        addLog("Ubuntu ${variant.codename} ($arch) is ready — basic userspace; dev tools are opt-in")
        return Result.success(Unit)
    }

    /**
     * apt update + full upgrade (spec §4 "Update"). v0.1.15: no longer
     * re-installs the dev tools/Node — those are owned by [installDevTools],
     * so "Update" stays what it says (package updates) and finishes fast.
     */
    suspend fun update(): Result<Unit> = try {
        updateInner()
    } catch (e: CancellationException) {
        setState(UbuntuState.ERROR, "The update was interrupted — you can run Update again.", 0)
        throw e
    }

    private suspend fun updateInner(): Result<Unit> {
        ensureReady().getOrElse { return Result.failure(it) }
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        try {
            setState(UbuntuState.UPDATING, "Updating packages (apt update + upgrade)…", 10)
            if (abi() == "x86_64") {
                // An upgrade can ship a new libc-bin whose trigger runs
                // /sbin/ldconfig — keep the seccomp-proof diversion current.
                divertLdconfig().getOrElse { return failStep("ldconfig bootstrap failed", it) }
            }
            runAptUpdate().getOrElse { return failStep("apt-get update failed", it) }
            runChecked("DEBIAN_FRONTEND=noninteractive apt-get -y upgrade")
                .getOrElse { return failStep("apt-get upgrade failed", it) }
            setState(
                UbuntuState.READY,
                "Ubuntu updated",
                100,
            ) { current -> current.copy(lastUpdated = System.currentTimeMillis()) }
            return Result.success(Unit)
        } finally {
            pipelineRunning.set(false)
        }
    }

    /**
     * Opt-in developer tool chain (v0.1.15, split out of the core install so
     * "Install Ubuntu" means just the basic userspace): apt tools
     * (curl, ca-certificates, xz-utils, git, python3 + pip, wget, procps,
     * sudo) plus the SHA-verified Node.js 20 at /opt/node. Idempotent — safe
     * to run again at any time (apt install -y is a no-op when current, Node
     * is skipped when /opt/node/bin/node exists). Requires READY.
     */
    suspend fun installDevTools(): Result<Unit> = try {
        installDevToolsInner()
    } catch (e: CancellationException) {
        setState(UbuntuState.ERROR, "The dev-tools installation was interrupted — run “Install dev tools” again.", 0)
        throw e
    }

    private suspend fun installDevToolsInner(): Result<Unit> {
        ensureReady().getOrElse { return Result.failure(it) }
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        try {
            setState(UbuntuState.INSTALLING_PACKAGES, "Installing developer tools (apt)…", 20)
            if (abi() == "x86_64") {
                divertLdconfig().getOrElse { return failStep("ldconfig bootstrap failed", it) }
            }
            runAptUpdate().getOrElse { return failStep("apt-get update failed", it) }
            runChecked(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                    "-o Acquire::Retries=3 -o Acquire::http::Timeout=60 -o Acquire::https::Timeout=60 " +
                    "curl ca-certificates xz-utils git python3 python3-pip wget procps sudo",
            ).getOrElse { return failStep("apt-get install failed", it) }
            setState(UbuntuState.INSTALLING_TOOLS, "Installing Node.js…", 70)
            installer.installNode(execStreamFn(), execFn(), ::addLog, abi())
                .getOrElse { return failStep("Node.js installation failed", it) }
            setState(UbuntuState.INSTALLING_TOOLS, "Verifying the tools…", 90)
            val smoke = runCommand("git --version && python3 -V && node -v")
            val smokeOut = smoke.getOrElse { return failStep("Tool verification failed", it) }
            val ok = smokeOut.contains("git version", ignoreCase = true) &&
                smokeOut.contains("python", ignoreCase = true) &&
                smokeOut.contains("v20", ignoreCase = true)
            if (!ok) {
                return failStep(
                    "Tool verification failed — unexpected output: " +
                        (smokeOut.lineSequence().firstOrNull()?.take(200) ?: "(empty)"),
                    IllegalStateException("dev tools smoke mismatch"),
                )
            }
            setState(UbuntuState.READY, "Developer tools installed (git, python3, node 20, curl, wget, sudo)", 100) {
                current -> current.copy(lastUpdated = System.currentTimeMillis())
            }
            addLog("Developer tools ready: git, python3 + pip, node 20, curl, wget, sudo, procps")
            return Result.success(Unit)
        } finally {
            pipelineRunning.set(false)
        }
    }

    /**
     * Packs the whole rootfs into the user-picked [uri] as a .tar.gz backup
     * (Export). Works from READY *and* from ERROR — backing up a broken
     * installation before Reset is exactly the point. Live sessions are closed
     * first so the archive captures a quiet filesystem. Non-rootfs assets
     * (proot binary, cache) are not part of the archive — the proot binary is
     * ABI-specific and re-downloaded automatically on import.
     */
    suspend fun export(uri: Uri): Result<Unit> {
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        // Captured BEFORE the busy state is set — the cancel handler restores it.
        val prevState = _status.value.state
        try {
            val rootfs = rootfsDir()
            if (!File(rootfs, "bin/bash").isFile) {
                return failStep(
                    "Export failed",
                    IllegalStateException("there is no Ubuntu rootfs to export — install Ubuntu first"),
                )
            }
            setState(UbuntuState.EXPORTING, "Packing the Ubuntu userspace…", 5)
            try {
                onReset?.invoke()
            } catch (e: Exception) {
                addLog("[warn] pre-export hook failed: ${e.message ?: e.javaClass.simpleName}")
            }
            var stats = UbuntuArchive.Stats()
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("could not open the destination file for writing")
                out.use { o ->
                    stats = UbuntuArchive.write(rootfs, o) { done, total ->
                        if (total > 0) {
                            updateProgress(
                                5 + ((done * 90) / total).toInt().coerceIn(5, 95),
                                "Exporting userspace… ${done / MB} / ${total / MB} MB",
                            )
                        }
                    }
                }
            }
            addLog(
                "Export complete: ${stats.files} files, ${stats.dirs} dirs, ${stats.symlinks} symlinks, " +
                    "${stats.skippedSpecial} special entries skipped (${stats.bytes / MB} MB content)",
            )
            setState(
                if (prevState == UbuntuState.READY) UbuntuState.READY else prevState,
                "Userspace exported — keep this archive safe, Import restores it exactly",
                100,
            )
            return Result.success(Unit)
        } catch (e: CancellationException) {
            // v0.1.15 cancel-safety: navigating away mid-export used to leave
            // the busy EXPORTING state persisted forever (every button dead).
            val restore = if (prevState.busy) UbuntuState.ERROR else prevState
            setState(restore, "Export interrupted — the userspace was not modified", 0)
            throw e
        } catch (e: Exception) {
            return failStep("Export failed", e)
        } finally {
            pipelineRunning.set(false)
        }
    }

    /**
     * Restores a .tar.gz backup (or any Ubuntu rootfs tarball) from [uri]:
     * stream-extracts into a staging dir, validates `bin/bash` (flattens a
     * single wrapper directory when present), swaps it in place of the current
     * rootfs, re-asserts DNS and runs a smoke test. A wrong-architecture
     * archive fails honestly at the smoke test — nothing is left half-broken.
     */
    suspend fun import(uri: Uri): Result<Unit> {
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        val staging = UbuntuFileSystem.importStagingDir(context)
        try {
            setState(UbuntuState.IMPORTING, "Importing the Ubuntu userspace…", 2)
            try {
                onReset?.invoke()
            } catch (e: Exception) {
                addLog("[warn] pre-import hook failed: ${e.message ?: e.javaClass.simpleName}")
            }
            var stats = UbuntuArchive.Stats()
            withContext(Dispatchers.IO) {
                staging.deleteRecursively()
                staging.mkdirs()
                val size = runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                }.getOrNull() ?: -1L
                val ins = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("could not read the selected archive")
                ins.use { s ->
                    stats = UbuntuArchive.extract(s, staging) { read ->
                        if (size > 0) {
                            updateProgress(
                                2 + ((read * 78) / size).toInt().coerceIn(2, 80),
                                "Importing… ${read / MB} / ${size / MB} MB",
                            )
                        }
                    }
                }
            }
            addLog(
                "Import extracted: ${stats.files} files, ${stats.dirs} dirs, ${stats.symlinks} symlinks, " +
                    "${stats.skippedSpecial} special entries skipped",
            )

            setState(UbuntuState.IMPORTING, "Validating the imported rootfs…", 82)
            val flattened = withContext(Dispatchers.IO) { UbuntuArchive.normalizeRoot(staging) }
            if (flattened > 0) addLog("The archive wrapped the rootfs in one directory — flattened")

            setState(UbuntuState.IMPORTING, "Ensuring the proot binary…", 86)
            proot.ensureProot().getOrElse { return failStep("proot download failed", it) }

            setState(UbuntuState.IMPORTING, "Swapping in the imported rootfs…", 90)
            val rootfs = rootfsDir()
            withContext(Dispatchers.IO) {
                if (rootfs.exists()) rootfs.deleteRecursively()
                if (!staging.renameTo(rootfs)) {
                    staging.copyRecursively(rootfs, overwrite = true)
                    staging.deleteRecursively()
                }
                // Re-assert host-side network config (idempotent, keeps APT working).
                val etc = File(rootfs, "etc").apply { mkdirs() }
                File(etc, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                File(etc, "hosts").writeText("127.0.0.1 localhost\n")
                // Imported userspaces on x86_64 devices need the seccomp
                // syscall-compat preload just like a fresh install does
                // (legacy glibc file syscalls get ENOSYS under the app filter).
                installer.installSyscallCompatForImport(rootfs)
                // Imported rootfs also need the pinned keyring + CA bundle:
                // raw ubuntu-base tarballs may carry an outdated archive keyring
                // and no CA store at all (HTTPS sources would fail to verify).
                installer.restoreAptTrust(rootfs)
                // Guest tmp dirs: the bind targets must exist and the imported
                // rootfs's own /tmp must be present (fallback layer).
                UbuntuFileSystem.ensureGuestTmpDirs(context, rootfs)
            }

            setState(UbuntuState.IMPORTING, "Running smoke test…", 95)
            val smoke = runCommand("bash --version && uname -m")
            val smokeOut = smoke.getOrElse {
                return failStep(
                    "Imported rootfs smoke test failed",
                    IllegalStateException("bash did not start — the archive may target a different device architecture"),
                )
            }
            if (!smokeOut.contains("bash", ignoreCase = true)) {
                return failStep(
                    "Imported rootfs failed the smoke test",
                    IllegalStateException("unexpected tool output: ${smokeOut.lineSequence().firstOrNull()?.take(160) ?: "(empty)"}"),
                )
            }
            val detectedArch = smokeOut.lineSequence().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

            // Raw ubuntu-base tarballs ship WITHOUT apt sources — without this,
            // the first `apt-get update` after importing a downloaded base fails.
            // App backups already carry their sources; they are left untouched.
            val aptDir = File(File(rootfs, "etc").apply { mkdirs() }, "apt")
            val sourcesReady = withContext(Dispatchers.IO) { AptSources.hasActiveSources(aptDir) }
            val importArch = AptSources.archFromUname(detectedArch)
            if (!sourcesReady && importArch != null) {
                setState(UbuntuState.IMPORTING, "Raw base detected — writing APT sources…", 97)
                val osRelease = withContext(Dispatchers.IO) {
                    runCatching { File(rootfs, "etc/os-release").readText() }.getOrDefault("")
                }
                val codename = AptSources.codenameFromOsRelease(osRelease) ?: RootfsCatalog.DEFAULT_CODENAME
                withContext(Dispatchers.IO) {
                    AptSources.writeFor(aptDir, importArch, codename)
                    File(File(rootfs, "etc"), "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                    File(File(rootfs, "etc"), "hosts").writeText("127.0.0.1 localhost\n")
                }
                addLog("Raw base tarball: APT sources written for $importArch ($codename) — apt-get update will work")
            } else if (!sourcesReady) {
                addLog("[warn] The archive has no active APT sources and the arch could not be detected — run 'apt-get update' may fail; use Repair to configure sources")
            }

            val arch = when (detectedArch) {
                "aarch64" -> "arm64"
                "x86_64" -> "x86_64"
                "armv7l", "armv8l" -> "armhf"
                else -> null
            }
            setState(
                UbuntuState.READY,
                "Ubuntu imported",
                100,
            ) { current ->
                current.copy(
                    installed = true,
                    version = "imported",
                    arch = arch ?: current.arch,
                    rootfsPath = rootfs.absolutePath,
                    lastUpdated = System.currentTimeMillis(),
                )
            }
            addLog("Import complete — Ubuntu userspace restored (${arch ?: "arch unknown"})")
            return Result.success(Unit)
        } catch (e: CancellationException) {
            setState(UbuntuState.ERROR, "The import was interrupted — run Import again to restore the userspace", 0)
            throw e
        } catch (e: SecurityException) {
            return failStep("Malicious archive entry rejected", e)
        } catch (e: Exception) {
            return failStep("Import failed", e)
        } finally {
            try {
                withContext(Dispatchers.IO) { staging.deleteRecursively() }
            } catch (_: Exception) {
                // Never mask the real outcome with a cleanup error.
            }
            pipelineRunning.set(false)
        }
    }

    /**
     * Deletes the rootfs and the cached tarballs (proot is kept — it is
     * reusable). [onReset] runs first so AppGraph can close live sessions.
     */
    suspend fun reset(): Result<Unit> {
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        try {
            setState(UbuntuState.RESETTING, "Removing the Ubuntu userspace…", 10)
            try {
                onReset?.invoke()
            } catch (e: Exception) {
                addLog("[warn] reset hook failed: ${e.message ?: e.javaClass.simpleName}")
            }
            withContext(Dispatchers.IO) {
                rootfsDir().deleteRecursively()
                UbuntuFileSystem.cacheDir(context).deleteRecursively()
            }
            setState(UbuntuState.NOT_INSTALLED, "Ubuntu userspace removed — install again to use terminals/exec", 0)
            return Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) {
                setState(UbuntuState.ERROR, "The reset was interrupted — run Reset again to finish deleting the userspace", 0)
                throw e
            }
            return failStep("Reset failed", e)
        } finally {
            pipelineRunning.set(false)
        }
    }

    /**
     * Copies the cached (already downloaded + SHA-256 verified) Ubuntu base
     * tarball to the user-picked [uri] — exporting the downloaded base itself
     * so it can be moved to another device or kept for offline reinstall.
     * Fast: a plain file copy, no re-packing.
     */
    suspend fun exportBaseCache(uri: Uri): Result<Unit> {
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        try {
            val prevState = _status.value.state
            setState(UbuntuState.EXPORTING, "Exporting the downloaded base archive…", 5)
            val cache = UbuntuFileSystem.cacheDir(context)
            val tarball = cache.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".tar.gz") || it.name.endsWith(".tgz")) }
                ?.maxByOrNull { it.length() }
                ?: return failStep(
                    "Export base failed",
                    IllegalStateException("there is no downloaded Ubuntu base in the cache — install once, or import a base file instead"),
                )
            addLog("Exporting base archive: ${tarball.name} (${tarball.length() / MB} MB)")
            withContext(Dispatchers.IO) {
                val out = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("could not open the destination file for writing")
                out.use { o ->
                    tarball.inputStream().use { ins ->
                        val buf = ByteArray(128 * 1024)
                        var done = 0L
                        val total = tarball.length()
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            updateProgress(
                                (5 + (done * 90 / total)).toInt().coerceIn(5, 95),
                                "Exporting base… ${done / MB} / ${total / MB} MB",
                            )
                        }
                    }
                }
            }
            setState(
                prevState,
                "Base archive exported (${tarball.name}) — Import accepts it on any device with the same architecture",
                100,
            )
            return Result.success(Unit)
        } catch (e: CancellationException) {
            val restore = if (prevState.busy) UbuntuState.ERROR else prevState
            setState(restore, "Base export interrupted — the cache was not modified", 0)
            throw e
        } catch (e: Exception) {
            return failStep("Export base failed", e)
        } finally {
            pipelineRunning.set(false)
        }
    }

    /**
     * Environment diagnostics for the Ubuntu stack — the honest numbers behind
     * every "why did apt fail" question: free space seen by the app, proot
     * binary state + version, PROOT_TMP_DIR writability, RAM available, DNS
     * and APT sources inside the rootfs. Each check degrades to "(check
     * failed: reason)" instead of aborting the whole report.
     */
    suspend fun diagnostics(): List<String> = withContext(Dispatchers.IO) {
        val out = mutableListOf<String>()
        val st = _status.value
        out.add("== OpenChat Ubuntu diagnostics ==")
        out.add("state: ${st.state} · installed=${st.installed} · version=${st.version ?: "?"} · arch=${st.arch ?: "?"}")
        out.add("device ABI: ${abi()}")

        // 1. Free space where the rootfs actually lives
        val free = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (e: Exception) {
            -1L
        }
        out.add(
            if (free >= 0) {
                "free space (app data volume): ${free / MB} MB (" +
                    "%.2f GB) — minimum install gate ${MIN_FREE_BYTES / MB} MB".format(free.toDouble() / (1000 * MB))
            } else {
                "free space: (measurement failed)"
            }
        )

        // 2. proot binary
        val bin = UbuntuFileSystem.prootBin(context)
        out.add(
            "proot binary: ${if (bin.isFile) "${bin.absolutePath} (${bin.length() / 1024} KB)" else "MISSING"}" +
                if (bin.isFile) " executable=${bin.canExecute()}" else "",
        )
        if (bin.isFile && bin.canExecute()) {
            try {
                // The bionic x86_64 build resolves libtalloc from our lib dir —
                // the app process env does not carry it, so add it explicitly.
                val pb = ProcessBuilder(bin.absolutePath, "--version")
                pb.environment().putAll(proot.hostEnvExtras())
                pb.redirectErrorStream(true)
                val p = pb.start()
                val ver = p.inputStream.bufferedReader().use { it.readText().trim().lineSequence().firstOrNull() ?: "" }
                p.waitFor(10, TimeUnit.SECONDS)
                out.add("proot --version: $ver (exit ${p.exitValue()})")
            } catch (e: Exception) {
                out.add("proot --version: FAILED to start — ${e.message ?: e.javaClass.simpleName}")
            }
        }

        // 3. PROOT_TMP_DIR writable?
        val tmp = UbuntuFileSystem.prootTmpDir(context)
        val probe = File(tmp, ".probe-${System.currentTimeMillis()}")
        out.add(
            "PROOT_TMP_DIR: ${tmp.absolutePath} exists=${tmp.isDirectory} writable=" + try {
                tmp.mkdirs()
                probe.createNewFile()
                probe.writeText("ok")
                probe.delete()
                true
            } catch (e: Exception) {
                "false (${e.message ?: e.javaClass.simpleName})"
            },
        )

        // 4. RAM available (host /proc/meminfo)
        try {
            val mem = File("/proc/meminfo").readLines()
                .firstOrNull { it.startsWith("MemAvailable") }
            out.add("RAM available: ${mem?.substringAfter(':')?.trim() ?: "unknown"}")
        } catch (_: Exception) {
            out.add("RAM available: (unreadable)")
        }

        // 5. Rootfs + in-rootfs checks (only when present)
        val rootfs = rootfsDir()
        val bash = File(rootfs, "bin/bash")
        out.add("rootfs: ${rootfs.absolutePath} exists=${rootfs.isDirectory} bin/bash=${if (bash.isFile) "present" else if (rootfs.isDirectory) "MISSING" else "n/a"}")
        if (bash.isFile) {
            val aptDir = File(File(rootfs, "etc"), "apt")
            out.add("apt sources configured: ${AptSources.hasActiveSources(aptDir)}")
            runCatching {
                val osRelease = File(rootfs, "etc/os-release").readText()
                out.add("os-release codename: ${AptSources.codenameFromOsRelease(osRelease) ?: "?"}")
            }
            // DNS + disk from INSIDE the rootfs (bypasses the READY gate honestly)
            val dns = runCommand("getent hosts ports.ubuntu.com || getent hosts archive.ubuntu.com", timeoutMs = 30_000)
            out.add(
                dns.fold(
                    { s -> "in-rootfs DNS: ${s.lineSequence().firstOrNull()?.take(120) ?: "no answer (getent missing in base?)"}" },
                    { "in-rootfs DNS: FAILED — ${it.message?.lineSequence()?.firstOrNull()?.take(120)}" },
                ),
            )
            val disk = runCommand("df -h /tmp 2>/dev/null | tail -1", timeoutMs = 30_000)
            disk.onSuccess { s -> out.add("in-rootfs /tmp volume: ${s.lineSequence().lastOrNull()?.take(140) ?: "?"}") }
            // The v0.1.14 device bug in one probe: apt's signature check does
            // exactly this mkstemp in /tmp — if it fails, apt reports every
            // repository as "not signed". Also records the guest tmp bind dirs.
            val tmpProbe = runCommand(
                "f=\$(mktemp /tmp/octest.XXXXXX 2>&1) && { echo \"MKTEMP_OK \$f\"; rm -f \"\$f\"; } || echo \"MKTEMP_FAILED \$f\"",
                timeoutMs = 30_000,
            )
            val tmpLine = tmpProbe.getOrNull()?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "(probe failed)"
            out.add("in-rootfs /tmp mktemp: ${tmpLine.take(140)}")
            out.add(
                "guest tmp bind dirs: tmp=${UbuntuFileSystem.guestTmpDir(context).isDirectory} " +
                    "shm=${UbuntuFileSystem.guestShmDir(context).isDirectory}",
            )
        }

        // 6. The last failure tail, if any — ties the report to the visible error
        val tail = _failureTail.value
        if (tail.isNotEmpty()) {
            out.add("last failure output tail:")
            tail.takeLast(ProotFailureMapper.DETAIL_TAIL_LINES).forEach { out.add("  | ${it.take(200)}") }
        }
        out
    }

    // -------------------------------------------------------------------- exec

    /**
     * Runs [cmd] inside the rootfs (`bash -lc`) and returns its full output.
     * Per contract the result is a success with the output even when the exit
     * code is non-zero — steps that need the exit code use [execStream] (whose
     * success value IS the exit code). Short-circuits through [ensureReady].
     */
    suspend fun exec(
        cmd: String,
        cwd: String = "/root",
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result<String> {
        ensureReady().getOrElse { return Result.failure(it) }
        return runCommand(cmd, cwd, env, timeoutMs)
    }

    /**
     * Streaming variant of [exec]: every output line goes to [onLine] as it is
     * produced; the success value is the real exit code (0 == success).
     * Fails on start errors and on timeout (process is killed forcibly).
     */
    suspend fun execStream(
        cmd: String,
        cwd: String = "/root",
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = STREAM_TIMEOUT_MS,
        onLine: (String) -> Unit,
    ): Result<Int> {
        ensureReady().getOrElse { return Result.failure(it) }
        return runCommandStream(cmd, cwd, env, timeoutMs, onLine)
    }

    /** The argv/env every interactive PTY session of this runtime runs with. */
    fun sessionCommand(cwd: String, cmd: List<String>, env: Map<String, String>): SessionSpec =
        proot.buildSessionSpec(cwd, cmd, env, rootfsDir())

    // ---------------------------------------------------------------- internal

    /**
     * Streaming exec lambda handed to [UbuntuInstaller]: every command runs
     * through [runCommandStream]; output lines go to both the caller's callback
     * (nullable) and the runtime log; the success value is the real exit code.
     */
    private fun execStreamFn(): suspend (String, ((String) -> Unit)?) -> Result<Int> = { cmd, onLine ->
        runCommandStream(cmd, "/root", emptyMap(), STREAM_TIMEOUT_MS) { line ->
            onLine?.invoke(line)
            addLog(line)
        }
    }

    /** Output-capturing exec lambda handed to [UbuntuInstaller] (probes/hashing). */
    private fun execFn(): suspend (String) -> Result<String> = { cmd ->
        runCommand(cmd, "/root", emptyMap(), DEFAULT_TIMEOUT_MS)
    }

    private suspend fun runCommand(
        cmd: String,
        cwd: String = "/root",
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Result<String> = withContext(Dispatchers.IO) {
        val spec = sessionCommand(cwd, listOf("/bin/bash", "-lc", cmd), env)
        try {
            val proc = ProcessBuilder(spec.argv)
                .apply {
                    environment().clear()
                    environment().putAll(spec.env)
                    redirectErrorStream(true)
                }
                .start()
            val out = StringBuilder()
            val reader = Thread({
                try {
                    proc.inputStream.use { ins ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            out.append(String(buf, 0, n, Charsets.UTF_8))
                        }
                    }
                } catch (_: Exception) {
                    // stream closed on destroy — keep what we have
                }
            }, "openchat-exec-out")
            reader.isDaemon = true
            reader.start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(2000)
                return@withContext Result.failure(
                    ErrorInfoException(Errors.ubuntuFailure("Command timed out after ${timeoutMs}ms: $cmd")),
                )
            }
            reader.join(5000)
            Result.success(out.toString())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Failed to run command: ${e.message ?: e.javaClass.simpleName} — $cmd"),
                ),
            )
        }
    }

    private suspend fun runCommandStream(
        cmd: String,
        cwd: String = "/root",
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = STREAM_TIMEOUT_MS,
        onLine: (String) -> Unit,
    ): Result<Int> = withContext(Dispatchers.IO) {
        val spec = sessionCommand(cwd, listOf("/bin/bash", "-lc", cmd), env)
        val tail = ArrayDeque<String>()
        try {
            val proc = ProcessBuilder(spec.argv)
                .apply {
                    environment().clear()
                    environment().putAll(spec.env)
                    redirectErrorStream(true)
                }
                .start()
            val reader = Thread({
                try {
                    proc.inputStream.bufferedReader().use { br ->
                        while (true) {
                            val line = br.readLine() ?: break
                            synchronized(tail) {
                                tail.addLast(line)
                                while (tail.size > ProotFailureMapper.TAIL_LINES) tail.removeFirst()
                            }
                            try {
                                onLine(line)
                            } catch (_: Exception) {
                                // a failing consumer must not kill the pump
                            }
                        }
                    }
                } catch (_: Exception) {
                    // stream closed on destroy
                }
            }, "openchat-exec-stream")
            reader.isDaemon = true
            reader.start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                reader.join(2000)
                publishTail(tail)
                return@withContext Result.failure(
                    ErrorInfoException(Errors.ubuntuFailure("Command timed out after ${timeoutMs}ms: $cmd")),
                )
            }
            reader.join(5000)
            val code = proc.exitValue()
            if (code != 0) {
                val lines = synchronized(tail) { tail.toList() }
                _failureTail.value = lines
                if (code == PROOT_FATAL_EXIT) {
                    // Exit 255 is proot's own fatal code (apt failures return 1/100).
                    // EVERY 255 now maps to an actionable error that shows the real
                    // output tail — no more bare "error code 255" with the cause hidden.
                    return@withContext Result.failure(ErrorInfoException(ProotFailureMapper.map(lines)))
                }
                if (code >= 128) {
                    // Signal death (128+signal). Exit 159 = SIGSYS: the device's
                    // seccomp policy killed proot — previously surfaced as a bare
                    // "exited with code 159" with an empty tail.
                    return@withContext Result.failure(ErrorInfoException(ProotFailureMapper.mapSignalExit(code, lines)))
                }
            } else {
                _failureTail.value = emptyList()
            }
            Result.success(code)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            publishTail(tail)
            Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Failed to run command: ${e.message ?: e.javaClass.simpleName} — $cmd"),
                ),
            )
        }
    }

    /** Stores the captured output tail for the error card (synchronized read). */
    private fun publishTail(tail: ArrayDeque<String>) {
        _failureTail.value = synchronized(tail) { tail.toList() }
    }


    /** Checked run used inside the install pipeline (bypasses the READY gate). */
    private suspend fun runChecked(cmd: String): Result<Unit> =
        runCommandStream(cmd, "/root", emptyMap(), STREAM_TIMEOUT_MS) { line -> addLog(line) }.fold(
            onSuccess = { code ->
                if (code == 0) {
                    Result.success(Unit)
                } else {
                    // Non-zero guest command failure: surface the real output tail
                    // with the error so the cause is visible without opening the log.
                    // Temp-file failures take precedence over signature failures:
                    // a temp-file tail also contains "is not signed" lines (apt's
                    // conclusion after the mkstemp failure), and only the tmp-aware
                    // card names the real cause. Signature failures get their
                    // dedicated card — after the automatic keyring/HTTPS repair
                    // this is the honest dead end.
                    val failureInfo = when {
                        AptDiagnostics.isTempFileFailure(_failureTail.value) ->
                            AptDiagnostics.tempFileErrorInfo(_failureTail.value)
                        AptDiagnostics.isSignatureFailure(_failureTail.value) ->
                            AptDiagnostics.signatureErrorInfo(_failureTail.value)
                        else -> ErrorInfo(
                            title = "'$cmd' exited with code $code",
                            detail = ProotFailureMapper.withTail("The command failed (exit $code).", _failureTail.value),
                            causes = listOf(
                                "The command inside the rootfs reported an error",
                                "The full output is shown above and in the log below",
                            ),
                            suggestions = listOf(
                                "Read the output tail — apt prints the failing line explicitly",
                                "Run Repair to rebuild partial package state (apt-get -f install)",
                                "If it repeats every attempt, run diagnostics and report the output",
                            ),
                            retryable = true,
                        )
                    }
                    Result.failure(ErrorInfoException(failureInfo))
                }
            },
            onFailure = { Result.failure(it) },
        )

    /**
     * `apt-get update` with layered self-healing (v0.1.14):
     *
     *  1. Sources upgrade: legacy plain-HTTP official mirrors are rewritten
     *     to HTTPS before the first attempt (transparent proxies on real
     *     networks answer HTTP with their own content — the exact cause of
     *     the reported `repository … is not signed` failures).
     *  2. First attempt; a transient failure retries once after 3s.
     *  3. Temp-file failure (`Couldn't create temporary file … for passing
     *     config to apt-key`, real-device report v0.1.13/v0.1.14): re-provision
     *     every tmp directory host-side (bind dirs + rootfs /tmp, /var/tmp,
     *     apt partial dirs) and retry — heals a deleted/locked tmp tree. The
     *     /tmp bind itself lives in [ProotRunner.buildSessionSpec] and is
     *     already active for this attempt.
     *  4. Signature failure (`NO_PUBKEY` / `is not signed` / GPG error):
     *     re-provision the pinned Ubuntu archive keyring + CA bundle from the
     *     APK assets, clear the cached lists, and retry — this heals rootfs
     *     bases whose keyring predates the 2018 archive signing key. Checked
     *     AFTER the temp-file class: a temp-file tail also matches the
     *     signature markers (apt declares the repo "not signed" after the
     *     mkstemp failure), and the keyring repair cannot fix a /tmp problem
     *     (proven by the v0.1.13 device report: two keyring repairs, identical
     *     error).
     *  5. Whatever still fails surfaces honestly with the real apt output
     *     ([runChecked] attaches the cause-aware ErrorInfo).
     */
    private suspend fun runAptUpdate(): Result<Unit> {
        upgradeAptSourcesToHttps()
        val cmd = "apt-get -o Acquire::Retries=3 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 update"
        val first = runChecked(cmd)
        if (first.isSuccess) return first
        val tail = _failureTail.value
        if (AptDiagnostics.isTempFileFailure(tail)) {
            addLog("[warn] apt cannot create temporary files in /tmp — re-provisioning tmp " +
                "directories (host bind dirs + rootfs /tmp, /var/tmp, apt partials) and retrying")
            runCatching { UbuntuFileSystem.ensureGuestTmpDirs(context, rootfsDir()) }
                .onFailure { addLog("[warn] tmp re-provision failed: ${it.message ?: it.javaClass.simpleName}") }
            val repaired = runChecked(cmd)
            if (repaired.isSuccess) {
                addLog("APT tmp repair succeeded — /tmp is writable again")
            }
            return repaired // honest failure with the tmp-aware error below
        }
        if (AptDiagnostics.isSignatureFailure(tail)) {
            addLog("[warn] APT signature verification failed (NO_PUBKEY / 'is not signed') — " +
                "restoring the pinned Ubuntu archive keyring + CA bundle, clearing cached lists, retrying")
            val trustRestored = runCatching { installer.restoreAptTrust(rootfsDir()) }
                .onFailure { addLog("[warn] keyring restore failed: ${it.message ?: it.javaClass.simpleName}") }
                .isSuccess
            if (trustRestored) {
                runChecked("rm -rf /var/lib/apt/lists/*")
                val repaired = runChecked(cmd)
                if (repaired.isSuccess) {
                    addLog("APT signature repair succeeded — repository metadata now verifies")
                    return repaired
                }
                return repaired // honest failure with the signature-aware error below
            }
        }
        addLog("[warn] apt-get update failed once — retrying after 3s (transient network/mirror failures are common)")
        delay(3000)
        return runChecked(cmd)
    }

    /** Rewrites legacy HTTP official-mirror sources to HTTPS (idempotent). */
    private fun upgradeAptSourcesToHttps() {
        runCatching {
            val changed = AptSources.upgradeToHttps(File(rootfsDir(), "etc/apt"))
            if (changed) {
                addLog("APT sources upgraded to https:// (protects repository verification " +
                    "against proxies/portals that tamper with plain-HTTP mirror traffic)")
            }
        }.onFailure { addLog("[warn] APT sources https upgrade failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * Keeps /sbin/ldconfig functional on non-x86_64 rootfs, and diverts it to
     * a no-op on x86_64 (idempotent, all states).
     *
     * Why the x86_64 diversion: focal's /sbin/ldconfig is a shell wrapper that
     * `exec`s /sbin/ldconfig.real — a STATIC glibc binary. Static binaries
     * bypass the guest syscall-compat preload (only the dynamic loader reads
     * /etc/ld.so.preload), so under the zygote seccomp filter ldconfig.real
     * hits raw ENOSYS on the legacy syscalls glibc 2.31 still issues and dies
     * — `ldconfig || ldconfig --verbose` fails twice and the libc-bin
     * post-installation script fails every apt run (E2E run 18: dpkg error
     * for libc-bin 3/3 attempts). The cache itself is dispensable in this
     * containerized rootfs: the dynamic loader falls back to the default
     * library paths, which hold every library we ship — the same trade
     * Termux glibc environments make.
     *
     * Why NOT on other ABIs (v0.1.11 regression fixed here): arm64 has no
     * legacy-rename problem (the kernel ABI has no legacy syscalls at all —
     * glibc uses the *at() variants), the compat preload is not installed,
     * and a no-op ldconfig only breaks guest library bookkeeping. Worse, the
     * diversion leaked to arm64 in v0.1.11 and TOUCHES THE DPKG DATABASE —
     * so the non-x86_64 branch must actively UNDO it on rootfs installed by
     * that version: remove the no-op symlink, deregister the diversion (that
     * renames ldconfig.distrib back), restore the wrapper if a leftover
     * remains. Idempotent; safe on never-diverted rootfs.
     *
     * The diversion (not a plain overwrite) is what survives libc-bin
     * upgrades: dpkg writes the new wrapper to /sbin/ldconfig.distrib and
     * leaves our no-op in place; without registration dpkg would follow the
     * symlink and overwrite /bin/true itself. Re-run safely after a repair
     * re-extract (fresh dpkg DB + leftover .distrib) and on rootfs installed
     * by older app versions:
     *  - diversion registered → keep (dpkg writes upgrades to .distrib)
     *  - not registered → drop any stale .distrib from a pre-diversion run,
     *    rename the (freshly extracted) wrapper to .distrib, register
     *  - always refresh the no-op symlink
     */
    private suspend fun divertLdconfig(): Result<Unit> {
        if (Build.SUPPORTED_ABIS[0] != "x86_64") {
            return runChecked(
                // v0.1.11 leaked the diversion to every ABI — undo it here.
                "[ -L /sbin/ldconfig ] && rm -f /sbin/ldconfig; " +
                    "dpkg-divert --local --rename --remove /sbin/ldconfig 2>/dev/null || true; " +
                    "if [ ! -e /sbin/ldconfig ] && [ -f /sbin/ldconfig.distrib ]; then " +
                    "mv /sbin/ldconfig.distrib /sbin/ldconfig; fi; true",
            )
        }
        return runChecked(
            "if [ -z \"$(dpkg-divert --list /sbin/ldconfig)\" ]; then " +
                "rm -f /sbin/ldconfig.distrib; " +
                "dpkg-divert --local --rename --add /sbin/ldconfig; " +
                "fi; ln -sf /bin/true /sbin/ldconfig",
        )
    }


    private fun failStep(step: String, t: Throwable): Result<Unit> {
        if (t is CancellationException) throw t
        val detail = "$step: ${t.message ?: t.javaClass.simpleName}"
        addLog("[error] $detail")
        setState(UbuntuState.ERROR, detail, 0)
        return Result.failure(if (t is ErrorInfoException) t else ErrorInfoException(Errors.ubuntuFailure(detail)))
    }

    // -------------------------------------------------------- state + logging

    /** Status transition: preserves identity fields, persists, one log line. */
    private fun setState(
        state: UbuntuState,
        message: String,
        progress: Int,
        extra: (UbuntuStatus) -> UbuntuStatus = { it },
    ) {
        val base = _status.value.copy(state = state, message = message, progress = progress.coerceIn(0, 100))
        val next = extra(base)
        _status.value = next
        json.writeText(STATUS_FILE, next.toJson().toString())
        addLog("[state] ${state.name}: $message")
    }

    /** Fine-grained progress within one stage (no log spam, still persisted). */
    private fun updateProgress(progress: Int, message: String? = null) {
        val next = _status.value.copy(progress = progress.coerceIn(0, 100), message = message ?: _status.value.message)
        _status.value = next
        json.writeText(STATUS_FILE, next.toJson().toString())
    }

    private fun loadStatus(): UbuntuStatus {
        val raw = json.readText(STATUS_FILE) ?: return UbuntuStatus()
        return runCatching { UbuntuStatus.fromJson(JSONObject(raw)) }.getOrDefault(UbuntuStatus())
    }

    /** Appends to the tail-300 runtime log (synchronized — called from many threads). */
    fun addLog(line: String) {
        val stamped = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $line"
        synchronized(logLock) {
            _log.value = (_log.value + stamped).takeLast(MAX_LOG_LINES)
        }
    }

    private fun rootfsDir(): File = UbuntuFileSystem.rootfsDir(context)

    companion object {
        const val STATUS_FILE = "ubuntu_status.json"
        const val DEFAULT_TIMEOUT_MS = 180_000L
        const val STREAM_TIMEOUT_MS = 600_000L
        const val MAX_LOG_LINES = 300

        /** Bytes per MiB (Long math — rootfs sizes exceed Int range). */
        const val MB = 1024L * 1024L

        /** Hard pre-flight gate: refuse Install below this much free space. */
        const val MIN_FREE_BYTES = 1_000L * MB

        /** Warn-only threshold for a comfortable install/update. */
        const val WARN_FREE_BYTES = 2_000L * MB

        /** proot's own fatal exit code (guest command failures use 1/100/…). */
        const val PROOT_FATAL_EXIT = 255

        /** Output lines kept for failure diagnosis in [mapProotFailure]. */
        const val TAIL_LINES = 40

        /**
         * Pure heal decision (JVM-tested): a persisted busy state with NO live
         * pipeline must be coerced to ERROR ("interrupted") — otherwise every
         * button on the Ubuntu screen stays disabled forever (v0.1.14 bug:
         * navigating away mid-export cancelled the op but left EXPORTING
         * persisted). Returns null when nothing needs healing.
         */
        fun healState(state: UbuntuState, pipelineRunning: Boolean): UbuntuState? =
            if (state.busy && !pipelineRunning) UbuntuState.ERROR else null
    }
}
