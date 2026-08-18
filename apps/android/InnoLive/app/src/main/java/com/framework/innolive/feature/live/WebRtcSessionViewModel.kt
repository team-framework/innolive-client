package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framework.innolive.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.webrtc.VideoTrack

class WebRtcSessionViewModel : ViewModel() {
    private var startJob: Job? = null
    private var selectedAudioInput: AudioDeviceInfo? = null

    var connectionState by mutableStateOf(WebRtcConnectionState.IDLE)
        private set
    var connectionStatus by mutableStateOf("WebRTC 연결 대기")
        private set
    var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)
        private set

    var connection: WebRtcConnection? by mutableStateOf(null)
        private set

    fun selectAudioInput(audioInput: AudioDeviceInfo?) {
        selectedAudioInput = audioInput
        connection?.setPreferredAudioInput(audioInput)
    }

    fun start(
        context: Context,
        refreshAccessToken: suspend () -> String,
    ) {
        if (connectionState != WebRtcConnectionState.IDLE) return

        connectionState = WebRtcConnectionState.CONNECTING
        connectionStatus = "인증 토큰 갱신 중"
        startJob = viewModelScope.launch {
            try {
                val accessToken = refreshAccessToken()
                WebRtcConnection(
                    context = context,
                    serverUrl = BuildConfig.INNOLIVE_SERVER_URL,
                    accessToken = accessToken,
                    preferredAudioInput = selectedAudioInput,
                    onStateChanged = { state, message ->
                        connectionState = state
                        connectionStatus = message
                    },
                    onRemoteTrackChanged = { track -> remoteVideoTrack = track },
                ).also { webRtcConnection ->
                    connection = webRtcConnection
                    webRtcConnection.start()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                connectionState = WebRtcConnectionState.FAILED
                connectionStatus = exception.message
                    ?: "인증 토큰을 갱신하지 못했습니다."
            }
        }
    }

    fun close() {
        startJob?.cancel()
        startJob = null
        connection?.close()
        connection = null
        remoteVideoTrack = null
        connectionState = WebRtcConnectionState.IDLE
        connectionStatus = "WebRTC 연결 대기"
    }

    override fun onCleared() {
        close()
    }
}
