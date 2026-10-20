package com.framework.innolive.feature.face

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString
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
    profileEmail: String = "",
    broadcastCamera: CameraFrameAnalyzer? = null,
    repositoryFactory: (Context) -> ReferenceFaceRepository = { context ->
        ReferenceFaceRepository(context)
    },
) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val repository = remember(context) { repositoryFactory(context) }
    val currentGetAccessToken by rememberUpdatedState(onGetAccessToken)
    val currentRefreshAccessToken by rememberUpdatedState(onRefreshAccessToken)
    var phase by remember(profileEmail) { mutableStateOf(FaceManagementPhase.LOADING) }
    var statusMessage by remember(profileEmail) { mutableStateOf<UiText?>(null) }
    var faceStatus by remember(profileEmail) { mutableStateOf<ReferenceFaceStatus?>(null) }
    var faceImages by remember(profileEmail) { mutableStateOf<Map<String, Bitmap>>(emptyMap()) }
    var openRegistration by remember { mutableStateOf(false) }
    // 다시 등록할 얼굴입니다. 있으면 등록 화면을 이 얼굴의 다시 등록으로 엽니다.
    var reregisterTarget by remember { mutableStateOf<ReferenceFace?>(null) }
    // 등록 화면을 닫고 목록을 새로 읽은 뒤 보여 줄 안내입니다. 새로 읽기가 안내를 지우므로 따로 둡니다.
    var pendingStatusMessage by remember { mutableStateOf<UiText?>(null) }
    var requestJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            requestJob?.cancel()
            repository.close()
        }
    }

    suspend fun refreshStatus() {
        val accessToken = currentGetAccessToken()?.trim().orEmpty()
        if (accessToken.isEmpty()) {
            phase = FaceManagementPhase.ERROR
            faceStatus = null
            statusMessage = UiText.Resource(R.string.error_face_login_required)
            return
        }

        phase = FaceManagementPhase.LOADING
        statusMessage = null
        try {
            val snapshot = repository.loadStatus(
                accountEmail = profileEmail,
                accessToken = accessToken,
                refreshAccessToken = currentRefreshAccessToken,
            )
            faceStatus = snapshot.status
            faceImages = snapshot.images
            phase = FaceManagementPhase.READY
        } catch (exception: CancellationException) {
            if (faceStatus != null) {
                phase = FaceManagementPhase.READY
            } else {
                phase = FaceManagementPhase.ERROR
                statusMessage = UiText.Resource(R.string.error_face_status_cancelled)
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
            statusMessage = UiText.Resource(R.string.error_face_status)
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
                statusMessage = UiText.Resource(R.string.error_face_login_required)
                return@launchRequest
            }
            phase = FaceManagementPhase.LOADING
            statusMessage = UiText.Resource(R.string.face_deleting)
            try {
                val localDeleteFailed = repository.deleteFace(
                    faceId = faceId,
                    accountEmail = profileEmail,
                    accessToken = accessToken,
                    refreshAccessToken = currentRefreshAccessToken,
                )
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
                    statusMessage = UiText.Resource(R.string.error_face_local_photo)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReferenceFaceApiException) {
                if (exception.statusCode == 404) {
                    statusMessage = UiText.Resource(R.string.face_list_changed)
                    repository.deleteLocalFace(profileEmail, faceId)
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
                statusMessage = UiText.Resource(R.string.error_face_delete)
            }
        }
    }

    fun deleteAllFaces() {
        launchRequest {
            val accessToken = currentGetAccessToken()?.trim().orEmpty()
            if (accessToken.isEmpty()) {
                phase = FaceManagementPhase.ERROR
                statusMessage = UiText.Resource(R.string.error_face_login_required)
                return@launchRequest
            }
            phase = FaceManagementPhase.LOADING
            statusMessage = UiText.Resource(R.string.faces_deleting)
            try {
                val localDeleteFailed = repository.deleteAll(
                    accountEmail = profileEmail,
                    accessToken = accessToken,
                    refreshAccessToken = currentRefreshAccessToken,
                )
                faceStatus = faceStatus?.copy(
                    registered = false,
                    count = 0,
                    faces = emptyList(),
                )
                faceImages = emptyMap()
                refreshStatus()
                if (localDeleteFailed && phase == FaceManagementPhase.READY) {
                    statusMessage = UiText.Resource(R.string.error_face_local_photo)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: ReferenceFaceApiException) {
                if (exception.statusCode == 404) {
                    statusMessage = UiText.Resource(R.string.face_list_changed)
                    repository.deleteLocalFaces(profileEmail)
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
                statusMessage = UiText.Resource(R.string.error_face_delete)
            }
        }
    }

    val isRegistrationOpen = openRegistration || reregisterTarget != null
    LaunchedEffect(isRegistrationOpen, profileEmail) {
        if (!isRegistrationOpen) {
            refreshStatus()
            pendingStatusMessage?.let { message ->
                if (phase == FaceManagementPhase.READY && statusMessage == null) statusMessage = message
            }
            pendingStatusMessage = null
        }
    }

    reregisterTarget?.let { target ->
        FaceRegistrationScreen(
            cameraLensFacing = cameraLensFacing,
            onGetAccessToken = currentGetAccessToken,
            onRefreshAccessToken = currentRefreshAccessToken,
            onBack = { reregisterTarget = null },
            // 다시 등록한 뒤에는 목록으로 돌아와 표시가 풀린 것을 보여 줍니다.
            onRegistrationSuccess = {
                pendingStatusMessage = UiText.Resource(R.string.face_reregistration_complete)
                reregisterTarget = null
            },
            profileEmail = profileEmail,
            existingFaces = faceStatus?.faces.orEmpty(),
            broadcastCamera = broadcastCamera,
            reregisterFace = target,
            onReregisterFaceMissing = {
                pendingStatusMessage = UiText.Resource(R.string.face_list_changed)
                reregisterTarget = null
            },
        )
        return
    }

    if (openRegistration) {
        FaceRegistrationScreen(
            cameraLensFacing = cameraLensFacing,
            onGetAccessToken = currentGetAccessToken,
            onRefreshAccessToken = currentRefreshAccessToken,
            onBack = { openRegistration = false },
            onRegistrationSuccess = onBack,
            profileEmail = profileEmail,
            existingFaces = faceStatus?.faces.orEmpty(),
            broadcastCamera = broadcastCamera,
        )
        return
    }

    fun close() {
        requestJob?.cancel()
        onBack()
    }

    BackHandler(onBack = ::close)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { launchRequest { refreshStatus() } },
                enabled = phase != FaceManagementPhase.LOADING,
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                    tint = Color.White,
                )
            }
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
                text = stringResource(R.string.face_management),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.face_management_description),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when (phase) {
            FaceManagementPhase.LOADING -> CircularProgressIndicator()
            FaceManagementPhase.READY -> {
                val status = faceStatus
                if (status?.reregistrationRequired == true) {
                    ReferenceFaceReregistrationBanner()
                }
                if (status?.registered == true && status.faces.isNotEmpty()) {
                    Text(
                        text = UiText.Plural(
                            R.plurals.registered_face_count,
                            status.count ?: status.faces.size,
                        ).asString(),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    status.faces.forEachIndexed { index, face ->
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
                                        contentDescription = stringResource(
                                            R.string.content_description_registered_face,
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } ?: Text(
                                    text = stringResource(R.string.no_face_photo),
                                    color = Color.White,
                                )
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.registered_face_name, index + 1),
                                    color = Color.White,
                                )
                                if (face.reregistrationRequired) {
                                    Text(
                                        text = stringResource(R.string.face_reregistration_required),
                                        color = ReferenceFaceWarningColor,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    val reregisterDescription = stringResource(
                                        R.string.content_description_reregister_registered_face,
                                        index + 1,
                                    )
                                    Button(
                                        onClick = {
                                            requestJob?.cancel()
                                            statusMessage = null
                                            reregisterTarget = face
                                        },
                                        modifier = Modifier.semantics {
                                            contentDescription = reregisterDescription
                                        },
                                    ) {
                                        Text(text = stringResource(R.string.action_reregister_face))
                                    }
                                }
                            }
                            val deleteDescription = stringResource(
                                R.string.content_description_delete_registered_face,
                                index + 1,
                            )
                            Button(
                                onClick = { deleteFace(face.faceId) },
                                modifier = Modifier.semantics {
                                    contentDescription = deleteDescription
                                },
                            ) {
                                Text(text = stringResource(R.string.action_delete))
                            }
                        }
                    }
                    Button(onClick = ::deleteAllFaces) {
                        Text(text = stringResource(R.string.action_delete_all_faces))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.no_registered_faces),
                        color = Color.White,
                    )
                }
            }

            FaceManagementPhase.ERROR -> {
                statusMessage?.let { message ->
                    Text(text = message.asString(), color = Color.White)
                }
            }
        }

        statusMessage?.let { message ->
            if (phase == FaceManagementPhase.READY) {
                Text(text = message.asString(), color = Color.White)
            }
        }
        }
        FilledIconButton (
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            onClick = { openRegistration = true },
            enabled = phase != FaceManagementPhase.LOADING,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.action_register_face),
                tint = Color.White,
                modifier = Modifier
                    .width(48.dp)
            )
        }
    }
}

private val ReferenceFaceWarningColor = Color(0xFFFFB74D)

/** AI 워커가 재시작해 잃은 기준 얼굴이 있다는 안내입니다. 그대로 두면 등록한 사람도 블러됩니다. */
@Composable
private fun ReferenceFaceReregistrationBanner() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ReferenceFaceWarningColor, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.face_reregistration_banner_title),
            color = ReferenceFaceWarningColor,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.face_reregistration_notice),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
