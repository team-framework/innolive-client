package com.framework.innolive.feature.youtube

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task

class YouTubeAuthorization {
    fun request(
        activity: Activity,
        configuration: YouTubeConfiguration,
        onAuthorizationRequired: (PendingIntent) -> Unit,
        onAuthorized: (String) -> Unit,
        onFailure: (Exception) -> Unit,
    ): Task<AuthorizationResult> = Identity.getAuthorizationClient(activity)
        .authorize(buildYouTubeAuthorizationRequest(configuration))
        .addOnSuccessListener { result ->
            runCatching {
                if (result.hasResolution()) {
                    onAuthorizationRequired(checkNotNull(result.pendingIntent))
                } else {
                    onAuthorized(requireServerAuthCode(result))
                }
            }.onFailure { exception ->
                onFailure(exception as? Exception ?: IllegalStateException(exception))
            }
        }
        .addOnFailureListener(onFailure)

    fun serverAuthCodeFromIntent(activity: Activity, data: Intent): String =
        requireServerAuthCode(
            Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data),
        )

    private fun requireServerAuthCode(result: AuthorizationResult): String = result.serverAuthCode
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: throw IllegalStateException("YouTube server authorization code is missing.")
}

internal fun buildYouTubeAuthorizationRequest(
    configuration: YouTubeConfiguration,
): AuthorizationRequest {
    return AuthorizationRequest.builder()
        .requestOfflineAccess(configuration.webClientId)
        .setPrompt(
            AuthorizationRequest.Prompt.CONSENT or
                AuthorizationRequest.Prompt.SELECT_ACCOUNT,
        )
        .setRequestedScopes(listOf(Scope(configuration.scope)))
        .build()
}
