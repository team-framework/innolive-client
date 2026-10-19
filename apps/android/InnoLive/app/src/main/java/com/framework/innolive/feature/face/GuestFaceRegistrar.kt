package com.framework.innolive.feature.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

private const val TAG = "GuestFaceRegistrar"
private const val REGISTERED_TOAST_MILLIS = 2_000L
private const val REGISTERED_NOTICE_MILLIS = 4_000L
private const val LOCAL_MODEL_TIMEOUT_MILLIS = 10_000L
private const val FACE_LIST_RETRY_DELAY_MILLIS = 500L

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

internal class GuestFaceRegistrationException(@param:StringRes val messageRes: Int) : Exception()

/** 등록 이미지를 만들고 실제로 등록하는 쪽입니다. 단위 테스트는 가짜 구현으로 상태 전이만 검증합니다. */
internal interface GuestFaceRegistrationBackend : AutoCloseable {
    /** 선택한 얼굴만 남긴 500px JPEG입니다. 얼굴을 확인하지 못하면 null입니다. 백그라운드 스레드에서 호출합니다. */
    fun prepare(frame: FaceSelectionFrame, faceIndex: Int): ByteArray?

    /** [jpeg]를 [target]에 등록합니다. 등록은 됐지만 기기에 사진을 보관하지 못했으면 false입니다. */
    suspend fun register(jpeg: ByteArray, name: String, target: GuestFaceTarget): Boolean
}

/**
 * 탭한 게스트를 백그라운드에서 등록해 홈 화면이 바로 송출 화면으로 돌아가게 합니다.
 * iOS `GuestFaceRegistrar`와 같은 상태를 가지며, 등록 이미지는 iOS처럼 JPEG 하나로만 보관합니다.
 * 상태는 [scope]의 메인 스레드에서 바뀌고, 마지막으로 시작한 작업만 상태를 씁니다.
 */
internal class GuestFaceRegistrar(
    private val scope: CoroutineScope,
    private val backend: GuestFaceRegistrationBackend,
    private val preparationContext: CoroutineContext = Dispatchers.Default,
    /** 등록 실패 원인을 남깁니다. 토스트는 일반 문구만 보여 주므로 실기기 조사는 이 로그로 합니다. */
    private val logFailure: (message: String, error: Throwable) -> Unit = { _, _ -> },
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        scope = scope,
        backend = DeviceGuestFaceRegistrationBackend(context),
        logFailure = { message, error -> Log.w(TAG, message, error) },
    )

    sealed interface Status {
        data object Registering : Status

        /** [notice]가 있으면 등록은 됐지만 사용자에게 알릴 일이 남은 경우입니다. */
        data class Registered(val notice: UiText? = null) : Status

        /** 얼굴 자체를 준비하지 못했으면 [canRetry]가 false이며 다시 선택해야 합니다. */
        data class Failed(val message: UiText, val canRetry: Boolean) : Status
    }

    private data class Request(val name: String, val target: GuestFaceTarget)

    var status by mutableStateOf<Status?>(null)
        private set

    private var request: Request? = null
    private var jpeg: ByteArray? = null
    private var job: Job? = null

    /** [frame] 소유권을 넘겨받아 등록 이미지를 만든 뒤 바로 해제합니다. 진행 중인 앞 등록은 취소합니다. */
    fun register(frame: FaceSelectionFrame, faceIndex: Int, name: String, target: GuestFaceTarget) {
        job?.cancel()
        request = Request(name, target)
        jpeg = null
        status = Status.Registering
        val registration = scope.launch {
            // 등록 이미지를 JPEG로 한 번 만들고 프레임은 바로 버려 카메라 크기의 버퍼를 오래 들고 있지 않습니다.
            val prepared = try {
                withContext(preparationContext) { backend.prepare(frame, faceIndex) }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                null
            } finally {
                frame.release()
            }
            currentCoroutineContext().ensureActive()
            if (prepared == null) {
                status = Status.Failed(UiText.Resource(R.string.guest_face_not_confirmed), canRetry = false)
                return@launch
            }
            jpeg = prepared
            upload()
        }
        // 준비는 취소돼도 끝까지 돈 뒤 위에서 프레임을 해제합니다. 시작 전에 취소돼 본문이 돌지 않았을 때만
        // 여기서 해제합니다. 해제는 한 번만 일어납니다.
        registration.invokeOnCompletion { frame.release() }
        job = registration
    }

    fun retry() {
        val failed = status as? Status.Failed ?: return
        if (!failed.canRetry || jpeg == null) return
        job?.cancel()
        status = Status.Registering
        job = scope.launch { upload() }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        request = null
        jpeg = null
        status = null
    }

    fun close() {
        dismiss()
        backend.close()
    }

    private suspend fun upload() {
        val request = request ?: return
        val jpeg = jpeg ?: return
        val photoSaved = try {
            backend.register(jpeg, request.name, request.target)
        } catch (exception: CancellationException) {
            // 이 작업이 취소된 것이 아니면 내부 시간 초과 등이므로 다시 시도할 수 있게 실패로 남깁니다.
            if (!currentCoroutineContext().isActive) throw exception
            logFailure("Guest face registration was cancelled internally", exception)
            status = Status.Failed(UiText.Resource(R.string.error_face_registration), canRetry = true)
            return
        } catch (exception: Exception) {
            // iOS `guard !Task.isCancelled`처럼 취소된 작업은 새 작업의 상태를 덮어쓰지 않습니다.
            if (!currentCoroutineContext().isActive) return
            logFailure(exception.describeFailure(), exception)
            status = Status.Failed(exception.toGuestMessage(), canRetry = true)
            return
        }
        currentCoroutineContext().ensureActive()
        this.jpeg = null
        this.request = null
        val registered = Status.Registered(
            notice = if (photoSaved) null else UiText.Resource(R.string.face_registered_local_photo_failed),
        )
        status = registered
        delay(if (registered.notice == null) REGISTERED_TOAST_MILLIS else REGISTERED_NOTICE_MILLIS)
        if (status === registered) status = null
    }

    private fun Exception.toGuestMessage(): UiText = when (this) {
        is ReferenceFaceApiException -> toUserMessage()
        is GuestFaceRegistrationException -> UiText.Resource(messageRes)
        else -> UiText.Resource(R.string.error_face_registration)
    }

    /** 서버 응답의 상태 코드·오류 코드·세부 사유까지 적어 일반 실패 문구 뒤의 원인을 구분합니다. */
    private fun Exception.describeFailure(): String = when (this) {
        is ReferenceFaceApiException ->
            "Guest face registration failed: status=$statusCode code=$code reason=$detailsReason message=$message"
        is GuestFaceRegistrationException -> "Guest face registration failed: messageRes=$messageRes"
        else -> "Guest face registration failed: ${javaClass.name}: $message"
    }
}

