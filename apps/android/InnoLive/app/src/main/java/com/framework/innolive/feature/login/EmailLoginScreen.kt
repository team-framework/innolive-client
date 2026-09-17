package com.framework.innolive.feature.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
internal fun EmailLoginScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    signIn: (suspend (String, String) -> Unit)?,
    signUp: (suspend (String, String) -> Unit)? = null,
    verifyEmail: (suspend (String) -> Unit)? = null,
    resendSignup: (suspend () -> Unit)? = null,
    cancelSignup: () -> Unit = {},
    hasPendingSignup: () -> Boolean = { true },
    isSignupVerified: () -> Boolean = { false },
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var isResending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableStateOf(0L) }
    var emailAddress by rememberSaveable { mutableStateOf("") }
    var showingVerification by rememberSaveable { mutableStateOf(false) }
    var startWithSignUp by rememberSaveable { mutableStateOf(false) }
    var resendGeneration by rememberSaveable { mutableIntStateOf(0) }
    val restoredWithoutSignup = showingVerification && !hasPendingSignup()
    LaunchedEffect(restoredWithoutSignup) {
        if (restoredWithoutSignup) {
            showingVerification = false
            startWithSignUp = true
        }
    }

    fun cancelRequest() {
        generation++
        request?.cancel()
        request = null
        pending = false
        isResending = false
        error = null
    }

    fun submit(resending: Boolean = false, action: suspend () -> Unit) {
        if (pending) return
        pending = true
        isResending = resending
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
                if (generation == activeGeneration) {
                    pending = false
                    isResending = false
                    request = null
                }
            }
        }
    }

    if (showingVerification && !restoredWithoutSignup && verifyEmail != null && resendSignup != null) {
        EmailVerificationScreen(
            email = emailAddress,
            pending = pending,
            verified = isSignupVerified(),
            isResending = isResending,
            error = error,
            resendGeneration = resendGeneration,
            onVerify = { code ->
                submit {
                    verifyEmail(code)
                    currentCoroutineContext().ensureActive()
                    showingVerification = false
                    onLogin()
                }
            },
            onResend = {
                submit(resending = true) {
                    resendSignup()
                    currentCoroutineContext().ensureActive()
                    resendGeneration++
                }
            },
            onBack = {
                cancelRequest()
                cancelSignup()
                showingVerification = false
                startWithSignUp = true
            },
        )
        return
    }

    EmailAuthScreen(
        onBack = {
            cancelRequest()
            cancelSignup()
            onBack()
        },
        isSubmitting = pending,
        errorMessage = error,
        initialEmail = emailAddress,
        startWithSignUp = startWithSignUp || restoredWithoutSignup,
        onModeChanged = { error = null },
        onSignIn = signIn?.let { authenticate ->
            { emailAddress, password ->
                submit {
                    authenticate(emailAddress, password)
                    currentCoroutineContext().ensureActive()
                    onLogin()
                }
            }
        },
        onSignUp = if (signUp != null && verifyEmail != null && resendSignup != null) {
            { submittedEmail, password ->
                submit {
                    signUp(submittedEmail, password)
                    currentCoroutineContext().ensureActive()
                    emailAddress = submittedEmail
                    showingVerification = true
                    resendGeneration = 0
                }
            }
        } else {
            null
        },
    )
}
