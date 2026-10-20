package com.framework.innolive.feature.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
