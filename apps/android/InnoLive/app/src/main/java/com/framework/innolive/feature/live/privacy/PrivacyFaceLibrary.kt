package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PrivacyRegisteredFace(
    val id: String,
    val name: String,
    val embedding: FloatArray,
    val registeredAtMillis: Long,
)

/** App-install-scoped, encrypted embeddings. Face images are never persisted. */
internal class PrivacyFaceLibrary(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "privacy-local-faces.bin"))
    private var entries = emptyList<PrivacyRegisteredFace>()
    private var observedRevision = -1L
    private var healthy = true

    init { reload() }

    @Synchronized fun snapshot(): List<PrivacyRegisteredFace> {
        if (observedRevision != revision.get()) reload()
        return if (healthy) entries.toList() else emptyList()
    }

    @Synchronized fun add(name: String, embedding: FloatArray) {
        snapshot()
        val trimmed = name.trim()
        val normalized = checkNotNull(PrivacyFaceMath.normalize(embedding)) { "Invalid face embedding" }
        require(trimmed.isNotEmpty() && trimmed.length <= 40 && entries.size < 20)
        check(entries.none { PrivacyFaceMath.cosine(it.embedding, normalized) >= .75f }) { "Duplicate face" }
        save(entries + PrivacyRegisteredFace(UUID.randomUUID().toString(), trimmed, normalized, System.currentTimeMillis()))
    }

    @Synchronized fun delete(id: String) {
        snapshot()
        save(entries.filterNot { it.id == id })
    }

    @Synchronized fun deleteAll() {
        snapshot()
        save(emptyList())
    }

    @Synchronized private fun reload() {
        try {
            val data = try { file.openRead().use { it.readBytes() } } catch (_: java.io.FileNotFoundException) {
                entries = emptyList()
                healthy = true
                observedRevision = revision.get()
                return
            }
            val document = JSONObject(String(decrypt(data), Charsets.UTF_8))
            require(document.getString("contract") == CONTRACT)
            val items = document.getJSONArray("entries")
            require(items.length() <= 20)
            val loaded = (0 until items.length()).map { index ->
                val item = items.getJSONObject(index)
                val embedding = item.getJSONArray("embedding")
                require(embedding.length() == 512)
                PrivacyRegisteredFace(
                    item.getString("id"), item.getString("name"),
                    FloatArray(512) { embedding.getDouble(it).toFloat() },
                    item.getLong("registered_at_millis"),
                )
            }
            require(loaded.map { it.id }.toSet().size == loaded.size)
            require(loaded.all { UUID.fromString(it.id) != null && it.name.isNotBlank() &&
                it.name.length <= 40 && PrivacyFaceMath.normalize(it.embedding) != null })
            entries = loaded
            healthy = true
        } catch (error: Exception) {
            entries = emptyList()
            healthy = false
            throw error
        } finally {
            observedRevision = revision.get()
        }
    }

    private fun save(updated: List<PrivacyRegisteredFace>) {
        check(healthy) { "Local face store is unavailable" }
        try {
            val document = JSONObject().put("contract", CONTRACT)
            val items = JSONArray()
            updated.forEach { face ->
                items.put(JSONObject().put("id", face.id).put("name", face.name)
                    .put("registered_at_millis", face.registeredAtMillis)
                    .put("embedding", JSONArray().apply { face.embedding.forEach { put(it.toDouble()) } }))
            }
            document.put("entries", items)
            val encrypted = encrypt(document.toString().toByteArray(Charsets.UTF_8))
            val stream = file.startWrite()
            try {
                stream.write(encrypted)
                file.finishWrite(stream)
            } catch (error: Exception) {
                runCatching { file.failWrite(stream) }
                throw error
            }
            entries = updated
            observedRevision = revision.incrementAndGet()
        } catch (error: Exception) {
            entries = emptyList()
            healthy = false
            revision.incrementAndGet()
            throw error
        }
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return MAGIC + cipher.iv + cipher.doFinal(data)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > MAGIC.size + 12 && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(MAGIC.size, MAGIC.size + 12)))
        return cipher.doFinal(data, MAGIC.size + 12, data.size - MAGIC.size - 12)
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@synchronized it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        generator.generateKey()
    }

    companion object {
        const val CONTRACT = "privacy-face-vit-kprpe-yunet-v1"
        val currentRevision: Long get() = revision.get()
        private const val KEY_ALIAS = "innolive_privacy_local_faces_v1"
        private val MAGIC = byteArrayOf('I'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 1)
        private val revision = AtomicLong()
        private val keyLock = Any()

        /** Does not load or decrypt the file, so damaged stores can still be removed on deletion. */
        fun clearForAccountDeletion(context: Context) {
            try {
                val keyFailure = runCatching {
                    synchronized(keyLock) {
                        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.run {
                            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
                        }
                    }
                }.exceptionOrNull()
                val base = File(context.applicationContext.noBackupFilesDir, "privacy-local-faces.bin")
                AtomicFile(base).delete()
                check(!base.exists() && !File(base.path + ".bak").exists() &&
                    !File(base.path + ".new").exists()) { "Unable to remove local face data" }
                if (keyFailure != null) throw keyFailure
            } finally {
                revision.incrementAndGet()
            }
        }
    }
}
