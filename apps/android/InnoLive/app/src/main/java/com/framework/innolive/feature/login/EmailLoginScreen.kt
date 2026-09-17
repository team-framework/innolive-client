package com.framework.innolive.feature.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun EmailLoginScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    signIn: (suspend (String, String) -> Unit)?,
    signUp: (suspend (String, String) -> String)? = null,
    verifyEmail: (suspend (String, String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0L) }
    var signupToken by remember { mutableStateOf<String?>(null) }
    var emailAddress by remember { mutableStateOf("") }
    var startWithSignUp by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun cancelRequest() {
        generation++
        request?.cancel()
        pending = false
        error = null
    }

    fun submit(action: suspend () -> Unit) {
        if (pending) return
        pending = true
        error = null
        val activeGeneration = ++generation
        request = scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == activeGeneration) {
                    error = when (failure) {
                        is EmailSignInException, is EmailSignUpException -> failure.message
                        else -> "요청을 완료하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요."
                    }
                }
            } finally {
                if (generation == activeGeneration) pending = false
            }
        }
    }

    val token = signupToken
    if (token != null && verifyEmail != null) {
        EmailVerificationScreen(
            email = emailAddress, pending = pending, error = error,
            onVerify = { code ->
                submit {
                    verifyEmail(token, code)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    signupToken = null
                    startWithSignUp = false
                    notice = "회원가입이 완료됐습니다. 이메일로 로그인해 주세요."
                }
            },
            onRestart = {
                cancelRequest()
                signupToken = null
                startWithSignUp = true
            },
            onSignIn = {
                cancelRequest()
                signupToken = null
                startWithSignUp = false
            },
        )
        return
    }

    EmailAuthScreen(
        onBack = { cancelRequest(); onBack() },
        isSubmitting = pending,
        errorMessage = error,
        noticeMessage = notice,
        initialEmail = emailAddress,
        startWithSignUp = startWithSignUp,
        onModeChanged = { error = null; notice = null },
        onSignIn = signIn?.let { authenticate ->
            { email, password ->
                submit {
                    authenticate(email, password)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    onLogin()
                }
            }
        },
        onSignUp = if (signUp != null && verifyEmail != null) { email, password ->
            submit {
                val newToken = signUp(email, password)
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                emailAddress = email
                notice = null
                signupToken = newToken
            }
        } else null,
    )
}
