package com.example.innolive.feature.live

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
) {
    val displayName: String
        get() = if (isDefault) "$name (기본 기기)" else name
}
