package com.framework.innolive.feature.live.components

import com.framework.innolive.ui.theme.ModalSurface
import com.framework.innolive.ui.theme.InnoLiveDialog
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchor
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialDialogFrame
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialGuide
import com.framework.innolive.feature.live.tutorial.broadcastTutorialAnchor

/** 방송 설정 메뉴의 한 줄입니다. [summary]는 현재 상태(계정, 구성 등)를 짧게 보여 줍니다. */
internal class BroadcastSettingsMenuItem(
    val title: String,
    val summary: String,
    val onClick: () -> Unit,
    /** 방송 안내가 계정 연결을 가리킬 때 이 줄을 강조합니다. */
    val highlightsAccount: Boolean = false,
    /** 플랫폼 로고입니다. 없으면 [iconVector]를 씁니다. */
    @param:DrawableRes val iconRes: Int? = null,
    val iconVector: ImageVector? = null,
)

/** 방송 설정 메뉴 맨 위의 안내입니다. [onClick]이 있으면 눌러 해결할 화면으로 갑니다. */
internal class BroadcastSettingsNotice(
    val title: String,
    val message: String,
    val onClick: (() -> Unit)? = null,
)

/**
 * 방송 준비 버튼이 여는 방송 설정 메뉴입니다. YouTube·치지직·화질 및 플랫폼을 고르면
 * 각 기존 설정 창이 열리고, 닫으면 이 메뉴로 돌아옵니다. 방송 준비는 이 메뉴에서만 시작합니다.
 */
@Composable
internal fun BroadcastSettingsMenu(
    items: List<BroadcastSettingsMenuItem>,
    canPrepare: Boolean,
    onPrepare: () -> Unit,
    onDismiss: () -> Unit,
    guide: BroadcastTutorialGuide? = null,
    notice: BroadcastSettingsNotice? = null,
) {
    InnoLiveDialog(onDismissRequest = onDismiss) {
        ModalSurface(modifier = Modifier.cappedDialogWidth(400.dp)) {
            BroadcastTutorialDialogFrame(guide) {
                // 화면이 낮아도 방송 준비 버튼은 항상 보이도록 목록만 스크롤하고 버튼은 아래에 고정합니다.
                Column(modifier = Modifier.padding(vertical = 16.dp)) {
                    BroadcastDialogHeader(
                        title = stringResource(R.string.broadcast_settings_title),
                        onClose = onDismiss,
                        modifier = Modifier.padding(start = 20.dp, end = 8.dp),
                    )
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        notice?.let { BroadcastSettingsNoticeRow(it) }
                        items.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (item.highlightsAccount) {
                                            Modifier.broadcastTutorialAnchor(BroadcastTutorialAnchor.CONNECT_ACCOUNT)
                                        } else Modifier,
                                    )
                                    .clickable(role = Role.Button, onClick = item.onClick)
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                when {
                                    item.iconRes != null -> Image(
                                        painter = painterResource(item.iconRes),
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    item.iconVector != null -> Icon(
                                        item.iconVector,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyLarge)
                                    // 계정 확인 전후로 문구 길이가 바뀌어도 메뉴 높이가 변하지 않게 한 줄로 고정합니다.
                                    Text(
                                        item.summary,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                            }
                        }
                    }
                    Button(
                        onClick = onPrepare,
                        enabled = canPrepare,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
                            .broadcastTutorialAnchor(BroadcastTutorialAnchor.START_PREPARATION),
                    ) {
                        Text(stringResource(R.string.action_prepare_broadcast))
                    }
                }
            }
        }
    }
}

@Composable
private fun BroadcastSettingsNoticeRow(notice: BroadcastSettingsNotice) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                notice.onClick?.let { onClick -> Modifier.clickable(role = Role.Button, onClick = onClick) }
                    ?: Modifier,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(notice.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            Text(
                notice.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (notice.onClick != null) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
}
