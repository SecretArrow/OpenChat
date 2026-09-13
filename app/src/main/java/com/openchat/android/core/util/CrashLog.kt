package com.openchat.android.core.util

import android.content.Context
import android.os.Build
import com.openchat.android.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device crash diagnostics (spec §26: errors must be honest and actionable).
 *
 * Installs a default UncaughtExceptionHandler that writes a full report to
 * `filesDir/crash/last_crash.txt` and THEN delegates to the previous handler,
 * so a crash is recorded — never suppressed. The report is viewable and
 * copyable from Settings → About → Crash log, which makes every crash
 * diagnosable without adb.
 */
object CrashLog {

    private const val DIR = "crash"
    private const val FILE = "last_crash.txt"

    @Volatile
    private var dir: File? = null

    /** Installs the recorder exactly once, before any other app code runs. */
    fun install(context: Context) {
        if (dir != null) return
        val d = File(context.filesDir, DIR)
        dir = d
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeReport(d, t, e) }
                .onFailure { Redact.e("OpenChat/CrashLog", "failed to persist crash report", it) }
            // Never swallow: the system dialog / process teardown must proceed.
            previous?.uncaughtException(t, e)
        }
    }

    private fun writeReport(d: File, t: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val report = buildString {
            appendLine("Open Chat crash report")
            appendLine("time   : $stamp")
            appendLine("app    : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("abi    : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
            appendLine("thread : ${t.name}")
            appendLine()
            appendLine(sw.toString())
        }
        d.mkdirs()
        File(d, FILE).writeText(report)
    }

    private fun reportFile(): File? =
        dir?.let { File(it, FILE) }?.takeIf { it.exists() && it.length() > 0 }

    /** Full report text, or null when no crash has been recorded. */
    fun read(): String? =
        reportFile()?.let { f -> runCatching { f.readText() }.getOrNull() }

    /** Epoch millis of the last recorded crash, or null. */
    fun lastTimeMillis(): Long? = reportFile()?.lastModified()

    /** Deletes the recorded report (called from the About screen). */
    fun clear(): Boolean = reportFile()?.delete() ?: true
}
