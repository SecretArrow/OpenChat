package com.openchat.android.core.storage

import org.json.JSONObject

/**
 * App-wide settings (spec §27) persisted as JSON, restored on restart.
 */
data class TerminalSettings(
    val fontSizeSp: Float = 12f,
    val fontFamily: String = "monospace",
    val cursorBlink: Boolean = true,
    val scrollback: Int = 2000,
    val theme: String = "dark",
    val showExtraKeys: Boolean = true,
)

data class AppSettings(
    val appearance: String = "dark",            // dark | light | system
    val dynamicColors: Boolean = false,
    val chatBackend: String = "DIRECT",         // DIRECT | OPENCODE
    val opencodeAutoSyncConfig: Boolean = true, // §29 sync model config on model switch
    val terminal: TerminalSettings = TerminalSettings(),
    val defaultWorkspaceId: String? = null,
    val prootUrlOverride: String? = null,       // advanced: custom proot download URL
    val rootfsUrlOverride: String? = null,      // advanced: custom rootfs tarball URL
    val keepScreenOnInTerminal: Boolean = false,
    val localGpu: Boolean = true,               // Vulkan offload for on-device models (CPU fallback)
    val updateAutoCheck: Boolean = true,        // weekly-style silent update check on app start
) {
    fun toJson(): JSONObject = JSONObject()
        .put("appearance", appearance)
        .put("dynamicColors", dynamicColors)
        .put("chatBackend", chatBackend)
        .put("opencodeAutoSyncConfig", opencodeAutoSyncConfig)
        .put("terminal", JSONObject()
            .put("fontSizeSp", terminal.fontSizeSp.toDouble())
            .put("fontFamily", terminal.fontFamily)
            .put("cursorBlink", terminal.cursorBlink)
            .put("scrollback", terminal.scrollback)
            .put("theme", terminal.theme)
            .put("showExtraKeys", terminal.showExtraKeys))
        .put("defaultWorkspaceId", defaultWorkspaceId ?: JSONObject.NULL)
        .put("prootUrlOverride", prootUrlOverride ?: JSONObject.NULL)
        .put("rootfsUrlOverride", rootfsUrlOverride ?: JSONObject.NULL)
        .put("keepScreenOnInTerminal", keepScreenOnInTerminal)
        .put("localGpu", localGpu)
        .put("updateAutoCheck", updateAutoCheck)

    companion object {
        fun fromJson(o: JSONObject): AppSettings {
            val t = o.optJSONObject("terminal") ?: JSONObject()
            return AppSettings(
                appearance = o.optString("appearance", "dark"),
                dynamicColors = o.optBoolean("dynamicColors", false),
                chatBackend = o.optString("chatBackend", "DIRECT"),
                opencodeAutoSyncConfig = o.optBoolean("opencodeAutoSyncConfig", true),
                terminal = TerminalSettings(
                    fontSizeSp = t.optDouble("fontSizeSp", 12.0).toFloat(),
                    fontFamily = t.optString("fontFamily", "monospace"),
                    cursorBlink = t.optBoolean("cursorBlink", true),
                    scrollback = t.optInt("scrollback", 2000),
                    theme = t.optString("theme", "dark"),
                    showExtraKeys = t.optBoolean("showExtraKeys", true),
                ),
                defaultWorkspaceId = if (o.isNull("defaultWorkspaceId")) null else o.optString("defaultWorkspaceId"),
                prootUrlOverride = if (o.isNull("prootUrlOverride")) null else o.optString("prootUrlOverride"),
                rootfsUrlOverride = if (o.isNull("rootfsUrlOverride")) null else o.optString("rootfsUrlOverride"),
                keepScreenOnInTerminal = o.optBoolean("keepScreenOnInTerminal", false),
                localGpu = o.optBoolean("localGpu", true),
                updateAutoCheck = o.optBoolean("updateAutoCheck", true),
            )
        }
    }
}

class SettingsStore(private val json: JsonStore) {

    private val state = kotlinx.coroutines.flow.MutableStateFlow(load())
    val settings = state

    private fun load(): AppSettings =
        runCatching {
            json.readText(FILE)?.let { AppSettings.fromJson(JSONObject(it)) }
        }.getOrNull() ?: AppSettings()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        state.value = next
        json.writeText(FILE, next.toJson().toString())
    }

    companion object {
        const val FILE = "settings.json"
    }
}
