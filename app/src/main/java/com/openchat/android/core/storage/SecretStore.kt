package com.openchat.android.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.openchat.android.core.util.Redact
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secure secrets manager (spec §12).
 *
 * - Keys are generated inside AndroidKeyStore (hardware-backed when available)
 *   and NEVER leave it in plaintext.
 * - Ciphertext (AES-256-GCM, random 12-byte IV per entry) is stored in a
 *   private-mode preferences file.
 * - Raw values are never logged; the UI only ever shows "••••••••••••".
 */
class SecretStore(private val prefsFile: File) {

    private val keyAlias = "openchat-master-v1"

    @Synchronized
    fun put(id: String, value: String): Boolean {
        if (id.isBlank() || value.isEmpty()) return false
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, masterKey())
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) +
                ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
            val all = readAll().toMutableMap()
            all[id] = payload
            writeAll(all)
            true
        }.onFailure {
            Log.e(TAG, "put failed for ${Redact.idOf(id)}: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    @Synchronized
    fun get(id: String): String? {
        val payload = readAll()[id] ?: return null
        return runCatching {
            val parts = payload.split(":")
            require(parts.size == 2) { "corrupt payload" }
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.onFailure {
            Log.e(TAG, "get failed for ${Redact.idOf(id)}: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    @Synchronized
    fun delete(id: String): Boolean {
        val all = readAll().toMutableMap()
        val removed = all.remove(id) != null
        if (removed) writeAll(all)
        return removed
    }

    fun exists(id: String): Boolean = readAll().containsKey(id)

    fun listIds(): List<String> = readAll().keys.toList()

    // ------------------------------------------------------------------ impl

    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun readAll(): Map<String, String> {
        if (!prefsFile.exists()) return emptyMap()
        return runCatching {
            val out = mutableMapOf<String, String>()
            prefsFile.readLines().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) out[line.substring(0, idx)] = line.substring(idx + 1)
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(all: Map<String, String>): Boolean {
        val tmp = File(prefsFile.parentFile, prefsFile.name + ".tmp")
        return runCatching {
            tmp.writeText(all.entries.joinToString("\n") { (k, v) -> "$k=$v" })
            if (!tmp.renameTo(prefsFile)) {
                prefsFile.delete()
                check(tmp.renameTo(prefsFile))
            }
            true
        }.onFailure { Log.e(TAG, "persist failed: ${it.message}") }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "OpenChat/SecretStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
