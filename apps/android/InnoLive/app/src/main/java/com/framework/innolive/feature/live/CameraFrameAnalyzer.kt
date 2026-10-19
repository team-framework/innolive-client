package com.framework.innolive.feature.live

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.privacy.PrivacyFrameMode
import com.framework.innolive.feature.live.privacy.PrivacyFrameProcessor
import com.framework.innolive.feature.live.privacy.PrivacyFrameRoute
import com.framework.innolive.feature.live.privacy.PrivacyNativePixels
import com.framework.innolive.feature.live.privacy.PrivacyFrameAnalysis
import com.framework.innolive.feature.live.privacy.PrivacyFrameTimings
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference
import org.webrtc.EglBase
import com.framework.innolive.feature.live.privacy.PrivacyCameraInput
import com.framework.innolive.feature.live.privacy.isPrivacyProcessingFailure
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraFrameAnalyzer(
    private val capturerObserver: CapturerObserver? = null,
    private val applicationContext: Context? = null,
    initialOnDevice: Boolean = false,
    initialAnonymizationEnabled: Boolean = true,
    private val onProcessingFailure: () -> Unit = {},
    private val onProtectedFrameSent: () -> Unit = {},
    private val onCaptureFormat: (Int, Int) -> Unit = { _, _ -> },
    private val sharedEglContext: EglBase.Context? = null,
    private val directCameraInput: Boolean = true,
    private val directGpuInput: Boolean = true,
    private val nativePostprocessing: Boolean = true,
    private val batchTwoOptimizations: Boolean = true,
    private val keepLatestProtectedFrame:Boolean = true,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val captureLock = Any()
    private val settings = AtomicReference(BroadcastVideoQualitySettings())
    private val previewExposure = PreviewExposureFrameGate()
    private val colorProcessor = VideoColorFrameProcessor()
    private val previewRenderer = VideoLookPreviewRenderer()
    val lookPreviews: StateFlow<VideoLookPreviews?> = previewRenderer.previews
    @Volatile private var previewSink: VideoSink? = null
    @Volatile private var directPublishSink: VideoSink? = null
    /** 비식별화·색 보정 전 카메라 원본을 받는 녹화 싱크입니다. 분석 스레드에서 바로 부르며 프레임은 호출 뒤 해제합니다. */
    @Volatile private var rawRecordingSink: VideoSink? = null
    /** 실제로 송출한 프레임(온디바이스 가림 처리 출력 또는 가림을 끈 보정 프레임)을 받는 녹화 싱크입니다. */
    @Volatile private var processedRecordingSink: VideoSink? = null
    // 녹화 렌더러가 프레임을 잠시 붙잡는 동안 다음 프레임이 새 버퍼를 할당하지 않도록 여분을 둡니다.
    private val recordingBuffers = PrivacyCameraBufferPool(maxIdle = RECORDING_BUFFER_SLOTS)
    private val faceFrameTap = AtomicReference<((ImageProxy) -> Unit)?>(null)
    private val anonymizationFailures = AtomicLong()
    private var reportedColorFailure = false

    internal var faceCoordinatorFactory: (() -> com.framework.innolive.feature.live.privacy.PrivacyFaceCoordinator)? = null
    private val enabled = AtomicBoolean(false)
    private val protectedFrameReported = AtomicBoolean(false)
    private val processing = AtomicBoolean(false)
    private val pendingProtectedCamera = ProtectedFrameSlot<PendingProtectedCamera>()
    @Volatile private var protectedPrepared = !batchTwoOptimizations
    /** 준비에 실패해 다시 모드를 바꾸기 전까지 가림 처리 프레임을 낼 수 없습니다. */
    @Volatile private var protectedPreparationFailed = false
    private val preparationQueued=AtomicBoolean(false)
    private val preparationLatch=CountDownLatch(1)
    internal fun awaitProtectedPreparation(timeoutSeconds:Long=30):Boolean =
        preparationLatch.await(timeoutSeconds,TimeUnit.SECONDS) && protectedPrepared
    private val resetPending = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "privacy-uplink").apply { priority = Thread.NORM_PRIORITY + 1 }
    }
    private val received = AtomicLong()
    private val dropped = AtomicLong()
    private val delivered = AtomicLong()
    private var lastLogNs = System.nanoTime()
    private var lastCaptureFormat: Pair<Int, Int>? = null
    private var lastStagesLogNs = System.nanoTime()
    internal var onFrameDiagnostics: ((PrivacyCaptureDiagnostics) -> Unit)? = null
    private val processorLock = Any()
    private val cameraBuffers = PrivacyCameraBufferPool(maxIdle = RECORDING_BUFFER_SLOTS)
    @Volatile
    private var localProcessor: PrivacyFrameProcessor? =
        if (initialOnDevice && !batchTwoOptimizations) PrivacyFrameProcessor(checkNotNull(applicationContext), sharedEglContext,directGpuInput=directGpuInput,
            nativePostprocessing=nativePostprocessing,batchTwoOptimizations=false,
            faceCoordinatorFactory=faceCoordinatorFactory) else null
    private val route = PrivacyFrameRoute(
        when {
            !initialOnDevice -> PrivacyFrameMode.SERVER
            initialAnonymizationEnabled -> PrivacyFrameMode.LOCAL_PROTECTED
            else -> PrivacyFrameMode.LOCAL_RAW
        },
    )

    private fun processor():PrivacyFrameProcessor = localProcessor ?: PrivacyFrameProcessor(
        checkNotNull(applicationContext),sharedEglContext,directGpuInput=directGpuInput,
        nativePostprocessing=nativePostprocessing,batchTwoOptimizations=batchTwoOptimizations,
        faceCoordinatorFactory=faceCoordinatorFactory,
    ).also {localProcessor=it}

    private fun schedulePreparation() {
        if (!batchTwoOptimizations || protectedPrepared || stopped.get() ||
            !preparationQueued.compareAndSet(false,true)) return
        worker.execute {
            try {
                synchronized(processorLock) {processor().prepare()}
                if (!stopped.get()) protectedPrepared=true
            } catch(error:Throwable) {
                if(!error.isPrivacyProcessingFailure()) throw error
                protectedPreparationFailed=true
                Log.w("PrivacyPipeline","prepare_failed type=${error.javaClass.simpleName}")
                route.ticket()?.takeIf {it.mode==PrivacyFrameMode.LOCAL_PROTECTED}?.let {
                    route.deliver(it,onProcessingFailure)
                }
            } finally {
                preparationQueued.set(false)
                preparationLatch.countDown()
            }
        }
    }

    fun setProcessingMode(onDevice: Boolean, anonymizationEnabled: Boolean) {
        if (onDevice && localProcessor == null && !batchTwoOptimizations) {
            synchronized(processorLock) {if(localProcessor==null) processor()}
        }
        val nextMode = when {
            !onDevice -> PrivacyFrameMode.SERVER
            anonymizationEnabled -> PrivacyFrameMode.LOCAL_PROTECTED
            else -> PrivacyFrameMode.LOCAL_RAW
        }
        if (nextMode == PrivacyFrameMode.LOCAL_PROTECTED &&
            route.ticket()?.mode != PrivacyFrameMode.LOCAL_PROTECTED) {
            protectedPrepared = false
        }
        protectedPreparationFailed = false
        resetPending.set(true)
        route.change(nextMode)
        pendingProtectedCamera.clearPending()?.close()
        protectedFrameReported.set(false)
        if (batchTwoOptimizations && !stopped.get()) {
            if (onDevice && anonymizationEnabled && enabled.get()) schedulePreparation()
            else worker.execute { synchronized(processorLock) {localProcessor?.deactivateFaces()} }
        }
    }

    fun setVideoQualitySettings(value: BroadcastVideoQualitySettings) {
        settings.set(value.normalized())
    }

    internal fun beginPreviewExposure() = synchronized(captureLock) {
        previewExposure.begin()
        previewRenderer.invalidatePendingSample()
    }

    internal fun recordPreviewExposure(timestamp: Long, exposureEV: Float, settled: Boolean) =
        previewExposure.record(timestamp, exposureEV, settled)

    fun setLookPreviewEnabled(value: Boolean) = synchronized(captureLock) {
        previewRenderer.setEnabled(value)
    }

    fun resetLookPreviewSample() = synchronized(captureLock) {
        previewExposure.begin()
        previewRenderer.resetSample()
    }

    fun setProcessedPreviewSink(sink: VideoSink?) = synchronized(captureLock) {
        if (!stopped.get()) previewSink = sink
    }

    /**
     * 앱 직접 송출 입력입니다. 가림 처리된 프레임과 사용자가 가림을 끈 프레임만 받습니다.
     * 서버가 가림 처리할 원본(SERVER 모드)은 넘기지 않습니다.
     */
    fun setDirectPublishSink(sink: VideoSink?) = synchronized(captureLock) {
        directPublishSink = if (stopped.get()) null else sink
    }

    /** 원본 녹화 입력입니다. 서버 AI 모드에서도 카메라 원본을 넘깁니다. */
    fun setRawRecordingSink(sink: VideoSink?) = synchronized(captureLock) {
        rawRecordingSink = if (stopped.get()) null else sink
    }

    /** 송출 프레임 녹화 입력입니다. 서버 AI 모드에서는 서버가 돌려주는 영상이 송출 결과이므로 여기로는 오지 않습니다. */
    fun setProcessedRecordingSink(sink: VideoSink?) = synchronized(captureLock) {
        processedRecordingSink = if (stopped.get()) null else sink
    }

    private fun deliverToRecording(sink: VideoSink?, frame: VideoFrame) {
        sink ?: return
        // 녹화 오류가 송출·미리보기 전달이나 가림 처리 실패 판정으로 번지지 않게 합니다.
        try {
            sink.onFrame(frame)
        } catch (error: Exception) {
            Log.w("CameraFrameAnalyzer", "recording_frame_failed type=${error.javaClass.simpleName}")
        }
    }

    /**
     * 온디바이스 가림 처리 경로용 원본 녹화 복사입니다. 이 경로는 카메라 이미지를 작업 스레드로 넘겨 복사본이
     * 없으므로 따로 복사합니다. 서버 AI·가림 해제 경로는 이미 만든 카메라 복사본을 공유합니다.
     */
    private fun deliverRawToRecording(image: ImageProxy) {
        val sink = rawRecordingSink ?: return
        val crop = image.cropRect
        val source = try { recordingBuffers.acquire(image.width, image.height) } catch (_: IllegalStateException) { return }
        try {
            copyPlane(image.planes[0], image.width, image.height, source.dataY, source.strideY)
            copyPlane(image.planes[1], (image.width + 1) / 2, (image.height + 1) / 2, source.dataU, source.strideU)
            copyPlane(image.planes[2], (image.width + 1) / 2, (image.height + 1) / 2, source.dataV, source.strideV)
            val output = if (crop.left == 0 && crop.top == 0 && crop.width() == image.width && crop.height() == image.height) {
                source.apply { retain() }
            } else source.cropAndScale(crop.left, crop.top, crop.width(), crop.height(), crop.width(), crop.height())
            val frame = VideoFrame(output, image.imageInfo.rotationDegrees, image.imageInfo.timestamp)
            try { deliverToRecording(sink, frame) } finally { frame.release() }
        } finally {
            source.release()
        }
    }

    /**
     * 방송 카메라를 끄지 않고 얼굴을 등록하도록 가림 처리 전 원본 프레임을 빌려 줍니다.
     * [tap]은 분석 스레드에서 바로 호출되므로 프레임을 오래 붙잡지 말고 반환 전에 복사해야 합니다.
     */
    internal fun setFaceFrameTap(tap: (ImageProxy) -> Unit) {
        if (!stopped.get()) faceFrameTap.set(tap)
    }

    /** 다른 화면이 새로 등록한 탭을 지우지 않도록 [tap]이 현재 탭일 때만 해제합니다. */
    internal fun clearFaceFrameTap(tap: (ImageProxy) -> Unit) {
        faceFrameTap.compareAndSet(tap, null)
    }

    private fun deliverToFaceFrameTap(image: ImageProxy) {
        if (stopped.get()) return
        val tap = faceFrameTap.get() ?: return
        // 얼굴 등록 오류가 송출 프레임 처리로 번지지 않게 합니다.
        try {
            tap(image)
        } catch (error: Exception) {
            Log.w("CameraFrameAnalyzer", "face_frame_tap_failed type=${error.javaClass.simpleName}")
        }
    }

    /** 가림 처리에 실패해 내보내지 않은 누적 프레임 수입니다. 하트비트 `anonymization.failed_frames`입니다. */
    val anonymizationFailedFrames: Long get() = anonymizationFailures.get()

    private fun deliverToDirectPublish(frame: VideoFrame) {
        val sink = directPublishSink ?: return
        // 직접 송출 오류가 미리보기·WebRTC 전달이나 가림 처리 실패 판정으로 번지지 않게 합니다.
        try {
            sink.onFrame(frame)
        } catch (error: Exception) {
            Log.w("CameraFrameAnalyzer", "direct_publish_frame_failed type=${error.javaClass.simpleName}")
        }
    }

    fun resetFaceExceptions() {
        route.invalidate()
        pendingProtectedCamera.clearPending()?.close()
        resetPending.set(true)
    }

    fun start() {
        if (enabled.compareAndSet(false, true)) {
            capturerObserver?.onCapturerStarted(true)
            if (route.ticket()?.mode==PrivacyFrameMode.LOCAL_PROTECTED) schedulePreparation()
        }
    }

    fun stop() {
        route.stop()
        pendingProtectedCamera.clearPending()?.close()
        if (!stopped.compareAndSet(false, true)) return
        if (enabled.compareAndSet(true, false)) {
            capturerObserver?.onCapturerStopped()
        }
        worker.execute {
            synchronized(processorLock) {
                localProcessor?.close()
                localProcessor = null
                cameraBuffers.close()
                recordingBuffers.close()
            }
        }
        worker.shutdown()
    }

    override fun close() {
        synchronized(captureLock) {
            previewSink = null
            directPublishSink = null
            rawRecordingSink = null
            processedRecordingSink = null
            previewRenderer.close()
        }
        faceFrameTap.set(null)
        stop()
    }

    override fun analyze(image: ImageProxy) {
        var ticket: PrivacyFrameRoute.Ticket? = null
        var reserved = false
        var transferredImage = false
        try {
            // 가림 처리 경로가 이미지를 작업 스레드로 넘기기 전에 원본을 빌려 줍니다.
            deliverToFaceFrameTap(image)
            synchronized(captureLock) {
                if (!stopped.get() && (enabled.get() || previewRenderer.isEnabled || previewSink != null)) {
                    val currentSettings = settings.get()
                    previewExposure.exposureForFrame(image.imageInfo.timestamp)?.let { exposureEV ->
                        previewRenderer.offer(image, currentSettings, exposureEV)
                    }
                }
            }
            if (!enabled.get() && previewSink == null) return
            val currentTicket = route.ticket() ?: return
            ticket = currentTicket
            val receivedAtNs=System.nanoTime()
            val crop = image.cropRect
            val format = crop.width() to crop.height()
            if (lastCaptureFormat != format) {
                lastCaptureFormat = format
                onCaptureFormat(format.first, format.second)
            }
            // 가림 처리 경로는 이미지를 그대로 가져가므로 원본 녹화를 먼저 복사합니다. 녹화 실패는 송출에 영향을 주지 않습니다.
            if (rawRecordingSink != null && currentTicket.mode == PrivacyFrameMode.LOCAL_PROTECTED) try {
                deliverRawToRecording(image)
            } catch (error: Exception) {
                Log.w("CameraFrameAnalyzer", "recording_copy_failed type=${error.javaClass.simpleName}")
            }
            if (currentTicket.mode == PrivacyFrameMode.LOCAL_PROTECTED) {
                received.incrementAndGet()
                if (!protectedPrepared) {
                    dropped.incrementAndGet()
                    // 준비 실패 뒤 버리는 프레임도 가림 처리 실패로 알려 직접 송출이 경고를 이어 가게 합니다.
                    if (protectedPreparationFailed) {
                        anonymizationFailures.incrementAndGet()
                        route.deliver(currentTicket, onProcessingFailure)
                    }
                    return
                }
                if(directCameraInput && keepLatestProtectedFrame) {
                    val candidate=PendingProtectedCamera(PrivacyCameraInput(image),currentTicket,receivedAtNs)
                    val offered=pendingProtectedCamera.offer(candidate)
                    transferredImage=true
                    offered.displaced?.let {it.close();dropped.incrementAndGet()}
                    if(offered.start) try {
                        worker.execute {processProtectedCamera(candidate)}
                    } catch(error:Exception) {
                        pendingProtectedCamera.abort()?.close()
                        candidate.close()
                        throw error
                    }
                    return
                }
                if (!processing.compareAndSet(false, true)) {
                    dropped.incrementAndGet()
                    return
                }
                reserved = true
                if(directCameraInput) {
                    val camera=PrivacyCameraInput(image)
                    worker.execute {processProtected(currentTicket,0.0,{it.process(camera)},
                        {camera.close()},receivedAtNs=receivedAtNs)}
                    transferredImage=true
                    reserved=false
                    return
                }
            }
            val copyStarted = System.nanoTime()
            val source = cameraBuffers.acquire(image.width, image.height)
            try {
                copyPlane(image.planes[0], image.width, image.height, source.dataY, source.strideY)
                copyPlane(
                    image.planes[1],
                    (image.width + 1) / 2,
                    (image.height + 1) / 2,
                    source.dataU,
                    source.strideU,
                )
                copyPlane(
                    image.planes[2],
                    (image.width + 1) / 2,
                    (image.height + 1) / 2,
                    source.dataV,
                    source.strideV,
                )

                val output = if (crop.left==0 && crop.top==0 && crop.width()==image.width &&
                    crop.height()==image.height) source.apply {retain()}
                else source.cropAndScale(crop.left,crop.top,crop.width(),crop.height(),crop.width(),crop.height())
                val frame = VideoFrame(
                    output,
                    image.imageInfo.rotationDegrees,
                    image.imageInfo.timestamp,
                )
                if (reserved) {
                    val copyMs = (System.nanoTime() - copyStarted) / 1e6
                    try {
                        worker.execute { processProtected(frame, currentTicket, copyMs,receivedAtNs) }
                        reserved = false // The worker now owns the frame and the in-flight slot.
                    } catch (error: Exception) {
                        frame.release()
                        throw error
                    }
                } else {
                    try {
                        // 색 보정은 새 버퍼에 쓰므로 카메라 복사본을 원본 녹화와 공유해도 됩니다. 추가 복사가 없습니다.
                        if (currentTicket.mode != PrivacyFrameMode.LOCAL_PROTECTED) deliverToRecording(rawRecordingSink, frame)
                        route.deliver(currentTicket) {
                            relayAdjustedFrame(frame) { adjusted ->
                                previewSink?.onFrame(adjusted)
                                capturerObserver?.onFrameCaptured(adjusted)
                                if (currentTicket.mode == PrivacyFrameMode.LOCAL_RAW) {
                                    deliverToDirectPublish(adjusted)
                                    deliverToRecording(processedRecordingSink, adjusted)
                                }
                            }
                        }
                    } finally {
                        frame.release()
                    }
                }
            } finally {
                source.release()
            }
        } catch (error: Throwable) {
            if (!error.isPrivacyProcessingFailure()) throw error
            reportVideoAdjustmentFailure(error)
            if (ticket?.mode == PrivacyFrameMode.LOCAL_PROTECTED) {
                anonymizationFailures.incrementAndGet()
                ticket?.let { route.deliver(it, onProcessingFailure) }
            }
        } finally {
            if (reserved) processing.set(false)
            if(!transferredImage) image.close()
        }
    }

    private fun processProtected(frame: VideoFrame, ticket: PrivacyFrameRoute.Ticket,
        cameraCopyMs: Double,receivedAtNs:Long) {
        processProtected(ticket,cameraCopyMs,{it.process(frame)},{frame.release()},receivedAtNs=receivedAtNs)
    }

    private fun processProtectedCamera(first:PendingProtectedCamera) {
        var current:PendingProtectedCamera?=first
        while(current!=null) {
            val active=current
            if(System.nanoTime()-active.receivedAtNs>100_000_000L) {
                active.close();dropped.incrementAndGet()
                current=pendingProtectedCamera.finish()
                continue
            }
            processProtected(active.ticket,0.0,{it.process(active.camera)},{active.close()},
                releaseSlot=false,receivedAtNs=active.receivedAtNs)
            current=pendingProtectedCamera.finish()
        }
    }

    private fun processProtected(ticket:PrivacyFrameRoute.Ticket,cameraCopyMs:Double,
        transform:(PrivacyFrameProcessor)->VideoFrame,releaseInput:()->Unit,releaseSlot:Boolean=true,
        receivedAtNs:Long=System.nanoTime()) {
        try {
            if(!route.deliver(ticket) {}) return
            // 처리에 실패한 프레임은 원본으로 대신하지 않고 버립니다(iOS PrivacyUplinkProcessor와 같은 정책).
            val outcome = deliverProtectedFrame(
                process = {
                    synchronized(processorLock) {
                        val processor = processor()
                        if (resetPending.getAndSet(false)) processor.resetFaceExceptions()
                        transform(processor)
                    }
                },
                deliver = { outgoing ->
                    val deliveryStarted = System.nanoTime()
                    val wasDelivered = route.deliver(ticket) {
                        relayAdjustedFrame(outgoing) { adjusted ->
                            previewSink?.onFrame(adjusted)
                            capturerObserver?.onFrameCaptured(adjusted)
                            deliverToDirectPublish(adjusted)
                            deliverToRecording(processedRecordingSink, adjusted)
                            delivered.incrementAndGet()
                            if (protectedFrameReported.compareAndSet(false, true)) onProtectedFrameSent()
                        }
                    }
                    reportDelivery(wasDelivered, cameraCopyMs, deliveryStarted, receivedAtNs)
                    wasDelivered
                },
                release = VideoFrame::release,
                onFailure = { error ->
                    if (anonymizationFailures.incrementAndGet() == 1L) {
                        Log.w("PrivacyPipeline", "protected_frame_dropped type=${error.javaClass.simpleName}")
                    }
                    route.deliver(ticket, onProcessingFailure)
                },
            )
            if (outcome == ProtectedFrameOutcome.BUSY) dropped.incrementAndGet()
        } finally {
            releaseInput()
            if(releaseSlot) processing.set(false)
            logCounts()
        }
    }

    private fun reportDelivery(wasDelivered: Boolean, cameraCopyMs: Double, deliveryStarted: Long, receivedAtNs: Long) {
        val completed = System.nanoTime()
        if ((BuildConfig.DEBUG || BuildConfig.PRIVACY_PERF_DIAGNOSTICS) && wasDelivered) {
            localProcessor?.let { processor ->
                val timings = processor.lastTimings
                val analysis = processor.lastAnalysis
                if (timings != null && analysis != null) onFrameDiagnostics?.invoke(
                    PrivacyCaptureDiagnostics(cameraCopyMs, (completed - deliveryStarted) / 1e6,
                        timings, analysis, processor.lastCameraCopiedPlanes,
                        (completed-receivedAtNs)/1e6))
            }
        }
        if (BuildConfig.DEBUG && completed - lastStagesLogNs >= 5_000_000_000L) {
            lastStagesLogNs = completed
            Log.i("PrivacyPipeline", "camera_copy_ms=$cameraCopyMs " +
                "delivery_ms=${(completed - deliveryStarted) / 1e6}")
        }
    }

    private fun relayAdjustedFrame(frame: VideoFrame, deliver: (VideoFrame) -> Unit) {
        relayColorFrame(frame, settings.get(), colorProcessor::process, deliver)
        reportedColorFailure = false
    }

    private fun reportVideoAdjustmentFailure(error: Throwable) {
        if (!reportedColorFailure) {
            reportedColorFailure = true
            Log.w("CameraFrameAnalyzer", "video_adjustment_frame_dropped type=${error.javaClass.simpleName}")
        }
    }

    private fun logCounts() {
        if (!BuildConfig.DEBUG) return
        val now = System.nanoTime()
        if (now - lastLogNs < 5_000_000_000L) return
        lastLogNs = now
        Log.i("PrivacyPipeline", "capture_received=${received.getAndSet(0)} " +
            "busy_dropped=${dropped.getAndSet(0)} delivered=${delivered.getAndSet(0)}")
    }
}

