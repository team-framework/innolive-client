package com.example.innolive.feature.live

enum class CameraLensFacing(
    val displayName: String,
) {
    BACK("후면 카메라 (기본 기기)"),
    FRONT("전면 카메라 (기본 기기)"),
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