/** 서버 기준 얼굴 API와 기기 얼굴 라이브러리에 실제로 등록합니다. */
internal class DeviceGuestFaceRegistrationBackend(
    context: Context,
    private val repositoryFactory: (Context) -> ReferenceFaceRepository = { ReferenceFaceRepository(it) },
) : GuestFaceRegistrationBackend {
    private val context = context.applicationContext
    private val detector = FaceSelectionDetector()

    override fun prepare(frame: FaceSelectionFrame, faceIndex: Int): ByteArray? {
        val upright = frame.image.toUprightBitmap()
        val image = try {
            prepareImage(upright, frame.faces, faceIndex)
        } finally {
            upright.recycle()
        } ?: return null
        return try {
            image.toJpegBytes()
        } finally {
            image.recycle()
        }
    }

    override suspend fun register(jpeg: ByteArray, name: String, target: GuestFaceTarget): Boolean =
        when (target) {
            is GuestFaceTarget.Server -> registerOnServer(jpeg, name, target)
            is GuestFaceTarget.OnDevice -> {
                registerOnDevice(jpeg, name, target)
                true
            }
        }

    override fun close() {
        detector.close()
    }

    /** 500px 등록 이미지에서 얼굴이 가운데 하나만 남았는지 확인합니다. 자르면서 커진 다른 얼굴은 다시 가립니다. */
    private fun prepareImage(frame: Bitmap, faces: List<FaceSelectionCandidate>, faceIndex: Int): Bitmap? {
        var image = FaceRegistrationImage.crop(frame, faces, faceIndex) ?: return null
        try {
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
        } catch (error: Throwable) {
            image.recycle()
            throw error
        }
    }

    private suspend fun registerOnServer(jpeg: ByteArray, name: String, target: GuestFaceTarget.Server): Boolean {
        val accessToken = target.getAccessToken()?.trim().orEmpty()
        if (accessToken.isEmpty()) throw GuestFaceRegistrationException(R.string.error_face_login_required)
        return repositoryFactory(context).use { repository ->
            try {
                repository.ensureApiAvailable()
            } catch (_: Exception) {
                throw GuestFaceRegistrationException(R.string.error_face_server_unavailable)
            }
            // 새 얼굴의 사진을 기기에 보관하려면 등록 전 목록이 필요합니다. 한 번 더 시도하고, 그래도 못 읽으면
            // 등록은 진행합니다. 저장소가 새 얼굴을 가려내지 못하면 사진을 보관하지 않고 false로 알립니다.
            val existingFaces = loadExistingFaces(repository, accessToken, target) ?: emptyList()
            val result = repository.append(
                images = listOf(jpeg),
                existingFaces = existingFaces,
                accountEmail = target.accountEmail,
                accessToken = accessToken,
                refreshAccessToken = target.refreshAccessToken,
                name = name,
            )
            !result.localImageStoreFailed
        }
    }

    private suspend fun loadExistingFaces(
        repository: ReferenceFaceRepository,
        accessToken: String,
        target: GuestFaceTarget.Server,
    ): List<ReferenceFace>? {
        repeat(2) { attempt ->
            if (attempt > 0) delay(FACE_LIST_RETRY_DELAY_MILLIS)
            try {
                return repository.loadStatus(target.accountEmail, accessToken, target.refreshAccessToken).status.faces
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun registerOnDevice(jpeg: ByteArray, name: String, target: GuestFaceTarget.OnDevice) {
        val library = target.accountScope?.let { scope ->
            runCatching { PrivacyFaceLibrary(context, scope) }.getOrNull()
        } ?: throw GuestFaceRegistrationException(R.string.local_face_store_error)
        val service = PrivacyFaceService.get(context)
        service.retain()
        try {
            if (service.preparationFailed) service.retryPreparation() else service.prepare()
            val ready = withContext(Dispatchers.IO) { service.awaitPreparation(LOCAL_MODEL_TIMEOUT_MILLIS) }
            if (!ready) throw GuestFaceRegistrationException(R.string.local_face_model_failed)
            // enroll은 받은 비트맵을 재활용하므로 시도마다 JPEG에서 새로 만듭니다. iOS도 같은 JPEG로 등록합니다.
            val sample = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            } ?: throw GuestFaceRegistrationException(R.string.local_face_enroll_failed)
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
}
