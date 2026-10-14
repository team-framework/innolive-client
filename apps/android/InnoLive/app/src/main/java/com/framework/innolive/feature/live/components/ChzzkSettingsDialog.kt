package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ChzzkCategory
import com.framework.innolive.feature.live.validationField
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchor
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialDialogFrame
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialGuide
import com.framework.innolive.feature.live.tutorial.broadcastTutorialAnchor
import kotlinx.coroutines.launch

@Composable
internal fun ChzzkSettingsDialog(
    settings: ChzzkBroadcastSettings,
    accountLabel: String,
    canPrepare: Boolean,
    canConnect: Boolean,
    canDisconnect: Boolean,
    isBusy: Boolean,
    onChanged: (ChzzkBroadcastSettings) -> Unit,
    onRefreshAccount: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSearch: suspend (String) -> List<ChzzkCategory>,
    /** null이면 방송 설정 메뉴에서 연 편집 창이라 준비 버튼 없이 닫기만 둡니다. */
    onPrepare: (() -> Unit)?,
    onDismiss: () -> Unit,
    onChangePlatform: (() -> Unit)? = null,
    guide: BroadcastTutorialGuide? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.heightIn(max = 700.dp)) {
            BroadcastTutorialDialogFrame(guide) {
                Column(
                    Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BroadcastDialogHeader(
                        title = stringResource(R.string.chzzk_settings_title),
                        onClose = onDismiss,
                    )
                    onChangePlatform?.let { change ->
                        TextButton(onClick = change, enabled = !isBusy) { Text(stringResource(R.string.action_change_platform)) }
                    }
                    ChzzkSettingsFields(
                        settings = settings,
                        accountLabel = accountLabel,
                        canConnect = canConnect,
                        canDisconnect = canDisconnect,
                        isBusy = isBusy,
                        onChanged = onChanged,
                        onRefreshAccount = onRefreshAccount,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onSearch = onSearch,
                    )
                    val invalidField = settings.validationField()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        onPrepare?.let { prepare ->
                            Button(
                                onClick = prepare,
                                enabled = canPrepare && !isBusy && invalidField == null,
                                modifier = Modifier
                                    .weight(1f)
                                    .broadcastTutorialAnchor(BroadcastTutorialAnchor.START_PREPARATION),
                            ) {
                                Text(stringResource(R.string.action_prepare_broadcast))
                            }
                        }
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.direct_mode_done))
                        }
                    }
                }
            }
        }
    }
}

/** 치지직 방송 설정 입력 항목입니다. 설정 창과 통합 방송 설정 창이 함께 씁니다. */
@Composable
internal fun ChzzkSettingsFields(
    settings: ChzzkBroadcastSettings,
    accountLabel: String,
    canConnect: Boolean,
    canDisconnect: Boolean,
    isBusy: Boolean,
    onChanged: (ChzzkBroadcastSettings) -> Unit,
    onRefreshAccount: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSearch: suspend (String) -> List<ChzzkCategory>,
) {
    var query by remember { mutableStateOf("") }
    var categories by remember { mutableStateOf(emptyList<ChzzkCategory>()) }
    var message by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(accountLabel)
        TextButton(onClick = onRefreshAccount, enabled = canConnect && !isBusy) { Text(stringResource(R.string.chzzk_action_refresh_account)) }
        Row(Modifier.broadcastTutorialAnchor(BroadcastTutorialAnchor.CONNECT_ACCOUNT)) {
            Button(onClick = onConnect, enabled = canConnect && !isBusy) { Text(stringResource(R.string.chzzk_action_connect)) }
            TextButton(onClick = onDisconnect, enabled = canDisconnect && !isBusy) { Text(stringResource(R.string.chzzk_action_disconnect)) }
        }
        OutlinedTextField(settings.title, { onChanged(settings.copy(title = it)) },
            label = { Text(stringResource(R.string.label_broadcast_title)) }, singleLine = true,
            modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth())
        OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.chzzk_label_category_search)) },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = {
            scope.launch {
                message = null
                runCatching { onSearch(query) }
                    .onSuccess { categories = it; if (it.isEmpty()) message = R.string.chzzk_search_empty }
                    .onFailure { message = R.string.chzzk_search_failed }
            }
        }, enabled = query.isNotBlank() && !isBusy) { Text(stringResource(R.string.chzzk_action_search)) }
        categories.forEach { category ->
            TextButton(onClick = {
                onChanged(settings.copy(categoryType = category.type, categoryId = category.id))
                categories = emptyList()
            }) { Text("${category.value} (${category.type})") }
        }
        Text(stringResource(R.string.chzzk_selected_category, settings.categoryType, settings.categoryId))
        Text(stringResource(R.string.chzzk_default_settings_hint))
        TextButton(onClick = { onChanged(settings.copy(categoryType = "", categoryId = "")) }) {
            Text(stringResource(R.string.chzzk_action_clear_category))
        }
        OutlinedTextField(settings.tags.joinToString(","), { raw ->
            onChanged(settings.copy(tags = if (raw.isBlank()) emptyList()
                else raw.split(',').map(String::trim)))
        }, label = { Text(stringResource(R.string.chzzk_label_tags)) },
            modifier = Modifier.fillMaxWidth())
        val invalidField = settings.validationField()
        if (invalidField != null) Text(stringResource(R.string.chzzk_validation_field, invalidField),
            color = Color.Red)
        message?.let { Text(stringResource(it), color = Color.Red) }
    }
}
