package com.framework.innolive.feature.login.account

import android.content.Context
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.sessionRecoveryScope
import com.framework.innolive.feature.live.privacy.PrivacyFaceLibrary
import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore

class AccountDeletionCleanupStoreTest {
    @Test
    fun accountDeletionRemovesLocalFacesForTheNextAccount() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val library = PrivacyFaceLibrary(context)
        library.deleteAll()
        library.add("Previous account", FloatArray(512).apply { this[0] = 1f })
        val base = File(context.noBackupFilesDir, "privacy-local-faces.bin")
        assertTrue(base.exists())
        try {
            AccountLocalDataCleaner(context).clear(session("previous-account"))

            assertFalse(base.exists())
            assertFalse(File(base.path + ".bak").exists())
            assertFalse(File(base.path + ".new").exists())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .containsAlias("innolive_privacy_local_faces_v1"))
            assertTrue(library.snapshot().isEmpty())
            assertTrue(PrivacyFaceLibrary(context).snapshot().isEmpty())
        } finally {
            PrivacyFaceLibrary.clearForAccountDeletion(context)
        }
    }

    @Test
    fun deletionPhaseSurvivesRecreationAndRemainsAccountScoped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountDeletionCleanupStore(context)
        val first = session("account-a")
        val second = session("account-b")
        store.clear()
        try {
            store.save(first, AccountDeletionPhase.REMOTE_DELETION_PENDING)

            assertEquals(
                AccountDeletionPhase.REMOTE_DELETION_PENDING,
                AccountDeletionCleanupStore(context).loadPhaseFor(first),
            )
            assertNull(AccountDeletionCleanupStore(context).loadPhaseFor(second))

            store.save(first, AccountDeletionPhase.LOCAL_CLEANUP_PENDING)
            assertEquals(
                AccountDeletionPhase.LOCAL_CLEANUP_PENDING,
                AccountDeletionCleanupStore(context).loadPhaseFor(first),
            )

            store.save(first, AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING)
            assertEquals(
                AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING,
                AccountDeletionCleanupStore(context).loadPhaseFor(first),
            )
        } finally {
            store.clear()
        }
    }

    @Test
    fun completedDeletionMarkerWithoutAuthenticationIsNotInheritedByNextLogin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountDeletionCleanupStore(context)
        val previousSession = session("same-account")
        store.clear()
        try {
            store.save(previousSession, AccountDeletionPhase.AUTHENTICATION_CLEANUP_PENDING)

            AccountDeletionCleanupStore(context).apply {
                discardCompletedDeletionWithoutAuthentication()
                assertNull(loadPhaseFor(session("same-account")))
            }
        } finally {
            store.clear()
        }
    }

    @Test
    fun markerFromPreviousAppVersionLoadsAsLocalCleanupPending() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountDeletionCleanupStore(context)
        val session = session("legacy-account")
        val scope = sessionRecoveryScope(BuildConfig.INNOLIVE_SERVER_URL, session.accessToken)
        val preferences = context.getSharedPreferences(
            "innolive_account_deletion_cleanup",
            Context.MODE_PRIVATE,
        )
        store.clear()
        try {
            check(preferences.edit().putString("account_scope_key", scope.storageKey).commit())

            assertEquals(
                AccountDeletionPhase.LOCAL_CLEANUP_PENDING,
                AccountDeletionCleanupStore(context).loadPhaseFor(session),
            )
        } finally {
            store.clear()
        }
    }

    private fun session(subject: String) = GoogleSessionStore.Session(
        accessToken = accessToken(subject),
        refreshToken = "refresh-token",
        tokenType = "Bearer",
        expiresIn = 3_600,
        refreshExpiresIn = 7_200,
        profileName = "User",
        profileEmail = "$subject@example.com",
    )

    private fun accessToken(subject: String): String {
        val payload = Base64.encodeToString(
            "{\"sub\":\"$subject\"}".toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        return "header.$payload.signature"
    }
}
