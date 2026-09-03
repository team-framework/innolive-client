package com.framework.innolive.feature.login.oauth.google

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class AuthenticationSessionRepositoryTest {
    @Test
    fun concurrentRefreshesShareOneApiCallAndPersistOneResult() = runBlocking {
        val oldSession = session("old-access", "old-refresh")
        val refreshedSession = session("new-access", "new-refresh")
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefreshToFinish = CompletableDeferred<Unit>()
        val apiCalls = AtomicInteger(0)
        val api = object : AuthenticationApi {
            override suspend fun refresh(currentSession: GoogleSessionStore.Session): GoogleSessionStore.Session {
                assertSame(oldSession, currentSession)
                apiCalls.incrementAndGet()
                refreshStarted.complete(Unit)
                allowRefreshToFinish.await()
                return refreshedSession
            }
        }
        val store = FakeSessionStore(oldSession)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = AuthenticationSessionRepository(store, api, scope)

        try {
            val refreshes = List(10) { async { repository.refresh() } }
            withTimeout(5_000) { refreshStarted.await() }
            assertEquals(1, apiCalls.get())

            allowRefreshToFinish.complete(Unit)
            assertEquals(List(10) { refreshedSession }, refreshes.awaitAll())
            assertEquals(1, store.saveCalls.get())
            assertEquals(refreshedSession, store.persistedSession)
            assertEquals(refreshedSession, repository.session.value)
        } finally {
            repository.close()
            scope.cancel()
        }
    }

    @Test
    fun failedRefreshKeepsTheExistingSession() = runBlocking {
        val oldSession = session("old-access", "old-refresh")
        val store = FakeSessionStore(oldSession)
        val api = object : AuthenticationApi {
            override suspend fun refresh(currentSession: GoogleSessionStore.Session): GoogleSessionStore.Session {
                throw IOException("network unavailable")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = AuthenticationSessionRepository(store, api, scope)

        try {
            try {
                repository.refresh()
                fail("Expected refresh to fail")
            } catch (_: IOException) {
                // The original session must remain available after a failed request.
            }
            assertEquals(oldSession, repository.session.value)
            assertEquals(oldSession, store.persistedSession)
            assertEquals(0, store.saveCalls.get())
        } finally {
            repository.close()
            scope.cancel()
        }
    }

    @Test
    fun staleRefreshResultCannotOverwriteSessionSavedWhileRefreshWasInFlight() = runBlocking {
        val oldSession = session("old-access", "old-refresh")
        val savedSession = session("saved-access", "saved-refresh")
        val staleSession = session("stale-access", "stale-refresh")
        val refreshStarted = CompletableDeferred<Unit>()
        val returnStaleResult = CompletableDeferred<Unit>()
        val api = object : AuthenticationApi {
            override suspend fun refresh(currentSession: GoogleSessionStore.Session): GoogleSessionStore.Session {
                assertSame(oldSession, currentSession)
                refreshStarted.complete(Unit)
                withContext(NonCancellable) { returnStaleResult.await() }
                return staleSession
            }
        }
        val store = FakeSessionStore(oldSession)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = AuthenticationSessionRepository(store, api, scope)
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh() }

        try {
            withTimeout(5_000) { refreshStarted.await() }
            repository.save(savedSession)
            returnStaleResult.complete(Unit)

            try {
                refresh.await()
                fail("Expected the stale refresh to be cancelled")
            } catch (_: CancellationException) {
                // A cancellation-ignoring API result must not replace the newly saved session.
            }
            assertEquals(savedSession, repository.session.value)
            assertEquals(savedSession, store.persistedSession)
            assertEquals(1, store.saveCalls.get())
        } finally {
            returnStaleResult.complete(Unit)
            repository.close()
            scope.cancel()
        }
    }

    @Test
    fun staleRefreshResultCannotRestoreSessionClearedWhileRefreshWasInFlight() = runBlocking {
        val oldSession = session("old-access", "old-refresh")
        val staleSession = session("stale-access", "stale-refresh")
        val refreshStarted = CompletableDeferred<Unit>()
        val returnStaleResult = CompletableDeferred<Unit>()
        val api = object : AuthenticationApi {
            override suspend fun refresh(currentSession: GoogleSessionStore.Session): GoogleSessionStore.Session {
                assertSame(oldSession, currentSession)
                refreshStarted.complete(Unit)
                withContext(NonCancellable) { returnStaleResult.await() }
                return staleSession
            }
        }
        val store = FakeSessionStore(oldSession)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = AuthenticationSessionRepository(store, api, scope)
        val refresh = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh() }

        try {
            withTimeout(5_000) { refreshStarted.await() }
            repository.clear()
            returnStaleResult.complete(Unit)

            try {
                refresh.await()
                fail("Expected the stale refresh to be cancelled")
            } catch (_: CancellationException) {
                // A cancellation-ignoring API result must not restore a cleared session.
            }
            assertNull(repository.session.value)
            assertNull(store.persistedSession)
            assertEquals(0, store.saveCalls.get())
            assertEquals(1, store.clearCalls.get())
        } finally {
            returnStaleResult.complete(Unit)
            repository.close()
            scope.cancel()
        }
    }

    @Test
    fun clearRemovesPersistedSessionAndReloadRestoresTheStoreValue() {
        val oldSession = session("old-access", "old-refresh")
        val reloadedSession = session("reloaded-access", "reloaded-refresh")
        val store = FakeSessionStore(oldSession)
        val repository = AuthenticationSessionRepository(
            store = store,
            api = object : AuthenticationApi {
                override suspend fun refresh(
                    currentSession: GoogleSessionStore.Session,
                ): GoogleSessionStore.Session = error("Refresh is not used in this test")
            },
        )

        repository.clear()
        assertNull(repository.session.value)
        assertNull(store.persistedSession)
        assertEquals(1, store.clearCalls.get())

        store.persistedSession = reloadedSession
        assertEquals(reloadedSession, repository.reload())
        assertEquals(reloadedSession, repository.session.value)
        repository.close()
    }

    @Test
    fun mutationsUpdateTheRepositoryStateFlow() = runBlocking {
        val initialSession = session("initial-access", "initial-refresh")
        val savedSession = session("saved-access", "saved-refresh")
        val reloadedSession = session("reloaded-access", "reloaded-refresh")
        val refreshedSession = session("refreshed-access", "refreshed-refresh")
        val store = FakeSessionStore(initialSession)
        val repository = AuthenticationSessionRepository(
            store = store,
            api = object : AuthenticationApi {
                override suspend fun refresh(
                    currentSession: GoogleSessionStore.Session,
                ): GoogleSessionStore.Session = refreshedSession
            },
        )

        try {
            assertEquals(initialSession, repository.session.value)

            repository.save(savedSession)
            assertEquals(savedSession, repository.session.value)

            store.persistedSession = reloadedSession
            repository.reload()
            assertEquals(reloadedSession, repository.session.value)

            assertEquals(refreshedSession, repository.refresh())
            assertEquals(refreshedSession, repository.session.value)

            repository.clear()
            assertNull(repository.session.value)
        } finally {
            repository.close()
        }
    }

    private class FakeSessionStore(initialSession: GoogleSessionStore.Session?) :
        AuthenticationSessionStore {
        var persistedSession: GoogleSessionStore.Session? = initialSession
        val saveCalls = AtomicInteger(0)
        val clearCalls = AtomicInteger(0)

        override fun load(): GoogleSessionStore.Session? = persistedSession

        override fun save(session: GoogleSessionStore.Session) {
            saveCalls.incrementAndGet()
            persistedSession = session
        }

        override fun clear() {
            clearCalls.incrementAndGet()
            persistedSession = null
        }
    }

    private fun session(accessToken: String, refreshToken: String) = GoogleSessionStore.Session(
        accessToken = accessToken,
        refreshToken = refreshToken,
        tokenType = "Bearer",
        expiresIn = 900,
        refreshExpiresIn = 86_400,
        profileName = "Test User",
        profileEmail = "test@example.com",
    )
}
