package com.framework.innolive.feature.login

import androidx.compose.runtime.Immutable
import com.framework.innolive.feature.login.oauth.google.GoogleSignInState

@Immutable
data class LoginScreenProps(
    val onLogin: () -> Unit,
    val onGoogleLogin: () -> Unit,
    val onGoogleSignInSuccess: () -> Unit = {},
    val googleSignInState: GoogleSignInState = GoogleSignInState.Idle,
    val onEmailSignUp: (suspend (String, String) -> Unit)? = null,
    val onEmailVerification: (suspend (String) -> Unit)? = null,
    val onEmailSignupResend: (suspend () -> Unit)? = null,
    val onEmailSignupCancel: () -> Unit = {},
    val hasPendingEmailSignup: () -> Boolean = { true },
    val isEmailSignupVerified: () -> Boolean = { false },
    val onEmailLogin: (suspend (String, String) -> Unit)? = null,
)
