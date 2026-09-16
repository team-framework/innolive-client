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
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableStateOf<Job?>(null) }
    EmailAuthScreen(
        onBack = {
            request?.cancel()
            onBack()
        },
        isSubmitting = pending,
        errorMessage = error,
        onSignIn = signIn?.let { authenticate ->
            { email, password ->
                if (!pending) {
                    pending = true
                    error = null
                    request = scope.launch {
                        try {
                            authenticate(email, password)
                            ensureActive()
                            onLogin()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: EmailSignInException) {
                            error = failure.message
                        } catch (_: Exception) {
                            error = "로그인하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요."
                        } finally {
                            pending = false
                        }
                    }
                }
            }
        },
    )
}
