package com.framework.innolive.feature.live.tutorial

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import kotlin.math.min
import kotlin.math.roundToInt

enum class BroadcastTutorialAnchor {
    PRIMARY_BUTTON,
    PLATFORM_OPTIONS,
    CONNECT_ACCOUNT,
    START_PREPARATION,
    LIVE_STATUS,
}

/** 한 창 안에서 안내가 가리킬 요소의 위치와 현재 강조 대상을 모은다. */
class BroadcastTutorialAnchors {
    var activeAnchor by mutableStateOf<BroadcastTutorialAnchor?>(null)
    var highlightColor by mutableStateOf(Color.White)
    internal val bounds = mutableStateMapOf<BroadcastTutorialAnchor, Rect>()
}

val LocalBroadcastTutorialAnchors = staticCompositionLocalOf<BroadcastTutorialAnchors?> { null }

private val HighlightPadding = 6.dp
private val CalloutSpacing = 12.dp

/** 안내가 가리킬 화면 요소를 표시한다. 안내가 없는 화면에서는 아무 영향이 없다. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.broadcastTutorialAnchor(anchor: BroadcastTutorialAnchor): Modifier = composed {
    val anchors = LocalBroadcastTutorialAnchors.current ?: return@composed Modifier
    val requester = remember { BringIntoViewRequester() }
    val isActive = anchors.activeAnchor == anchor
    LaunchedEffect(isActive) {
        // Dialog가 스크롤되어 있어도 가리키는 요소가 화면에 보이게 한다.
        if (isActive) runCatching { requester.bringIntoView() }
    }
    DisposableEffect(anchors, anchor) {
        onDispose { anchors.bounds.remove(anchor) }
    }
    val color = anchors.highlightColor
    Modifier
        .bringIntoViewRequester(requester)
        .onGloballyPositioned { anchors.bounds[anchor] = it.boundsInWindow() }
        .drawWithContent {
            drawContent()
            if (isActive) {
                val padding = HighlightPadding.toPx()
                val radius = min(size.height / 2 + padding, 22.dp.toPx())
                drawRoundRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = Offset(-padding, -padding),
                    size = size.copy(width = size.width + padding * 2, height = size.height + padding * 2),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
}

@get:StringRes
val BroadcastTutorialStep.titleRes: Int
    get() = when (this) {
        BroadcastTutorialStep.OPEN_PREPARATION -> R.string.tutorial_open_preparation_title
        BroadcastTutorialStep.CHOOSE_PLATFORM -> R.string.tutorial_choose_platform_title
        BroadcastTutorialStep.CONNECT_ACCOUNT -> R.string.tutorial_connect_account_title
        BroadcastTutorialStep.START_PREPARATION -> R.string.tutorial_start_preparation_title
        BroadcastTutorialStep.WAIT_FOR_PREPARATION -> R.string.tutorial_wait_preparation_title
        BroadcastTutorialStep.RETRY_PREPARATION -> R.string.tutorial_retry_preparation_title
        BroadcastTutorialStep.GO_LIVE -> R.string.tutorial_go_live_title
    }

@get:StringRes
val BroadcastTutorialStep.messageRes: Int
    get() = when (this) {
        BroadcastTutorialStep.OPEN_PREPARATION -> R.string.tutorial_open_preparation_message
        BroadcastTutorialStep.CHOOSE_PLATFORM -> R.string.tutorial_choose_platform_message
        BroadcastTutorialStep.CONNECT_ACCOUNT -> R.string.tutorial_connect_account_message
        BroadcastTutorialStep.START_PREPARATION -> R.string.tutorial_start_preparation_message
        BroadcastTutorialStep.WAIT_FOR_PREPARATION -> R.string.tutorial_wait_preparation_message
        BroadcastTutorialStep.RETRY_PREPARATION -> R.string.tutorial_retry_preparation_message
        BroadcastTutorialStep.GO_LIVE -> R.string.tutorial_go_live_message
    }

fun BroadcastTutorialStep.anchor(host: BroadcastTutorialHost): BroadcastTutorialAnchor? = when (this) {
    BroadcastTutorialStep.OPEN_PREPARATION,
    BroadcastTutorialStep.GO_LIVE -> BroadcastTutorialAnchor.PRIMARY_BUTTON
    BroadcastTutorialStep.CHOOSE_PLATFORM -> BroadcastTutorialAnchor.PLATFORM_OPTIONS
    BroadcastTutorialStep.CONNECT_ACCOUNT -> BroadcastTutorialAnchor.CONNECT_ACCOUNT
    BroadcastTutorialStep.START_PREPARATION -> BroadcastTutorialAnchor.START_PREPARATION
    BroadcastTutorialStep.WAIT_FOR_PREPARATION ->
        if (host == BroadcastTutorialHost.HOME) BroadcastTutorialAnchor.PRIMARY_BUTTON else null
    BroadcastTutorialStep.RETRY_PREPARATION ->
        if (host == BroadcastTutorialHost.HOME) BroadcastTutorialAnchor.PRIMARY_BUTTON
        else BroadcastTutorialAnchor.START_PREPARATION
}

/** Dialog 안내에 필요한 값. 해당 Dialog가 지금 단계의 화면이 아니면 null이다. */
data class BroadcastTutorialGuide(
    val step: BroadcastTutorialStep,
    val host: BroadcastTutorialHost,
    val progress: BroadcastTutorialProgress?,
    val onSkip: () -> Unit,
)

