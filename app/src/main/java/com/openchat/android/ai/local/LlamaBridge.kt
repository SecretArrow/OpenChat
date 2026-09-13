package com.openchat.android.ai.local

/**
 * JNI bridge to the on-device GGUF engine (`libllamajni.so`, llama.cpp
 * pinned b10941). Safe on any JVM: when the native library is absent (unit
 * tests, or an APK built without the pinned llama.cpp source), [isAvailable]
 * is false and every call degrades to an honest no-op/error — never a crash,
 * never a fake success (spec §32).
 *
 * All methods are intended to be called from a single background thread
 * (LocalInferenceEngine serializes access via a limited dispatcher).
 */
object LlamaBridge {

    /** The JNI library could actually be loaded. */
    val available: Boolean = runCatching { System.loadLibrary("llamajni") }.isSuccess

    /** Streaming sink; returning false cleanly stops generation (cancel path). */
    interface Callback {
        fun onToken(piece: String): Boolean
    }

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    private external fun nativeStartCompletion(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temp: Float,
        callback: Callback,
    ): String?
    private external fun nativeRequestCancel()
    private external fun nativeFree(handle: Long)

    /** True only when the real engine is present and reports itself ready. */
    fun isAvailable(): Boolean =
        available && runCatching { nativeIsAvailable() }.getOrDefault(false)

    /**
     * Loads a GGUF model with mmap; [nGpuLayers] > 0 offloads weight layers to
     * the GPU when the build includes the Vulkan backend AND the device exposes
     * a Vulkan driver — otherwise the load transparently stays on CPU.
     * @return engine handle (>0), or 0 on failure (bad file / out of memory).
     */
    fun load(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int = 0): Long =
        if (available) {
            runCatching { nativeLoad(path, nCtx, nThreads, nGpuLayers) }.getOrDefault(0L)
        } else 0L

    /**
     * Runs one completion synchronously on the caller thread, streaming pieces
     * through [callback]. @return finish reason: "stop" | "length" | "cancelled"
     * | "overflow" | "unloaded" | "unavailable" | "no-messages" | "template"
     * | "tokenize" | "decode".
     */
    fun startCompletion(
        handle: Long,
        history: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        callback: Callback,
    ): String? {
        if (!available || history.isEmpty()) return "unavailable"
        return runCatching {
            nativeStartCompletion(
                handle,
                history.map { it.first }.toTypedArray(),
                history.map { it.second }.toTypedArray(),
                maxTokens,
                temperature,
                callback,
            )
        }.getOrElse { "decode" }
    }

    /** Asks the running loop (if any) to stop at the next token boundary. */
    fun requestCancel() {
        if (available) runCatching { nativeRequestCancel() }
    }

    /** Releases model + context memory. Safe to call with 0 / stale handles. */
    fun free(handle: Long) {
        if (available && handle > 0L) runCatching { nativeFree(handle) }
    }
}
