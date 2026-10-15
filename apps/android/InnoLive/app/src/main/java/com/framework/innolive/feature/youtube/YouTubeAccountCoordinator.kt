package com.framework.innolive.feature.youtube

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.login.oauth.google.InvalidRefreshTokenException
import com.framework.innolive.feature.settings.PlanUsage
import kotlinx.coroutines.CancellationException

class YouTubeAccountCoordinator(
    private val activity: Activity?,
) : AutoCloseable {
    private val authorization = YouTubeAuthorization()
    private var api: YouTubeApi? = null

    suspend fun loadAccount(refreshAccessToken: suspend () -> String): StreamingAccount? =
        retryYouTubeUnauthorized(refreshAccessToken(), refreshAccessToken) { token ->
            findYouTubeAccount(api().listAccounts(token))
        }

    suspend fun loadPlanUsage(accessToken: String, refreshAccessToken: suspend () -> String): PlanUsage =
        retryYouTubeUnauthorized(accessToken, {
            try {
                refreshAccessToken()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                throw planRefreshFailure(exception)
            }
        }) { api().planUsage(it) }

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

/** 무효 refresh token만 401로 바꿔 로그인으로 돌아간다. 일시적 갱신 실패는 조회 실패로 남겨 마지막 값을 유지한다. */
internal fun planRefreshFailure(exception: Exception): YouTubeApiException =
    if (exception is InvalidRefreshTokenException) YouTubeApiException(401, "plan authentication", cause = exception)
    else YouTubeApiException(null, "plan authentication", cause = exception)

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
