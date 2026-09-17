package com.framework.innolive.feature.login

import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 이메일 인증이 끝날 때까지 가입 정보를 메모리에만 유지한다.
 * 비밀번호와 가입 토큰은 화면 복원 상태나 디스크에 저장하지 않는다.
 */
internal class EmailSignupSession(
    private val signUp: suspend (String, String) -> String,
    private val verifyEmail: suspend (String, String) -> Unit,
    private val authenticate: suspend (String, String) -> GoogleSessionStore.Session,
    private val saveSession: (GoogleSessionStore.Session) -> Unit,
) {
    private var pendingSignup: PendingSignup? = null

    suspend fun start(email: String, password: String) {
        val normalizedEmail = normalizeEmail(email)
        val token = signUp(normalizedEmail, password)
        currentCoroutineContext().ensureActive()
        pendingSignup = PendingSignup(normalizedEmail, password, token)
    }

    suspend fun resend() {
        val pending = pendingSignup ?: throw expiredSignup()
        val token = signUp(pending.email, pending.password)
        currentCoroutineContext().ensureActive()
        if (pendingSignup !== pending) throw CancellationException("Email signup was cancelled.")
        pendingSignup = pending.copy(token = token)
    }

    suspend fun verify(code: String) {
        val pending = pendingSignup ?: throw expiredSignup()
        verifyEmail(pending.token, code)
        currentCoroutineContext().ensureActive()
        val session = authenticate(pending.email, pending.password)
        currentCoroutineContext().ensureActive()
        if (pendingSignup !== pending) throw CancellationException("Email signup was cancelled.")
        saveSession(session)
        pendingSignup = null
    }

    fun cancel() {
        pendingSignup = null
    }

    private fun expiredSignup() = EmailSignUpException(
        "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요.",
    )

    private data class PendingSignup(
        val email: String,
        val password: String,
        val token: String,
    )
}

internal fun normalizeEmail(email: String): String = email.trim().lowercase()
