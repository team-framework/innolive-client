package com.framework.innolive.feature.login.oauth.google

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

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

    fun reload(): GoogleSessionStore.Session? = repository.reload()

    fun save(session: GoogleSessionStore.Session) {
        repository.save(session)
    }

    suspend fun continueWithGoogle(context: Context) {
        com.framework.innolive.feature.login.oauth.google.continueWithGoogle(
            context = context,
            sessionRepository = repository,
        )
    }

    private val emailApi = com.framework.innolive.feature.login.EmailSignInApi()

    private val emailSignUpApi = com.framework.innolive.feature.login.EmailSignUpApi()

    private val emailSignupSession = com.framework.innolive.feature.login.EmailSignupSession(
        signUp = emailSignUpApi::signUp,
        verifyEmail = emailSignUpApi::verify,
        authenticate = emailApi::authenticate,
        saveSession = repository::save,
    )

    suspend fun startEmailSignup(email: String, password: String) {
        emailSignupSession.start(email, password)
    }

    suspend fun resendEmailSignup() {
        emailSignupSession.resend()
    }

    suspend fun verifyEmailSignup(code: String) {
        emailSignupSession.verify(code)
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
    }

    suspend fun refresh(): GoogleSessionStore.Session = repository.refresh()

    suspend fun refreshAccessToken(): String = refresh().accessToken

    fun clear() {
        emailSignupSession.cancel()
        repository.clear()
    }

    override fun onCleared() {
        emailSignupSession.cancel()
        repository.close()
        super.onCleared()
    }
}
