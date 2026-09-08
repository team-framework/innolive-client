package com.framework.innolive.feature.face

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.framework.innolive.BuildConfig
import com.framework.innolive.feature.live.CameraLensFacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FaceRegistrationPhase {
    CAPTURING,
    UPLOADING,
    SUCCESS,
    ERROR,
}

@Composable
internal fun FaceRegistrationScreen(
    cameraLensFacing: CameraLensFacing,
    onGetAccessToken: () -> String?,
    onRefreshAccessToken: suspend () -> String,
    onBack: () -> Unit,
    profileEmail: String = "",
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val detector = remember { FaceDetectionPipeline() }
    val apiState = remember { mutableStateOf<ReferenceFaceApi?>(null) }
    val imageStore = remember(context) { ReferenceFaceImageStore(context) }
    val currentGetAccessToken by rememberUpdatedState(onGetAccessToken)
    val currentRefreshAccessToken by rememberUpdatedState(onRefreshAccessToken)
    var phase by remember { mutableStateOf(FaceRegistrationPhase.CAPTURING) }
    var statusMessage by remember { mutableStateOf("얼굴을 화면 중앙에 맞추고 잠시 기다려 주세요.") }
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var operationGeneration by remember { mutableIntStateOf(0) }
    var isLifecycleActive by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var uploadJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(detector) {
        onDispose { detector.close() }
    }
    DisposableEffect(Unit) {
        onDispose { apiState.value?.close() }
    }
    DisposableEffect(lifecycleOwner, detector) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleActive = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> {
                    isLifecycleActive = false
                    operationGeneration += 1
                    detector.reset()
                    uploadJob?.cancel()
                    if (phase == FaceRegistrationPhase.UPLOADING) {
                        phase = FaceRegistrationPhase.ERROR
                        statusMessage = "등록이 중단되었습니다. 다시 촬영해 주세요."
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun stopWithError(message: String) {
        operationGeneration += 1
        detector.reset()
        uploadJob?.cancel()
        phase = FaceRegistrationPhase.ERROR
        statusMessage = message
    }

    fun startUpload(bitmap: Bitmap) {
        if (phase != FaceRegistrationPhase.CAPTURING || !isLifecycleActive) return
        val accessToken = currentGetAccessToken()?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            stopWithError("로그인 정보가 없습니다. 다시 로그인해 주세요.")
            return
        }
        val api = try {
            apiState.value ?: ReferenceFaceApi(BuildConfig.INNOLIVE_SERVER_URL).also {
                apiState.value = it
            }
        } catch (_: Exception) {
            stopWithError("얼굴 등록 서버를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.")
            return
        }
        val uploadGeneration = operationGeneration
        val refreshAccessToken = currentRefreshAccessToken
        phase = FaceRegistrationPhase.UPLOADING
        statusMessage = "얼굴을 등록하는 중입니다."
        uploadJob = scope.launch {
            try {
                val image = withContext(Dispatchers.Default) { bitmap.toJpegBytes() }
                val registration = api.register(image, accessToken, refreshAccessToken)
                val localStorageWarning = if (profileEmail.isBlank()) {
                    null
                } else {
                    try {
                        withContext(Dispatchers.IO) {
                            if (registration.faces.size == 1) {
                                imageStore.save(
                                    accountEmail = profileEmail,
                                    faceId = registration.faces.single().faceId,
                                    jpeg = image,
                                )
                            } else {
                                imageStore.deleteAll(profileEmail)
                            }
                        }
                        null
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        "얼굴 등록은 완료되었지만 사진을 기기에 저장하지 못했습니다."
                    }
                }
                if (uploadGeneration == operationGeneration && isLifecycleActive) {
                    phase = FaceRegistrationPhase.SUCCESS
                    statusMessage = localStorageWarning ?: "얼굴 등록이 완료되었습니다."
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReferenceFaceApiException) {
                if (uploadGeneration == operationGeneration && isLifecycleActive) {
                    phase = FaceRegistrationPhase.ERROR
                    statusMessage = exception.toUserMessage()
                }
            } catch (_: Exception) {
                if (uploadGeneration == operationGeneration && isLifecycleActive) {
                    phase = FaceRegistrationPhase.ERROR
                    statusMessage = "얼굴 등록에 실패했습니다. 다시 시도해 주세요."
                }
            }
        }
    }

    fun retry() {
        operationGeneration += 1
        uploadJob?.cancel()
        detector.reset()
        latestBitmap = null
        phase = FaceRegistrationPhase.CAPTURING
        statusMessage = "얼굴을 화면 중앙에 맞추고 잠시 기다려 주세요."
    }

    fun close() {
        operationGeneration += 1
        detector.reset()
        uploadJob?.cancel()
        onBack()
    }

    BackHandler(onBack = ::close)

    val captureGeneration = operationGeneration
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        FaceCaptureCamera(
            cameraLensFacing = cameraLensFacing,
            enabled = isLifecycleActive && phase == FaceRegistrationPhase.CAPTURING,
            onFrame = { bitmap ->
                val accepted = detector.submit(bitmap) { detectedBitmap, result ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (
                            captureGeneration != operationGeneration ||
                            !isLifecycleActive ||
                            phase != FaceRegistrationPhase.CAPTURING
                        ) {
                            return@launch
                        }
                        latestBitmap = detectedBitmap
                        when (result.status) {
                            FaceStabilityStatus.NO_FACE ->
                                statusMessage = "얼굴을 찾지 못했습니다. 카메라를 바라봐 주세요."

                            FaceStabilityStatus.MULTIPLE_FACES ->
                                statusMessage = "한 사람만 화면에 보여 주세요."

                            FaceStabilityStatus.OFF_CENTER ->
                                statusMessage = "얼굴을 화면 중앙에 맞춰 주세요."

                            FaceStabilityStatus.TOO_SMALL ->
                                statusMessage = "얼굴 전체가 중앙 영역에 충분한 크기로 보이도록 맞춰 주세요."

                            FaceStabilityStatus.MOVING,
                            FaceStabilityStatus.WAITING,
                            -> statusMessage = "얼굴을 중앙에 맞추고 움직이지 마세요."

                            FaceStabilityStatus.STABLE -> startUpload(detectedBitmap)
                            FaceStabilityStatus.DETECTOR_ERROR ->
                                stopWithError("얼굴을 인식하지 못했습니다. 다시 촬영해 주세요.")
                        }
                    }
                }
                if (accepted) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (
                            captureGeneration == operationGeneration &&
                            isLifecycleActive &&
                            phase == FaceRegistrationPhase.CAPTURING
                        ) {
                            latestBitmap = bitmap
                        }
                    }
                }
            },
            onSourceTooSmall = {
                scope.launch(Dispatchers.Main.immediate) {
                    if (
                        captureGeneration == operationGeneration &&
                        phase == FaceRegistrationPhase.CAPTURING &&
                        isLifecycleActive
                    ) {
                        stopWithError("카메라 해상도가 등록 기준(최소 500px)보다 낮습니다.")
                    }
                }
            },
            onCameraError = {
                scope.launch(Dispatchers.Main.immediate) {
                    if (
                        captureGeneration == operationGeneration &&
                        phase == FaceRegistrationPhase.CAPTURING &&
                        isLifecycleActive
                    ) {
                        stopWithError("카메라를 사용할 수 없습니다. 다시 시도해 주세요.")
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
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
                text = "얼굴 등록",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "등록하면 기존에 등록한 얼굴이 교체됩니다.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                latestBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "얼굴 등록 미리보기",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } ?: Text(
                    text = "카메라를 준비하는 중입니다.",
                    color = Color.White,
                )
            }
            Text(
                text = statusMessage,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (phase == FaceRegistrationPhase.UPLOADING) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
            if (phase == FaceRegistrationPhase.ERROR) {
                Button(onClick = ::retry) {
                    Text(text = "다시 촬영")
                }
            }
        }
    }
}

internal fun ReferenceFaceApiException.toUserMessage(): String = when {
    code == "face_not_detected" -> "얼굴을 찾지 못했습니다. 한 사람의 얼굴을 중앙에 맞춰 주세요."
    code == "invalid_image" -> "이미지를 처리하지 못했습니다. 다시 촬영해 주세요."
    code == "reference_rejected" -> "기준 얼굴 등록이 거부되었습니다. 얼굴을 선명하게 맞춰 주세요."
    code == "bad_request" && detailsReason == "ai_disabled" ->
        "얼굴 등록 기능을 사용할 수 없습니다. 관리자에게 문의해 주세요."

    code == "ai_unavailable" || statusCode == 502 ->
        "얼굴 인식 서버를 사용할 수 없습니다. 잠시 후 다시 시도해 주세요."

    statusCode == 401 || code == "authentication_error" ->
        "로그인 정보가 만료되었습니다. 다시 로그인해 주세요."

    else -> "얼굴 등록에 실패했습니다. 다시 시도해 주세요."
}
