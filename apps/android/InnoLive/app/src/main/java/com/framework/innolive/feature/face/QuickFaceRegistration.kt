package com.framework.innolive.feature.face

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraFrameAnalyzer
import com.framework.innolive.feature.live.WebRtcNativeRuntime
import com.framework.innolive.ui.text.asString
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/** 원탭 등록이 새 사람에게 붙이는 이름입니다. 예: "10월 17일 오후 3시 - 게스트". iOS `GuestFaceName`과 같습니다. */
internal object GuestFaceName {
    private const val SKELETON = "MMMdj"

    fun make(context: Context, date: Date = Date()): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return make(
            suffix = context.getString(R.string.guest_face_name_suffix),
            pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, SKELETON),
            date = date,
            locale = locale,
        )
    }

    fun make(
        suffix: String,
        pattern: String,
        date: Date,
        locale: Locale,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val formatter = SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }
        return "${formatter.format(date)} - $suffix"
    }
}

internal sealed interface FaceSelectionPhase {
    data object Preparing : FaceSelectionPhase
    data class Detecting(@param:StringRes val message: Int) : FaceSelectionPhase
    data class Failed(@param:StringRes val message: Int) : FaceSelectionPhase
}

/** 모델이 프레임 소스를 여는 방법입니다. 단위 테스트는 가짜 소스로 세대·소유권 규칙만 검증합니다. */
internal interface FaceSelectionSources : AutoCloseable {
    fun open(
        mirrored: Boolean,
        onFrame: (FaceSelectionFrame) -> Unit,
        onError: () -> Unit,
    ): FaceSelectionSource
}

/** 모델 상태를 바꾸는 메인 스레드 작업 예약입니다. */
internal interface FaceSelectionScheduler {
    fun post(action: () -> Unit)

    /** [action]을 [delayMillis] 뒤에 실행합니다. 돌려준 함수를 부르면 취소합니다. */
    fun postDelayed(delayMillis: Long, action: () -> Unit): () -> Unit
}

private object MainFaceSelectionScheduler : FaceSelectionScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun post(action: () -> Unit) {
        handler.post(action)
    }

    override fun postDelayed(delayMillis: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return { handler.removeCallbacks(runnable) }
    }
}

/** 방송 분석기의 원본 프레임을 쓰는 소스입니다. 윤곽 감지기는 재시도해도 다시 만들지 않습니다. */
internal class CameraFaceSelectionSources(
    context: Context,
    private val analyzer: CameraFrameAnalyzer,
) : FaceSelectionSources {
    private val detector = FaceSelectionDetector(contours = true)

    init {
        // 프레임 복사·축소가 WebRTC I420 버퍼를 쓰므로 네이티브 런타임을 먼저 올립니다. 이미 올렸으면 바로 돌아옵니다.
        WebRtcNativeRuntime.initialize(context)
    }

    override fun open(
        mirrored: Boolean,
        onFrame: (FaceSelectionFrame) -> Unit,
        onError: () -> Unit,
    ): FaceSelectionSource = FaceSelectionFrameSource(analyzer, mirrored, detector, onFrame, onError)

    override fun close() {
        detector.close()
    }
}

/** 프레임 전달을 시작한 뒤 이 시간 안에 첫 프레임이 없으면 다시 시도하도록 안내합니다. */
internal const val FIRST_FACE_FRAME_TIMEOUT_MILLIS = 5_000L

/**
 * 홈 화면 원탭 등록이 고를 수 있는 얼굴을 원본 카메라에서 찾습니다. 탭한 얼굴은 [GuestFaceRegistrar]가
 * 등록하므로 선택은 바로 끝낼 수 있습니다. 설정의 얼굴 등록 화면은 그대로 `FaceDetectionPipeline`을 씁니다.
 * 상태는 메인 스레드에서만 바뀌고, 프레임은 [start]마다 올리는 세대 번호로 오래된 것을 버립니다.
 */
