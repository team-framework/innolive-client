package com.framework.innolive.feature.live.direct.broadcast

import android.media.AudioDeviceInfo
import com.framework.innolive.feature.live.AnonymizationFailure
import com.framework.innolive.feature.live.AnonymizationState
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ConnectionFailure
import com.framework.innolive.feature.live.LiveBroadcastCallbacks
import com.framework.innolive.feature.live.LiveBroadcastTransport
import com.framework.innolive.feature.live.WebRtcConnectionState
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 플랫폼 없이 온디바이스 가림 처리 미리보기만 보여 주는 송출 경로입니다. 네트워크가 불안정한 시연에서도
 * 쓰도록 서버 세션·토큰 갱신·송출 엔진을 만들지 않고 카메라와 앱 가림 처리만 엽니다.
 *
 * 첫 가림 처리 프레임이 나오면 CONNECTED를 알려 화면이 직접 송출과 같은 보호 미리보기를 그리게 합니다.
 * 방송 준비·라이브 요청은 받지 않으므로 방송하려면 이 경로를 닫고 새 송출 경로로 준비합니다.
 */
internal class OnDevicePreviewTransport(
    private val callbacks: LiveBroadcastCallbacks,
    private val media: DirectMediaPipeline,
    initialAnonymizationEnabled: Boolean,
    /** 카메라 열기·닫기를 차례로 실행할 스레드입니다. */
    private val owner: SerialScheduler,
    /** 화면 콜백을 보낼 곳입니다(메인 스레드). */
    private val callbackExecutor: Executor,
    private val log: (String) -> Unit = {},
) : LiveBroadcastTransport {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val closeLock = Any()
    private val closeCallbacks = mutableListOf<() -> Unit>()
    private var closeFinished = false

    // 아래 값은 owner 스레드에서만 다룹니다.
    private var opened = false
    private var released = false
    private var connected = false
    private var failed = false
    private var anonymizationEnabled = initialAnonymizationEnabled

    private val mediaEvents = object : DirectMediaEvents {
        override fun onFirstFrame() = owner.execute {
            if (!isActive() || connected || failed) return@execute
            connected = true
            val confirmed = anonymizationState(anonymizationEnabled)
            post { callbacks.onAnonymizationStateConfirmed(confirmed) }
            post { callbacks.onStateChanged(WebRtcConnectionState.CONNECTED, null) }
        }

        override fun onProcessingFailure() = owner.execute {
            // 첫 프레임 전 실패는 가림 처리를 준비하지 못한 것입니다. 이후 실패는 해당 프레임만 버려집니다.
            if (!isActive() || connected) return@execute
            fail(ConnectionFailure.GENERIC)
        }

        // 마이크를 열지 않으므로 상태가 오지 않습니다.
        override fun onMicrophoneStatus(status: MicrophoneStatus) = Unit
    }

    override fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        post { callbacks.onStateChanged(WebRtcConnectionState.CONNECTING, null) }
        // 준비 대기와 같은 상태 흐름을 쓰도록 연결 시작을 알립니다. 서버 요청은 없습니다.
        post { callbacks.onInitialSignalingStarted() }
        owner.execute {
            if (!isActive()) return@execute
            try {
                media.open(mediaEvents)
                opened = true
            } catch (exception: Exception) {
                log("preview_open_failed type=${exception.javaClass.simpleName}")
                fail(ConnectionFailure.GENERIC)
            }
        }
    }

    override fun close(onComplete: (() -> Unit)?) {
        var completeNow: (() -> Unit)? = null
        val shouldClean = synchronized(closeLock) {
            onComplete?.let { done -> if (closeFinished) completeNow = done else closeCallbacks += done }
            closed.compareAndSet(false, true)
        }
        completeNow?.let { done -> callbackExecutor.execute { done() } }
        if (!shouldClean) return
        owner.execute {
            releaseMedia()
            val callbacks = synchronized(closeLock) {
                closeFinished = true
                closeCallbacks.toList().also { closeCallbacks.clear() }
            }
            callbacks.forEach { done -> callbackExecutor.execute { done() } }
            owner.shutdown()
        }
    }

    override fun setPreferredAudioInput(audioInput: AudioDeviceInfo?) = Unit

    /** 서버가 없으므로 앱 가림 처리만 바꾸고 그 결과를 확인값으로 알립니다. */
    override fun setAnonymizationEnabled(
        enabled: Boolean,
        onComplete: (AnonymizationState?, AnonymizationFailure?) -> Unit,
    ) = owner.execute {
        if (!isActive() || !opened) return@execute post { onComplete(null, AnonymizationFailure.REQUEST) }
        try {
            media.setAnonymizationEnabled(enabled)
            anonymizationEnabled = enabled
            val confirmed = anonymizationState(enabled)
            post { callbacks.onAnonymizationStateConfirmed(confirmed) }
            post { onComplete(confirmed, null) }
        } catch (_: Exception) {
            post { onComplete(null, AnonymizationFailure.REQUEST) }
        }
    }

    // 미리보기 전용 경로는 플랫폼 설정·방송 요청을 받지 않습니다.
    override fun saveBroadcastSettings(settings: BroadcastSettings) = Unit

    override fun prepareBroadcast(settings: BroadcastSettings, allowConcurrent: Boolean): Boolean = false

    override fun prepareBroadcast(chzzkSettings: ChzzkBroadcastSettings): Boolean = false

    override fun prepareTargets(
        youtube: BroadcastSettings?,
        chzzk: ChzzkBroadcastSettings?,
        allowConcurrent: Boolean,
    ): Boolean = false

    override fun goLive(onAccepted: () -> Unit): Boolean = false

    override fun pauseBroadcast() = Unit

    override fun resumeBroadcast() = Unit

    override fun stopBroadcast() = Unit

    private fun fail(failure: ConnectionFailure) {
        if (failed) return
        failed = true
        releaseMedia()
        post { callbacks.onStateChanged(WebRtcConnectionState.FAILED, failure) }
    }

    /** 여는 도중 실패해도 만든 카메라·EGL을 돌려주도록 한 번은 닫습니다. */
    private fun releaseMedia() {
        if (released) return
        released = true
        opened = false
        runCatching { media.close() }
            .onFailure { log("preview_close_failed type=${it.javaClass.simpleName}") }
    }

    private fun isActive(): Boolean = !closed.get()

    private fun anonymizationState(enabled: Boolean) =
        if (enabled) AnonymizationState.ENABLED else AnonymizationState.DISABLED

    private fun post(block: () -> Unit) {
        callbackExecutor.execute { if (!closed.get()) block() }
    }
}
