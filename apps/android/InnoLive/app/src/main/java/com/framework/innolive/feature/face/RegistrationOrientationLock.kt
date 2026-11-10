package com.framework.innolive.feature.face

import android.content.pm.ActivityInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/**
 * 촬영한 얼굴 사진은 화면 회전으로 Activity가 다시 만들어지면 사라지므로, 촬영을 시작한 뒤에는 화면 방향을 고정합니다.
 * 방송처럼 다른 곳에서 이미 방향을 정했으면 그 값을 건드리지 않습니다.
 */
internal object RegistrationOrientationLock {
    /** 지금 요청된 방향에서 고정할 방향입니다. 고정하지 않으면 null입니다. */
    fun lockFrom(requestedOrientation: Int): Int? =
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            null
        }

    /** 고정을 풀 때 돌려놓을 방향입니다. 그사이 다른 곳에서 방향을 바꿨으면 null입니다. */
    fun unlockFrom(requestedOrientation: Int): Int? =
        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            null
        }
}

/** [enabled]인 동안 지금 화면 방향을 고정합니다. */
@Composable
internal fun LockScreenOrientationWhile(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, enabled) {
        val locked = if (enabled) RegistrationOrientationLock.lockFrom(activity.requestedOrientation) else null
        if (locked != null) activity.requestedOrientation = locked
        onDispose {
            if (locked == null) return@onDispose
            RegistrationOrientationLock.unlockFrom(activity.requestedOrientation)?.let {
                activity.requestedOrientation = it
            }
        }
    }
}
