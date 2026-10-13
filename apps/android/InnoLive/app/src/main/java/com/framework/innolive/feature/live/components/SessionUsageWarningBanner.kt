package com.framework.innolive.feature.live.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framework.innolive.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SessionUsageWarningBanner(
    noticeCode: String?,
    onDismissRequest: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val messageResource = noticeCode?.let(::sessionNoticeMessageResource)
    val density = LocalDensity.current
    val swipeDismissThresholdPx = with(density) { 48.dp.toPx() }
    val fastSwipeThresholdPx = with(density) { 800.dp.toPx() }
    val currentOnDismissRequest = rememberUpdatedState(onDismissRequest)
    val scope = rememberCoroutineScope()
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var bannerHeightPx by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val draggableState = rememberDraggableState { delta ->
        dragOffsetPx = (dragOffsetPx + delta).coerceIn(-bannerHeightPx, bannerHeightPx)
    }

    LaunchedEffect(noticeCode) {
        if (noticeCode != null) {
            settleJob?.cancel()
            dragOffsetPx = 0f
        }
    }

    AnimatedVisibility(
        visible = messageResource != null,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
    ) {
        Surface(
            modifier = Modifier
                .offset { IntOffset(0, dragOffsetPx.roundToInt()) }
                .fillMaxWidth()
                .heightIn(min = 116.dp)
                .onSizeChanged { bannerHeightPx = it.height.toFloat() }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStarted = { settleJob?.cancel() },
                    onDragStopped = { velocity ->
                        if (dragOffsetPx <= -swipeDismissThresholdPx || velocity <= -fastSwipeThresholdPx) {
                            noticeCode?.let(currentOnDismissRequest.value)
                        } else {
                            val releaseOffset = dragOffsetPx
                            settleJob = scope.launch {
                                Animatable(releaseOffset).animateTo(0f, spring()) {
                                    dragOffsetPx = value
                                }
                            }
                        }
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    dismiss {
                        noticeCode?.let(onDismissRequest)
                        true
                    }
                },
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = 0.9f),
            contentColor = Color.Black,
        ) {
            Text(
                text = stringResource(checkNotNull(messageResource)),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.sp,
                ),
            )
        }
    }
}

@StringRes
internal fun sessionNoticeMessageResource(code: String): Int? = when (code) {
    "broadcast_limit_30m" -> R.string.session_notice_broadcast_limit_30m
    "broadcast_limit_10m" -> R.string.session_notice_broadcast_limit_10m
    "broadcast_limit_reached" -> R.string.session_notice_broadcast_limit_reached
    "monthly_usage_80" -> R.string.session_notice_monthly_usage_80
    "monthly_usage_100" -> R.string.session_notice_monthly_usage_100
    "monthly_limit_reached" -> R.string.session_notice_monthly_limit_reached
    "no_input_stopped" -> R.string.session_notice_no_input_stopped
    "channel_live_elsewhere" -> R.string.session_notice_channel_live_elsewhere
    "platform_broadcast_ended" -> R.string.session_notice_platform_broadcast_ended
    "youtube_quota_low" -> R.string.session_notice_youtube_quota_low
    else -> null
}
