package com.framework.innolive.feature.live.status

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.direct.broadcast.BroadcastTargetRow
import com.framework.innolive.feature.live.direct.broadcast.DirectStatusWarning
import com.framework.innolive.ui.text.asString

private val PanelBackground = Color.Black.copy(alpha = 0.55f)
private val ChipBackground = Color.White.copy(alpha = 0.16f)
private val SecondaryText = Color(0xFFD0D0D0)
private val AttentionColor = Color(0xFFFFB020)

/**
 * 방송 중 플랫폼별 송출 상태와 앱의 업로드 품질을 한곳에 보여 준다.
 * 직접 송출이면 [warnings]에 대기 화면·마이크·발열·준비 마감 안내가 온다.
 * [onClick]이 있으면 방송 전 구성 변경 진입점으로 쓴다.
 */
@Composable
internal fun BroadcastLiveStatusPanel(
    targets: List<BroadcastTargetRow>,
    broadcastResolution: String?,
    uplinkQuality: BroadcastUplinkQuality?,
    modifier: Modifier = Modifier,
    warnings: List<DirectStatusWarning> = emptyList(),
    onClickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(PanelBackground, shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .animateContentSize()
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        targets.forEach { target -> TargetRow(target) }

        val chips = detailChips(broadcastResolution, uplinkQuality)
        if (chips.isNotEmpty()) {
            // 화질 저하 사유는 패널 높이가 바뀌지 않도록 칩 줄 오른쪽 끝에 짧게 적는다.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { chip -> StatusChip(chip) }
                Spacer(modifier = Modifier.weight(1f))
                uplinkQuality?.limitation?.let { limitation -> LimitationLabel(limitation) }
            }
        }

        warnings.forEach { warning ->
            WarningRow(warning.text.asString(), if (warning.urgent) AttentionColor else SecondaryText)
        }
    }
}

@Composable
private fun LimitationLabel(limitation: BroadcastUplinkLimitation) {
    val description = stringResource(limitation.warningRes)
    Text(
        text = stringResource(limitation.shortLabelRes),
        color = AttentionColor,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = description
        },
    )
}

@Composable
private fun WarningRow(text: String, color: Color) {
    Row(
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(text = text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun TargetRow(target: BroadcastTargetRow) {
    val tone = target.tone
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(tone.dotColor, CircleShape),
        )
        Text(
            text = providerTitle(target.provider),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(target.labelRes),
            color = if (tone == BroadcastTargetTone.ATTENTION) AttentionColor else SecondaryText,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private data class StatusChipModel(
    val text: String,
    val icon: ImageVector,
    val description: String,
)

@Composable
private fun detailChips(
    broadcastResolution: String?,
    uplinkQuality: BroadcastUplinkQuality?,
): List<StatusChipModel> = buildList {
    if (broadcastResolution != null) {
        val resolution = broadcastResolutionLabel(broadcastResolution)
        add(
            StatusChipModel(
                text = resolution,
                icon = Icons.Outlined.Tv,
                description = stringResource(R.string.live_status_broadcast_resolution, resolution),
            ),
        )
    }
    if (uplinkQuality != null) {
        val text = uplinkQuality.summary ?: stringResource(R.string.live_status_uplink_measuring)
        add(
            StatusChipModel(
                text = text,
                icon = Icons.Outlined.ArrowUpward,
                description = stringResource(R.string.live_status_uplink, text),
            ),
        )
    }
}

/** 업로드 화질("720p")과 같은 표기로 맞추고, 서버 값 `fhd`는 화질 선택 창과 같은 `FHD`로 보여 줍니다. */
internal fun broadcastResolutionLabel(value: String): String {
    val resolution = value.lowercase()
    return if (resolution == "fhd") "FHD" else resolution
}

@Composable
private fun StatusChip(chip: StatusChipModel) {
    Row(
        modifier = Modifier
            .background(ChipBackground, CircleShape)
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = chip.description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(chip.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text(
            text = chip.text,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun providerTitle(provider: String): String = when (provider) {
    "youtube" -> "YouTube"
    "chzzk" -> "CHZZK"
    else -> provider
}

private val BroadcastTargetTone.dotColor: Color
    get() = when (this) {
        BroadcastTargetTone.LIVE -> Color(0xFFFF3B30)
        BroadcastTargetTone.READY -> Color(0xFF34C759)
        BroadcastTargetTone.ATTENTION -> AttentionColor
        BroadcastTargetTone.PROGRESS, BroadcastTargetTone.ENDED -> Color(0xFF9A9A9A)
    }

private val BroadcastUplinkLimitation.warningRes: Int
    get() = when (this) {
        BroadcastUplinkLimitation.NETWORK -> R.string.live_status_warning_network
        BroadcastUplinkLimitation.DEVICE -> R.string.live_status_warning_device
    }

private val BroadcastUplinkLimitation.shortLabelRes: Int
    get() = when (this) {
        BroadcastUplinkLimitation.NETWORK -> R.string.live_status_limit_network
        BroadcastUplinkLimitation.DEVICE -> R.string.live_status_limit_device
    }
