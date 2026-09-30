package com.framework.innolive.feature.live.privacy

internal object PrivacyNativeGpuFence {
    init { System.loadLibrary("innolive_privacy") }
    external fun create(): Long
    external fun awaitReady(handle: Long)
    external fun destroy(handle: Long)
}
