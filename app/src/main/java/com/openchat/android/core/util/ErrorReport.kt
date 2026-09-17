package com.openchat.android.core.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.openchat.android.core.model.ErrorInfo
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the copyable "error + logs" report attached to every error surface
 * (spec §24/§26 honesty): device identity, the structured [ErrorInfo], any
 * extra sections (e.g. the live Ubuntu log) and every log section registered
 * by services ([registerProvider] — AppGraph wires the Ubuntu runtime log and
 * the failure output tail at startup).
 *
 * Motivation (v0.1.12 real-device reports): users had to hand-type what the
 * screen showed ("E: repository ubuntu focal is not signed") because there
 * was no way to copy the surrounding log — which contained the actual
 * `W: GPG error … NO_PUBKEY …` line that names the real cause. Every error
 * can now be copied in full, so a bug report carries its own evidence.
 *
 * [format] is pure and JVM-tested ([ErrorReportTest]); the context-dependent
 * collectors live in [deviceLines]/[build].
 */
object ErrorReport {

    private val providers = LinkedHashMap<String, () -> List<String>>()

    /** Registers (or replaces) a named log section provider. Thread-safe. */
    @Synchronized
    fun registerProvider(name: String, provider: () -> List<String>) {
        providers[name] = provider
    }

    @Synchronized
    fun removeProvider(name: String) {
        providers.remove(name)
    }

    /** Device/app identity lines — always the first section of a report. */
    fun deviceLines(context: Context): List<String> = buildList {
        add("app: OpenChat v${versionName(context)}")
        add(
            "device: ${Build.MANUFACTURER} ${Build.MODEL} — " +
                "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        )
        add("abi: ${Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty()}")
        add("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}")
        add("free space (app data): ${freeSpaceMb(context)} MB")
    }

    /**
     * Assembles the full report: device identity + [error] + [extraSections]
     * + every registered provider section (failures inside a provider never
     * break the report — the failing section is skipped).
     */
    fun build(
        context: Context,
        error: ErrorInfo?,
        extraSections: List<Pair<String, List<String>>> = emptyList(),
    ): String = format(
        deviceLines = deviceLines(context),
        error = error,
        extraSections = extraSections,
        providerSections = snapshotProviders(),
    )

    /** Pure report assembly (unit-tested). Empty sections are omitted. */
    fun format(
        deviceLines: List<String>,
        error: ErrorInfo?,
        extraSections: List<Pair<String, List<String>>>,
        providerSections: List<Pair<String, List<String>>>,
    ): String = buildString {
        appendLine("=== OpenChat error report ===")
        deviceLines.forEach { appendLine(it) }
        error?.let { e ->
            appendLine()
            appendLine("error: ${e.title}")
            appendLine(e.detail)
            if (e.causes.isNotEmpty()) {
                appendLine()
                appendLine("possible causes:")
                e.causes.forEach { appendLine("• $it") }
            }
            if (e.suggestions.isNotEmpty()) {
                appendLine()
                appendLine("what you can do:")
                e.suggestions.forEach { appendLine("→ $it") }
            }
        }
        (extraSections + providerSections).forEach { (name, lines) ->
            if (lines.isEmpty()) return@forEach
            appendLine()
            appendLine("--- $name ---")
            lines.forEach { appendLine(it) }
        }
    }.trimEnd() + "\n"

    /** Throwable → indented stack-trace section (for unexpected app errors). */
    fun stackTraceSection(t: Throwable, maxLines: Int = 24): List<String> {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().lineSequence().take(maxLines).toList()
    }

    /** Copies [text] to the system clipboard; false when no service exists. */
    fun copy(context: Context, text: String, label: String = "OpenChat error report"): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        return true
    }

    // ---------------------------------------------------------------- helpers

    @Synchronized
    private fun snapshotProviders(): List<Pair<String, List<String>>> =
        providers.mapNotNull { (name, provider) ->
            val lines = runCatching { provider() }.getOrNull() ?: return@mapNotNull null
            name to lines
        }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    private fun freeSpaceMb(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes / (1024 * 1024)
    }.getOrDefault(-1L)
}
