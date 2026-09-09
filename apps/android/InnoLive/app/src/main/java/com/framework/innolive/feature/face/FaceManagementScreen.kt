package com.framework.innolive.feature.face

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.CameraLensFacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    profileEmail: String = "",
    apiFactory: () -> ReferenceFaceApi = { ReferenceFaceApi(BuildConfig.INNOLIVE_SERVER_URL) },
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val apiState = remember { mutableStateOf<ReferenceFaceApi?>(null) }
    val imageStore = remember(context) { ReferenceFaceImageStore(context) }
    val currentGetAccessToken by rememberUpdatedState(onGetAccessToken)
    val currentRefreshAccessToken by rememberUpdatedState(onRefreshAccessToken)
    val currentApiFactory by rememberUpdatedState(apiFactory)
    var phase by remember(profileEmail) { mutableStateOf(FaceManagementPhase.LOADING) }
    var statusMessage by remember(profileEmail) { mutableStateOf<String?>(null) }
    var faceStatus by remember(profileEmail) { mutableStateOf<ReferenceFaceStatus?>(null) }
    var faceImages by remember(profileEmail) { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
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
            val api = apiState.value ?: currentApiFactory().also {
                apiState.value = it
            }
            val status = api.getStatus(accessToken, currentRefreshAccessToken)
            faceStatus = status
            faceImages = if (profileEmail.isBlank()) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    status.faces.mapNotNull { face ->
                        imageStore.load(profileEmail, face.faceId)?.let { face.faceId to it }
                    }.toMap()
                }
            }
            phase = FaceManagementPhase.READY
        } catch (exception: CancellationException) {
            if (faceStatus != null) {
                phase = FaceManagementPhase.READY
            } else {
                phase = FaceManagementPhase.ERROR
                statusMessage = "얼굴 등록 상태 확인이 취소되었습니다. 다시 시도해 주세요."
            }
            throw exception
        } catch (exception: ReferenceFaceApiException) {
            if (faceStatus != null && exception.statusCode == 502) {
                phase = FaceManagementPhase.READY
                statusMessage = exception.toUserMessage()
            } else {
                phase = FaceManagementPhase.ERROR
                faceStatus = null
                faceImages = emptyMap()
                statusMessage = exception.toUserMessage()
            }
        } catch (_: Exception) {
            phase = FaceManagementPhase.ERROR
            faceStatus = null
            faceImages = emptyMap()
            statusMessage = "얼굴 등록 상태를 확인하지 못했습니다. 다시 시도해 주세요."
        }
    }

    fun launchRequest(request: suspend () -> Unit) {
        requestJob?.cancel()
        requestJob = scope.launch { request() }
    }

    suspend fun deleteLocalFace(faceId: String): Boolean = try {
        withContext(Dispatchers.IO) { imageStore.delete(profileEmail, faceId) }
        false
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        true
    }

    suspend fun deleteLocalFaces(): Boolean = try {
        withContext(Dispatchers.IO) { imageStore.deleteAll(profileEmail) }
        false
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        true
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
                val api = apiState.value ?: currentApiFactory().also {
                    apiState.value = it
                }
                api.deleteFace(faceId, accessToken, currentRefreshAccessToken)
                val localDeleteFailed = deleteLocalFace(faceId)
                faceStatus = faceStatus?.let { status ->
                    val remainingFaces = status.faces.filterNot { face -> face.faceId == faceId }
                    status.copy(
                        registered = remainingFaces.isNotEmpty(),
                        count = remainingFaces.size,
                        faces = remainingFaces,
                    )
                }
                faceImages = faceImages - faceId
                refreshStatus()
                if (localDeleteFailed && phase == FaceManagementPhase.READY) {
                    statusMessage = "얼굴은 삭제했지만 저장된 사진을 제거하지 못했습니다."
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReferenceFaceApiException) {
                if (exception.statusCode == 404) {
                    statusMessage = "얼굴 목록이 변경되었습니다. 목록을 새로 고침했습니다."
                    deleteLocalFace(faceId)
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
                val api = apiState.value ?: currentApiFactory().also {
                    apiState.value = it
                }
                api.deleteAll(accessToken, currentRefreshAccessToken)
                val localDeleteFailed = deleteLocalFaces()
                faceStatus = faceStatus?.copy(
                    registered = false,
                    count = 0,
                    faces = emptyList(),
                )
                faceImages = emptyMap()
                refreshStatus()
                if (localDeleteFailed && phase == FaceManagementPhase.READY) {
                    statusMessage = "얼굴은 삭제했지만 저장된 사진을 제거하지 못했습니다."
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReferenceFaceApiException) {
                if (exception.statusCode == 404) {
                    statusMessage = "얼굴 목록이 변경되었습니다. 목록을 새로 고침했습니다."
                    deleteLocalFaces()
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

    LaunchedEffect(openRegistration, profileEmail) {
        if (!openRegistration) refreshStatus()
    }

    if (openRegistration) {
        FaceRegistrationScreen(
            cameraLensFacing = cameraLensFacing,
            onGetAccessToken = currentGetAccessToken,
            onRefreshAccessToken = currentRefreshAccessToken,
            onBack = { openRegistration = false },
            profileEmail = profileEmail,
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
                            Box(
                                modifier = Modifier.size(96.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                faceImages[face.faceId]?.let { bitmap ->
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "등록된 얼굴",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } ?: Text(text = "사진 없음", color = Color.White)
                            }
                            Text(
                                text = face.registeredAt ?: "등록된 얼굴",
                                color = Color.White,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
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
