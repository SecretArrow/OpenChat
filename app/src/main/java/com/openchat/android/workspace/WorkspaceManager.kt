package com.openchat.android.workspace

import android.content.Context
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.Workspace
import com.openchat.android.core.storage.JsonStore
import com.openchat.android.core.util.Errors
import com.openchat.android.ubuntu.ErrorInfoException
import com.openchat.android.ubuntu.UbuntuFileSystem
import com.openchat.android.ubuntu.UbuntuRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Manages named workspaces inside the Ubuntu rootfs (spec §16):
 * each workspace is a real directory under `/root/workspaces/<name>` created on
 * the mapped host path, carrying its own provider/model defaults and env vars.
 * The list + current selection persist to `workspaces.json`
 * (`{"items":[…], "currentId": …}`).
 */
class WorkspaceManager(
    private val context: Context,
    private val ubuntu: UbuntuRuntime,
    private val json: JsonStore,
) {

    private val loaded: Pair<List<Workspace>, String?> = load()
    private val _workspaces: MutableStateFlow<List<Workspace>> = MutableStateFlow(loaded.first)
    val workspaces: StateFlow<List<Workspace>> = _workspaces

    private val _currentId: MutableStateFlow<String?> = MutableStateFlow(loaded.second)
    val currentId: StateFlow<String?> = _currentId

    /**
     * Creates a workspace directory under `/root/workspaces/<name>` inside the
     * rootfs (host-side via the mapped path) and registers it.
     *
     * The name is sanitized to `[A-Za-z0-9._-]`; blank names and names that are
     * not safe as a single path segment ("." / "..") are rejected.
     */
    fun create(
        name: String,
        providerId: String?,
        modelId: String?,
        envVars: Map<String, String>,
    ): Result<Workspace> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(
                ErrorInfoException(
                    ErrorInfo(
                        title = "Workspace name is required",
                        detail = "Enter a name for the workspace.",
                        suggestions = listOf("Use letters, digits, dot, dash or underscore"),
                    ),
                ),
            )
        }
        val safe = trimmed.replace(Regex("[^A-Za-z0-9._-]"), "-")
        if (safe.isEmpty() || safe == "." || safe == "..") {
            return Result.failure(
                ErrorInfoException(
                    ErrorInfo(
                        title = "Invalid workspace name",
                        detail = "“$name” cannot be used as a directory name.",
                        suggestions = listOf("Pick a name made of letters, digits, dot, dash or underscore"),
                    ),
                ),
            )
        }
        if (!ubuntu.isReady()) {
            return Result.failure(ErrorInfoException(Errors.ubuntuNotReady()))
        }
        val root = workspaceRoot()
        val dir = File(root, safe)
        if (dir.exists() && dir.list()?.isNotEmpty() == true) {
            return Result.failure(
                ErrorInfoException(
                    ErrorInfo(
                        title = "Workspace already exists",
                        detail = "The directory /root/workspaces/$safe already exists and is not empty.",
                        suggestions = listOf("Pick another name", "Open the existing workspace instead"),
                    ),
                ),
            )
        }
        if (!dir.mkdirs() && !dir.isDirectory) {
            return Result.failure(
                ErrorInfoException(
                    ErrorInfo(
                        title = "Could not create the workspace",
                        detail = "mkdir failed for ${dir.absolutePath} (storage full or permission denied).",
                        suggestions = listOf("Check free storage in Settings → Storage"),
                    ),
                ),
            )
        }
        val workspace = Workspace(
            id = UUID.randomUUID().toString(),
            name = safe,
            path = "$ROOT_PREFIX$safe",
            providerId = providerId,
            modelId = modelId,
            envVars = envVars,
        )
        _workspaces.value = _workspaces.value + workspace
        if (_currentId.value == null) _currentId.value = workspace.id
        persist()
        return Result.success(workspace)
    }

    /** Replaces the stored entry (e.g. after editing env vars / defaults). */
    fun update(w: Workspace) {
        _workspaces.value = _workspaces.value.map { if (it.id == w.id) w else it }
        persist()
    }

    /**
     * Deletes the registry entry AND the directory on the host-mapped rootfs
     * path (resolved through [UbuntuFileSystem.safeResolve] — traversal-proof).
     */
    fun delete(id: String) {
        val ws = _workspaces.value.firstOrNull { it.id == id } ?: return
        val rel = ws.path.removePrefix(ROOT_PREFIX)
        UbuntuFileSystem.safeResolve(workspaceRoot(), rel).onSuccess { dir ->
            if (dir.absolutePath != workspaceRoot().absolutePath) {
                dir.deleteRecursively()
            }
        }
        _workspaces.value = _workspaces.value.filterNot { it.id == id }
        if (_currentId.value == id) _currentId.value = null
        persist()
    }

    /** Marks [id] as the current workspace and bumps lastOpenedAt. */
    fun open(id: String) {
        val ws = _workspaces.value.firstOrNull { it.id == id } ?: return
        ws.lastOpenedAt = System.currentTimeMillis()
        _currentId.value = id
        persist()
    }

    /** The currently opened workspace, or null. */
    fun current(): Workspace? =
        _workspaces.value.firstOrNull { it.id == _currentId.value }

    // ------------------------------------------------------------------ internals

    private fun workspaceRoot(): File = UbuntuFileSystem.workspaceRoot(UbuntuFileSystem.rootfsDir(context))

    private fun persist() {
        val arr = JSONArray()
        _workspaces.value.forEach { arr.put(it.toJson()) }
        val doc = JSONObject()
            .put("items", arr)
            .put("currentId", _currentId.value ?: JSONObject.NULL)
        json.writeText(FILE, doc.toString())
    }

    private fun load(): Pair<List<Workspace>, String?> {
        val raw = json.readText(FILE) ?: return emptyList<Workspace>() to null
        return runCatching {
            val doc = JSONObject(raw)
            val arr = doc.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).mapNotNull { i ->
                runCatching { Workspace.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }
            val current = if (doc.isNull("currentId")) null else doc.optString("currentId")
            items to current
        }.getOrDefault(emptyList<Workspace>() to null)
    }

    companion object {
        const val FILE = "workspaces.json"

        /** Workspace paths are rootfs-relative (inside proot). */
        const val ROOT_PREFIX = "/root/workspaces/"
    }
}
