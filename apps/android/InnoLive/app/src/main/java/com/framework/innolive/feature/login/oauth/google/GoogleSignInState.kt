package com.framework.innolive.feature.login.oauth.google

import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Activity와 무관한 Google 인증 표시 상태입니다. 소유 ViewModel은 언어 변경에 따른 구성
 * 재생성에도 유지되므로, 다시 생성된 로그인 화면이 이미 진행 중인 자격 증명 요청을 다시
 * 시작할 수 없습니다.
 */
sealed interface GoogleSignInState {
    data object Idle : GoogleSignInState

    data object InProgress : GoogleSignInState

    data object Succeeded : GoogleSignInState

    data class Failed(val error: UiText) : GoogleSignInState
}

internal class GoogleSignInController(
    private val scope: CoroutineScope,
    private val authenticate: suspend (Context) -> Unit,
) {
    private val _state = MutableStateFlow<GoogleSignInState>(GoogleSignInState.Idle)
    val state: StateFlow<GoogleSignInState> = _state.asStateFlow()

    fun start(context: Context) {
        if (_state.value is GoogleSignInState.InProgress) return

        _state.value = GoogleSignInState.InProgress
        scope.launch {
            try {
                authenticate(context)
                _state.value = GoogleSignInState.Succeeded
            } catch (_: GetCredentialCancellationException) {
                _state.value = GoogleSignInState.Idle
            } catch (exception: CancellationException) {
                _state.value = GoogleSignInState.Idle
                throw exception
            } catch (_: Exception) {
                _state.value = GoogleSignInState.Failed(
                    UiText.Resource(R.string.google_login_failed),
                )
            }
        }
    }

    fun acknowledgeSuccess() {
        if (_state.value is GoogleSignInState.Succeeded) {
            _state.value = GoogleSignInState.Idle
        }
    }
}
