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
        findYouTubeAccount(api().listAccounts(refreshAccessToken()))

    suspend fun beginAuthorization(
        accountEmail: String?,
        onAuthorizationRequired: (PendingIntent) -> Unit,
        onAuthorized: (String) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        authorization.request(
            activity = requireActivity(),
            configuration = api().configuration(),
            accountEmail = accountEmail,
            onAuthorizationRequired = onAuthorizationRequired,
            onAuthorized = onAuthorized,
            onFailure = onFailure,
        )
    }

    suspend fun connect(
        serverAuthCode: String,
        refreshAccessToken: suspend () -> String,
    ): StreamingAccount? {
        val accessToken = refreshAccessToken()
        api().connect(serverAuthCode, accessToken)
        return findYouTubeAccount(api().listAccounts(accessToken))
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
