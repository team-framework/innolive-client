package com.framework.innolive.feature.live.privacy

import android.os.Handler
import android.os.HandlerThread
import org.webrtc.EglBase
import org.webrtc.ThreadUtils
import org.webrtc.YuvConverter
import java.util.concurrent.atomic.AtomicBoolean

/** CPU encoders must not queue texture conversion behind the next AI inference. */
internal class PrivacyTextureReadback(sharedContext: EglBase.Context) : AutoCloseable {
    private val thread = HandlerThread("privacy-texture-readback").apply { start() }
    val handler = Handler(thread.looper)
    private lateinit var egl: EglBase
    lateinit var converter: YuvConverter
        private set
    private val closed = AtomicBoolean(false)

    init {
        try {
            ThreadUtils.invokeAtFrontUninterruptibly(handler) {
                egl = EglBase.create(sharedContext, EglBase.CONFIG_PIXEL_BUFFER)
                egl.createDummyPbufferSurface()
                egl.makeCurrent()
                converter = YuvConverter()
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    // Called only after all output texture references have been released.
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ThreadUtils.invokeAtFrontUninterruptibly(handler) {
            if (::converter.isInitialized) converter.release()
            if (::egl.isInitialized) egl.release()
        }
        thread.quitSafely()
    }
}
