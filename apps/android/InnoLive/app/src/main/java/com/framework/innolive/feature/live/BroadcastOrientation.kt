package com.framework.innolive.feature.live

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.Surface

internal fun screenOrientationFor(rotation: Int, configurationOrientation: Int): Int {
    val naturalPortrait = when (rotation) {
        Surface.ROTATION_0, Surface.ROTATION_180 ->
            configurationOrientation == Configuration.ORIENTATION_PORTRAIT
        else -> configurationOrientation == Configuration.ORIENTATION_LANDSCAPE
    }
    return if (naturalPortrait) {
        when (rotation) {
            Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        }
    } else {
        when (rotation) {
            Surface.ROTATION_0 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

/** goLive가 수락될 때 잡은 회전을 해당 방송이 끝날 때까지 유지합니다. */
internal fun nextBroadcastRotation(
    lockedRotation: Int?,
    state: BroadcastState,
): Int? = when (state) {
    BroadcastState.IDLE,
    BroadcastState.PREPARED,
    BroadcastState.FAILED -> null
    else -> lockedRotation
}
