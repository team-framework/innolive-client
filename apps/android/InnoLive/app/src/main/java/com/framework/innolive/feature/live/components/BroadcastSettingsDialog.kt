package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastProvider
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.ChzzkBroadcastSettings
import com.framework.innolive.feature.live.ChzzkCategory
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastModeState
import com.framework.innolive.feature.live.direct.broadcast.DirectBroadcastPlan
import com.framework.innolive.feature.live.direct.broadcast.DirectProviderOrder
import com.framework.innolive.feature.live.direct.broadcast.title
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialAnchor
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialDialogFrame
import com.framework.innolive.feature.live.tutorial.BroadcastTutorialGuide
import com.framework.innolive.feature.live.tutorial.broadcastTutorialAnchor
import com.framework.innolive.feature.live.validationField
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.UiText

/** 방송 설정 창의 YouTube 입력입니다. 값과 계정 상태는 화면이 가집니다. */
internal class YouTubeSettingsInput(
    val settings: BroadcastSettings,
    val onChanged: (BroadcastSettings) -> Unit,
    val serverError: ServerErrorGuidance?,
    val accountStatus: String,
    val hasAccount: Boolean,
    val reconnectRequired: Boolean,
    val accountActionInProgress: Boolean,
    val connectEnabled: Boolean,
    val onConnect: () -> Unit,
)

/** 방송 설정 창의 치지직 입력입니다. 값과 계정 상태는 화면이 가집니다. */
internal class ChzzkSettingsInput(
    val settings: ChzzkBroadcastSettings,
    val accountLabel: String,
    val canConnect: Boolean,
    val canDisconnect: Boolean,
    val isBusy: Boolean,
    val onChanged: (ChzzkBroadcastSettings) -> Unit,
    val onRefreshAccount: () -> Unit,
    val onConnect: () -> Unit,
    val onDisconnect: () -> Unit,
    val onSearch: suspend (String) -> List<ChzzkCategory>,
)

/**
 * 방송 준비 전 설정을 한 창에서 고릅니다. 위쪽에서 화질과 송출 플랫폼을 고르고, 고른 플랫폼마다 탭으로
 * 설정을 편집합니다. 플랫폼 설정은 서로 이어지지 않으며, 방송 준비를 누를 때 고른 플랫폼을 모두 검사해
 * 입력이 빠진 탭으로 옮깁니다(iOS `BroadcastSettingsView`).
 */
@Composable
internal fun BroadcastSettingsDialog(
    planState: DirectBroadcastModeState,
    onPlanChanged: (DirectBroadcastPlan) -> Unit,
    onOpenPlan: () -> Unit,
    showResolution: Boolean,
    youtube: YouTubeSettingsInput,
    chzzk: ChzzkSettingsInput,
    canPrepare: Boolean,
    onPrepare: () -> Unit,
    onDismiss: () -> Unit,
    initialTab: BroadcastProvider? = null,
    accountChecking: (BroadcastProvider) -> Boolean = { false },
    accountIssue: (BroadcastProvider) -> UiText? = { null },
    guide: BroadcastTutorialGuide? = null,
) {
    val selected = DirectProviderOrder.filter { it in planState.plan.providers }
    var requestedTab by remember { mutableStateOf(initialTab) }
    val tab = requestedTab?.takeIf { it in selected } ?: selected.first()
    var youtubeValidation by remember { mutableStateOf(YouTubeLiveSettingsValidation()) }
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f

    fun prepare() {
        val youtubeCheck = validateYouTubeLiveSettings(youtube.settings)
        val youtubeInvalid = BroadcastProvider.YOUTUBE in selected && !youtubeCheck.isValid
        val chzzkInvalid = BroadcastProvider.CHZZK in selected && chzzk.settings.validationField() != null
        if (youtubeInvalid) youtubeValidation = youtubeCheck
        when {
            youtubeInvalid -> requestedTab = BroadcastProvider.YOUTUBE
            chzzkInvalid -> requestedTab = BroadcastProvider.CHZZK
            else -> onPrepare()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.cappedDialogWidth(600.dp).heightIn(max = maxHeight),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
        ) {
            BroadcastTutorialDialogFrame(guide) {
                Column(
                    modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(R.string.broadcast_settings_title), style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                    BroadcastPlanSelector(
                        state = planState,
                        onPlanChanged = onPlanChanged,
                        onOpenPlan = onOpenPlan,
                        showResolution = showResolution,
                        accountChecking = accountChecking,
                        accountIssue = accountIssue,
                    )
                    HorizontalDivider()
                    if (selected.size > 1) {
                        SecondaryTabRow(selectedTabIndex = selected.indexOf(tab)) {
                            selected.forEach { provider ->
                                Tab(
                                    selected = provider == tab,
                                    onClick = { requestedTab = provider },
                                    text = { Text(provider.title) },
                                )
                            }
                        }
                    }
                    when (tab) {
                        BroadcastProvider.YOUTUBE -> YouTubeLiveSettingsFields(
                            settings = youtube.settings,
                            onSettingsChanged = youtube.onChanged,
                            validation = youtubeValidation,
                            onValidationChanged = { youtubeValidation = it },
                            serverError = youtube.serverError,
                            youtubeAccountStatus = youtube.accountStatus,
                            hasYouTubeAccount = youtube.hasAccount,
                            isYouTubeReconnectRequired = youtube.reconnectRequired,
                            isYouTubeAccountActionInProgress = youtube.accountActionInProgress,
                            isYouTubeConnectEnabled = youtube.connectEnabled,
                            onConnectYouTube = youtube.onConnect,
                        )
                        BroadcastProvider.CHZZK -> ChzzkSettingsFields(
                            settings = chzzk.settings,
                            accountLabel = chzzk.accountLabel,
                            canConnect = chzzk.canConnect,
                            canDisconnect = chzzk.canDisconnect,
                            isBusy = chzzk.isBusy,
                            onChanged = chzzk.onChanged,
                            onRefreshAccount = chzzk.onRefreshAccount,
                            onConnect = chzzk.onConnect,
                            onDisconnect = chzzk.onDisconnect,
                            onSearch = chzzk.onSearch,
                        )
                    }
                    Button(
                        onClick = ::prepare,
                        // 준비 뒤 잠긴 구성에서도 남은 플랫폼 다시 준비는 열어 둡니다. 잠금은 화질·플랫폼 선택에만 적용합니다.
                        enabled = canPrepare,
                        modifier = Modifier
                            .fillMaxWidth()
                            .broadcastTutorialAnchor(BroadcastTutorialAnchor.START_PREPARATION),
                    ) {
                        Text(stringResource(R.string.action_prepare_broadcast))
                    }
                }
            }
        }
    }
}
