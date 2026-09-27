package com.framework.innolive.feature.live.privacy

/** Public LiteRT C API bridge. Calls run only with the image graph's current EGL context. */
internal object PrivacyNativeGpuModel {
    init { System.loadLibrary("innolive_privacy") }
    external fun create(path: String): Long
    external fun createVariant(path:String,glOutputs:Boolean):Long
    external fun predict(handle: Long, texture: Int, useFence:Boolean=true): Array<FloatArray>
    external fun predictInto(handle: Long, texture: Int, predictions: java.nio.ByteBuffer,
                             prototypes: java.nio.ByteBuffer, useFence: Boolean = true)
    external fun usesManagedInputSync(handle:Long):Boolean
    /** Each row contains the selected type, followed by supported output buffer types. */
    external fun outputBufferTypes(handle:Long):Array<IntArray>
    external fun destroy(handle: Long)
}
