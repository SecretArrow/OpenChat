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
 *    configure (DNS/APT/hosts) → apt tools → Node.js → smoke test → READY.
 *  - [repair]: re-verify/re-extract over the existing rootfs (keeps /root),
 *    `apt-get -f install`, smoke test.
 *  - [update]: apt update + upgrade + tools re-check.
 *  - [export]: pack the whole rootfs into a user-picked .tar.gz backup.
 *  - [import]: restore a .tar.gz backup into a fresh, validated rootfs.
 *  - [reset]: delete the rootfs and cached tarballs.
 *  - [exec]/[execStream]: run real commands inside the rootfs via proot.
 *  - [sessionCommand]: the SessionSpec used for every interactive PTY session.
 *
 * Status is persisted to `ubuntu_status.json` on every transition; busy states
 * found at app start are coerced to ERROR ("interrupted") — the app never
 * pretends a killed download is still running. Every step streams into [log]
 * (tail 300). UI only talks to this class through AppGraph (spec §17).
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
     * Optional post-install continuation (OpenCode install), wired by AppGraph.
     * A hook failure keeps Ubuntu READY — it must never corrupt the runtime state.
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
        val loaded = _status.value
        if (loaded.state.busy) {
            setState(
                UbuntuState.ERROR,
                "The previous operation was interrupted when the app was killed — use Repair or Reset.",
                0,
            )
        }
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
            UbuntuFileSystem.prootBin(context).exists()

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

    private suspend fun runPipeline(repair: Boolean): Result<Unit> {
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

        // ---- EXTRACTING (fresh install clears the old rootfs; repair keeps /root) ----
        val rootfs = rootfsDir()
        if (!repair) {
            addLog("Clearing any previous rootfs…")
            rootfs.deleteRecursively()
        }
        setState(UbuntuState.EXTRACTING, "Extracting rootfs…", 55)
        installer.extract(tarball, rootfs).getOrElse { return failStep("Extraction failed", it) }
        setState(UbuntuState.EXTRACTING, "Rootfs extracted (${rootfs.absolutePath})", 65)

        // ---- CONFIGURING ----
        setState(UbuntuState.CONFIGURING, "Configuring DNS + APT sources…", 72)
        installer.configure(rootfs, variant).getOrElse { return failStep("Configuration failed", it) }

        // ---- proot binary (needed for every exec below) ----
        setState(UbuntuState.CONFIGURING, "Ensuring the proot binary…", 76)
        proot.ensureProot().getOrElse { return failStep("proot download failed", it) }

        // ---- INSTALLING_PACKAGES (real apt run, streamed to the log) ----
        setState(UbuntuState.INSTALLING_PACKAGES, "Installing base packages (apt)…", 80)
        runChecked("apt-get update")
            .getOrElse { return failStep("apt-get update failed", it) }
        runChecked(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                "curl ca-certificates xz-utils git python3 python3-pip wget procps sudo",
        ).getOrElse { return failStep("apt-get install failed", it) }
        if (repair) {
            // Fix any broken partial state left over in the previous install.
            runChecked("DEBIAN_FRONTEND=noninteractive apt-get -f install -y")
                .getOrElse { return failStep("apt-get -f install failed", it) }
        }

        // ---- INSTALLING_TOOLS (idempotent re-check + verified Node.js) ----
        setState(UbuntuState.INSTALLING_TOOLS, "Installing tools + Node.js…", 86)
        installer.installTools(execStreamFn(), ::addLog).getOrElse { return failStep("Tool installation failed", it) }
        installer.installNode(execStreamFn(), execFn(), ::addLog, abi())
            .getOrElse { return failStep("Node.js installation failed", it) }

        // ---- smoke test ----
        setState(UbuntuState.INSTALLING_TOOLS, "Running smoke test…", 95)
        val smoke = runCommand("bash --version && git --version && python3 -V && node -v")
        val smokeOut = smoke.getOrElse { return failStep("Smoke test failed", it) }
        val smokeOk = smokeOut.contains("bash", ignoreCase = true) &&
            smokeOut.contains("git version", ignoreCase = true) &&
            smokeOut.contains("python", ignoreCase = true)
        if (!smokeOk) {
            return failStep(
                "Smoke test failed — unexpected tool output: ${smokeOut.lineSequence().firstOrNull()?.take(200) ?: "(empty)"}",
                IllegalStateException("smoke mismatch"),
            )
        }
        if (!smokeOut.contains("v20")) {
            addLog("[warn] node -v did not report v20 — Node may be missing; OpenCode install will re-check")
        }

        // ---- READY ----
        setState(
            UbuntuState.READY,
            "Ubuntu ready",
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
        addLog("Ubuntu ${variant.codename} ($arch) is ready")

        postInstallHook?.let { hook ->
            try {
                hook()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addLog("[warn] post-install step failed: ${e.message ?: e.javaClass.simpleName}")
                setState(UbuntuState.READY, "OpenCode not installed yet — install from Settings → OpenCode", 100)
            }
        }
        return Result.success(Unit)
    }

    /** apt update + full upgrade + tools re-check (spec §4 "Update"). */
    suspend fun update(): Result<Unit> {
        ensureReady().getOrElse { return Result.failure(it) }
        if (!pipelineRunning.compareAndSet(false, true)) {
            return Result.failure(
                ErrorInfoException(Errors.ubuntuFailure("An Ubuntu operation is already running — wait for it to finish")),
            )
        }
        try {
            setState(UbuntuState.UPDATING, "Updating packages (apt update + upgrade)…", 10)
            runChecked("apt-get update").getOrElse { return failStep("apt-get update failed", it) }
            runChecked("DEBIAN_FRONTEND=noninteractive apt-get -y upgrade")
                .getOrElse { return failStep("apt-get upgrade failed", it) }
            setState(UbuntuState.UPDATING, "Re-checking tools…", 70)
            installer.installTools(execStreamFn(), ::addLog).getOrElse { return failStep("Tool re-check failed", it) }
            installer.installNode(execStreamFn(), execFn(), ::addLog, abi())
                .getOrElse { return failStep("Node.js re-check failed", it) }
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
            val prevState = _status.value.state
            setState(
                if (prevState == UbuntuState.READY) UbuntuState.READY else prevState,
                "Userspace exported — keep this archive safe, Import restores it exactly",
                100,
            )
            return Result.success(Unit)
        } catch (e: CancellationException) {
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
            if (e is CancellationException) throw e
            return failStep("Reset failed", e)
        } finally {
            pipelineRunning.set(false)
        }
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
                                while (tail.size > TAIL_LINES) tail.removeFirst()
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
                return@withContext Result.failure(
                    ErrorInfoException(Errors.ubuntuFailure("Command timed out after ${timeoutMs}ms: $cmd")),
                )
            }
            reader.join(5000)
            val code = proc.exitValue()
            if (code == PROOT_FATAL_EXIT) {
                // Exit 255 is proot's own fatal code (apt failures return 1/100).
                // Map its log signatures to actionable errors, keep honest detail.
                val lines = synchronized(tail) { tail.toList() }
                mapProotFailure(lines)?.let { return@withContext Result.failure(it) }
            }
            Result.success(code)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(
                ErrorInfoException(
                    Errors.ubuntuFailure("Failed to run command: ${e.message ?: e.javaClass.simpleName} — $cmd"),
                ),
            )
        }
    }

    /**
     * Maps proot's own fatal output (exit 255) onto an actionable [ErrorInfo]
     * instead of a bare "exited with code 255" — the difference between an apt
     * failure and an environment problem lives in the log lines. Returns null
     * when the output holds no known proot signature (caller keeps the code).
     */
    private fun mapProotFailure(lines: List<String>): ErrorInfoException? {
        fun firstLine(vararg needles: String): String? =
            lines.firstOrNull { line -> needles.any { line.contains(it, ignoreCase = true) } }

        firstLine("can't create temporary", "can't create glue", "PROOT_TMP_DIR")?.let { hit ->
            return ErrorInfoException(
                ErrorInfo(
                    title = "proot could not create its temporary files",
                    detail = hit.trim(),
                    causes = listOf(
                        "proot writes glue/temp files to PROOT_TMP_DIR at startup",
                        "that directory was missing, not writable, or wiped by the system",
                    ),
                    suggestions = listOf(
                        "Run Repair — it recreates the app's writable directories",
                        "If it persists, Reset and Install again",
                        "Report this bug if it happens on every attempt",
                    ),
                    retryable = true,
                ),
            )
        }
        firstLine("No space left on device", "ENOSPC")?.let { hit ->
            return ErrorInfoException(
                ErrorInfo(
                    title = "Storage is full",
                    detail = hit.trim(),
                    causes = listOf("The rootfs and its packages need more space than is available"),
                    suggestions = listOf(
                        "Free up storage (Settings → Storage shows usage)",
                        "Remove large files from /root inside Ubuntu",
                        "As last resort Reset, then Install again",
                    ),
                    retryable = true,
                ),
            )
        }
        firstLine("Exec format error")?.let { hit ->
            return ErrorInfoException(
                ErrorInfo(
                    title = "Wrong architecture",
                    detail = hit.trim(),
                    causes = listOf(
                        "The rootfs (or an imported archive) was built for a different CPU architecture",
                        "for example an amd64/x86 image on an arm64 phone",
                    ),
                    suggestions = listOf(
                        "Use Reset, then Install — the pinned catalog always matches this device",
                        "For Import: pick an archive exported from the same architecture",
                    ),
                    retryable = false,
                ),
            )
        }
        firstLine("Permission denied")?.let { hit ->
            if (hit.contains("rootfs") || hit.contains("proot") || lines.any { it.contains("proot", ignoreCase = true) }) {
                return ErrorInfoException(
                    ErrorInfo(
                        title = "Permission denied on the Ubuntu files",
                        detail = hit.trim(),
                        causes = listOf(
                            "The app's private directories became inaccessible",
                            "A device cleaner/booster app or SELinux policy may have interfered",
                        ),
                        suggestions = listOf(
                            "Run Repair to recreate the writable directories",
                            "Close cleaner/booster apps that touch app data",
                            "As last resort Reset, then Install again",
                        ),
                        retryable = true,
                    ),
                )
            }
        }
        if (lines.any { it.contains("proot error", ignoreCase = true) }) {
            val first = lines.firstOrNull { it.contains("proot error", ignoreCase = true) } ?: ""
            return ErrorInfoException(
                ErrorInfo(
                    title = "proot failed to start the command",
                    detail = first.trim(),
                    causes = listOf("proot reported a fatal error before the command could run"),
                    suggestions = listOf(
                        "Check the Ubuntu log below for the full proot output",
                        "Run Repair; if it persists, Reset and Install again",
                    ),
                    retryable = true,
                ),
            )
        }
        return null
    }

    /** Checked run used inside the install pipeline (bypasses the READY gate). */
    private suspend fun runChecked(cmd: String): Result<Unit> =
        runCommandStream(cmd, "/root", emptyMap(), STREAM_TIMEOUT_MS) { line -> addLog(line) }.fold(
            onSuccess = { code ->
                if (code == 0) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        ErrorInfoException(Errors.ubuntuFailure("'$cmd' exited with code $code (see the Ubuntu log)")),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )

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
    }
}
