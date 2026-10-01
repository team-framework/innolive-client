package com.framework.innolive.feature.youtube

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.framework.innolive.BuildConfig

class YouTubeAccountCoordinator(
    private val activity: Activity?,
) : AutoCloseable {
    private val authorization = YouTubeAuthorization()
    private var api: YouTubeApi? = null

    suspend fun loadAccount(refreshAccessToken: suspend () -> String): StreamingAccount? =
        retryYouTubeUnauthorized(refreshAccessToken(), refreshAccessToken) { token ->
            findYouTubeAccount(api().listAccounts(token))
        }

    suspend fun beginAuthorization(
        onAuthorizationRequired: (PendingIntent) -> Unit,
        onAuthorized: (String) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        authorization.request(
            activity = requireActivity(),
            configuration = api().configuration(),
            onAuthorizationRequired = onAuthorizationRequired,
            onAuthorized = onAuthorized,
            onFailure = onFailure,
        )
    }

    suspend fun connect(
        serverAuthCode: String,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): StreamingAccount = retryYouTubeUnauthorized(accessToken, refreshAccessToken) { token ->
        api().connect(serverAuthCode, token)
    }

    fun serverAuthCodeFromIntent(data: Intent): String =
        authorization.serverAuthCodeFromIntent(requireActivity(), data)

    override fun close() {
        api?.close()
        api = null
    }

    private fun api(): YouTubeApi = api ?: YouTubeApi(BuildConfig.INNOLIVE_SERVER_URL).also { api = it }

    private fun requireActivity(): Activity = checkNotNull(activity) { "Activity is unavailable." }

}

internal fun findYouTubeAccount(accounts: List<StreamingAccount>): StreamingAccount? =
    accounts.firstOrNull { account -> account.provider.equals("youtube", ignoreCase = true) }

internal suspend fun <T> retryYouTubeUnauthorized(
    accessToken: String,
    refreshAccessToken: suspend () -> String,
    request: suspend (String) -> T,
): T = try {
    request(accessToken)
} catch (exception: YouTubeApiException) {
    if (exception.statusCode != 401) throw exception
    request(refreshAccessToken())
}
