package com.framework.innolive.feature.youtube

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import com.framework.innolive.ui.text.UiText

internal enum class YouTubeAccountVerificationState {
    UNVERIFIED,
    CHECKING,
    VERIFIED,
}

/** Server verification is valid only for the current screen instance. */
internal class YouTubeVerificationMemory {
    val state = mutableStateOf(YouTubeAccountVerificationState.UNVERIFIED)
    val verifiedProfileEmail = mutableStateOf<String?>(null)
    val suppressRefreshOnce = mutableStateOf(false)
}

@Composable
internal fun rememberYouTubeVerificationMemory(): YouTubeVerificationMemory =
    remember { YouTubeVerificationMemory() }

internal data class YouTubeAuthorizationCancellationState(
    val accountStatus: UiText,
    val verificationState: YouTubeAccountVerificationState,
    val isActionInProgress: Boolean,
    val shouldRefreshAccount: Boolean,
)

internal fun cancelYouTubeAuthorization(
    accountStatusBeforeAuthorization: UiText,
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
