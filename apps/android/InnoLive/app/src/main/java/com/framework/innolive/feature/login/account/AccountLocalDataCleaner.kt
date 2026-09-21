package com.framework.innolive.feature.login.account

import android.content.Context
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.face.ReferenceFaceImageStore
import com.framework.innolive.feature.live.EncryptedSessionRecoveryStore
import com.framework.innolive.feature.live.sessionRecoveryScope
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import com.framework.innolive.feature.youtube.YouTubePreferencesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AccountLocalDataCleaner(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun clear(session: GoogleSessionStore.Session) = withContext(Dispatchers.IO) {
        val recoveryScope = sessionRecoveryScope(
            server = BuildConfig.INNOLIVE_SERVER_URL,
            accessToken = session.accessToken,
        )
        EncryptedSessionRecoveryStore(applicationContext).run {
            clear(recoveryScope)
            clearLegacy()
        }
        ReferenceFaceImageStore(applicationContext).deleteAll(session.profileEmail)
        YouTubePreferencesStore(applicationContext).clearAccountData()
    }
}

/** Persists only a non-secret account scope hash and deletion phase for process-death recovery. */
internal class AccountDeletionCleanupStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun save(session: GoogleSessionStore.Session, phase: AccountDeletionPhase) {
        val scope = sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, session.accessToken)
        check(
            preferences.edit()
                .putString(ACCOUNT_SCOPE_KEY, scope.storageKey)
                .putString(PHASE_KEY, phase.name)
                .commit(),
        ) { "Unable to persist pending account deletion." }
    }

    fun loadPhaseFor(session: GoogleSessionStore.Session): AccountDeletionPhase? {
        val savedScopeKey = preferences.getString(ACCOUNT_SCOPE_KEY, null) ?: return null
        val matches = runCatching {
            sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, session.accessToken).storageKey == savedScopeKey
        }.getOrDefault(false)
        if (!matches) return null

        val savedPhase = preferences.getString(PHASE_KEY, null)
        return savedPhase?.let { value ->
            runCatching { AccountDeletionPhase.valueOf(value) }.getOrNull()
        } ?: AccountDeletionPhase.LOCAL_CLEANUP_PENDING
    }

    fun clear() {
        check(preferences.edit().clear().commit()) {
            "Unable to clear pending account cleanup."
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "innolive_account_deletion_cleanup"
        const val ACCOUNT_SCOPE_KEY = "account_scope_key"
        const val PHASE_KEY = "phase"
    }
}
