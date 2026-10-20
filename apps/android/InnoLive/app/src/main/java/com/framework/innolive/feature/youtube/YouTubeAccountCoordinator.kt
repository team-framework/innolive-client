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

    /** 실험실 표시 여부입니다. `GET /admin/me`가 200이면 관리자, 403·404면 관리자가 아닙니다. */
    suspend fun isAdministrator(accessToken: String, refreshAccessToken: suspend () -> String): Boolean =
        retryYouTubeUnauthorized(accessToken, refreshAccessToken) { token -> api().isAdministrator(token) }

    suspend fun connect(
        serverAuthCode: String,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): StreamingAccount = retryYouTubeUnauthorized(accessToken, refreshAccessToken) { token ->
        api().connect(serverAuthCode, token)
    }

    /** YouTube는 한 채널만 연결한다. 새 채널 연결 뒤 남아 있는 다른 YouTube 연결을 모두 해제한다. */
    suspend fun removeOtherYouTubeAccounts(
        connected: StreamingAccount,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ) = retryYouTubeUnauthorized(accessToken, refreshAccessToken) { token ->
        otherYouTubeAccountIds(api().listAccounts(token), connected.channelId)
            .forEach { accountId -> api().disconnectYouTubeAccount(accountId, token) }
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

/** 연결 UUID가 없는 이전 서버 응답은 채널 하나만 유지하므로 해제 대상에서 제외한다. */
internal fun otherYouTubeAccountIds(accounts: List<StreamingAccount>, keepChannelId: String): List<String> =
    accounts
        .filter { account -> account.provider.equals("youtube", ignoreCase = true) && account.channelId != keepChannelId }
        .mapNotNull(StreamingAccount::accountId)

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
