package com.framework.innolive.feature.live

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SessionRecoveryStore {
    fun load(scope: SessionRecoveryScope): CreatedSession?
    fun loadLegacy(): CreatedSession?
    fun save(session: CreatedSession, scope: SessionRecoveryScope)
    fun clear(scope: SessionRecoveryScope)
    fun clearLegacy()
}

/**
 * Keeps recovery credentials isolated to the server and authenticated account that created them.
 * The user identifier is derived only in memory from the access token; the token itself is never
 * persisted as part of the recovery record.
 */
internal data class SessionRecoveryScope(
    val server: String,
    val user: String,
) {
    val storageKey: String = MessageDigest.getInstance("SHA-256")
        .digest("$server\u0000$user".toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun sessionRecoveryScope(server: String, accessToken: String): SessionRecoveryScope {
    val payload = accessToken.split('.', limit = 3).getOrNull(1)
        ?: throw IllegalArgumentException("인증된 사용자 정보를 확인할 수 없습니다.")
    val claims = runCatching {
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(decoded.toString(Charsets.UTF_8))
    }.getOrElse {
        throw IllegalArgumentException("인증된 사용자 정보를 확인할 수 없습니다.", it)
    }
    val user = claims.optString("sub")
    require(user.isNotBlank()) { "인증된 사용자 정보를 확인할 수 없습니다." }
    return SessionRecoveryScope(
        server = server.trim().trimEnd('/'),
        user = user,
    )
}

/** Retains only the credentials needed to remove a session after process death. */
internal class EncryptedSessionRecoveryStore(
    context: Context,
    preferencesName: String = "innolive_session_recovery",
    private val keyAlias: String = "innolive_session_recovery",
) : SessionRecoveryStore {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(scope: SessionRecoveryScope): CreatedSession? {
        val encryptedKey = encryptedSessionKey(scope)
        val initializationVectorKey = initializationVectorKey(scope)
        val encrypted = preferences.getString(encryptedKey, null)
        val iv = preferences.getString(initializationVectorKey, null)
        if (encrypted != null || iv != null) {
            if (encrypted == null || iv == null) {
                clear(scope)
                return null
            }
            return decrypt(encrypted, iv) { clear(scope) }
        }

        return null
    }

    override fun loadLegacy(): CreatedSession? {
        val encrypted = preferences.getString(ENCRYPTED_SESSION, null)
        val iv = preferences.getString(INITIALIZATION_VECTOR, null)
        if (encrypted == null || iv == null) {
            if (encrypted != null || iv != null) clearLegacy()
            return null
        }
        return decrypt(encrypted, iv, ::clearLegacy)
    }

    private fun decrypt(encrypted: String, iv: String, clearInvalid: () -> Unit): CreatedSession? =
        runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val payload = JSONObject(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8))
            CreatedSession(
                sessionId = payload.getString("session_id"),
                ownerToken = payload.getString("owner_token"),
                anonymizationState = AnonymizationState.UNKNOWN,
            ).also { check(it.sessionId.isNotBlank() && it.ownerToken.isNotBlank()) }
        }.getOrElse {
            clearInvalid()
            null
        }

    override fun save(session: CreatedSession, scope: SessionRecoveryScope) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = JSONObject()
            .put("session_id", session.sessionId)
            .put("owner_token", session.ownerToken)
            .toString()
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        check(preferences.edit()
            .putString(encryptedSessionKey(scope), Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(initializationVectorKey(scope), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()) { "Unable to persist session recovery credentials." }
    }

    override fun clear(scope: SessionRecoveryScope) {
        check(preferences.edit()
            .remove(encryptedSessionKey(scope))
            .remove(initializationVectorKey(scope))
            .commit()) { "Unable to clear session recovery credentials." }
    }

    override fun clearLegacy() {
        check(preferences.edit()
            .remove(ENCRYPTED_SESSION)
            .remove(INITIALIZATION_VECTOR)
            .commit()) { "Unable to clear legacy session recovery credentials." }
    }

    private fun encryptedSessionKey(scope: SessionRecoveryScope): String =
        "${ENCRYPTED_SESSION}_${scope.storageKey}"

    private fun initializationVectorKey(scope: SessionRecoveryScope): String =
        "${INITIALIZATION_VECTOR}_${scope.storageKey}"

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
