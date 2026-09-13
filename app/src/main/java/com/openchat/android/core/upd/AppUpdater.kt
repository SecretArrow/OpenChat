package com.openchat.android.core.upd

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.app.DownloadManager
import android.content.Intent
import android.os.Environment
import androidx.core.app.NotificationCompat
import com.openchat.android.OpenChatApp
import com.openchat.android.core.net.Http
import com.openchat.android.core.storage.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * In-app update check against the public GitHub Releases of this repository
 * (the distribution channel per spec §21). Uses the public GitHub API — no
 * token is involved, no credential ever reaches the app (spec security rules).
 *
 * The APK asset matching the device ABI is selected the same honest way the
 * build splits ABIs (spec §2/§18): OpenChat-<abi>.apk for the first supported
 * ABI, falling back to the first OpenChat-*.apk asset.
 */
object AppUpdater {

    const val RELEASES_URL = "https://github.com/SecretArrow/OpenChat/releases"
    private const val API_LATEST =
        "https://api.github.com/repos/SecretArrow/OpenChat/releases/latest"

    data class Release(
        val tag: String,        // e.g. "v0.1.4"
        val name: String,
        val notes: String,      // release body (markdown)
        val releaseUrl: String, // html url of the release page
        val apkName: String,    // selected per-ABI asset
        val apkSize: Long,
        val apkUrl: String,     // browser_download_url of the selected asset
    )

    /** True when [latestTag] is strictly newer than [currentVersion] ("0.1.4"). */
    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        val l = semver(latestTag) ?: return false
        val c = semver(currentVersion) ?: return false
        for (i in 0 until maxOf(l.size, c.size)) {
            val li = l.getOrElse(i) { 0 }
            val ci = c.getOrElse(i) { 0 }
            if (li != ci) return li > ci
        }
        return false
    }

    private fun semver(raw: String): List<Int>? {
        val clean = raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+').trim()
        if (clean.isEmpty()) return null
        val parts = clean.split('.')
        if (parts.isEmpty() || parts.any { it.isBlank() || it.toIntOrNull() == null }) return null
        return parts.map { it.toInt() }
    }

    /** Honest per-ABI asset pick (spec §2/§18): exact ABI first, then any APK. */
    fun pickAsset(
        assets: List<Pair<String, Long>>,
        supportedAbis: List<String>,
    ): Pair<String, Long>? {
        if (assets.isEmpty()) return null
        val apks = assets.filter {
            it.first.startsWith("OpenChat-") && it.first.endsWith(".apk")
        }
        if (apks.isEmpty()) return null
        for (abi in supportedAbis) {
            val exact = apks.firstOrNull { it.first == "OpenChat-$abi.apk" }
            if (exact != null) return exact
        }
        return apks.first()
    }

    /** Query the latest GitHub release and select the APK for this device. */
    suspend fun checkLatest(supportedAbis: List<String>): Result<Release> =
        withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(
                    Http.newRequest(API_LATEST)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                ).execute().use { resp ->
                    require(resp.isSuccessful) { "GitHub API HTTP ${resp.code}" }
                    val body = resp.body?.string().orEmpty()
                    require(body.isNotEmpty()) { "Empty response from GitHub API" }
                    val o = JSONObject(body)
                    val arr: JSONArray = o.optJSONArray("assets") ?: JSONArray()
                    val assets = ArrayList<Pair<String, Long>>(arr.length())
                    val urls = HashMap<String, String>(arr.length())
                    for (i in 0 until arr.length()) {
                        val a = arr.getJSONObject(i)
                        val n = a.optString("name")
                        assets += n to a.optLong("size", 0L)
                        urls[n] = a.optString("browser_download_url")
                    }
                    val picked = pickAsset(assets, supportedAbis)
                        ?: error("No OpenChat APK asset found in the latest release")
                    Release(
                        tag = o.optString("tag_name").ifBlank { "?" },
                        name = o.optString("name").ifBlank { o.optString("tag_name") },
                        notes = o.optString("body"),
                        releaseUrl = o.optString("html_url").ifBlank { RELEASES_URL },
                        apkName = picked.first,
                        apkSize = picked.second,
                        apkUrl = urls[picked.first].orEmpty(),
                    )
                }
            }
        }

    /**
     * Queue the APK in the system DownloadManager. On completion the system
     * notification opens the APK with the package-archive MIME, which routes
     * into the system installer (signature-verified update over the existing
     * installation — the whole point of the persistent release keystore).
     */
    fun enqueueApkDownload(context: Context, release: Release): Long {
        val request = DownloadManager.Request(android.net.Uri.parse(release.apkUrl)).apply {
            setTitle("OpenChat ${release.tag}")
            setDescription("Update package (${release.apkName})")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, release.apkName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    fun humanSize(bytes: Long): String = when {
        bytes >= (1L shl 20) -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= (1L shl 10) -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ------------------------------------------------------------------
    // Silent start-up check (spec §26 — lazy, never blocks launch):
    // at most once per 24 h while "Auto-check for updates" is on. A newer
    // release posts one subtle notification; tapping it opens the app's
    // Updates screen (About), which offers the matching per-ABI APK.
    // ------------------------------------------------------------------

    private const val STATE_FILE = "update_check.json"
    const val CHANNEL_UPDATES = "updates"
    private const val NOTIFICATION_ID = 4101

    /** Result of a silent start-up check. */
    data class AutoCheckOutcome(val release: Release?, val checked: Boolean)

    suspend fun autoCheck(context: Context, json: JsonStore): AutoCheckOutcome =
        withContext(Dispatchers.IO) {
            val state = runCatching {
                json.readText(STATE_FILE)?.let { JSONObject(it) }
            }.getOrNull() ?: JSONObject()
            val last = state.optLong("lastCheck", 0L)
            val now = System.currentTimeMillis()
            if (now - last < 24L * 60L * 60L * 1000L) {
                return@withContext AutoCheckOutcome(null, checked = false)
            }
            val result = checkLatest(android.os.Build.SUPPORTED_ABIS.toList())
            state.put("lastCheck", now)
            json.writeText(STATE_FILE, state.toString())
            val release = result.getOrNull()?.takeIf {
                isNewer(it.tag, com.openchat.android.BuildConfig.VERSION_NAME)
            }
            AutoCheckOutcome(release, checked = true)
        }

    /** Posts (idempotently) the "update available" notification. */
    fun notifyUpdate(context: Context, release: Release) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                "Updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "New OpenChat releases" },
        )
        val intent = Intent(context, com.openchat.android.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(OpenChatApp.EXTRA_OPEN_UPDATES, true)
        }
        val pi = PendingIntent.getActivity(
            context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OpenChat ${release.tag} available")
            .setContentText("Tap to review and install the update (${humanSize(release.apkSize)}).")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("A new version of OpenChat is available. Tap to open " +
                        "Settings → About → Updates, where you can download the " +
                        "APK built for this device (${release.apkName})."),
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }
}
