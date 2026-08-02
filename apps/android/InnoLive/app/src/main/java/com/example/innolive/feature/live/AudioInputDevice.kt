package com.example.innolive.feature.live

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val isDefault: Boolean,
) {
    val displayName: String
        get() = if (isDefault) "내장 마이크" else name
}
