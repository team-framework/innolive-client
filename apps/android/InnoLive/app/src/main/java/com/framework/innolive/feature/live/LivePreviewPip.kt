package com.framework.innolive.feature.live

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.framework.innolive.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 작은 미리보기 창이 놓일 수 있는 범위입니다. 좌표는 방송 화면 왼쪽 위 기준 픽셀입니다. */
internal data class LivePreviewPipArea(
    val size: Size,
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
) {
    val centerX get() = (minX + maxX + size.width) / 2f

    fun clamp(offset: Offset) = Offset(offset.x.coerceIn(minX, maxX), offset.y.coerceIn(minY, maxY))

    companion object {
        val Empty = LivePreviewPipArea(Size.Zero, 0f, 0f, 0f, 0f)
    }
}

/**
 * 작은 미리보기 창의 위치입니다. 끄는 동안은 손가락을 따라가고, 놓으면 가까운 좌우 가장자리에 붙습니다.
 * 세로 위치는 놓은 자리를 비율로 기억해 화면 회전 뒤에도 비슷한 높이에 둡니다.
 */
@Stable
class LivePreviewPipState internal constructor(alignEnd: Boolean, verticalFraction: Float) {
    var alignEnd by mutableStateOf(alignEnd)
        private set
    var verticalFraction by mutableFloatStateOf(verticalFraction)
        private set
    internal var area by mutableStateOf(LivePreviewPipArea.Empty)
    internal var isVisible by mutableStateOf(false)
    /** 기본 화면 하단의 방송 버튼과 상태 패널이 실제로 차지하는 높이입니다. 글자 크기와 안내에 따라 달라집니다. */
    internal var bottomControlsHeightPx by mutableFloatStateOf(0f)
    /** 가로 화면에서 오른쪽 열에 놓인 방송 버튼 묶음의 폭입니다. 세로 화면에서는 0입니다. */
    internal var rightControlsWidthPx by mutableFloatStateOf(0f)

    /** 작은 창과 카메라 도구가 비워 둘 하단 높이입니다. 측정 전에는 기본 높이를 씁니다. */
    internal fun bottomReservePx(density: Density): Float =
        if (bottomControlsHeightPx > 0f) bottomControlsHeightPx
        else with(density) { LiveBottomControlsReserve.toPx() }
    private var dragOffset by mutableStateOf<Offset?>(null)
    private var settlingOffset by mutableStateOf<Offset?>(null)
    private val settle = Animatable(Offset.Zero, Offset.VectorConverter)
    private var moveCount = 0

    internal val offset: Offset get() = dragOffset ?: settlingOffset ?: restingOffset()

    internal val bounds: Rect get() = Rect(offset, area.size)

    /** 끌기를 마치고 가장자리에 붙었을 때의 자리입니다. 다른 버튼은 이 자리를 피해 움직입니다. */
    internal val restingBounds: Rect get() = Rect(restingOffset(), area.size)

    private fun restingOffset() = Offset(
        x = if (alignEnd) area.maxX else area.minX,
        y = area.minY + (area.maxY - area.minY) * verticalFraction,
    )

    internal fun dragBy(delta: Offset) {
        moveCount++
        dragOffset = area.clamp((dragOffset ?: offset) + delta)
    }

    internal suspend fun release() {
        val from = dragOffset ?: return
        alignEnd = from.x + area.size.width / 2f > area.centerX
        val range = area.maxY - area.minY
        verticalFraction = if (range > 0f) ((from.y - area.minY) / range).coerceIn(0f, 1f) else 0f
        dragOffset = null
        moveFrom(from)
    }

    internal suspend fun moveTo(alignEnd: Boolean, verticalFraction: Float) {
        val from = offset
        this.alignEnd = alignEnd
        this.verticalFraction = verticalFraction
        moveFrom(from)
    }

    private suspend fun moveFrom(from: Offset) {
        // 붙는 중에 다시 옮기면 이전 움직임이 끝나며 위치를 지우지 않도록 마지막 움직임만 정리합니다.
        val move = ++moveCount
        settlingOffset = from
        try {
            settle.snapTo(from)
            settle.animateTo(restingOffset(), spring(stiffness = Spring.StiffnessMediumLow)) {
                settlingOffset = value
            }
        } finally {
            if (move == moveCount) settlingOffset = null
        }
    }

