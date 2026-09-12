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

    suspend fun refresh(): GoogleSessionStore.Session = repository.refresh()

    suspend fun refreshAccessToken(): String = refresh().accessToken

    fun clear() {
        repository.clear()
    }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