fun BroadcastTutorialCoordinator?.guideFor(host: BroadcastTutorialHost): BroadcastTutorialGuide? {
    val coordinator = this ?: return null
    val stage = coordinator.stage?.takeIf { it.host == host } ?: return null
    return BroadcastTutorialGuide(stage.step, host, coordinator.progress, coordinator::skip)
}

/**
 * Dialog는 창 크기가 내용에 맞춰져 말풍선을 띄울 자리가 없다.
 * 안내를 Dialog 맨 위에 고정하고, 가리키는 요소는 강조 테두리로 표시한다.
 */
@Composable
fun BroadcastTutorialDialogFrame(
    guide: BroadcastTutorialGuide?,
    content: @Composable () -> Unit,
) {
    if (guide == null) {
        content()
        return
    }
    val anchors = remember { BroadcastTutorialAnchors() }
    val highlightColor = MaterialTheme.colorScheme.primary
    SideEffect {
        anchors.activeAnchor = guide.step.anchor(guide.host)
        anchors.highlightColor = highlightColor
    }
    CompositionLocalProvider(LocalBroadcastTutorialAnchors provides anchors) {
        Column {
            BroadcastTutorialCallout(
                progress = guide.progress,
                title = stringResource(guide.step.titleRes),
                message = stringResource(guide.step.messageRes),
                onSkip = guide.onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                elevated = false,
            )
            Box(modifier = Modifier.weight(1f, fill = false)) { content() }
        }
    }
}

/**
 * 홈 화면 위에 그리는 안내. 준비 안내는 화면을 어둡게 하고 가리키는 버튼과 말풍선만 누를 수 있게 한다.
 * 방송 중 상태 패널 안내는 종료·일시 중지를 바로 누를 수 있도록 화면을 가리지 않는다.
 */
@Composable
fun BroadcastTutorialHomeOverlay(
    tutorial: BroadcastTutorialCoordinator,
    anchors: BroadcastTutorialAnchors,
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val stage = tutorial.stage
    val guideStep = stage?.step?.takeIf { stage.host == BroadcastTutorialHost.HOME && isEnabled }
    val showsLiveStatusTip = guideStep == null && isEnabled && tutorial.isShowingLiveStatusTip
    val activeAnchor = when {
        guideStep != null -> guideStep.anchor(BroadcastTutorialHost.HOME)
        showsLiveStatusTip -> BroadcastTutorialAnchor.LIVE_STATUS
        else -> null
    }
    SideEffect {
        anchors.activeAnchor = activeAnchor
        anchors.highlightColor = Color.White
    }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { hostOrigin = it.positionInWindow() },
    ) {
        val target = activeAnchor?.let { anchors.bounds[it] }?.translate(-hostOrigin)
        AnimatedVisibility(visible = guideStep != null, enter = fadeIn(), exit = fadeOut()) {
            val step = guideStep ?: return@AnimatedVisibility
            HomeGuideLayer(
                target = target,
                dimsBackground = true,
                callout = {
                    BroadcastTutorialCallout(
                        progress = tutorial.progress,
                        title = stringResource(step.titleRes),
                        message = stringResource(step.messageRes),
                        onSkip = if (step == BroadcastTutorialStep.GO_LIVE) null else tutorial::skip,
                        primaryTitle = if (step == BroadcastTutorialStep.GO_LIVE) {
                            stringResource(R.string.tutorial_got_it)
                        } else null,
                        onPrimary = if (step == BroadcastTutorialStep.GO_LIVE) tutorial::finish else null,
                    )
                },
            )
        }
        AnimatedVisibility(visible = showsLiveStatusTip, enter = fadeIn(), exit = fadeOut()) {
            HomeGuideLayer(
                target = target,
                dimsBackground = false,
                callout = {
                    BroadcastTutorialCallout(
                        progress = null,
                        title = stringResource(R.string.tutorial_live_status_title),
                        message = stringResource(R.string.tutorial_live_status_message),
                        primaryTitle = stringResource(R.string.tutorial_got_it),
                        onPrimary = tutorial::dismissLiveStatusTip,
                    )
                },
            )
        }
    }
}

