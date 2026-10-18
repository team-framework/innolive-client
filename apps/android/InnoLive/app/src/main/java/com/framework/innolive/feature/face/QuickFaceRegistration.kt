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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraFrameAnalyzer
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
    data class Detecting(@StringRes val message: Int) : FaceSelectionPhase
    data class Failed(@StringRes val message: Int) : FaceSelectionPhase
}

/**
 * 홈 화면 원탭 등록이 고를 수 있는 얼굴을 원본 카메라에서 찾습니다. 탭한 얼굴은 [GuestFaceRegistrar]가
 * 등록하므로 선택은 바로 끝낼 수 있습니다. 설정의 얼굴 등록 화면은 그대로 `FaceDetectionPipeline`을 씁니다.
 */
internal class QuickFaceSelectionModel {
    var phase by mutableStateOf<FaceSelectionPhase>(FaceSelectionPhase.Preparing)
        private set
    var faces by mutableStateOf<List<FaceSelectionCandidate>>(emptyList())
        private set
    var frameWidth by mutableIntStateOf(0)
        private set
    var frameHeight by mutableIntStateOf(0)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val detector = FaceSelectionDetector()
    private var source: FaceSelectionFrameSource? = null
    private var latest: FaceSelectionFrame? = null
    // 변환 스레드가 오래된 프레임을 버릴 수 있도록 세대 번호를 공유합니다.
    @Volatile private var generation = 0

    fun start(analyzer: CameraFrameAnalyzer) {
        stop()
        generation += 1
        val current = generation
        phase = FaceSelectionPhase.Preparing
        source = FaceSelectionFrameSource(
            analyzer = analyzer,
            onFrame = { bitmap ->
                if (current != generation || !detector.detect(bitmap, { frame -> handle(current, frame, bitmap) })) {
                    bitmap.recycle()
                }
            },
            onError = {
                mainHandler.post {
                    if (current != generation) return@post
                    stopSource()
                    clearFaces()
                    phase = FaceSelectionPhase.Failed(R.string.quick_face_camera_failed)
                }
            },
        ).also { it.start() }
    }

    fun stop() {
        generation += 1
        stopSource()
        clearFaces()
        phase = FaceSelectionPhase.Preparing
    }

    fun close() {
        stop()
        detector.close()
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

    private fun handle(current: Int, frame: FaceSelectionFrame?, bitmap: android.graphics.Bitmap) {
        mainHandler.post {
            if (frame == null || current != generation || phase is FaceSelectionPhase.Failed) {
                bitmap.recycle()
                return@post
            }
            latest?.bitmap?.recycle()
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
    }

    private fun stopSource() {
        source?.stop()
        source = null
    }

    private fun clearFaces() {
        latest?.bitmap?.recycle()
        latest = null
        faces = emptyList()
    }
}

/**
 * 선택 중 홈 화면 메인 영역 위에 놓입니다. 원본 카메라 위에 얼굴 윤곽을 그리고, 탭하면 [onSelect]에 프레임을
 * 넘긴 뒤 선택이 끝납니다. 등록은 다른 곳에서 이어집니다.
 */
@Composable
internal fun FaceSelectionLayer(
    analyzer: CameraFrameAnalyzer,
    mirrored: Boolean,
    onSelect: (FaceSelectionFrame, Int) -> Unit,
    modifier: Modifier = Modifier,
    statusTopPadding: Dp = 96.dp,
) {
    val model = remember { QuickFaceSelectionModel() }
    DisposableEffect(model) {
        onDispose { model.close() }
    }
    DisposableEffect(model, analyzer) {
        model.start(analyzer)
        onDispose { model.stop() }
    }

    Box(modifier = modifier) {
        if (model.phase is FaceSelectionPhase.Detecting) {
            FaceSelectionOverlay(
                faces = model.faces,
                imageWidth = model.frameWidth,
                imageHeight = model.frameHeight,
                mirrored = mirrored,
                onSelect = { index -> model.takeFrameForSelection(index)?.let { onSelect(it, index) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = 24.dp, end = 24.dp, top = statusTopPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FaceSelectionStatus(phase = model.phase)
            if (model.phase is FaceSelectionPhase.Failed) {
                Button(
                    onClick = { model.start(analyzer) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
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

/** 원본 카메라 위의 실시간 윤곽입니다. 등록할 수 있을 만큼 큰 얼굴만 그립니다. */
@Composable
internal fun FaceSelectionOverlay(
    faces: List<FaceSelectionCandidate>,
    imageWidth: Int,
    imageHeight: Int,
    mirrored: Boolean,
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
            val rect = FaceSelectionGeometry.displayRect(
                normalized = FaceSelectionGeometry.scaled(face.rect, FaceSelectionGeometry.OUTLINE_SCALE),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                mirrored = mirrored,
            )
            index to rect
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            outlines.forEach { (_, rect) ->
                drawOval(
                    color = Color.Black.copy(alpha = 0.45f * outlineAlpha),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = strokeWidth + 2f),
                )
                drawOval(
                    color = Color.White.copy(alpha = outlineAlpha),
                    topLeft = Offset(rect.left, rect.top),
                    size = Size(rect.width, rect.height),
                    style = Stroke(width = strokeWidth),
                )
            }
        }

        outlines.forEach { (index, rect) ->
            FaceSelectionTarget(
                index = index,
                rect = rect,
                minimumTarget = minimumTarget,
                viewHeight = viewHeight,
                onSelect = onSelect,
            )
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

/** 얼굴을 탭한 뒤 홈 화면 하단에 보이는 백그라운드 등록 진행 상황입니다. */
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
            GuestFaceRegistrar.Status.Registered -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF66BB6A),
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.guest_registered),
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
                Text(
                    text = status.message.asString(),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
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
