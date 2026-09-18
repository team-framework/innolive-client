package com.framework.innolive.feature.youtube

import kotlinx.coroutines.CancellationException

internal enum class YouTubeAccountVerificationState {
    UNVERIFIED,
    CHECKING,
    VERIFIED,
}

internal data class YouTubeAuthorizationCancellationState(
    val accountStatus: String,
    val verificationState: YouTubeAccountVerificationState,
    val isActionInProgress: Boolean,
    val shouldRefreshAccount: Boolean,
)

internal fun cancelYouTubeAuthorization(
    accountStatusBeforeAuthorization: String,
    verificationState: YouTubeAccountVerificationState,
): YouTubeAuthorizationCancellationState = YouTubeAuthorizationCancellationState(
    accountStatus = accountStatusBeforeAuthorization,
    verificationState = verificationState,
    isActionInProgress = false,
    shouldRefreshAccount = false,
)

internal fun hasVerifiedYouTubeAccount(
    account: StreamingAccount?,
    verificationState: YouTubeAccountVerificationState,
    verifiedProfileEmail: String?,
    currentProfileEmail: String?,
): Boolean = account != null &&
    verificationState == YouTubeAccountVerificationState.VERIFIED &&
    currentProfileEmail != null &&
    verifiedProfileEmail == currentProfileEmail

internal fun acceptServerVerifiedYouTubeAccount(
    account: StreamingAccount?,
    onVerified: (StreamingAccount?) -> Unit,
    saveConnection: (StreamingAccount) -> Unit,
    removeConnection: () -> Unit,
): Boolean {
    onVerified(account)
    return try {
        if (account == null) removeConnection() else saveConnection(account)
        true
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        false
    }
}