internal class QuickFaceSelectionModel(
    private val sources: FaceSelectionSources,
    private val scheduler: FaceSelectionScheduler = MainFaceSelectionScheduler,
    private val firstFrameTimeoutMillis: Long = FIRST_FACE_FRAME_TIMEOUT_MILLIS,
) {
    var phase by mutableStateOf<FaceSelectionPhase>(FaceSelectionPhase.Preparing)
        private set
    var faces by mutableStateOf<List<FaceSelectionCandidate>>(emptyList())
        private set
    var frameWidth by mutableIntStateOf(0)
        private set
    var frameHeight by mutableIntStateOf(0)
        private set

    private var source: FaceSelectionSource? = null
    private var latest: FaceSelectionFrame? = null
    private var cancelWatchdog: (() -> Unit)? = null
    private var hasFrame = false
    private var closed = false
    private var generation = 0

    /** 프레임 전달을 (다시) 시작합니다. [mirrored]면 전면 카메라처럼 프레임을 좌우 반전합니다. */
    fun start(mirrored: Boolean) {
        if (closed) return
        stop()
        val current = generation
        val opened = try {
            sources.open(
                mirrored = mirrored,
                onFrame = { frame -> scheduler.post { handle(current, frame) } },
                onError = { scheduler.post { fail(current) } },
            )
        } catch (_: Exception) {
            fail(current)
            return
        }
        source = opened
        hasFrame = false
        try {
            opened.start()
        } catch (_: Exception) {
            fail(current)
            return
        }
        // iOS처럼 프레임 전달이 시작되면 바로 얼굴을 비추라고 안내하고, 첫 프레임이 오지 않으면 다시 시도하게 합니다.
        phase = FaceSelectionPhase.Detecting(R.string.quick_face_show_face)
        cancelWatchdog = scheduler.postDelayed(firstFrameTimeoutMillis) {
            if (current == generation && !hasFrame) fail(current)
        }
    }

    fun stop() {
        generation += 1
        stopSource()
        clearFaces()
        phase = FaceSelectionPhase.Preparing
    }

    fun close() {
        stop()
        if (closed) return
        closed = true
        sources.close()
    }

    /** 화면에 보이는 얼굴 중 [index]를 고를 수 있으면 그 프레임을 넘기고 소유권도 넘깁니다. */
    fun takeFrameForSelection(index: Int): FaceSelectionFrame? {
        if (phase !is FaceSelectionPhase.Detecting) return null
        val frame = latest ?: return null
        if (!frame.faces.getOrNull(index).let { it != null && it.isSelectable }) return null
        latest = null
        faces = emptyList()
        return frame
    }

    private fun handle(current: Int, frame: FaceSelectionFrame) {
        if (current != generation || phase !is FaceSelectionPhase.Detecting) {
            frame.release()
            return
        }
        hasFrame = true
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        latest?.release()
        latest = frame
        faces = frame.faces
        frameWidth = frame.width
        frameHeight = frame.height
        phase = FaceSelectionPhase.Detecting(
            when {
                frame.faces.any { it.isSelectable } -> R.string.quick_face_tap_to_register
                frame.faces.isEmpty() -> R.string.quick_face_show_face
                else -> R.string.quick_face_move_closer
            },
        )
    }

    private fun fail(current: Int) {
        if (current != generation) return
        stopSource()
        clearFaces()
        phase = FaceSelectionPhase.Failed(R.string.quick_face_camera_failed)
    }

    private fun stopSource() {
        cancelWatchdog?.invoke()
        cancelWatchdog = null
        source?.stop()
        source = null
    }

    private fun clearFaces() {
        latest?.release()
        latest = null
        faces = emptyList()
    }
}

/**
 * 얼굴을 고르는 동안 원본 카메라에서 얼굴을 찾습니다. 화면에서 빠지면 닫히고, [mirrored]가 바뀌면 프레임 전달을
 * 다시 시작합니다. 윤곽([FaceSelectionLayer])과 안내([FaceSelectionStatusPanel])가 놓이는 곳이 달라 함께 씁니다.
 */
