package com.framework.innolive.feature.live.recording

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.LiveBroadcastTransport
import com.framework.innolive.feature.live.VideoTrackLifecycle
import com.framework.innolive.ui.text.UiText
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

enum class BroadcastRecordingStatus { IDLE, RECORDING, SAVING, SAVED, FAILED }

/** 방송 화면이 보여 주는 녹화 상태입니다. 녹화 중에는 문구를 띄우지 않습니다. */
data class BroadcastRecordingUiState(
    val status: BroadcastRecordingStatus = BroadcastRecordingStatus.IDLE,
    val message: UiText? = null,
    val saved: SavedBroadcastRecording? = null,
)

/** 녹화를 시작할 때 필요한 송출 경로의 미디어입니다. */
internal data class RecordingMediaBindings(
    val frameAnalyzer: CameraFrameAnalyzer?,
    val eglContext: EglBase.Context?,
    val remoteVideoTrack: VideoTrack?,
    /** 온디바이스 처리면 분석기의 가림 처리 출력을, 서버 AI면 서버가 돌려주는 트랙을 송출 프레임으로 씁니다. */
    val onDeviceProcessing: Boolean,
    val transport: LiveBroadcastTransport?,
    val sessionId: String?,
    val provider: String,
)

/**
 * ViewModel 안에서 녹화 세션을 송출 경로에 붙이고 뗍니다. 모든 공개 메서드는 메인 스레드에서 부르며
 * 파일 마무리는 ViewModel 범위와 무관한 전용 스레드에서 끝까지 진행합니다.
 */
