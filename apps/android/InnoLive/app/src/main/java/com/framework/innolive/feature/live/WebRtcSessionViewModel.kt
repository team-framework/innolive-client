package com.framework.innolive.feature.live

import android.content.Context
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.framework.innolive.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class WebRtcSessionViewModel : ViewModel() {
    private var startJob: Job? = null
    private var selectedAudioInput: AudioDeviceInfo? = null
    private val connectionGeneration = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    var connectionState by mutableStateOf(WebRtcConnectionState.IDLE)
        private set
    var connectionStatus by mutableStateOf("WebRTC 연결 대기")
        private set
    var remoteVideoTrack by mutableStateOf<VideoTrack?>(null)
        private set
    var broadcastState by mutableStateOf(BroadcastState.IDLE)
        private set
    var broadcastStatus by mutableStateOf("방송 대기")
        private set

    var frameAnalyzer: CameraFrameAnalyzer? by mutableStateOf(null)
        private set
    var eglContext: EglBase.Context? by mutableStateOf(null)
        private set

    private var connection: WebRtcConnection? = null

    fun selectAudioInput(audioInput: AudioDeviceInfo?) {
        selectedAudioInput = audioInput
        connection?.setPreferredAudioInput(audioInput)
    }

    fun start(
        context: Context,
        refreshAccessToken: suspend () -> String,
    ) {
        if (
            connectionState == WebRtcConnectionState.CONNECTING ||
            connectionState == WebRtcConnectionState.CONNECTED
        ) {
            return
        }

        val generation = connectionGeneration.incrementAndGet()
        startJob?.cancel()
        startJob = null
        val previousConnection = connection
        connection = null
        remoteVideoTrack = null
        frameAnalyzer = null
        eglContext = null
        if (previousConnection != null) {
            broadcastState = BroadcastState.IDLE
            broadcastStatus = "방송 대기"
        }

        connectionState = WebRtcConnectionState.CONNECTING
        connectionStatus = "인증 토큰 갱신 중"
        startJob = viewModelScope.launch {
            try {
                previousConnection?.let { oldConnection -> awaitClose(oldConnection) }
                if (!isCurrentGeneration(generation)) return@launch

                val accessToken = refreshAccessToken()
                if (!isCurrentGeneration(generation)) return@launch

                val newConnection = WebRtcConnection(
                    context = context,
                    serverUrl = BuildConfig.INNOLIVE_SERVER_URL,
                    accessToken = accessToken,
                    preferredAudioInput = selectedAudioInput,
                    onStateChanged = { state, message ->
                        if (isCurrentGeneration(generation)) {
                            connectionState = state
                            connectionStatus = message
                        }
                    },
                    onRemoteTrackChanged = { track ->
                        if (isCurrentGeneration(generation)) remoteVideoTrack = track
                    },
                    onLocalMediaReady = { analyzer, context ->
                        mainHandler.post {
                            if (isCurrentGeneration(generation)) {
                                frameAnalyzer = analyzer
                                eglContext = context
                            }
                        }
                    },
                    onLocalMediaCleared = {
                        mainHandler.post {
                            if (isCurrentGeneration(generation)) {
                                frameAnalyzer = null
                                eglContext = null
                            }
                        }
                    },
                    onBroadcastStateChanged = { state, message ->
                        if (isCurrentGeneration(generation)) {
                            broadcastState = state
                            broadcastStatus = message
                        }
                    },
                )
                if (!isCurrentGeneration(generation)) {
                    newConnection.close()
                    return@launch
                }
                connection = newConnection
                newConnection.start()
            } catch (exception: CancellationException) {
                if (isCurrentGeneration(generation)) {
                    connectionState = WebRtcConnectionState.IDLE
                    connectionStatus = "WebRTC 연결 대기"
                }
                throw exception
            } catch (exception: Exception) {
                if (isCurrentGeneration(generation)) {
                    connectionState = WebRtcConnectionState.FAILED
                    connectionStatus = exception.message
                        ?: "인증 토큰을 갱신하지 못했습니다."
                }
            }
        }
    }

    fun saveBroadcastSettings(settings: BroadcastSettings) {
        connection?.saveBroadcastSettings(settings)
            ?: run {
                broadcastState = BroadcastState.FAILED
                broadcastStatus = "비식별화 연결 후 방송 설정을 저장해 주세요."
            }
    }

    fun prepareBroadcast(settings: BroadcastSettings) {
        connection?.prepareBroadcast(settings)
            ?: run {
                broadcastState = BroadcastState.FAILED
                broadcastStatus = "비식별화 연결 후 방송을 준비해 주세요."
            }
    }

    fun goLive() {
        connection?.goLive()
    }

    fun stopBroadcast() {
        connection?.stopBroadcast()
    }

    fun close() {
        connectionGeneration.incrementAndGet()
        startJob?.cancel()
        startJob = null
        val currentConnection = connection
        connection = null
        remoteVideoTrack = null
        frameAnalyzer = null
        eglContext = null
        currentConnection?.close()
        connectionState = WebRtcConnectionState.IDLE
        connectionStatus = "WebRTC 연결 대기"
        broadcastState = BroadcastState.IDLE
        broadcastStatus = "방송 대기"
    }

    override fun onCleared() {
        close()
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        connectionGeneration.get() == generation

    private suspend fun awaitClose(webRtcConnection: WebRtcConnection) {
        suspendCancellableCoroutine { continuation ->
            webRtcConnection.close {
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
    }
}
