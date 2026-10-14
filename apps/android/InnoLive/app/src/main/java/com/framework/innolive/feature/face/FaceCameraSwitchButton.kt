package com.framework.innolive.feature.face

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraLensFacing

/** 얼굴 등록 화면에서 전·후방 카메라를 바꾸는 버튼. 라이브 화면의 카메라 전환 버튼과 같은 모양을 쓴다. */
@Composable
internal fun FaceCameraSwitchButton(
    enabled: Boolean,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val packageManager = LocalContext.current.packageManager
    val canSwitchCamera = remember(packageManager) {
        CameraLensFacing.supported(
            hasBackCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA),
            hasFrontCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT),
        ).size > 1
    }
    if (!canSwitchCamera) return

    IconButton(
        onClick = onSwitchCamera,
        enabled = enabled,
        modifier = modifier.size(48.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.change_camera),
            contentDescription = stringResource(R.string.content_description_switch_camera),
            modifier = Modifier.size(28.dp),
            tint = Color.White,
        )
    }
}

internal fun CameraLensFacing.toggled(): CameraLensFacing = when (this) {
    CameraLensFacing.BACK -> CameraLensFacing.FRONT
    CameraLensFacing.FRONT -> CameraLensFacing.BACK
}
