package com.framework.innolive.feature.live.recording

import android.os.Handler
import android.os.Looper
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.LiveBroadcastTransport
import com.framework.innolive.feature.live.VideoTrackLifecycle
import com.framework.innolive.ui.text.UiText
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.util.UUID
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

/** ViewModel이 녹화를 다루는 동작입니다. 기기 테스트는 가짜 구현으로 전이를 확인합니다. */
internal interface BroadcastRecordingControl {
    /** 녹화를 시작합니다. false면 이번 방송은 녹화하지 않습니다. */
    fun begin(media: RecordingMediaBindings): Boolean

    /** 방송 시작(라이브·일시 중지)을 확인했습니다. */
    fun confirm()

    fun remoteTrackChanged(track: VideoTrack?)

    /** 녹화를 끝냅니다. [save]가 false면 파일을 버립니다. */
    fun finish(save: Boolean)
}

/**
 * ViewModel 안에서 녹화 세션을 송출 경로에 붙이고 뗍니다. 모든 공개 메서드는 메인 스레드에서 부르며
 * 파일 마무리는 ViewModel 범위와 무관한 전용 스레드에서 끝까지 진행합니다. 저장하는 동안에는
 * [keepAlive]로 프로세스를 유지합니다.
 */
internal class BroadcastRecordingHost(
    private val store: BroadcastRecordingStore,
    private val onStateChanged: (BroadcastRecordingUiState) -> Unit,
    private val keepAlive: RecordingKeepAlive,
    private val minimumFreeBytes: Long = DEFAULT_MINIMUM_FREE_BYTES,
    private val mediaFactory: (EglBase.Context) -> RecordingMediaFactory = { AndroidRecordingMediaFactory(it) },
    private val postToMain: (() -> Unit) -> Unit = Handler(Looper.getMainLooper()).let { handler -> { handler.post(it) } },
) : BroadcastRecordingControl {
    private class Active(
        val session: BroadcastRecordingSession,
        val analyzer: CameraFrameAnalyzer,
        val transport: LiveBroadcastTransport?,
        val usesRemoteTrack: Boolean,
        var remoteTrack: VideoTrack?,
        var confirmed: Boolean = false,
    )

    private val finisher = Executors.newSingleThreadExecutor { Thread(it, "recording-finish") }
    private var active: Active? = null
    /** 녹화를 시작할 때마다 늘립니다. 이전 녹화의 늦은 결과가 새 녹화 상태를 덮지 않게 합니다. */
    private var generation = 0L

    override fun begin(media: RecordingMediaBindings): Boolean {
        if (active != null) return false
        val thisGeneration = ++generation
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
        val id = UUID.randomUUID().toString()
        RecordingOwnership.claim(id)
        val session = try {
            val directory = store.createDirectory(id)
            BroadcastRecordingSession(
                id = id,
                directory = directory,
                sessionId = media.sessionId,
                provider = media.provider,
                createdAtMillis = System.currentTimeMillis(),
                media = mediaFactory(egl),
                alignProcessed = !media.onDeviceProcessing,
                onFailure = { reason ->
                    postToMain { if (generation == thisGeneration) onSessionFailed(reason) }
                },
            )
        } catch (error: Exception) {
            RecordingLog.w("recording_begin_failed type=${error.javaClass.simpleName}")
            runCatching { store.directory(id).deleteRecursively() }
            RecordingOwnership.release(id)
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

    override fun confirm() {
        val binding = active ?: return
        if (binding.confirmed) return
        binding.confirmed = true
        finisher.execute { binding.session.markConfirmed() }
    }

    /** 서버 AI 모드에서 재협상으로 돌려받는 트랙이 바뀌면 송출 프레임 입력을 옮깁니다. */
    override fun remoteTrackChanged(track: VideoTrack?) {
        val binding = active ?: return
        if (binding.usesRemoteTrack) attachRemoteTrack(binding, track)
    }

    override fun finish(save: Boolean) {
        val binding = active ?: return
        active = null
        detach(binding)
        val session = binding.session
        if (!save) {
            finisher.execute {
                try {
                    runCatching { session.discard() }
                } finally {
                    RecordingOwnership.release(session.id)
                }
            }
            publish(BroadcastRecordingUiState())
            return
        }
        publish(BroadcastRecordingUiState(BroadcastRecordingStatus.SAVING, UiText.Resource(R.string.recording_status_saving)))
        val lease = runCatching { keepAlive.acquire() }.getOrNull()
        val finishedGeneration = generation
        finisher.execute {
            val result = try {
                session.markFinalizing()
                runCatching { session.stop() }
            } finally {
                RecordingOwnership.release(session.id)
            }
            postToMain {
                runCatching { lease?.close() }
                // 저장하는 동안 새 녹화를 시작했으면 그 상태를 그대로 둡니다. 보관함은 파일로 결과를 보여 줍니다.
                if (generation == finishedGeneration && active == null) publishSaveResult(result)
                else result.onFailure { RecordingLog.w("recording_save_failed type=${it.javaClass.simpleName}") }
            }
        }
    }

    private fun publishSaveResult(result: Result<SavedBroadcastRecording>) {
        result.onSuccess { saved ->
            publish(BroadcastRecordingUiState(BroadcastRecordingStatus.SAVED,
                UiText.Resource(R.string.recording_status_saved), saved))
        }.onFailure { error ->
            RecordingLog.w("recording_save_failed type=${error.javaClass.simpleName}")
            val reason = (error as? RecordingFailedException)?.reason ?: RecordingFailure.STORAGE
            publish(BroadcastRecordingUiState(BroadcastRecordingStatus.FAILED, failureMessage(reason)))
        }
    }

    /** 녹화 중 치명적인 인코더·저장 오류입니다. 방송은 그대로 둡니다. 확인된 방송의 파일은 보관합니다. */
    private fun onSessionFailed(reason: RecordingFailure) {
        val binding = active ?: return
        active = null
        detach(binding)
        val confirmed = binding.confirmed
        finisher.execute {
            try {
                runCatching { binding.session.abandon(confirmed) }
            } finally {
                RecordingOwnership.release(binding.session.id)
            }
        }
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
        /** iOS `RecordingSessionStore`와 같은 시작 기준입니다. */
        const val DEFAULT_MINIMUM_FREE_BYTES = 256L * 1024L * 1024L

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

/** 앱이 쓰는 기본 녹화 호스트입니다. */
internal fun createBroadcastRecordingHost(
    context: android.content.Context,
    onStateChanged: (BroadcastRecordingUiState) -> Unit,
): BroadcastRecordingControl = BroadcastRecordingHost(
    store = BroadcastRecordingStore(BroadcastRecordingStore.defaultRoot(context)),
    onStateChanged = onStateChanged,
    keepAlive = ServiceRecordingKeepAlive(context),
)