@Composable
internal fun rememberQuickFaceSelectionModel(analyzer: CameraFrameAnalyzer, mirrored: Boolean): QuickFaceSelectionModel {
    val context = LocalContext.current
    val model = remember(analyzer) {
        QuickFaceSelectionModel(CameraFaceSelectionSources(context.applicationContext, analyzer))
    }
    DisposableEffect(model) {
        onDispose { model.close() }
    }
    DisposableEffect(model, mirrored) {
        model.start(mirrored)
        onDispose { model.stop() }
    }
    return model
}

/**
 * 선택 중 홈 화면 메인 영역 위에 놓입니다. 원본 카메라 위에 얼굴 윤곽을 그리고, 탭하면 [onSelect]에 프레임을
 * 넘긴 뒤 선택이 끝납니다. 등록은 다른 곳에서 이어집니다.
 */
@Composable
internal fun FaceSelectionLayer(
    model: QuickFaceSelectionModel,
    onSelect: (FaceSelectionFrame, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (model.phase is FaceSelectionPhase.Detecting) {
        FaceSelectionOverlay(
            faces = model.faces,
            imageWidth = model.frameWidth,
            imageHeight = model.frameHeight,
            onSelect = { index -> model.takeFrameForSelection(index)?.let { onSelect(it, index) } },
            modifier = modifier,
        )
    }
}

/**
 * 선택 단계 안내와 실패 시 다시 시도 버튼입니다. iOS `FaceSelectionLayer`처럼 하단 방송 버튼 바로 위에 둬
 * 상단 바·상태 칩·카메라 도구와 겹치지 않게 합니다.
 */
@Composable
internal fun FaceSelectionStatusPanel(
    phase: FaceSelectionPhase,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FaceSelectionStatus(phase = phase)
        if (phase is FaceSelectionPhase.Failed) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
            ) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun FaceSelectionStatus(phase: FaceSelectionPhase) {
    Row(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (phase) {
            FaceSelectionPhase.Preparing -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            is FaceSelectionPhase.Failed -> Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Color(0xFFFFA726),
                modifier = Modifier.size(20.dp),
            )
            is FaceSelectionPhase.Detecting -> Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF64B5F6),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    when (phase) {
                        FaceSelectionPhase.Preparing -> R.string.quick_face_preparing
                        is FaceSelectionPhase.Detecting -> phase.message
                        is FaceSelectionPhase.Failed -> phase.message
                    },
                ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.quick_face_original_not_sent),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 원본 카메라 위의 실시간 윤곽입니다. 등록할 수 있을 만큼 큰 얼굴만 그립니다. iOS `FaceSelectionOverlay`와 같습니다. */
@Composable
internal fun FaceSelectionOverlay(
    faces: List<FaceSelectionCandidate>,
    imageWidth: Int,
    imageHeight: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberInfiniteTransition(label = "face-outline")
    val outlineAlpha by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "face-outline-alpha",
    )
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val viewWidth = with(density) { maxWidth.toPx() }
        val viewHeight = with(density) { maxHeight.toPx() }
        val minimumTarget = with(density) { 44.dp.toPx() }
        val strokeWidth = with(density) { 3.dp.toPx() }
        val outlines = faces.mapIndexedNotNull { index, face ->
            if (!face.isSelectable) return@mapIndexedNotNull null
            val shape = FaceOutline.shape(face, imageWidth, imageHeight, viewWidth, viewHeight)
            Triple(index, shape, shape.toPath())
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            outlines.forEach { (_, _, path) ->
                drawPath(
                    path = path,
                    color = Color.Black.copy(alpha = 0.45f * outlineAlpha),
                    style = Stroke(width = strokeWidth + 2f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = outlineAlpha),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }

        outlines.forEach { (index, shape, _) ->
            FaceSelectionTarget(
                index = index,
                rect = shape.bounds,
                minimumTarget = minimumTarget,
                viewHeight = viewHeight,
                onSelect = onSelect,
            )
        }
    }
}

/** 턱선은 iOS처럼 점 사이 중점을 지나는 2차 곡선으로 부드럽게 잇고, 이마는 3차 곡선으로 닫습니다. */
private fun FaceOutlineShape.toPath(): Path = Path().apply {
    when (val shape = this@toPath) {
        is FaceOutlineShape.Ellipse -> addOval(
            Rect(shape.bounds.left, shape.bounds.top, shape.bounds.right, shape.bounds.bottom),
        )
        is FaceOutlineShape.Contour -> {
            val jaw = shape.jaw
            val first = jaw.first()
            val last = jaw.last()
            moveTo(first.x, first.y)
            for (index in 1 until jaw.size - 1) {
                val point = jaw[index]
                val next = jaw[index + 1]
                quadraticTo(point.x, point.y, (point.x + next.x) / 2f, (point.y + next.y) / 2f)
            }
            lineTo(last.x, last.y)
            cubicTo(last.x, shape.domeTop, first.x, shape.domeTop, first.x, first.y)
            close()
        }
    }
}

@Composable
private fun FaceSelectionTarget(
    index: Int,
    rect: FaceBox,
    minimumTarget: Float,
    viewHeight: Float,
    onSelect: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val targetWidth = max(rect.width, minimumTarget)
    val targetHeight = max(rect.height, minimumTarget)
    val label = stringResource(R.string.quick_face_select_face, index + 1)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (rect.centerX - targetWidth / 2f).toInt(),
                    (rect.centerY - targetHeight / 2f).toInt(),
                )
            }
            .size(
                width = with(density) { targetWidth.toDp() },
                height = with(density) { targetHeight.toDp() },
            )
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onSelect(index) },
            ),
    )
    // 칩은 얼굴 아래에 두고, 화면 아래로 나가면 위로 올립니다. 칩 상자를 얼굴 가운데에 맞춰 글자를 가운데 정렬합니다.
    val chipBoxWidth = with(density) { 160.dp.toPx() }
    val chipBoxHeight = with(density) { 24.dp.toPx() }
    val chipBelow = rect.bottom + with(density) { 30.dp.toPx() } <= viewHeight
    val chipTop = if (chipBelow) rect.bottom + with(density) { 6.dp.toPx() } else rect.top - chipBoxHeight - with(density) { 6.dp.toPx() }
    Box(
        modifier = Modifier
            .offset { IntOffset((rect.centerX - chipBoxWidth / 2f).toInt(), chipTop.toInt()) }
            .size(width = 160.dp, height = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.quick_face_tap_chip),
            color = Color.White,
            fontSize = 11.sp,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private val GuestToastMinFontSize = 10.sp

/** 얼굴을 탭한 뒤 홈 화면 하단 방송 버튼 위에 보이는 백그라운드 등록 진행 상황입니다. */
@Composable
internal fun GuestRegistrationToast(
    registrar: GuestFaceRegistrar,
    onReselect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = registrar.status ?: return
    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = 44.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            GuestFaceRegistrar.Status.Registering -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.guest_registering),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            is GuestFaceRegistrar.Status.Registered -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF66BB6A),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = status.notice?.asString() ?: stringResource(R.string.guest_registered),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            is GuestFaceRegistrar.Status.Failed -> {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color(0xFFFFA726),
                    modifier = Modifier.size(20.dp),
                )
                // 좁은 화면이나 큰 글꼴에서도 두 줄에 들어가도록 문구 크기를 폭에 맞춰 줄입니다.
                Text(
                    text = status.message.asString(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = GuestToastMinFontSize,
                        maxFontSize = MaterialTheme.typography.bodySmall.fontSize,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onClick = {
                        if (status.canRetry) {
                            registrar.retry()
                        } else {
                            registrar.dismiss()
                            onReselect()
                        }
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (status.canRetry) R.string.action_retry else R.string.action_reselect_face,
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                IconButton(onClick = registrar::dismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
