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
 * 암호화된 인증 세션의 저장소 경계입니다.
 *
 * 메모리 세션과 갱신 작업은 repository만 소유합니다. 테스트에서는 호출자가 scope를
 * 넘길 수 있고, 운영 코드에서는 [close]로 취소되는 repository 소유 scope를 씁니다.
 */
interface AuthenticationSessionStore {
    fun load(): GoogleSessionStore.Session?

    fun save(session: GoogleSessionStore.Session)

    fun clear()
}

/** [AuthenticationSessionRepository]가 쓰는 서버 경계입니다. */
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

    /** 가장 최근에 저장된 세션이며, repository의 모든 변경에서 갱신됩니다. */
    val session: StateFlow<GoogleSessionStore.Session?> = _session.asStateFlow()

    val currentSession: GoogleSessionStore.Session?
        get() = synchronized(lock) { session.value }

    /** 저장된 세션을 다시 읽고 진행 중인 갱신을 무효화합니다. */
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

    /** 새로 인증된 세션을 저장하고 현재 세션으로 지정합니다. */
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
     * 현재 세션을 갱신합니다. 갱신 중에 들어온 호출은 같은 Deferred를 기다리므로
     * 네트워크 요청 하나를 공유합니다.
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

    /** 메모리 세션과 암호화해 저장한 세션을 모두 지웁니다. */
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

    /** 진행 중인 갱신을 취소하고 repository 소유 scope를 해제합니다. */
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
            // GoogleSessionStore는 암호문과 IV를 한 번의 commit으로 저장합니다.
            store.save(refreshedSession)
            // StateFlow는 저장과 같은 임계 구역에서 갱신되므로, 저장소 commit 뒤에 호출자가
            // 취소돼도 관찰자는 결과를 받습니다.
            _session.value = refreshedSession
            sessionGeneration += 1
        }
        return refreshedSession
    }
}