internal class BroadcastRecordingHost(
    private val store: BroadcastRecordingStore,
    private val onStateChanged: (BroadcastRecordingUiState) -> Unit,
    private val minimumFreeBytes: Long = DEFAULT_MINIMUM_FREE_BYTES,
) {
    private class Active(
        val session: BroadcastRecordingSession,
        val analyzer: CameraFrameAnalyzer,
        val transport: LiveBroadcastTransport?,
        val usesRemoteTrack: Boolean,
        var remoteTrack: VideoTrack?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val finisher = Executors.newSingleThreadExecutor { Thread(it, "recording-finish") }
    private var active: Active? = null
    private var generation = 0L

    fun begin(media: RecordingMediaBindings): Boolean {
        if (active != null) return false
        val analyzer = media.frameAnalyzer
        val egl = media.eglContext
        if (analyzer == null || egl == null) {
            publish(BroadcastRecordingUiState(BroadcastRecordingStatus.FAILED, UiText.Resource(R.string.recording_failed_start)))
            return false
        }
        val available = store.availableBytes()
        if (available == null || available < minimumFreeBytes) {
            publish(BroadcastRecordingUiState(BroadcastRecordingStatus.FAILED, UiText.Resource(R.string.recording_failed_storage)))
            return false
        }
        val thisGeneration = ++generation
        val session = try {
            val directory = store.createDirectory()
            store.activeId = directory.name
            BroadcastRecordingSession(
                id = directory.name,
                directory = directory,
                sharedEglContext = egl,
                sessionId = media.sessionId,
                provider = media.provider,
                createdAtMillis = System.currentTimeMillis(),
                onFailure = { reason ->
                    mainHandler.post { if (generation == thisGeneration) onSessionFailed(reason) }
                },
            )
        } catch (error: Exception) {
            Log.w(LOG_TAG, "recording_begin_failed type=${error.javaClass.simpleName}")
            store.activeId = null
            publish(BroadcastRecordingUiState(BroadcastRecordingStatus.FAILED, UiText.Resource(R.string.recording_failed_start)))
            return false
        }
        val binding = Active(session, analyzer, media.transport, usesRemoteTrack = !media.onDeviceProcessing, remoteTrack = null)
        active = binding
        analyzer.setRawRecordingSink(session.rawSink)
        if (binding.usesRemoteTrack) attachRemoteTrack(binding, media.remoteVideoTrack)
        else analyzer.setProcessedRecordingSink(session.processedSink)
        media.transport?.setRecordingAudioSink(session.audioSink)
        publish(BroadcastRecordingUiState(BroadcastRecordingStatus.RECORDING))
        return true
    }

    /** 서버 AI 모드에서 재협상으로 돌려받는 트랙이 바뀌면 송출 프레임 입력을 옮깁니다. */
    fun remoteTrackChanged(track: VideoTrack?) {
        val binding = active ?: return
        if (binding.usesRemoteTrack) attachRemoteTrack(binding, track)
    }

    fun finish(save: Boolean) {
        val binding = active ?: return
        active = null
        detach(binding)
        val session = binding.session
        if (!save) {
            store.activeId = null
            finisher.execute { runCatching { session.discard() } }
            publish(BroadcastRecordingUiState())
            return
        }
        publish(BroadcastRecordingUiState(BroadcastRecordingStatus.SAVING, UiText.Resource(R.string.recording_status_saving)))
        finisher.execute {
            val result = runCatching { session.stop() }
            store.activeId = null
            mainHandler.post {
                result.onSuccess { saved ->
                    publish(BroadcastRecordingUiState(BroadcastRecordingStatus.SAVED,
                        UiText.Resource(R.string.recording_status_saved), saved))
                }.onFailure { error ->
                    Log.w(LOG_TAG, "recording_save_failed type=${error.javaClass.simpleName}")
                    val reason = (error as? RecordingFailedException)?.reason ?: RecordingFailure.STORAGE
                    publish(BroadcastRecordingUiState(BroadcastRecordingStatus.FAILED, failureMessage(reason)))
                }
            }
        }
    }

    /** 녹화 중 치명적인 인코더·저장 오류입니다. 파일을 버리고 방송은 그대로 둡니다. */
    private fun onSessionFailed(reason: RecordingFailure) {
        val binding = active ?: return
        active = null
        detach(binding)
        store.activeId = null
        finisher.execute { runCatching { binding.session.discard() } }
        publish(BroadcastRecordingUiState(BroadcastRecordingStatus.FAILED, failureMessage(reason)))
    }

    private fun attachRemoteTrack(binding: Active, track: VideoTrack?) {
        val previous = binding.remoteTrack
        if (previous === track) return
        previous?.let { runCatching { VideoTrackLifecycle.removeSink(it, binding.session.processedSink) } }
        binding.remoteTrack = track?.takeIf { VideoTrackLifecycle.addSink(it, binding.session.processedSink) }
    }

    private fun detach(binding: Active) {
        runCatching { binding.transport?.setRecordingAudioSink(null) }
        runCatching { binding.analyzer.setRawRecordingSink(null) }
        runCatching { binding.analyzer.setProcessedRecordingSink(null) }
        binding.remoteTrack?.let { runCatching { VideoTrackLifecycle.removeSink(it, binding.session.processedSink) } }
        binding.remoteTrack = null
    }

    private fun publish(state: BroadcastRecordingUiState) = onStateChanged(state)

    companion object {
        private const val LOG_TAG = "BroadcastRecording"
        const val DEFAULT_MINIMUM_FREE_BYTES = 512L * 1024L * 1024L

        fun failureMessage(reason: RecordingFailure): UiText = UiText.Resource(
            when (reason) {
                RecordingFailure.NO_VIDEO -> R.string.recording_failed_no_video
                RecordingFailure.NO_AUDIO -> R.string.recording_failed_no_audio
                RecordingFailure.ENCODER -> R.string.recording_failed_encoder
                RecordingFailure.STORAGE -> R.string.recording_failed_storage
                RecordingFailure.INVALID_FILE -> R.string.recording_failed_invalid
                RecordingFailure.START -> R.string.recording_failed_start
            },
        )
    }
}
