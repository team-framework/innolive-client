package com.framework.innolive.feature.login.oauth.google

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

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "innolive_google_session"
private const val PREFERENCES_NAME = "innolive_google_session"
private const val ENCRYPTED_SESSION_KEY = "encrypted_session"
private const val INITIALIZATION_VECTOR_KEY = "initialization_vector"

class GoogleSessionStore(context: Context) : AuthenticationSessionStore {
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresIn: Long,
        val refreshExpiresIn: Long,
        val profileName: String = "",
        val profileEmail: String = "",
    )

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun save(session: Session) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encryptedSession = cipher.doFinal(session.toJson().toByteArray(Charsets.UTF_8))

        check(
            preferences.edit()
                .putString(
                    ENCRYPTED_SESSION_KEY,
                    Base64.encodeToString(encryptedSession, Base64.NO_WRAP),
                )
                .putString(
                    INITIALIZATION_VECTOR_KEY,
                    Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                )
                .commit(),
        ) { "Unable to persist the Google authentication session." }
    }

    override fun load(): Session? {
        val encryptedSession = preferences.getString(ENCRYPTED_SESSION_KEY, null)
        val initializationVector = preferences.getString(INITIALIZATION_VECTOR_KEY, null)
        if (encryptedSession == null || initializationVector == null) {
            if (encryptedSession != null || initializationVector != null) {
                removeCorruptedSession()
            }
            return null
        }

        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(initializationVector, Base64.NO_WRAP)),
            )
            val sessionJson = cipher.doFinal(
                Base64.decode(encryptedSession, Base64.NO_WRAP),
            ).toString(Charsets.UTF_8)
            JSONObject(sessionJson).toSession()
        }.getOrElse {
            removeCorruptedSession()
            null
        }
    }

    override fun clear() {
        check(preferences.edit().clear().commit()) {
            "Unable to clear the Google authentication session."
        }
    }

    private fun removeCorruptedSession() {
        preferences.edit()
            .remove(ENCRYPTED_SESSION_KEY)
            .remove(INITIALIZATION_VECTOR_KEY)
            .commit()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey

        return existingKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun Session.toJson(): String = JSONObject()
        .put("access_token", accessToken)
        .put("refresh_token", refreshToken)
        .put("token_type", tokenType)
        .put("expires_in", expiresIn)
        .put("refresh_expires_in", refreshExpiresIn)
        .put("profile_name", profileName)
        .put("profile_email", profileEmail)
        .toString()

    private fun JSONObject.toSession(): Session = Session(
        accessToken = getString("access_token"),
        refreshToken = getString("refresh_token"),
        tokenType = getString("token_type"),
        expiresIn = getLong("expires_in"),
        refreshExpiresIn = getLong("refresh_expires_in"),
        profileName = optString("profile_name"),
        profileEmail = optString("profile_email"),
    )
}
