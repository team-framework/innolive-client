package com.framework.innolive.feature.face

import kotlinx.coroutines.CancellationException

/**
 * 방송 준비 전에 서버 기준 얼굴 중 다시 등록할 얼굴이 있는지 확인합니다. 확인하지 못하면 null입니다.
 * 안내는 방송 준비를 막지 않으므로 실패는 조용히 넘깁니다.
 */
internal suspend fun loadReferenceFaceReregistrationRequired(
    getAccessToken: () -> String?,
    refreshAccessToken: suspend () -> String,
    apiFactory: () -> ReferenceFaceApi = { ReferenceFaceApi() },
): Boolean? {
    val accessToken = getAccessToken()?.trim().orEmpty()
    if (accessToken.isEmpty()) return null
    val api = try {
        apiFactory()
    } catch (_: Exception) {
        return null
    }
    return try {
        api.getStatus(accessToken, refreshAccessToken).reregistrationRequired
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        null
    } finally {
        api.close()
    }
}
