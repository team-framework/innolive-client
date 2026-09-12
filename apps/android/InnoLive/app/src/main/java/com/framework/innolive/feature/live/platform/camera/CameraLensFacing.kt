package com.framework.innolive.feature.live

enum class CameraLensFacing(
    val displayName: String,
) {
    BACK("후면 카메라"),
    FRONT("전면 카메라"),
    ;

    companion object {
        fun supported(
            hasBackCamera: Boolean,
            hasFrontCamera: Boolean,
        ): List<CameraLensFacing> = entries.filter { facing ->
            when (facing) {
                BACK -> hasBackCamera
                FRONT -> hasFrontCamera
            }
        }
    }
}
