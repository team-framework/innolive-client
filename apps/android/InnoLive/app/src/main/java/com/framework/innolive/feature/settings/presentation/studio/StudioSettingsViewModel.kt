package com.framework.innolive.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.CameraResolution

class StudioSettingsViewModel : ViewModel() {
    var selectedResolutionKey by mutableStateOf<String?>(null)
    var selectedCameraLensFacing by mutableStateOf(CameraLensFacing.BACK)
    var supportedCameraResolutions by mutableStateOf(emptyList<CameraResolution>())
    var selectedAudioDeviceId by mutableIntStateOf(-1)
    var selectedBroadcastPlatform by mutableStateOf("YouTube")
    var broadcastTitle by mutableStateOf("")
    var broadcastDescription by mutableStateOf("")
    var broadcastPrivacy by mutableStateOf("private")
    var broadcastAudience by mutableStateOf("unset")
    var broadcastCategoryId by mutableStateOf("")
}
