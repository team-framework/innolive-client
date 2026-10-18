package com.framework.innolive.feature.face

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.framework.innolive.R
import com.framework.innolive.feature.live.privacy.PrivacyFaceLibrary
import com.framework.innolive.feature.live.privacy.PrivacyFaceService
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val REGISTERED_TOAST_MILLIS = 2_000L
private const val LOCAL_MODEL_TIMEOUT_MILLIS = 10_000L

/** 탭한 게스트를 어디에 등록할지입니다. 방송에 고른 처리 방식과 같습니다. */
internal sealed interface GuestFaceTarget {
    /** 서버 처리 방송의 기준 얼굴 API입니다. */
    data class Server(
        val accountEmail: String,
        val getAccessToken: () -> String?,
        val refreshAccessToken: suspend () -> String,
    ) : GuestFaceTarget

    /** 온디바이스 처리 방송의 기기 얼굴 라이브러리입니다. 등록 후 [onChanged]로 방송 예외 목록을 새로 고칩니다. */
    data class OnDevice(
        val accountScope: String?,
        val onChanged: () -> Unit,
    ) : GuestFaceTarget
}

internal class GuestFaceRegistrationException(@StringRes val messageRes: Int) : Exception()

/**
 * 탭한 게스트를 백그라운드에서 등록해 홈 화면이 바로 송출 화면으로 돌아가게 합니다.
 * iOS `GuestFaceRegistrar`와 같은 상태를 가집니다.
 */
