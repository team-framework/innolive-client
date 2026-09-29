package com.framework.innolive.feature.live

import android.content.Context
import org.webrtc.PeerConnectionFactory

internal object WebRtcNativeRuntime {
    private var initialized = false

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        initialized = true
    }
}
