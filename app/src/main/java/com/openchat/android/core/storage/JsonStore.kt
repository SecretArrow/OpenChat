package com.openchat.android.core.storage

import android.util.Log
import java.io.File

/**
 * Tiny JSON-file persistence layer under filesDir/data (spec §25).
 * All writes are atomic (tmp file + rename). Never store raw secrets here.
 */
class JsonStore(private val baseDir: File) {

    val dataDir: File = File(baseDir, "data").apply { mkdirs() }
    val conversationsDir: File = File(dataDir, "conversations").apply { mkdirs() }

    fun file(name: String): File = File(dataDir, name)

    fun readText(name: String): String? {
        val f = file(name)
        if (!f.exists()) return null
        return runCatching { f.readText() }
            .onFailure { Log.w(TAG, "read failed: $name: ${it.message}") }
            .getOrNull()
    }

    fun writeText(name: String, content: String): Boolean {
        val f = file(name)
        val tmp = File(f.parentFile, f.name + ".tmp")
        return runCatching {
            tmp.writeText(content)
            if (!tmp.renameTo(f)) {
                f.delete()
                check(tmp.renameTo(f)) { "atomic rename failed for $name" }
            }
            true
        }.onFailure {
            Log.w(TAG, "write failed: $name: ${it.message}")
            tmp.delete()
        }.getOrDefault(false)
    }

    fun delete(name: String) {
        file(name).delete()
    }

    companion object {
        private const val TAG = "OpenChat/JsonStore"
    }
}
