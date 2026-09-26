package com.framework.innolive.feature.face

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.feature.live.privacy.PrivacyFaceService
import com.framework.innolive.feature.live.privacy.PrivacyRegisteredFace
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun LocalFaceManagementScreen(
    cameraLensFacing: CameraLensFacing,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val service = remember(context) { PrivacyFaceService.get(context) }
    var name by remember { mutableStateOf("") }
    var enrollingName by remember { mutableStateOf<String?>(null) }
    var faces by remember { mutableStateOf<List<PrivacyRegisteredFace>>(emptyList()) }
    var ready by remember { mutableStateOf(service.ready) }
    var failed by remember { mutableStateOf(service.preparationFailed) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(service) {
        service.prepare()
        while (true) {
            ready = service.ready
            failed = service.preparationFailed
            if (service.library == null) message = context.getString(R.string.local_face_store_error)
            faces = try { service.library?.snapshot().orEmpty() } catch (_: Exception) {
                message = context.getString(R.string.local_face_store_error)
                emptyList()
            }
            delay(250)
        }
    }

    enrollingName?.let { selectedName ->
        LocalFaceRegistrationScreen(
            cameraLensFacing = cameraLensFacing,
            name = selectedName,
            service = service,
            onBack = { enrollingName = null },
            onRegistered = {
                name = ""
                enrollingName = null
                onChanged()
            },
        )
        return
    }

    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.local_face_title), color = Color.White,
                style = MaterialTheme.typography.headlineSmall)
            IconButton(onClick = onBack) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }
        }
        Text(stringResource(R.string.local_face_description), color = Color.White)
        if (!ready) {
            if (failed) {
                Text(stringResource(R.string.local_face_model_failed), color = Color.White)
                Button(onClick = { service.retryPreparation() }) { Text(stringResource(R.string.action_retry)) }
            } else {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Text(stringResource(R.string.local_face_model_preparing), color = Color.White)
            }
        }
        LocalFaceNameField(value = name, onValueChange = { name = it.take(40) })
        Button(
            onClick = { enrollingName = name.trim() },
            enabled = ready && service.library != null && name.trim().isNotEmpty() && faces.size < 20,
        ) { Text(stringResource(R.string.local_face_enroll)) }
        message?.let { Text(it, color = Color.White) }
        if (faces.isEmpty()) Text(stringResource(R.string.no_registered_faces), color = Color.White)
        faces.forEach { face ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(face.name, color = Color.White)
                IconButton(onClick = {
                    try {
                        service.library?.delete(face.id)
                        onChanged()
                    } catch (_: Exception) {
                        message = context.getString(R.string.local_face_store_error)
                        onChanged()
                    }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.local_face_delete), tint = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun LocalFaceNameField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.local_face_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = Color.LightGray,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = Color.LightGray,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun LocalFaceRegistrationScreen(
    cameraLensFacing: CameraLensFacing,
    name: String,
    service: PrivacyFaceService,
    onBack: () -> Unit,
    onRegistered: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val detector = remember { FaceDetectionPipeline() }
    var capturing by remember { mutableStateOf(true) }
    var active by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var latest by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf(context.getString(R.string.face_registration_instruction)) }
    var generation by remember { mutableStateOf(0) }

    DisposableEffect(detector) { onDispose { generation++; detector.close() } }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> active = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    active = false
                    generation++
                    capturing = false
                    detector.reset()
                    message = context.getString(R.string.error_face_registration_interrupted)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(generation) {
        delay(30_000)
        if (capturing) {
            generation++
            capturing = false
            detector.reset()
            message = context.getString(R.string.local_face_timeout)
        }
    }
    val close = { generation++; onBack() }
    BackHandler(onBack = close)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val currentGeneration = generation
        FaceCaptureCamera(
            cameraLensFacing = cameraLensFacing,
            enabled = active && capturing,
            onFrame = { bitmap ->
                detector.submit(bitmap) { detectedBitmap, result ->
                    if (currentGeneration != generation || !capturing || !active) return@submit
                    latest = detectedBitmap
                    when (result.status) {
                        FaceStabilityStatus.STABLE -> {
                            capturing = false
                            message = context.getString(R.string.local_face_enrolling)
                            val sample = detectedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            service.enroll(sample) { embedding ->
                                if (currentGeneration != generation || !active) return@enroll
                                try {
                                    checkNotNull(embedding)
                                    checkNotNull(service.library).add(name, embedding)
                                    onRegistered()
                                } catch (_: Exception) {
                                    message = context.getString(R.string.local_face_enroll_failed)
                                }
                            }
                        }
                        FaceStabilityStatus.NO_FACE -> message = context.getString(R.string.error_face_not_detected_camera)
                        FaceStabilityStatus.MULTIPLE_FACES -> message = context.getString(R.string.error_multiple_faces)
                        FaceStabilityStatus.OFF_CENTER -> message = context.getString(R.string.error_face_off_center)
                        FaceStabilityStatus.TOO_SMALL -> message = context.getString(R.string.error_face_too_small)
                        FaceStabilityStatus.DETECTOR_ERROR -> {
                            capturing = false
                            message = context.getString(R.string.error_face_detection)
                        }
                        else -> message = context.getString(R.string.face_hold_still)
                    }
                }
            },
            onSourceTooSmall = {
                scope.launch(Dispatchers.Main.immediate) {
                    capturing = false
                    message = context.getString(R.string.error_face_resolution)
                }
            },
            onCameraError = {
                scope.launch(Dispatchers.Main.immediate) {
                    capturing = false
                    message = context.getString(R.string.error_face_camera)
                }
            },
        )
        Column(modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = close) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
            }
            Text(name, color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(message, color = Color.White)
            latest?.let {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    FaceRegistrationPreviewImage(it, cameraLensFacing)
                }
            }
            if (!capturing) Button(onClick = {
                generation++
                detector.reset()
                latest = null
                capturing = true
                message = context.getString(R.string.face_registration_instruction)
            }) { Text(stringResource(R.string.action_capture_again)) }
        }
    }
}
