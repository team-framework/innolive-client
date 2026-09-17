package com.framework.innolive.feature.live

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SessionRecoveryStore {
    fun load(): CreatedSession?
    fun save(session: CreatedSession)
    fun clear()
}

/** Retains only the credentials needed to remove a session after process death. */
internal class EncryptedSessionRecoveryStore(
    context: Context,
    preferencesName: String = "innolive_session_recovery",
    private val keyAlias: String = "innolive_session_recovery",
) : SessionRecoveryStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): CreatedSession? {
        val encrypted = preferences.getString(ENCRYPTED_SESSION, null)
        val iv = preferences.getString(INITIALIZATION_VECTOR, null)
        if (encrypted == null || iv == null) {
            if (encrypted != null || iv != null) clear()
            return null
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val payload = JSONObject(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8))
            CreatedSession(
                sessionId = payload.getString("session_id"),
                ownerToken = payload.getString("owner_token"),
                anonymizationState = AnonymizationState.UNKNOWN,
            ).also { check(it.sessionId.isNotBlank() && it.ownerToken.isNotBlank()) }
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(session: CreatedSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = JSONObject()
            .put("session_id", session.sessionId)
            .put("owner_token", session.ownerToken)
            .toString()
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString(ENCRYPTED_SESSION, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(INITIALIZATION_VECTOR, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "Unable to persist session recovery credentials." }
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) { "Unable to clear session recovery credentials." }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keyStore.getKey(keyAlias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build())
            }.generateKey()
    }

    private companion object {
        const val ENCRYPTED_SESSION = "encrypted_session"
        const val INITIALIZATION_VECTOR = "initialization_vector"
    }
}