@Composable
private fun HomeGuideLayer(
    target: Rect?,
    dimsBackground: Boolean,
    callout: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var layerSize by remember { mutableStateOf(Size.Zero) }
    val bounds = Rect(Offset.Zero, layerSize)
    val visibleTarget = target?.takeIf { layerSize != Size.Zero && it.overlaps(bounds) }
    val highlight = visibleTarget?.inflate(with(density) { HighlightPadding.toPx() })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { layerSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) },
    ) {
        if (dimsBackground) {
            val radius = highlight?.let { min(it.height / 2, with(density) { 22.dp.toPx() }) } ?: 0f
            Canvas(modifier = Modifier.fillMaxSize()) {
                val path = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    highlight?.let { addRoundRect(RoundRect(it, CornerRadius(radius, radius))) }
                }
                drawPath(path, Color.Black.copy(alpha = 0.45f))
            }
            // 강조된 버튼 자리에는 막는 영역을 두지 않아 터치가 그대로 아래 버튼에 전달된다.
            TouchBlockers(highlight = highlight, layerSize = layerSize)
        }

        val calloutModifier = Modifier
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp)
        val spacing = with(density) { CalloutSpacing.toPx() }
        when {
            highlight != null && highlight.center.y > layerSize.height / 2 -> Box(
                modifier = calloutModifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = with(density) { (layerSize.height - highlight.top + spacing).toDp() }),
            ) { callout() }
            highlight != null -> Box(
                modifier = calloutModifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(density) { (highlight.bottom + spacing).toDp() }),
            ) { callout() }
            else -> Box(
                modifier = calloutModifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(bottom = 24.dp),
            ) { callout() }
        }
    }
}

@Composable
private fun TouchBlockers(highlight: Rect?, layerSize: Size) {
    val regions = if (highlight == null) {
        listOf(Rect(Offset.Zero, layerSize))
    } else {
        listOf(
            Rect(0f, 0f, layerSize.width, highlight.top),
            Rect(0f, highlight.bottom, layerSize.width, layerSize.height),
            Rect(0f, highlight.top, highlight.left, highlight.bottom),
            Rect(highlight.right, highlight.top, layerSize.width, highlight.bottom),
        )
    }
    val density = LocalDensity.current
    regions.filter { it.width > 0f && it.height > 0f }.forEach { region ->
        Box(
            modifier = Modifier
                .offset { IntOffset(region.left.roundToInt(), region.top.roundToInt()) }
                .size(with(density) { region.width.toDp() }, with(density) { region.height.toDp() })
                .pointerInput(Unit) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        )
    }
}

@Composable
internal fun BroadcastTutorialCallout(
    progress: BroadcastTutorialProgress?,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onSkip: (() -> Unit)? = null,
    primaryTitle: String? = null,
    onPrimary: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    elevated: Boolean = true,
) {
    Surface(
        modifier = modifier.widthIn(max = 360.dp),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (elevated) 8.dp else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (progress != null) {
                val description = stringResource(R.string.tutorial_progress_description, progress.index, progress.total)
                Text(
                    text = "${progress.index}/${progress.total}",
                    modifier = Modifier.semantics { contentDescription = description },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 단계가 바뀌면 TalkBack이 제목과 설명을 읽는다.
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onSkip != null || onPrimary != null) {
                val skipHint = stringResource(R.string.tutorial_skip_hint)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onSkip != null) {
                        TextButton(
                            onClick = onSkip,
                            modifier = Modifier.semantics { onClick(label = skipHint) { onSkip(); true } },
                        ) {
                            Text(stringResource(R.string.tutorial_skip))
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (primaryTitle != null && onPrimary != null) {
                        Button(onClick = onPrimary) { Text(primaryTitle) }
                    }
                }
            }
        }
    }
}
