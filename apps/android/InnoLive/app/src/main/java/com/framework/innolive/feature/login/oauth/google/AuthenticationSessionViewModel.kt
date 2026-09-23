package com.framework.innolive.feature.login.oauth.google

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
 * Activity-scoped owner for the encrypted Google session and its network
 * operations. The instance survives Activity configuration recreation and
 * closes the repository when the Activity's ViewModel store is destroyed.
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
    )

    internal val accountDeletionState: StateFlow<AccountDeletionState> =
        accountDeletionCoordinator.state

    internal fun deleteAccount(onRemoteDeletionConfirmed: () -> Unit = {}) {
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
