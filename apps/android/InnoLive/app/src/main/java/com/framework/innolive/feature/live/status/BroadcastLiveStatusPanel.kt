package com.framework.innolive.feature.live.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.framework.innolive.feature.live.SessionTarget

private val PanelBackground = Color.Black.copy(alpha = 0.55f)
private val ChipBackground = Color.White.copy(alpha = 0.16f)
private val SecondaryText = Color(0xFFD0D0D0)
private val AttentionColor = Color(0xFFFFB020)

/** 방송 중 플랫폼별 송출 상태와 앱의 업로드 품질을 한곳에 보여 준다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BroadcastLiveStatusPanel(
    targets: List<SessionTarget>,
    broadcastResolution: String?,
    uplinkQuality: BroadcastUplinkQuality?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(PanelBackground, RoundedCornerShape(14.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        targets.forEach { target -> TargetRow(target) }

        val chips = detailChips(broadcastResolution, uplinkQuality)
        if (chips.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                chips.forEach { chip -> StatusChip(chip) }
            }
        }

        uplinkQuality?.limitation?.let { limitation ->
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = AttentionColor,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(limitation.warningRes),
                    color = AttentionColor,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun TargetRow(target: SessionTarget) {
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
            text = stringResource(target.statusLabelRes),
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
        // 업로드 화질("720p")과 같은 표기로 맞춘다.
        val resolution = broadcastResolution.lowercase()
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
