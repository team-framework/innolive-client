package com.framework.innolive.feature.youtube

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class YouTubeAccountViewModel : ViewModel() {
    var provider by mutableStateOf<String?>(null)
    var channelId by mutableStateOf<String?>(null)
    var channelTitle by mutableStateOf<String?>(null)
    var reconnectRequired by mutableStateOf(false)
    var status by mutableStateOf("로그인 후 YouTube 계정을 연동할 수 있습니다.")
    var isActionInProgress by mutableStateOf(false)
    var isAuthorizationLaunched by mutableStateOf(false)
    var authorizationOperation by mutableStateOf<Long?>(null)
    internal val operationGeneration = OperationGeneration()
}
