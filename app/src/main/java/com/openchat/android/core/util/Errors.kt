package com.openchat.android.core.util

import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.RepairAction
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Maps low-level failures into specific, actionable ErrorInfo (spec §24).
 * The UI never shows a bare "Unknown error".
 */
object Errors {

    fun network(t: Throwable, context: String): ErrorInfo {
        val title = "$context failed"
        return when (t) {
            is UnknownHostException -> ErrorInfo(
                title = title,
                detail = "Hostname could not be resolved: ${t.message ?: "unknown host"}",
                causes = listOf("No internet connection", "Wrong host/IP in URL", "DNS unavailable on device"),
                suggestions = listOf(
                    "Check Wi-Fi / mobile data",
                    "Verify the Base URL spelling and port",
                    "For Ollama on LAN, use the server IP, e.g. http://192.168.1.10:11434"
                ),
                retryable = true,
                repairAction = RepairAction.CHECK_CONNECTION,
            )
            is SocketTimeoutException -> ErrorInfo(
                title = title,
                detail = "The server did not respond in time (timeout).",
                causes = listOf("Server offline or overloaded", "Firewall blocking the port", "Model still loading on server"),
                suggestions = listOf(
                    "Retry in a few seconds",
                    "For Ollama, run 'ollama serve' on the host or check the port",
                    "Try a smaller/faster model first to warm up the server"
                ),
                retryable = true,
            )
            is ConnectException, is NoRouteToHostException -> ErrorInfo(
                title = title,
                detail = "Connection refused or unreachable: ${t.message}",
                causes = listOf("Service not listening on that address/port", "Wrong protocol (http vs https)"),
                suggestions = listOf(
                    "Verify the service is running",
                    "Check the Base URL, e.g. http://IP:11434 for Ollama",
                    "For localhost-in-Ubuntu use 127.0.0.1 inside the userspace"
                ),
                retryable = true,
            )
            is SSLException -> ErrorInfo(
                title = title,
                detail = "TLS/SSL error: ${t.message}",
                causes = listOf("Invalid or self-signed certificate", "Wrong port serving plain HTTP"),
                suggestions = listOf("Use http:// for LAN Ollama", "Use a valid https endpoint for cloud providers"),
                retryable = false,
            )
            is IOException -> ErrorInfo(
                title = title,
                detail = "I/O error: ${t.message ?: t.javaClass.simpleName}",
                causes = listOf("Network dropped mid-request", "Storage/IO problem"),
                suggestions = listOf("Retry", "Check connection stability"),
                retryable = true,
            )
            else -> ErrorInfo(
                title = title,
                detail = t.message ?: t.javaClass.simpleName,
                causes = listOf(t.javaClass.simpleName),
                suggestions = listOf("Retry", "View logs for details"),
                retryable = true,
            )
        }
    }

    fun ubuntuNotReady(): ErrorInfo = ErrorInfo(
        title = "Ubuntu userspace is not ready",
        detail = "This action requires an installed and configured Ubuntu userspace.",
        causes = listOf(
            "Ubuntu has never been installed",
            "Installation is still in progress",
            "The rootfs is corrupted or was cleared by the system"
        ),
        suggestions = listOf(
            "Open Settings → Ubuntu and run Install",
            "If installed, use Repair (re-extract + reconfigure)",
            "As last resort use Reset, then Install again"
        ),
        retryable = false,
        repairAction = RepairAction.INSTALL_UBUNTU,
    )

    fun ubuntuFailure(causeDetail: String): ErrorInfo = ErrorInfo(
        title = "Ubuntu runtime error",
        detail = causeDetail,
        causes = listOf(
            "Corrupted rootfs (incomplete download/extract)",
            "Missing executable (proot/bash/opencode deleted)",
            "Network/mirror failure during apt operations",
            "Permission/SELinux restriction on this device",
            "Storage pressure on the app's data volume (check Settings → Ubuntu → Run diagnostics for the real numbers)"
        ),
        suggestions = listOf(
            "Use Repair to re-verify and re-extract the rootfs",
            "Use Reset for a clean re-install",
            "Run diagnostics (Settings → Ubuntu) for an honest environment report"
        ),
        retryable = true,
        repairAction = RepairAction.UBUNTU_REPAIR,
    )

    fun opencodeFailure(causeDetail: String): ErrorInfo = ErrorInfo(
        title = "OpenCode CLI error",
        detail = causeDetail,
        causes = listOf(
            "OpenCode is not installed in the Ubuntu userspace",
            "Node.js missing or too old (needs >= 18)",
            "No provider/model configured for OpenCode",
            "Command exited with an error"
        ),
        suggestions = listOf(
            "Open Settings → OpenCode → Install/Reinstall",
            "Check the OpenCode log output",
            "Verify a provider + API key is configured and synced"
        ),
        retryable = true,
        repairAction = RepairAction.OPENCODE_REINSTALL,
    )

    fun ollamaConnection(baseUrl: String): ErrorInfo = ErrorInfo(
        title = "Cannot connect to Ollama",
        detail = "No response from $baseUrl",
        causes = listOf(
            "Ollama server is not running on the host",
            "Wrong IP or port",
            "Ollama bound to 127.0.0.1 only (must bind 0.0.0.0 for LAN access)"
        ),
        suggestions = listOf(
            "On the host run: OLLAMA_HOST=0.0.0.0 ollama serve",
            "Test with: curl http://IP:11434/api/tags",
            "Verify device and host are on the same network"
        ),
        retryable = true,
        repairAction = RepairAction.CHECK_CONNECTION,
    )

    fun providerAuth(providerName: String): ErrorInfo = ErrorInfo(
        title = "Authentication failed ($providerName)",
        detail = "The provider rejected the API key (HTTP 401/403).",
        causes = listOf("API key missing, revoked or from another account", "Key not saved for this provider"),
        suggestions = listOf("Open Settings → Providers → edit → replace the API key", "Run Test Connection after saving"),
        retryable = false,
    )
}
