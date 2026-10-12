package com.framework.innolive.feature.live.direct.publish

import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 가림 처리된 [VideoFrame]을 인코더 입력 Surface에 그립니다.
 *
 * 텍스처 프레임을 그리려고 가림 처리 파이프라인과 같은 EGL 공유 컨텍스트를 씁니다. 출력 텍스처는
 * 동시에 빌릴 수 있는 수가 정해져 있어 그리기를 기다리는 프레임은 최신 한 장만 들고 있습니다.
 */
internal class EncoderSurfaceRenderer(
    sharedContext: EglBase.Context,
    surface: Surface,
    private val width: Int,
    private val height: Int,
    private val onRendered: () -> Unit,
    private val onDropped: () -> Unit,
    private val onError: (Exception) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("direct-publish-render").apply { start() }
    private val handler = Handler(thread.looper)
    private val pending = AtomicReference<Pair<VideoFrame, Long>?>(null)
    private var eglBase: EglBase? = null
    private var drawer: GlRectDrawer? = null
    private var frameDrawer: VideoFrameDrawer? = null
    @Volatile private var closed = false

    init {
        val ready = CountDownLatch(1)
        var failure: Exception? = null
        handler.post {
            try {
                eglBase = EglBase.create(sharedContext, EglBase.CONFIG_RECORDABLE).apply {
                    createSurface(surface)
                    makeCurrent()
                }
                drawer = GlRectDrawer()
                frameDrawer = VideoFrameDrawer()
            } catch (error: Exception) {
                failure = error
            } finally {
                ready.countDown()
            }
        }
        check(ready.await(5, TimeUnit.SECONDS)) { "인코더 렌더러를 준비하지 못했습니다." }
        failure?.let { close(); throw it }
    }

    /** 가림 처리 스레드에서 부릅니다. [presentationTimeNs]는 단조 시계 기준입니다. */
    fun render(frame: VideoFrame, presentationTimeNs: Long) {
        if (closed) return
        frame.retain()
        pending.getAndSet(frame to presentationTimeNs)?.let { (stale, _) ->
            stale.release()
            onDropped()
        }
        handler.post(::drawPending)
    }

    private fun drawPending() {
        val (frame, presentationTimeNs) = pending.getAndSet(null) ?: return
        try {
            if (closed) return
            val egl = checkNotNull(eglBase)
            egl.makeCurrent()
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val viewport = fitViewport(frame.rotatedWidth, frame.rotatedHeight, width, height)
            checkNotNull(frameDrawer).drawFrame(frame, checkNotNull(drawer), null,
                viewport.x, viewport.y, viewport.width, viewport.height)
            egl.swapBuffers(presentationTimeNs)
            onRendered()
        } catch (error: Exception) {
            onError(error)
        } finally {
            frame.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pending.getAndSet(null)?.first?.release()
        val done = CountDownLatch(1)
        handler.post {
            runCatching { frameDrawer?.release() }
            runCatching { drawer?.release() }
            runCatching { eglBase?.release() }
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }
}
