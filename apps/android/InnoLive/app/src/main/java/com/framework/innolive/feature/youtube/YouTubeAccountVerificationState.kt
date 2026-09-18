package com.framework.innolive.feature.youtube

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
