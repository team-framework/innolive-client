package com.framework.innolive.feature.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.settings.camera.CameraStabilizationControl

/**
 * iOS 설정 첫 화면처럼 구역별 어두운 카드에 하위 화면으로 들어가는 줄만 둡니다. 세부 값은 각 하위 화면에서 바꿉니다.
 * 방송 화면에서는 이 화면만 흰 창 없이 미리보기 위에 카드를 바로 띄웁니다.
 */
@Composable
fun SettingsScreen(props: SettingsScreenProps) {
    val enabled = !props.isDeletingAccount && !props.isAccountDeletionPending
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsSectionCard(stringResource(R.string.settings_section_destination)) {
            SettingsNavigationRow(stringResource(R.string.settings_broadcast_info), Icons.Outlined.Edit,
                enabled, props.onOpenBroadcastSettings, Modifier.testTag("settings-broadcast"))
        }
        SettingsSectionCard(stringResource(R.string.settings_section_video_audio)) {
            SettingsNavigationRow(stringResource(R.string.settings_camera_audio), Icons.Filled.PhotoCamera,
                enabled, props.onOpenCameraAudioSettings, Modifier.testTag("settings-camera"))
        }
        SettingsSectionCard(stringResource(R.string.settings_section_anonymization)) {
            SettingsNavigationRow(stringResource(R.string.settings_ai_faces), Icons.Outlined.Face,
                enabled, props.onOpenAISettings, Modifier.testTag("settings-ai"))
        }
        SettingsSectionCard(stringResource(R.string.settings_section_monitoring)) {
            // iOS처럼 방송 준비·방송 중에는 보관함에 들어가지 않습니다.
            SettingsNavigationRow(stringResource(R.string.settings_recordings), Icons.Outlined.SmartDisplay,
                enabled && props.canOpenRecordings, props.onOpenRecordings, Modifier.testTag("settings-recordings"))
        }
        SettingsSectionCard(stringResource(R.string.settings_section_usage)) {
            SettingsNavigationRow(stringResource(R.string.plan_title), Icons.Outlined.Schedule,
                enabled, props.onOpenPlanUsage, Modifier.testTag("settings-usage"))
            // 계정 삭제 뒤 기기 정리를 다시 시도할 수 있도록 다른 줄이 잠겨도 계정 화면까지는 들어갈 수 있게 둡니다.
            SettingsNavigationRow(stringResource(R.string.settings_app_info), Icons.Outlined.Info,
                true, props.onOpenAppInfo, Modifier.testTag("settings-app-info"))
        }
    }
}

/** iOS `CameraAudioSettingsView`처럼 카메라와 오디오 입력을 한 화면에서 고릅니다. */
@Composable
fun CameraAudioSettingsScreen(props: SettingsScreenProps, onBack: () -> Unit) {
    val enabled = !props.isDeletingAccount && !props.isAccountDeletionPending
    SettingsSubpage(stringResource(R.string.settings_camera_audio), onBack) {
        SettingsSectionLabel(stringResource(R.string.settings_section_camera))
        SettingsValueRow(stringResource(R.string.label_camera_resolution), props.selectedResolution,
            enabled, props.onOpenResolutionOptions)
        SettingsValueRow(stringResource(R.string.label_camera_device), props.selectedCameraDevice,
            enabled, props.onOpenCameraDeviceOptions)
        CameraStabilizationControl(
            settings = props.videoQualitySettings,
            captureState = props.videoQualityCaptureState,
            onSettingsChanged = props.onVideoQualitySettingsChanged,
            enabled = enabled,
        )
        SettingsDivider()
        SettingsSectionLabel(stringResource(R.string.settings_section_audio))
        SettingsValueRow(stringResource(R.string.label_audio_device), props.selectedAudioDevice,
            enabled, props.onOpenAudioDeviceOptions)
    }
}

/** iOS `AISettingsView`처럼 비식별화 처리 방식을 고릅니다. */
@Composable
fun AISettingsScreen(props: SettingsScreenProps, onBack: () -> Unit) {
    SettingsSubpage(stringResource(R.string.settings_ai_faces), onBack) {
        AIProcessingSettings(props)
    }
}

