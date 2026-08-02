package com.example.innolive.feature.live

enum class CameraResolution(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val displayName: String,
) {
    FULL_HD_30(1920, 1080, 30, "1080p - 30fps"),
    FULL_HD_24(1920, 1080, 24, "1080p - 24fps"),
    HD_30(1280, 720, 30, "720p - 30fps"),
    HD_24(1280, 720, 24, "720p - 24fps"),
}
