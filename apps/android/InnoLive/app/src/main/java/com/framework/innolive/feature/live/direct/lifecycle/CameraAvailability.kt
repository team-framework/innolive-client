package com.framework.innolive.feature.live.direct.lifecycle

import androidx.camera.core.CameraState
import androidx.lifecycle.Observer
import com.framework.innolive.feature.live.direct.broadcast.DirectMediaControl
import com.framework.innolive.feature.live.direct.broadcast.SlateReason

/**
 * CameraX 카메라 상태로 카메라를 쓸 수 없는지 판단합니다.
 *
 * 카메라가 열리면 false, 오류가 붙은 상태(다른 앱이 가져감, 정책상 비활성, 치명 오류 등)면 true입니다.
 * 백그라운드 진입처럼 오류 없이 닫히는 경우는 판단하지 않고 null을 돌려줍니다. 그 경우는
 * [SlateReason.BACKGROUND]가 맡습니다.
 */
internal fun cameraUnavailable(type: CameraState.Type, hasError: Boolean): Boolean? = when {
    type == CameraState.Type.OPEN -> false
    hasError -> true
    else -> null
}

/**
 * 카메라 상태를 [SlateReason.CAMERA_UNAVAILABLE] 대기 화면으로 바꿉니다.
 * `camera.cameraInfo.cameraState.observe(owner, observer)`로 붙이고, 송출이 끝나면 [reset]합니다.
 */
internal class CameraAvailabilitySlateObserver(
    private val control: DirectMediaControl,
) : Observer<CameraState> {
    private var unavailable = false

    override fun onChanged(value: CameraState) {
        update(cameraUnavailable(value.type, value.error != null) ?: return)
    }

    fun reset() = update(false)

    private fun update(value: Boolean) {
        if (unavailable == value) return
        unavailable = value
        control.setSlate(SlateReason.CAMERA_UNAVAILABLE, value)
    }
}