    companion object {
        val Saver = listSaver<LivePreviewPipState, Any>(
            save = { listOf(it.alignEnd, it.verticalFraction) },
            restore = { LivePreviewPipState(it[0] as Boolean, it[1] as Float) },
        )
    }
}

/** 하단 방송 버튼과 상태 패널 높이를 재기 전에 비워 둘 기본 높이입니다. */
internal val LiveBottomControlsReserve = 168.dp

/** 카메라 도구를 원래 자리에서 옮길 거리입니다. [shiftLeft]만큼 왼쪽으로, [top] 높이에 둡니다. */
internal data class CameraToolsPlacement(val top: Float, val shiftLeft: Float = 0f)

/**
 * 카메라 도구가 오른쪽에 붙은 작은 창과 겹치지 않을 자리입니다. 겹치지 않으면 원래 자리에 두고,
 * 창 아래에 공간이 있으면 아래로 내리고, 없으면 같은 높이에서 창 왼쪽으로 비켜 둡니다.
 * 좌표는 모두 방송 화면 왼쪽 위 기준 픽셀이고, [toolsRight]는 원래 자리의 오른쪽 끝입니다.
 */
internal fun cameraToolsPlacement(
    naturalTop: Float,
    toolsHeight: Float,
    toolsRight: Float,
    maxTop: Float,
    pipBounds: Rect?,
    gap: Float,
): CameraToolsPlacement {
    if (pipBounds == null || pipBounds.top >= naturalTop + toolsHeight || pipBounds.bottom <= naturalTop) {
        return CameraToolsPlacement(naturalTop)
    }
    val below = pipBounds.bottom + gap
    if (below <= maxTop) return CameraToolsPlacement(below)
    return CameraToolsPlacement(naturalTop, shiftLeft = (toolsRight - (pipBounds.left - gap)).coerceAtLeast(0f))
}

/** 처음에는 지금처럼 상단 바 아래 왼쪽에 둡니다. */
@Composable
fun rememberLivePreviewPipState(): LivePreviewPipState =
    rememberSaveable(saver = LivePreviewPipState.Saver) { LivePreviewPipState(alignEnd = false, verticalFraction = 0f) }

/**
 * 작은 미리보기 창 위에서 시작한 끌기만 가로채 창을 옮깁니다.
 * 영상은 넘기는 화면 아래에 그려지므로 화면 전체에서 먼저(Initial) 받아, 끌기가 시작되면 페이지 넘김과 버튼 누름을 막습니다.
 * 그냥 누르면 아래의 버튼이 그대로 받습니다.
 */
internal fun Modifier.livePreviewPipDrag(
    state: LivePreviewPipState,
    enabled: Boolean,
    scope: CoroutineScope,
): Modifier =
    if (!enabled) this else pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!state.isVisible || !state.bounds.contains(down.position)) return@awaitEachGesture
            var dragging = false
            var travelled = Offset.Zero
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                if (!dragging) {
                    travelled += delta
                    dragging = travelled.getDistance() > viewConfiguration.touchSlop
                }
                if (dragging) {
                    state.dragBy(delta)
                    change.consume()
                }
            }
            // 손을 떼면 가까운 가장자리로 붙입니다.
            if (dragging) scope.launch { state.release() }
        }
    }

/**
 * 화면 낭독기에서 작은 미리보기 창을 찾고 옮길 수 있게 같은 자리에 의미 정보만 둡니다.
 * 누름은 받지 않으므로 아래 화면의 터치를 막지 않습니다.
 */
@Composable
internal fun LivePreviewPipAccessibility(state: LivePreviewPipState) {
    if (!state.isVisible) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val description = stringResource(R.string.live_pip_description)
    val moveSide = stringResource(R.string.live_pip_move_side)
    val moveVertical = stringResource(R.string.live_pip_move_vertical)
    val size = with(density) { DpSize(state.area.size.width.toDp(), state.area.size.height.toDp()) }
    Box(
        Modifier
            .offset { state.offset.round() }
            .size(size)
            .semantics {
                contentDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(moveSide) {
                        scope.launch { state.moveTo(!state.alignEnd, state.verticalFraction) }
                        true
                    },
                    CustomAccessibilityAction(moveVertical) {
                        scope.launch { state.moveTo(state.alignEnd, if (state.verticalFraction < 0.5f) 1f else 0f) }
                        true
                    },
                )
            },
    )
}
