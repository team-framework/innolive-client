package com.framework.innolive.feature.live

import androidx.test.platform.app.InstrumentationRegistry
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
        val store = FakeStore(CreatedSession("old-session", "old-owner", AnonymizationState.UNKNOWN))
        Fixture(store).use { fixture ->
            val created = fixture.createSession()
            assertEquals("new-session", created.sessionId)
            assertEquals(listOf("DELETE", "POST"), fixture.requests.map { it.method })
            assertEquals("/sessions/old-session", fixture.requests[0].url.encodedPath)
            assertEquals("old-owner", fixture.requests[0].header("X-Session-Owner-Token"))
            assertEquals("/sessions", fixture.requests[1].url.encodedPath)
            assertTrue(fixture.requests.all { it.header("Authorization") == "Bearer test-access" })
            assertEquals("new-session", store.session?.sessionId)
        }
    }

    @Test fun failedCleanupPreservesCredentialsAndDoesNotCreateAnotherSession() {
        val store = FakeStore(CreatedSession("old-session", "old-owner", AnonymizationState.UNKNOWN))
        Fixture(store, deleteStatus = 503).use { fixture ->
            val failure = assertThrows(IOException::class.java) { fixture.createSession() }
            assertTrue(failure.message.orEmpty().contains("503"))
            assertEquals(listOf("DELETE"), fixture.requests.map { it.method })
            assertEquals("old-session", store.session?.sessionId)
        }
    }

    @Test fun serverAlreadyRemovedPreviousSessionAllowsNewSession() {
        val store = FakeStore(CreatedSession("old-session", "old-owner", AnonymizationState.UNKNOWN))
        Fixture(store, deleteStatus = 404).use { fixture ->
            assertEquals("new-session", fixture.createSession().sessionId)
            assertEquals(listOf("DELETE", "POST"), fixture.requests.map { it.method })
            assertEquals("new-session", store.session?.sessionId)
        }
    }

    @Test fun unknownLegacyConflictIsNotDeletedWithoutOwnerToken() {
        Fixture(FakeStore(), createStatus = 409).use { fixture ->
            val failure = assertThrows(IOException::class.java) { fixture.createSession() }
            assertTrue(failure.message.orEmpty().contains("기존 방송을 종료"))
            assertEquals(listOf("POST"), fixture.requests.map { it.method })
        }
    }

    @Test fun encryptedCredentialsSurviveStoreRecreationAndAreNotPlaintext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedSessionRecoveryStore(
            context,
            preferencesName = "innolive_session_recovery_test",
            keyAlias = "innolive_session_recovery_test",
        )
        store.clear()
        try {
            store.save(CreatedSession("private-session", "private-owner", AnonymizationState.UNKNOWN))
            val raw = context.getSharedPreferences("innolive_session_recovery_test", 0).all.toString()
            assertFalse(raw.contains("private-session"))
            assertFalse(raw.contains("private-owner"))
            val restored = EncryptedSessionRecoveryStore(
                context,
                preferencesName = "innolive_session_recovery_test",
                keyAlias = "innolive_session_recovery_test",
            ).load()
            assertEquals("private-session", restored?.sessionId)
            assertEquals("private-owner", restored?.ownerToken)
        } finally {
            store.clear()
        }
    }

    private class FakeStore(var session: CreatedSession? = null) : SessionRecoveryStore {
        override fun load() = session
        override fun save(session: CreatedSession) { this.session = session }
        override fun clear() { session = null }
    }

    private class Fixture(
        store: FakeStore,
        private val deleteStatus: Int = 204,
        private val createStatus: Int = 201,
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Request>()
        private val connection = WebRtcConnection(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            serverUrl = "https://example.test",
            accessToken = "test-access",
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
}
