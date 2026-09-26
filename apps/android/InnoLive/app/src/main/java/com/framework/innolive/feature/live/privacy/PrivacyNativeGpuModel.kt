package com.framework.innolive.feature.live.privacy

/** Public LiteRT C API bridge. Calls run only with the image graph's current EGL context. */
internal object PrivacyNativeGpuModel {
    init { System.loadLibrary("innolive_privacy") }
    external fun create(path: String): Long
    external fun predict(handle: Long, texture: Int): Array<FloatArray>
    external fun destroy(handle: Long)
}
