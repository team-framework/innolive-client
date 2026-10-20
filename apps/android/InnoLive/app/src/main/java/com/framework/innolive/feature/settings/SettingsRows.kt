package com.framework.innolive.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.live.ProfileDisplay

/** 구역 이름은 본문보다 작지만 흐린 회색 대신 굵기를 줘서 훑어볼 때 구역이 먼저 눈에 띄게 합니다. */
@Composable
internal fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/** iOS 설정 첫 화면처럼 어두운 카드에 구역 이름과 그 구역의 줄을 함께 묶습니다. 미리보기 위에서도 흰 글자가 읽히게 거의 불투명하게 칠합니다. */
@Composable
internal fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = SettingsSectionCardShape,
        color = SettingsSectionCardColor,
        contentColor = Color.White,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
            )
            content()
        }
    }
}

/** 카드 안에서 하위 설정 화면으로 들어가는 줄입니다. 아이콘·이름·화살표를 iOS와 같은 순서로 둡니다. */
@Composable
internal fun SettingsNavigationRow(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = alpha),
            modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.65f * alpha),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 설정 첫 화면에서 들어간 하위 화면의 공통 틀입니다. 뒤로 가기 단추와 제목 아래에 내용을 세로로 넘겨 봅니다. */
@Composable
internal fun SettingsSubpage(
    title: String,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() })
            actions()
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

private val SettingsSectionCardShape = RoundedCornerShape(20.dp)
private val SettingsSectionCardColor = Color(0xFF1E1E1E).copy(alpha = 0.94f)

/** 이름은 진하게, 현재 값은 회색으로 나눠 두 정보를 한눈에 구분하고, 화살표로 누르면 바꿀 수 있음을 알립니다. */
@Composable
internal fun SettingsValueRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        // 긴 외부 기기 이름은 두 줄까지만 보여 줍니다.
        Text(value, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        SettingsChevron()
    }
}

/** 다른 화면이나 동작으로 이어지는 줄입니다. 글자 버튼 대신 다른 줄과 같은 폭·높이로 맞춥니다. */
@Composable
internal fun SettingsLinkRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: Color = MaterialTheme.colorScheme.onSurface,
    showChevron: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) color else color.copy(alpha = 0.38f),
            modifier = Modifier.weight(1f),
        )
        if (showChevron) SettingsChevron()
    }
}

/** 프로필 전체를 누르는 줄입니다. 화살표로 계정 설정 화면이 이어짐을 알립니다. */
@Composable
internal fun SettingsAccountRow(name: String, email: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(
                onClickLabel = stringResource(R.string.settings_account),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) { ProfileDisplay(name = name, email = email) }
        SettingsChevron()
    }
}

/** 눌러서 아래 내용을 펼치고 접는 줄입니다. 화살표가 돌아 지금 상태를 보여 줍니다. */
@Composable
internal fun SettingsAccordionHeader(label: String, expanded: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) R.string.settings_expanded else R.string.settings_collapsed)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "accordionChevron")
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp).rotate(rotation),
        )
    }
}

@Composable
private fun SettingsChevron() {
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}
