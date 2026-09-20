package com.framework.innolive.feature.live

import androidx.test.platform.app.InstrumentationRegistry
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionRecoveryApiTest {
    @Test fun previousProcessSessionIsDeletedBeforeCreatingAReplacement() {
        val store = FakeStore().apply {
            save(CreatedSession("old-session", "old-owner", AnonymizationState.UNKNOWN), scopeFor("user-a"))
        }
        Fixture(store).use { fixture ->
            val created = fixture.createSession()
            assertEquals("new-session", created.sessionId)
            assertEquals(listOf("DELETE", "POST"), fixture.requests.map { it.method })
            assertEquals("/sessions/old-session", fixture.requests[0].url.encodedPath)
            assertEquals("old-owner", fixture.requests[0].header("X-Session-Owner-Token"))
            assertEquals("/sessions", fixture.requests[1].url.encodedPath)
            assertTrue(fixture.requests.all { it.header("Authorization") == "Bearer ${accessTokenFor("user-a")}" })
            assertEquals("new-session", store.load(scopeFor("user-a"))?.sessionId)
        }
    }

    @Test fun failedCleanupPreservesCredentialsAndDoesNotCreateAnotherSession() {
        val store = FakeStore().apply {
            save(CreatedSession("old-session", "old-owner", AnonymizationState.UNKNOWN), scopeFor("user-a"))
        }
        Fixture(store, deleteStatus = 503).use { fixture ->
            val failure = assertThrows(IOException::class.java) { fixture.createSession() }
            assertTrue(failure.message.orEmpty().contains("503"))
            assertEquals(listOf("DELETE"), fixture.requests.map { it.method })
            assertEquals("old-session", store.load(scopeFor("user-a"))?.sessionId)
        }
    }

    @Test fun serverAlreadyRemovedPreviousSessionAllowsNewSession() {
        val store = FakeStore().apply {
            save(CreatedSession("old-session", "old-owner", AnonymizationState.UNKNOWN), scopeFor("user-a"))
        }
        Fixture(store, deleteStatus = 404).use { fixture ->
            assertEquals("new-session", fixture.createSession().sessionId)
            assertEquals(listOf("DELETE", "POST"), fixture.requests.map { it.method })
            assertEquals("new-session", store.load(scopeFor("user-a"))?.sessionId)
        }
    }

    @Test fun unknownLegacyConflictIsNotDeletedWithoutOwnerToken() {
        Fixture(FakeStore(), createStatus = 409).use { fixture ->
            val failure = assertThrows(IOException::class.java) { fixture.createSession() }
            assertTrue(failure.message.orEmpty().contains("기존 방송을 종료"))
            assertEquals(listOf("POST"), fixture.requests.map { it.method })
        }
    }

    @Test fun differentAccountDoesNotReadOrDeleteAnotherAccountsRecoverySession() {
        val store = FakeStore().apply {
            save(CreatedSession("user-a-session", "user-a-owner", AnonymizationState.UNKNOWN), scopeFor("user-a"))
        }
        Fixture(store, accessToken = accessTokenFor("user-b")).use { fixture ->
            assertEquals("new-session", fixture.createSession().sessionId)
            assertEquals(listOf("POST"), fixture.requests.map { it.method })
            assertEquals("user-a-session", store.load(scopeFor("user-a"))?.sessionId)
            assertEquals("new-session", store.load(scopeFor("user-b"))?.sessionId)
        }
    }

    @Test fun recoveryScopeNormalizesTrailingServerSlash() {
        assertEquals(
            sessionRecoveryScope("https://example.test", accessTokenFor("user-a")),
            sessionRecoveryScope(" https://example.test/ ", accessTokenFor("user-a")),
        )
    }

    @Test fun encryptedCredentialsSurviveStoreRecreationAndAreNotPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedSessionRecoveryStore(
            context,
            preferencesName = "innolive_session_recovery_test",
            keyAlias = "innolive_session_recovery_test",
        )
        val firstScope = scopeFor("user-a")
        val otherScope = scopeFor("user-b")
        val otherServerScope = sessionRecoveryScope("https://other.example.test/", accessTokenFor("user-a"))
        store.clear(firstScope)
        try {
            store.save(CreatedSession("private-session", "private-owner", AnonymizationState.UNKNOWN), firstScope)
            val raw = context.getSharedPreferences("innolive_session_recovery_test", 0).all.toString()
            assertFalse(raw.contains("private-session"))
            assertFalse(raw.contains("private-owner"))
            assertFalse(raw.contains("user-a"))
            val restored = EncryptedSessionRecoveryStore(
                context,
                preferencesName = "innolive_session_recovery_test",
                keyAlias = "innolive_session_recovery_test",
            ).load(firstScope)
            assertEquals("private-session", restored?.sessionId)
            assertEquals("private-owner", restored?.ownerToken)
            assertNull(store.load(otherScope))
            assertNull(store.load(otherServerScope))
        } finally {
            store.clear(firstScope)
            store.clear(otherScope)
            store.clear(otherServerScope)
        }
    }

    @Test fun legacyCredentialsAreMigratedToTheCurrentAccountScope() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "innolive_session_recovery_legacy_test"
        val keyAlias = "innolive_session_recovery_legacy_test"
        val preferences = context.getSharedPreferences(preferencesName, 0)
        val store = EncryptedSessionRecoveryStore(context, preferencesName, keyAlias)
        val scope = scopeFor("user-a")
        store.clear(scope)
        try {
            store.save(CreatedSession("legacy-session", "legacy-owner", AnonymizationState.UNKNOWN), scope)
            val scopedValues = preferences.all.mapValues { it.value as String }
            assertEquals(2, scopedValues.size)
            assertTrue(preferences.edit().clear()
                .putString("encrypted_session", scopedValues.entries.single { it.key.startsWith("encrypted_session_") }.value)
                .putString("initialization_vector", scopedValues.entries.single { it.key.startsWith("initialization_vector_") }.value)
                .commit())

            val restored = store.load(scope)

            assertEquals("legacy-session", restored?.sessionId)
            assertEquals("legacy-owner", restored?.ownerToken)
            assertFalse(preferences.contains("encrypted_session"))
            assertFalse(preferences.contains("initialization_vector"))
            assertTrue(preferences.all.keys.any { it.startsWith("encrypted_session_") })
            assertTrue(preferences.all.keys.any { it.startsWith("initialization_vector_") })
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private class FakeStore : SessionRecoveryStore {
        private val sessions = mutableMapOf<SessionRecoveryScope, CreatedSession>()

        override fun load(scope: SessionRecoveryScope) = sessions[scope]
        override fun save(session: CreatedSession, scope: SessionRecoveryScope) { sessions[scope] = session }
        override fun clear(scope: SessionRecoveryScope) { sessions.remove(scope) }
    }

    private class Fixture(
        store: FakeStore,
        private val deleteStatus: Int = 204,
        private val createStatus: Int = 201,
        accessToken: String = SessionRecoveryApiTest.accessTokenFor("user-a"),
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        private val connection = WebRtcConnection(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            serverUrl = "https://example.test",
            accessToken = accessToken,
            initialAnonymizationEnabled = false,
            preferredAudioInput = null,
            onStateChanged = { _, _ -> }, onRemoteTrackChanged = {},
            onLocalMediaReady = { _, _ -> }, onLocalMediaCleared = {},
            onBroadcastStateChanged = { _, _ -> }, onAnonymizationStateConfirmed = {},
        )

        init {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests.add(request)
                val code = if (request.method == "DELETE") deleteStatus else createStatus
                val body = if (code == 409) {
                    """{"error":{"code":"session_already_exists","message":"active"}}"""
                } else {
                    """{"session_id":"new-session","owner_token":"new-owner","media":{"anonymization_enabled":false}}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").body(body.toResponseBody()).build()
            }.build()
            field("httpClient", client)
            field("sessionRecoveryStore", store)
        }

        private fun field(name: String, value: Any) {
            WebRtcConnection::class.java.getDeclaredField(name).apply { isAccessible = true; set(connection, value) }
        }

        fun createSession(): CreatedSession = try {
            WebRtcConnection::class.java.getDeclaredMethod("createSession").apply { isAccessible = true }
                .invoke(connection) as CreatedSession
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }

        override fun close() {
            val completed = CountDownLatch(1)
            connection.close { completed.countDown() }
            assertTrue(completed.await(10, TimeUnit.SECONDS))
        }
    }

    private companion object {
        fun scopeFor(user: String): SessionRecoveryScope =
            sessionRecoveryScope("https://example.test/", accessTokenFor(user))

        fun accessTokenFor(user: String): String {
            val payload = Base64.encodeToString(
                "{\"sub\":\"$user\"}".toByteArray(),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            return "header.$payload.signature"
        }
    }
}
