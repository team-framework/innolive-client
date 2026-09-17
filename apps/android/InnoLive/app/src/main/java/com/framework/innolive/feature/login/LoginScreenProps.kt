package com.framework.innolive.feature.login

import androidx.compose.runtime.Immutable

@Immutable
data class LoginScreenProps(
    val onLogin: () -> Unit,
    val onGoogleLogin: suspend () -> Unit,
    val onEmailSignUp: (suspend (String, String) -> String)? = null,
    val onEmailVerification: (suspend (String, String) -> Unit)? = null,
    val onEmailLogin: (suspend (String, String) -> Unit)? = null,
)
