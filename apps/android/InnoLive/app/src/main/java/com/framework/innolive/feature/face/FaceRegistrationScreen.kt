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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FaceRegistrationPhase {
    CAPTURING,
    PREPARING,
    READY_TO_SUBMIT,
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
    onRegistrationSuccess: () -> Unit = {},
    profileEmail: String = "",
    existingFaces: List<ReferenceFace> = emptyList(),
) {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val detector = remember { FaceDetectionPipeline() }
    val repository = remember(context) { ReferenceFaceRepository(context) }
    val currentGetAccessToken by rememberUpdatedState(onGetAccessToken)
    val currentRefreshAccessToken by rememberUpdatedState(onRefreshAccessToken)
    val currentOnRegistrationSuccess by rememberUpdatedState(onRegistrationSuccess)
    var phase by remember { mutableStateOf(FaceRegistrationPhase.CAPTURING) }
    var statusMessage by remember {
        mutableStateOf<UiText>(UiText.Resource(R.string.face_registration_instruction))
    }
    var latestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var capturedImages by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var operationGeneration by remember { mutableIntStateOf(0) }
    var isLifecycleActive by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var uploadJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(detector) {
        onDispose { detector.close() }
    }
    DisposableEffect(Unit) {
        onDispose { repository.close() }
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
                    if (
                        phase == FaceRegistrationPhase.UPLOADING ||
                        phase == FaceRegistrationPhase.PREPARING
                    ) {
                        phase = FaceRegistrationPhase.ERROR
                        statusMessage = UiText.Resource(R.string.error_face_registration_interrupted)
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun stopWithError(message: UiText) {
        operationGeneration += 1
        detector.reset()
        uploadJob?.cancel()
        phase = FaceRegistrationPhase.ERROR
        statusMessage = message
    }

    fun captureFace(bitmap: Bitmap) {
        if (phase != FaceRegistrationPhase.CAPTURING || !isLifecycleActive) return
        phase = FaceRegistrationPhase.PREPARING
        detector.reset()
        statusMessage = UiText.Resource(R.string.face_capture_preparing)
        uploadJob?.cancel()
        uploadJob = scope.launch {
            try {
                val image = withContext(Dispatchers.Default) { bitmap.toJpegBytes() }
                capturedImages = capturedImages + image
                if (isLifecycleActive) {
                    phase = FaceRegistrationPhase.READY_TO_SUBMIT
                    statusMessage = UiText.Plural(
                        R.plurals.face_capture_ready_count,
                        capturedImages.size,
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                capturedImages = emptyList()
                phase = FaceRegistrationPhase.ERROR
                statusMessage = UiText.Resource(R.string.error_face_image_processing)
            }
        }
    }

    fun startUpload() {
        if (phase != FaceRegistrationPhase.READY_TO_SUBMIT || !isLifecycleActive) return
        val images = capturedImages
        if (images.isEmpty()) return
        val accessToken = currentGetAccessToken()?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            stopWithError(UiText.Resource(R.string.error_face_login_required))
            return
        }
        try {
            repository.ensureApiAvailable()
        } catch (_: Exception) {
            stopWithError(UiText.Resource(R.string.error_face_server_unavailable))
            return
        }
        val uploadGeneration = operationGeneration
        val refreshAccessToken = currentRefreshAccessToken
        phase = FaceRegistrationPhase.UPLOADING
        statusMessage = UiText.Resource(R.string.face_registering)
        uploadJob = scope.launch {
            try {
                val result = repository.append(
                    images = images,
                    existingFaces = existingFaces,
                    accountEmail = profileEmail,
                    accessToken = accessToken,
                    refreshAccessToken = refreshAccessToken,
                )
                if (uploadGeneration == operationGeneration && isLifecycleActive) {
                    phase = FaceRegistrationPhase.SUCCESS
                    statusMessage = if (result.localImageStoreFailed) {
                        UiText.Resource(R.string.face_registered_local_photo_failed)
                    } else {
                        UiText.Resource(R.string.face_registration_complete)
                    }
                    currentOnRegistrationSuccess()
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
                    statusMessage = UiText.Resource(R.string.error_face_registration)
                }
            }
        }
    }

    fun retry() {
        operationGeneration += 1
        uploadJob?.cancel()
        detector.reset()
        latestBitmap = null
        capturedImages = emptyList()
        phase = FaceRegistrationPhase.CAPTURING
        statusMessage = UiText.Resource(R.string.face_registration_instruction)
    }

    fun captureAnother() {
        if (capturedImages.size >= 20) {
            statusMessage = UiText.Resource(R.string.error_face_maximum)
            return
        }
        detector.reset()
        latestBitmap = null
        phase = FaceRegistrationPhase.CAPTURING
        statusMessage = UiText.Resource(R.string.face_registration_instruction)
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
                                statusMessage = UiText.Resource(
                                    R.string.error_face_not_detected_camera,
                                )

                            FaceStabilityStatus.MULTIPLE_FACES ->
                                statusMessage = UiText.Resource(R.string.error_multiple_faces)

                            FaceStabilityStatus.OFF_CENTER ->
                                statusMessage = UiText.Resource(R.string.error_face_off_center)

                            FaceStabilityStatus.TOO_SMALL ->
                                statusMessage = UiText.Resource(R.string.error_face_too_small)

                            FaceStabilityStatus.MOVING,
                            FaceStabilityStatus.WAITING,
                            -> statusMessage = UiText.Resource(R.string.face_hold_still)

                            FaceStabilityStatus.STABLE -> captureFace(detectedBitmap)
                            FaceStabilityStatus.DETECTOR_ERROR ->
                                stopWithError(UiText.Resource(R.string.error_face_detection))
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
                        stopWithError(UiText.Resource(R.string.error_face_resolution))
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
                        stopWithError(UiText.Resource(R.string.error_face_camera))
                    }
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = ::close) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = Color.White,
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.action_register_face),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.face_registration_description),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
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
                        contentDescription = stringResource(
                            R.string.content_description_face_registration_preview,
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } ?: Text(
                    text = stringResource(R.string.face_camera_preparing),
                    color = Color.White,
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = statusMessage.asString(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (phase == FaceRegistrationPhase.UPLOADING) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
                if (phase == FaceRegistrationPhase.PREPARING) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
                if (phase == FaceRegistrationPhase.READY_TO_SUBMIT) {
                    Text(
                        text = UiText.Plural(
                            R.plurals.prepared_face_count,
                            capturedImages.size,
                        ).asString(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = ::captureAnother,
                            modifier = Modifier.fillMaxWidth(0.5f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.background,
                                contentColor = MaterialTheme.colorScheme.onBackground
                            )
                            ) {
                            Text(text = stringResource(R.string.action_capture_another_face))
                        }
                        Button(
                            onClick = ::startUpload,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black
                            )
                        ) {
                            Text(text = stringResource(R.string.action_submit_face))
                        }
                    }
                }
            }
            if (phase == FaceRegistrationPhase.ERROR) {
                Button(onClick = ::retry) {
                    Text(text = stringResource(R.string.action_capture_again))
                }
            }
        }
    }
}

internal fun ReferenceFaceApiException.toUserMessage(): UiText = when {
    code == "face_not_detected" -> UiText.Resource(R.string.error_face_not_detected)
    code == "invalid_image" -> UiText.Resource(R.string.error_face_image_processing)
    code == "reference_rejected" -> UiText.Resource(R.string.error_face_rejected)
    code == "bad_request" && detailsReason == "ai_disabled" ->
        UiText.Resource(R.string.error_face_feature_unavailable)

    code == "ai_unavailable" || statusCode == 502 ->
        UiText.Resource(R.string.error_face_server_unavailable)

    statusCode == 401 || code == "authentication_error" ->
        UiText.Resource(R.string.error_face_session_expired)

    else -> UiText.Resource(R.string.error_face_registration)
}
