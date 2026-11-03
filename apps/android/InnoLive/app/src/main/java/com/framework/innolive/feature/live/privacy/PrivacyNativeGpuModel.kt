package com.framework.innolive.feature.live.privacy

/** 공개 LiteRT C API 브리지입니다. 호출은 이미지 그래프의 EGL 컨텍스트가 현재일 때만 실행합니다. */
internal object PrivacyNativeGpuModel {
    init { System.loadLibrary("innolive_privacy") }
    external fun create(path: String): Long
    external fun createVariant(path:String,glOutputs:Boolean):Long
    external fun predict(handle: Long, texture: Int, useFence:Boolean=true): Array<FloatArray>
    external fun predictInto(handle: Long, texture: Int, predictions: java.nio.ByteBuffer,
                             prototypes: java.nio.ByteBuffer, useFence: Boolean = true)
    external fun usesManagedInputSync(handle:Long):Boolean
    /** 각 행에는 선택된 타입과 그 뒤로 지원하는 출력 버퍼 타입이 들어 있습니다. */
    external fun outputBufferTypes(handle:Long):Array<IntArray>
    external fun destroy(handle: Long)
}
