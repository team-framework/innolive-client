package com.framework.innolive.feature.face

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.CameraLensFacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class FaceManagementPhase {
    LOADING,
    READY,
    ERROR,
}

@Composable
internal fun FaceManagementScreen(
    cameraLensFacing: CameraLensFacing,
    onGetAccessToken: () -> String?,
    onRefreshAccessToken: suspend () -> String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val apiState = remember { mutableStateOf<ReferenceFaceApi?>(null) }
    val currentGetAccessToken by rememberUpdatedState(onGetAccessToken)
    val currentRefreshAccessToken by rememberUpdatedState(onRefreshAccessToken)
    var phase by remember { mutableStateOf(FaceManagementPhase.LOADING) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var faceStatus by remember { mutableStateOf<ReferenceFaceStatus?>(null) }
    var openRegistration by remember { mutableStateOf(false) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            requestJob?.cancel()
            apiState.value?.close()
        }
    }

    suspend fun refreshStatus() {
        val accessToken = currentGetAccessToken()?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            phase = FaceManagementPhase.ERROR
            faceStatus = null
            statusMessage = "로그인 정보가 없습니다. 다시 로그인해 주세요."
            return
        }

        phase = FaceManagementPhase.LOADING
        statusMessage = null
        try {
            val api = apiState.value ?: ReferenceFaceApi(BuildConfig.INNOLIVE_SERVER_URL).also {
                apiState.value = it
            }
            faceStatus = api.getStatus(accessToken, currentRefreshAccessToken)
            phase = FaceManagementPhase.READY
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ReferenceFaceApiException) {
            if (faceStatus != null && exception.statusCode == 502) {
                phase = FaceManagementPhase.READY
                statusMessage = exception.toUserMessage()
            } else {
                phase = FaceManagementPhase.ERROR
                faceStatus = null
                statusMessage = exception.toUserMessage()
            }
        } catch (_: Exception) {
            phase = FaceManagementPhase.ERROR
            faceStatus = null
            statusMessage = "얼굴 등록 상태를 확인하지 못했습니다. 다시 시도해 주세요."
        }
    }

    fun launchRequest(request: suspend () -> Unit) {
        requestJob?.cancel()
        requestJob = scope.launch { request() }
    }

    fun deleteFace(faceId: String) {
        launchRequest {
            val accessToken = currentGetAccessToken()?.trim().orEmpty()
            if (accessToken.isEmpty()) {
                phase = FaceManagementPhase.ERROR
                statusMessage = "로그인 정보가 없습니다. 다시 로그인해 주세요."
                return@launchRequest
            }
            phase = FaceManagementPhase.LOADING
            statusMessage = "얼굴을 삭제하는 중입니다."
            try {
                val api = apiState.value ?: ReferenceFaceApi(BuildConfig.INNOLIVE_SERVER_URL).also {
                    apiState.value = it
                }
                api.deleteFace(faceId, accessToken, currentRefreshAccessToken)
                refreshStatus()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReferenceFaceApiException) {
                if (exception.statusCode == 404) {
                    statusMessage = "얼굴 목록이 변경되었습니다. 목록을 새로 고침했습니다."
                    refreshStatus()
                } else if (exception.statusCode == 502 && faceStatus != null) {
                    phase = FaceManagementPhase.READY
                    statusMessage = exception.toUserMessage()
                } else {
                    phase = FaceManagementPhase.ERROR
                    statusMessage = exception.toUserMessage()
                }
            } catch (_: Exception) {
                phase = FaceManagementPhase.ERROR
                statusMessage = "얼굴을 삭제하지 못했습니다. 다시 시도해 주세요."
            }
        }
    }

    fun deleteAllFaces() {
        launchRequest {
            val accessToken = currentGetAccessToken()?.trim().orEmpty()
            if (accessToken.isEmpty()) {
                phase = FaceManagementPhase.ERROR
                statusMessage = "로그인 정보가 없습니다. 다시 로그인해 주세요."
                return@launchRequest
            }
            phase = FaceManagementPhase.LOADING
            statusMessage = "등록된 얼굴을 삭제하는 중입니다."
            try {
                val api = apiState.value ?: ReferenceFaceApi(BuildConfig.INNOLIVE_SERVER_URL).also {
                    apiState.value = it
                }
                api.deleteAll(accessToken, currentRefreshAccessToken)
                refreshStatus()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReferenceFaceApiException) {
                if (exception.statusCode == 404) {
                    statusMessage = "얼굴 목록이 변경되었습니다. 목록을 새로 고침했습니다."
                    refreshStatus()
                } else if (exception.statusCode == 502 && faceStatus != null) {
                    phase = FaceManagementPhase.READY
                    statusMessage = exception.toUserMessage()
                } else {
                    phase = FaceManagementPhase.ERROR
                    statusMessage = exception.toUserMessage()
                }
            } catch (_: Exception) {
                phase = FaceManagementPhase.ERROR
                statusMessage = "얼굴을 삭제하지 못했습니다. 다시 시도해 주세요."
            }
        }
    }

    LaunchedEffect(openRegistration) {
        if (!openRegistration) refreshStatus()
    }

    if (openRegistration) {
        FaceRegistrationScreen(
            cameraLensFacing = cameraLensFacing,
            onGetAccessToken = currentGetAccessToken,
            onRefreshAccessToken = currentRefreshAccessToken,
            onBack = { openRegistration = false },
        )
        return
    }

    fun close() {
        requestJob?.cancel()
        onBack()
    }

    BackHandler(onBack = ::close)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = ::close) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "닫기",
                    tint = Color.White,
                )
            }
        }
        Text(
            text = "얼굴 관리",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "등록된 얼굴을 확인하고 관리할 수 있습니다.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )

        when (phase) {
            FaceManagementPhase.LOADING -> CircularProgressIndicator()
            FaceManagementPhase.READY -> {
                val status = faceStatus
                if (status?.registered == true && status.faces.isNotEmpty()) {
                    Text(
                        text = "등록된 얼굴 ${status.count ?: status.faces.size}개",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    status.faces.forEach { face ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = face.registeredAt ?: "등록된 얼굴",
                                color = Color.White,
                            )
                            Button(
                                onClick = { deleteFace(face.faceId) },
                            ) {
                                Text(text = "삭제")
                            }
                        }
                    }
                    Button(onClick = ::deleteAllFaces) {
                        Text(text = "전체 삭제")
                    }
                } else {
                    Text(
                        text = "등록된 얼굴이 없습니다.",
                        color = Color.White,
                    )
                }
            }

            FaceManagementPhase.ERROR -> {
                statusMessage?.let { message ->
                    Text(text = message, color = Color.White)
                }
            }
        }

        statusMessage?.let { message ->
            if (phase == FaceManagementPhase.READY) {
                Text(text = message, color = Color.White)
            }
        }
        Button(
            onClick = { openRegistration = true },
            enabled = phase != FaceManagementPhase.LOADING,
        ) {
            Text(text = "얼굴 등록")
        }
        Button(
            onClick = { launchRequest { refreshStatus() } },
            enabled = phase != FaceManagementPhase.LOADING,
        ) {
            Text(text = "새로 고침")
        }
    }
}
