package com.framework.innolive.feature.login.oauth.google

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CancellationException

/**
 * Storage boundary for the encrypted authentication session.
 *
 * The repository is the only owner of the in-memory session and the refresh
 * operation. Callers can provide a scope in tests; production callers get a
 * repository-owned scope that is cancelled by [close].
 */
interface AuthenticationSessionStore {
    fun load(): GoogleSessionStore.Session?

    fun save(session: GoogleSessionStore.Session)

    fun clear()
}

/** Server boundary used by [AuthenticationSessionRepository]. */
interface AuthenticationApi {
    suspend fun refresh(currentSession: GoogleSessionStore.Session): GoogleSessionStore.Session
}

class AuthenticationSessionRepository(
    private val store: AuthenticationSessionStore,
    private val api: AuthenticationApi,
    scope: CoroutineScope? = null,
) {
    private val lock = Any()
    private val refreshScope: CoroutineScope = scope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownsRefreshScope = scope == null

    private val _session = MutableStateFlow<GoogleSessionStore.Session?>(store.load())
    private var activeRefresh: Deferred<GoogleSessionStore.Session>? = null
    private var sessionGeneration = 0L
    private var isClosed = false

    /** The latest persisted session, updated by every repository mutation. */
    val session: StateFlow<GoogleSessionStore.Session?> = _session.asStateFlow()

    val currentSession: GoogleSessionStore.Session?
        get() = synchronized(lock) { session.value }

    /** Reloads the persisted session and invalidates an in-flight refresh. */
    fun reload(): GoogleSessionStore.Session? {
        val refreshToCancel: Deferred<GoogleSessionStore.Session>?
        val reloadedSession: GoogleSessionStore.Session?
        synchronized(lock) {
            reloadedSession = store.load()
            _session.value = reloadedSession
            sessionGeneration += 1
            refreshToCancel = activeRefresh
            activeRefresh = null
        }
        refreshToCancel?.cancel()
        return reloadedSession
    }

    /** Saves a newly authenticated session and makes it the current session. */
    fun save(session: GoogleSessionStore.Session) {
        val refreshToCancel: Deferred<GoogleSessionStore.Session>?
        synchronized(lock) {
            check(!isClosed) { "Authentication session repository is closed." }
            store.save(session)
            _session.value = session
            sessionGeneration += 1
            refreshToCancel = activeRefresh
            activeRefresh = null
        }
        refreshToCancel?.cancel()
    }

    /**
     * Refreshes the current session. Calls made while a refresh is running
     * await the same Deferred and therefore share one network request.
     */
    suspend fun refresh(): GoogleSessionStore.Session {
        val refreshTask = synchronized(lock) {
            check(!isClosed) { "Authentication session repository is closed." }
            val sessionAtStart = checkNotNull(session.value) {
                "Authentication session is missing."
            }
            val generationAtStart = sessionGeneration
            activeRefresh?.takeUnless { it.isCompleted }
                ?: refreshScope.async(start = CoroutineStart.DEFAULT) {
                    refreshAndStore(sessionAtStart, generationAtStart)
                }.also { activeRefresh = it }
        }

        return try {
            refreshTask.await()
        } finally {
            if (refreshTask.isCompleted) {
                synchronized(lock) {
                    if (activeRefresh === refreshTask) {
                        activeRefresh = null
                    }
                }
            }
        }
    }

    suspend fun refreshAccessToken(): String = refresh().accessToken

    /** Clears both the in-memory and encrypted persisted session. */
    fun clear() {
        var refreshToCancel: Deferred<GoogleSessionStore.Session>? = null
        try {
            synchronized(lock) {
                sessionGeneration += 1
                refreshToCancel = activeRefresh
                activeRefresh = null
                store.clear()
                _session.value = null
            }
        } finally {
            refreshToCancel?.cancel()
        }
    }

    /** Cancels an in-flight refresh and releases the repository-owned scope. */
    fun close() {
        val refreshToCancel: Deferred<GoogleSessionStore.Session>?
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            sessionGeneration += 1
            refreshToCancel = activeRefresh
            activeRefresh = null
        }
        refreshToCancel?.cancel()
        if (ownsRefreshScope) {
            refreshScope.cancel()
        }
    }

    private suspend fun refreshAndStore(
        sessionAtStart: GoogleSessionStore.Session,
        generationAtStart: Long,
    ): GoogleSessionStore.Session {
        val refreshedSession = api.refresh(sessionAtStart)

        synchronized(lock) {
            if (
                isClosed ||
                sessionGeneration != generationAtStart ||
                session.value != sessionAtStart
            ) {
                throw CancellationException("Authentication session changed while refreshing.")
            }
            // GoogleSessionStore persists the ciphertext and IV in one commit.
            store.save(refreshedSession)
            // StateFlow is updated in the same critical section as persistence,
            // so observers see the result even when the caller was cancelled
            // after the store commit.
            _session.value = refreshedSession
            sessionGeneration += 1
        }
        return refreshedSession
    }
}
