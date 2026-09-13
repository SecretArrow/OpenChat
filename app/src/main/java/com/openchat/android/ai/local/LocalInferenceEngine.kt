package com.openchat.android.ai.local

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/** Engine lifecycle, observable from the chat UI (honest loading states). */
sealed class LocalEngineState {
    object Idle : LocalEngineState()
    data class Loading(val modelId: String, val name: String) : LocalEngineState()
    data class Ready(val modelId: String, val handle: Long) : LocalEngineState()
    data class Failed(val message: String) : LocalEngineState()
}

/**
 * Owns the single on-device inference slot:
 *  - exactly one loaded model at a time (unload on switch / trim / delete)
 *  - generation runs on a dedicated single-thread dispatcher → the UI thread
 *    is never blocked and chat navigation never kills the run (AppGraph
 *    singleton; the foreground service protects the process while active)
 *  - cancellation is cooperative: Kotlin raises it inside the token callback,
 *    the native loop unwinds cleanly, partial content is kept
 *
 * Architecture support: arm64-v8a, armeabi-v7a, x86_64 (CPU, mmap). Lifecycle
 * support: Application#onTrimMemory → [onTrimMemory]; generation activity →
 * AppGraph starts/stops RuntimeForegroundService.
 */
class LocalInferenceEngine(
    @Suppress("unused") private val context: Context,
    private val manager: LocalModelManager,
) {

    private val genDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + genDispatcher)

    private val state = MutableStateFlow<LocalEngineState>(LocalEngineState.Idle)
    val engineState: StateFlow<LocalEngineState> = state

    private val busy = AtomicBoolean(false)

    /** AppGraph hook: protect the process while generating (spec §14/§25). */
    var onGenerationActive: ((Boolean) -> Unit)? = null

    fun specFor(aiModelId: String): LocalModelSpec? = manager.specForAiId(aiModelId)

    fun isBusy(): Boolean = busy.get()

    /** Frees the loaded model (no-op when idle / different model). */
    suspend fun unload() = withContext(genDispatcher) {
        val s = state.value
        if (s is LocalEngineState.Ready) {
            LlamaBridge.free(s.handle)
            state.value = LocalEngineState.Idle
        }
        Unit
    }

    /** Called from Application#onTrimMemory — free weights when RAM is low. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !busy.get()) {
            scope.launch { unload() }
        }
    }

    /** Unloads before a model file is deleted. */
    fun unloadIfLoaded(modelId: String) {
        scope.launch {
            val s = state.value
            if (s is LocalEngineState.Ready && s.modelId == modelId) unload()
        }
    }

    /**
     * Streams one completion. [history] holds ordered (role, content) pairs;
     * it is trimmed to the context budget and sent through the model's own
     * chat template. Cancellation keeps partial content (ChatService contract).
     */
    suspend fun generate(
        spec: LocalModelSpec,
        history: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Double,
        onDelta: (String) -> Unit,
    ): Result<Unit> = withContext(genDispatcher) {
        val ctx = currentCoroutineContext()
        if (!LlamaBridge.isAvailable()) {
            return@withContext Result.failure(IllegalStateException(
                "This APK build does not include the on-device engine (libllamajni)."))
        }
        if (!manager.isDownloaded(spec.id)) {
            return@withContext Result.failure(IllegalStateException(
                "\"${spec.name}\" is not downloaded yet — Settings → Local models."))
        }

        busy.set(true)
        onGenerationActive?.invoke(true)
        try {
            // ---- ensure loaded (single slot) --------------------------------
            val current = state.value
            if (current is LocalEngineState.Ready && current.modelId != spec.id) {
                LlamaBridge.free(current.handle)
                state.value = LocalEngineState.Idle
            }
            var handle = (state.value as? LocalEngineState.Ready)
                ?.takeIf { it.modelId == spec.id }?.handle
            if (handle == null) {
                state.value = LocalEngineState.Loading(spec.id, spec.name)
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                val h = LlamaBridge.load(manager.fileFor(spec.id).absolutePath, N_CTX, threads)
                if (h <= 0L) {
                    val msg = "Failed to load \"${spec.name}\". Close other apps and retry " +
                        "(needs ≈${spec.ramHintMb} MB free RAM; invalid GGUF also causes this)."
                    state.value = LocalEngineState.Failed(msg)
                    Log.w(TAG, "load failed: $msg")
                    return@withContext Result.failure(IllegalStateException(msg))
                }
                handle = h
                state.value = LocalEngineState.Ready(spec.id, h)
            }

            // ---- generate ---------------------------------------------------
            val trimmed = LocalHistory.trim(history)
            var cancelCause: Throwable? = null
            val cb = object : LlamaBridge.Callback {
                override fun onToken(piece: String): Boolean = try {
                    onDelta(piece)
                    ctx.ensureActive()
                    true
                } catch (t: Throwable) {
                    cancelCause = t
                    false
                }
            }
            val finish = LlamaBridge.startCompletion(
                handle!!, trimmed, maxTokens.coerceIn(64, 1024), temperature.toFloat(), cb,
            )
            cancelCause?.let { throw it }
            when (finish) {
                "stop", "length", "cancelled" -> Result.success(Unit)
                "overflow" -> Result.failure(IllegalStateException(
                    "Conversation is longer than the 4096-token context — start a new chat."))
                else -> Result.failure(IllegalStateException(
                    "On-device engine error: $finish (see Settings → Local models)"))
            }
        } finally {
            busy.set(false)
            onGenerationActive?.invoke(false)
        }
    }

    companion object {
        private const val TAG = "OpenChat/LocalEngine"

        /** 4096 covers the trimmed prompt budget plus 1024 generated tokens. */
        const val N_CTX = 4096
    }
}
