package com.framework.innolive.feature.login.oauth.google

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framework.innolive.feature.live.components.YouTubeViewerChatWebData
import com.framework.innolive.feature.login.account.AccountDeletionApi
import com.framework.innolive.feature.login.account.AccountDeletionCoordinator
import com.framework.innolive.feature.login.account.AccountDeletionCleanupStore
import com.framework.innolive.feature.login.account.AccountDeletionState
import com.framework.innolive.feature.login.account.AccountDeletionUseCase
import com.framework.innolive.feature.login.account.AccountLocalDataCleaner
import com.framework.innolive.feature.login.account.PendingAccountDeletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * 암호화된 Google 세션과 그 네트워크 작업을 소유하는 Activity 범위 객체입니다. Activity 구성
 * 변경으로 재생성돼도 유지되며, Activity의 ViewModel store가 소멸하면 repository를 닫습니다.
 */
class AuthenticationSessionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository = AuthenticationSessionRepository(
        store = GoogleSessionStore(application),
        api = GoogleAuthenticationApi(),
    )

    val session: StateFlow<GoogleSessionStore.Session?> = repository.session

    private val googleSignInController = GoogleSignInController(
        scope = viewModelScope,
        authenticate = { context ->
            com.framework.innolive.feature.login.oauth.google.continueWithGoogle(
                context = context,
                sessionRepository = repository,
            )
            syncAccountDeletionState()
        },
    )
    val googleSignInState: StateFlow<GoogleSignInState> = googleSignInController.state

    fun reload(): GoogleSessionStore.Session? = repository.reload().also {
        syncAccountDeletionState()
    }

    fun save(session: GoogleSessionStore.Session) {
        repository.save(session)
        syncAccountDeletionState()
    }

    fun startGoogleSignIn(context: Context) = googleSignInController.start(context)

    fun acknowledgeGoogleSignInSuccess() = googleSignInController.acknowledgeSuccess()

    private val emailApi = com.framework.innolive.feature.login.EmailSignInApi()

    private val emailSignUpApi = com.framework.innolive.feature.login.EmailSignUpApi()

    private val emailSignupSession = com.framework.innolive.feature.login.EmailSignupSession(
        signUp = emailSignUpApi::signUp,
        verifyEmail = emailSignUpApi::verify,
        authenticate = emailApi::authenticate,
        saveSession = repository::save,
    )

    private val accountLocalDataCleaner = AccountLocalDataCleaner(application)
    /** 삭제 확인과 세션 만료 로그아웃 모두 방송 연결을 닫아야 하므로 마지막 삭제 요청의 콜백을 보관한다. */
    private var closeBroadcastOnSessionExpiry: () -> Unit = {}
    private val accountDeletionCleanupStore = AccountDeletionCleanupStore(application)
    private val initialPendingDeletion = repository.currentSession?.let { currentSession ->
        accountDeletionCleanupStore.loadPhaseFor(currentSession)?.let { phase ->
            PendingAccountDeletion(currentSession, phase)
        }
    } ?: run {
        accountDeletionCleanupStore.discardCompletedDeletionWithoutAuthentication()
        null
    }
    private val accountDeletionCoordinator = AccountDeletionCoordinator(
        scope = viewModelScope,
        currentSession = { repository.currentSession },
        deleteRemoteAccount = { deletingSession ->
            AccountDeletionApi().use { deletionApi ->
                AccountDeletionUseCase(
                    gateway = deletionApi,
                    refreshAccessToken = repository::refreshAccessToken,
                ).delete(deletingSession.accessToken)
            }
        },
        clearLocalAccountData = accountLocalDataCleaner::clear,
        clearAuthentication = {
            emailSignupSession.cancel()
            repository.clear()
            YouTubeViewerChatWebData.clearAppWebData("account_deletion")
        },
        initialPendingDeletion = initialPendingDeletion,
        persistPendingDeletion = { session, phase ->
            withContext(Dispatchers.IO) {
                accountDeletionCleanupStore.save(session, phase)
            }
        },
        clearPendingDeletion = {
            withContext(Dispatchers.IO) {
                accountDeletionCleanupStore.clear()
            }
        },
        onSessionExpired = { closeBroadcastOnSessionExpiry() },
    )

    internal val accountDeletionState: StateFlow<AccountDeletionState> =
        accountDeletionCoordinator.state

    internal fun deleteAccount(onRemoteDeletionConfirmed: () -> Unit = {}) {
        closeBroadcastOnSessionExpiry = onRemoteDeletionConfirmed
        accountDeletionCoordinator.delete(onRemoteDeletionConfirmed)
    }

    suspend fun startEmailSignup(email: String, password: String) {
        emailSignupSession.start(email, password)
    }

    suspend fun resendEmailSignup() {
        emailSignupSession.resend()
    }

    suspend fun verifyEmailSignup(code: String) {
        emailSignupSession.verify(code)
        syncAccountDeletionState()
    }

    fun cancelEmailSignup() {
        emailSignupSession.cancel()
    }

    fun hasPendingEmailSignup(): Boolean = emailSignupSession.hasPendingSignup()

    fun isEmailSignupVerified(): Boolean = emailSignupSession.isVerified()

    suspend fun signInWithEmail(email: String, password: String) {
        com.framework.innolive.feature.login.authenticateAndSaveEmailSession(
            email, password, emailApi::authenticate, repository::save,
        )
        syncAccountDeletionState()
    }

    suspend fun refresh(): GoogleSessionStore.Session = repository.refresh()

    suspend fun refreshAccessToken(): String = refresh().accessToken

    fun clear() {
        emailSignupSession.cancel()
        repository.clear()
        // 시청자 채팅 WebView가 남긴 YouTube 쿠키·WebStorage는 로그아웃 계정과 함께 지운다(android-viewer-chat.md).
        YouTubeViewerChatWebData.clearAppWebData("logout")
        syncAccountDeletionState()
    }

    private fun syncAccountDeletionState() {
        val pending = repository.currentSession?.let { currentSession ->
            accountDeletionCleanupStore.loadPhaseFor(currentSession)?.let { phase ->
                PendingAccountDeletion(currentSession, phase)
            }
        }
        accountDeletionCoordinator.authenticationChanged(pending)
    }

    override fun onCleared() {
        emailSignupSession.cancel()
        repository.close()
        super.onCleared()
    }
}
