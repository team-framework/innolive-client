package com.framework.innolive.feature.youtube

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CancellationException
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.feature.live.BroadcastState

internal enum class YouTubeAccountVerificationState {
    UNVERIFIED,
    CHECKING,
    VERIFIED,
}

/** 서버 확인 결과는 현재 화면 인스턴스에서만 유효합니다. */
internal class YouTubeVerificationMemory {
    val state = mutableStateOf(YouTubeAccountVerificationState.UNVERIFIED)
    val verifiedProfileEmail = mutableStateOf<String?>(null)
    val suppressRefreshOnce = mutableStateOf(false)

    internal suspend fun connectAccount(connectAndVerify: suspend () -> Unit) {
        // 응답이 이 화면에 도착하지 않더라도 서버는 채널을 교체했을 수 있습니다.
        state.value = YouTubeAccountVerificationState.UNVERIFIED
        verifiedProfileEmail.value = null
        try {
            connectAndVerify()
        } finally {
            suppressRefreshOnce.value = state.value == YouTubeAccountVerificationState.VERIFIED
        }
    }
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

// FAILED는 응답만 유실됐다는 뜻일 수 있습니다. 서버 폴링이나 취소로 IDLE에 돌아와야 합니다.
internal fun canChangeYouTubeAccount(
    hasSignedInUser: Boolean,
    broadcastState: BroadcastState,
    isPreparingBroadcast: Boolean,
    isDeletingAccount: Boolean,
    isAccountDeletionPending: Boolean,
): Boolean = hasSignedInUser && broadcastState == BroadcastState.IDLE && !isPreparingBroadcast &&
    !isDeletingAccount && !isAccountDeletionPending

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