/** 요금제와 남은 시간을 보여 줍니다. 다시 불러오기는 제목 옆에 둡니다. */
@Composable
fun PlanUsageScreen(props: SettingsScreenProps, onBack: () -> Unit) {
    SettingsSubpage(
        title = stringResource(R.string.plan_title),
        onBack = onBack,
        actions = {
            TextButton(onClick = props.onRefreshPlan, enabled = !props.isPlanLoading) {
                Text(stringResource(if (props.isPlanLoading) R.string.plan_loading else R.string.plan_refresh))
            }
        },
    ) {
        PlanUsageSettings(props)
    }
}

/** iOS `SettingsView`(앱 정보 및 계정)처럼 계정 설정, 안내 다시 보기, 개인정보처리방침, 관리자 실험실을 모읍니다. */
@Composable
fun AppInfoSettingsScreen(props: SettingsScreenProps, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val privacyPolicyUrl = privacyPolicyUrlForLanguage(
        LocalConfiguration.current.locales[0].language,
    )
    val enabled = !props.isDeletingAccount && !props.isAccountDeletionPending
    SettingsSubpage(stringResource(R.string.settings_app_info), onBack, backEnabled = enabled) {
        SettingsSectionLabel(stringResource(R.string.settings_section_account))
        // iOS처럼 계정 줄을 누르면 로그아웃·계정 삭제를 모은 화면으로 들어갑니다.
        // 계정 삭제 뒤 기기 정리를 다시 시도할 수 있도록 다른 줄이 잠겨도 열 수 있게 둡니다.
        SettingsAccountRow(
            name = props.profileName,
            email = props.profileEmail,
            onClick = props.onOpenAccountSettings,
        )
        SettingsDivider()
        SettingsLinkRow(stringResource(R.string.tutorial_replay), enabled && props.canReplayBroadcastGuide,
            props.onReplayBroadcastGuide)
        SettingsLinkRow(stringResource(R.string.privacy_policy), enabled, { uriHandler.openUri(privacyPolicyUrl) })
        if (props.isAdministrator) {
            SettingsDivider()
            LabSettings(props)
        }
    }
}

@Composable
private fun AIProcessingSettings(props: SettingsScreenProps) {
    val enabled = props.canChangeAIProcessing && !props.isDeletingAccount &&
        !props.isAccountDeletionPending
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).selectableGroup()) {
        SettingsSectionLabel(stringResource(R.string.settings_ai_processing))
        Text(stringResource(R.string.ai_mode_change_availability),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp))
        listOf(false to R.string.ai_mode_server, true to R.string.ai_mode_on_device).forEach { (onDevice, label) ->
            val selected = props.onDeviceProcessing == onDevice
            Row(
                modifier = Modifier.fillMaxWidth().selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = { if (!selected) props.onSelectAIProcessing(onDevice) },
                ).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * 관리자 전용 실험 기능입니다. iOS와 같이 비식별화 방법(블러·합성)을 고릅니다. 얼굴 가림 방식은 세션 생성 때
 * 고정되므로 처리 방식과 같은 때에만 바꿉니다.
 */
@Composable
private fun LabSettings(props: SettingsScreenProps) {
    val enabled = props.canChangeAIProcessing && !props.isDeletingAccount &&
        !props.isAccountDeletionPending
    var expanded by rememberSaveable { mutableStateOf(false) }
    val options = listOf(false to R.string.lab_face_anonymization_blur, true to R.string.lab_face_anonymization_face_swap)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        SettingsSectionLabel(stringResource(R.string.settings_section_lab))
        Box {
            SettingsValueRow(
                label = stringResource(R.string.lab_face_anonymization),
                value = stringResource(
                    if (props.faceSwapEnabled) R.string.lab_face_anonymization_face_swap
                    else R.string.lab_face_anonymization_blur,
                ),
                enabled = enabled,
                onClick = { expanded = true },
            )
            DropdownMenu(
                expanded = expanded && enabled,
                onDismissRequest = { expanded = false },
                modifier = Modifier.testTag("lab-face-anonymization"),
            ) {
                options.forEach { (faceSwap, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        trailingIcon = if (props.faceSwapEnabled == faceSwap) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            expanded = false
                            if (props.faceSwapEnabled != faceSwap) props.onFaceSwapChanged(faceSwap)
                        },
                    )
                }
            }
        }
        Text(
            stringResource(
                if (props.canChangeAIProcessing) R.string.lab_face_anonymization_description
                else R.string.lab_face_anonymization_locked,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsDivider() {
    Image(
        painter = painterResource(R.drawable.settings_divider),
        contentDescription = null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(3.dp),
    )
}