internal class GuestFaceRegistrar(
    context: Context,
    private val scope: CoroutineScope,
    private val repositoryFactory: (Context) -> ReferenceFaceRepository = { ReferenceFaceRepository(it) },
) {
    sealed interface Status {
        data object Registering : Status
        data object Registered : Status

        /** 얼굴 자체를 준비하지 못했으면 [canRetry]가 false이며 다시 선택해야 합니다. */
        data class Failed(val message: UiText, val canRetry: Boolean) : Status
    }

    private data class Request(val name: String, val target: GuestFaceTarget)

    var status by mutableStateOf<Status?>(null)
        private set

    private val context = context.applicationContext
    private val detector = FaceSelectionDetector()
    private var request: Request? = null
    private var prepared: Bitmap? = null
    private var job: Job? = null

    fun register(frame: FaceSelectionFrame, faceIndex: Int, name: String, target: GuestFaceTarget) {
        job?.cancel()
        recyclePrepared()
        request = Request(name, target)
        status = Status.Registering
        job = scope.launch {
            // 등록 이미지를 한 번 만들고 프레임은 바로 버려 카메라 버퍼 크기의 비트맵을 오래 들고 있지 않습니다.
            val image = try {
                withContext(Dispatchers.Default) { prepare(frame, faceIndex) }
            } finally {
                frame.bitmap.recycle()
            }
            if (image == null) {
                status = Status.Failed(UiText.Resource(R.string.guest_face_not_confirmed), canRetry = false)
                return@launch
            }
            prepared = image
            upload()
        }
    }

    fun retry() {
        val failed = status as? Status.Failed ?: return
        if (!failed.canRetry || prepared == null) return
        job?.cancel()
        status = Status.Registering
        job = scope.launch { upload() }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        request = null
        recyclePrepared()
        status = null
    }

    fun close() {
        dismiss()
        detector.close()
    }

    /** 500px 등록 이미지에서 얼굴이 가운데 하나만 남았는지 확인합니다. 자르면서 커진 다른 얼굴은 다시 가립니다. */
    private fun prepare(frame: FaceSelectionFrame, faceIndex: Int): Bitmap? {
        var image = FaceRegistrationImage.crop(frame, faceIndex) ?: return null
        var boxes = detector.detectBoxes(image)?.map { FaceSelectionGeometry.normalized(it, image.width, image.height) }
            ?: run { image.recycle(); return null }
        val others = boxes.filterNot(FaceSelectionGeometry::containsCenter)
        val selected = boxes.firstOrNull(FaceSelectionGeometry::containsCenter)
        if (others.isNotEmpty() && selected != null) {
            val remasked = FaceRegistrationImage.maskOthers(image, others, selected)
            image.recycle()
            image = remasked
            boxes = detector.detectBoxes(image)?.map { FaceSelectionGeometry.normalized(it, image.width, image.height) }
                ?: run { image.recycle(); return null }
        }
        if (boxes.size != 1 || !FaceSelectionGeometry.containsCenter(boxes[0])) {
            image.recycle()
            return null
        }
        return image
    }

    private suspend fun upload() {
        val request = request ?: return
        val image = prepared ?: return
        try {
            when (val target = request.target) {
                is GuestFaceTarget.Server -> registerOnServer(image, request.name, target)
                is GuestFaceTarget.OnDevice -> registerOnDevice(image, request.name, target)
            }
            recyclePrepared()
            this.request = null
            status = Status.Registered
            delay(REGISTERED_TOAST_MILLIS)
            if (status == Status.Registered) status = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: ReferenceFaceApiException) {
            status = Status.Failed(exception.toUserMessage(), canRetry = true)
        } catch (exception: GuestFaceRegistrationException) {
            status = Status.Failed(UiText.Resource(exception.messageRes), canRetry = true)
        } catch (_: Exception) {
            status = Status.Failed(UiText.Resource(R.string.error_face_registration), canRetry = true)
        }
    }

    private suspend fun registerOnServer(image: Bitmap, name: String, target: GuestFaceTarget.Server) {
        val accessToken = target.getAccessToken()?.trim().orEmpty()
        if (accessToken.isEmpty()) throw GuestFaceRegistrationException(R.string.error_face_login_required)
        val jpeg = withContext(Dispatchers.Default) { image.toJpegBytes() }
        repositoryFactory(context).use { repository ->
            try {
                repository.ensureApiAvailable()
            } catch (_: Exception) {
                throw GuestFaceRegistrationException(R.string.error_face_server_unavailable)
            }
            // 새 얼굴의 사진을 기기에 보관하려면 등록 전 목록이 필요합니다. 못 읽어도 등록은 진행합니다.
            val existingFaces = try {
                repository.loadStatus(target.accountEmail, accessToken, target.refreshAccessToken).status.faces
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                emptyList()
            }
            repository.append(
                images = listOf(jpeg),
                existingFaces = existingFaces,
                accountEmail = target.accountEmail,
                accessToken = accessToken,
                refreshAccessToken = target.refreshAccessToken,
                name = name,
            )
        }
    }

    private suspend fun registerOnDevice(image: Bitmap, name: String, target: GuestFaceTarget.OnDevice) {
        val library = target.accountScope?.let { scope ->
            runCatching { PrivacyFaceLibrary(context, scope) }.getOrNull()
        } ?: throw GuestFaceRegistrationException(R.string.local_face_store_error)
        val service = PrivacyFaceService.get(context)
        service.retain()
        try {
            if (service.preparationFailed) service.retryPreparation() else service.prepare()
            val ready = withContext(Dispatchers.IO) { service.awaitPreparation(LOCAL_MODEL_TIMEOUT_MILLIS) }
            if (!ready) throw GuestFaceRegistrationException(R.string.local_face_model_failed)
            // enroll은 받은 비트맵을 재활용하므로 다시 시도할 수 있게 복사본을 넘깁니다.
            val sample = image.copy(Bitmap.Config.ARGB_8888, false)
            val embedding = suspendCancellableCoroutine<FloatArray?> { continuation ->
                service.enroll(sample) { result -> if (continuation.isActive) continuation.resume(result) }
            } ?: throw GuestFaceRegistrationException(R.string.local_face_enroll_failed)
            try {
                withContext(Dispatchers.IO) { library.add(name, embedding) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                throw GuestFaceRegistrationException(R.string.local_face_store_error)
            }
            target.onChanged()
        } finally {
            service.release()
        }
    }

    private fun recyclePrepared() {
        prepared?.recycle()
        prepared = null
    }
}
