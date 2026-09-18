package com.framework.innolive.feature.youtube

import kotlinx.coroutines.CancellationException

internal enum class YouTubeAccountVerificationState {
    UNVERIFIED,
    CHECKING,
    VERIFIED,
}

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
