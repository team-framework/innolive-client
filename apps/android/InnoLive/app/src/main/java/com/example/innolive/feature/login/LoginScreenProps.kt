package com.example.innolive.feature.login

import androidx.compose.runtime.Immutable

@Immutable
data class LoginScreenProps(
    val onLogin: () -> Unit,
    val onGoogleLogin: suspend () -> Unit,
)