/** 녹화 렌더러가 붙잡는 프레임(최대 1~2개)을 고려한 버퍼 여분입니다. */
private const val RECORDING_BUFFER_SLOTS = 3

private data class PendingProtectedCamera(val camera:PrivacyCameraInput,
    val ticket:PrivacyFrameRoute.Ticket,val receivedAtNs:Long):AutoCloseable {
    override fun close() {camera.close()}
}

internal data class PrivacyCaptureDiagnostics(
    val cameraCopyMs: Double, val deliveryMs: Double,
    val timings: PrivacyFrameTimings, val analysis: PrivacyFrameAnalysis,
    val cameraCopiedPlanes:Int = 3,
    val frameAgeMs:Double = Double.NaN,
)

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    target: ByteBuffer,
    targetStride: Int,
) {
    val source = plane.buffer.duplicate()
    val sourceStart = source.position()

    if (source.isDirect && target.isDirect) {
        PrivacyNativePixels.copyPlane(source.slice(), plane.rowStride, plane.pixelStride,
            width, height, target.duplicate().apply { clear() }, targetStride)
        target.position(0)
        return
    }

    repeat(height) { row ->
        val sourceRow = sourceStart + row * plane.rowStride
        val targetRow = row * targetStride
        if (plane.pixelStride == 1) {
            source.limit(source.capacity())
            source.position(sourceRow)
            source.limit(sourceRow + width)
            target.position(targetRow)
            target.put(source)
        } else {
            repeat(width) { column ->
                target.put(
                    targetRow + column,
                    source.get(sourceRow + column * plane.pixelStride),
                )
            }
        }
    }
    target.position(0)
}
