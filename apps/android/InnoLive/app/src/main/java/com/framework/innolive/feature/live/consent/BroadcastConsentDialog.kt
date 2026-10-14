package com.framework.innolive.feature.live.consent

import android.content.ActivityNotFoundException
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R
import com.framework.innolive.feature.settings.privacyPolicyUrlForLanguage

/**
 * 방송 준비 전 동의·안내 창입니다. 본문 끝까지 스크롤해야 확인 버튼을 켜고,
 * 본문이 한 화면에 들어오면 바로 켭니다. 스크롤만으로는 수락하지 않습니다.
 */
@Composable
internal fun BroadcastConsentDialog(
    step: BroadcastConsentStep,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 단계마다 새 창으로 열어 스크롤 위치와 확인 상태를 초기화합니다.
    key(step) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.testTag("broadcastConsent.${step.name}")) {
                BroadcastConsentContent(step, onAccept, onDismiss)
            }
        }
    }
}

/** 창 높이 안에서 본문만 스크롤하고 제목과 버튼은 고정합니다. */
@Composable
internal fun BroadcastConsentContent(
    step: BroadcastConsentStep,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = step.content
    val uriHandler = LocalUriHandler.current
    val privacyPolicyUrl = privacyPolicyUrlForLanguage(LocalConfiguration.current.locales[0].language)
    val scrollState = rememberScrollState()
    var hasReadToEnd by remember { mutableStateOf(false) }
    var externalLinkFailed by remember { mutableStateOf(false) }
    var bodyBottom by remember { mutableFloatStateOf(Float.NaN) }
    // 측정 전에는 maxValue가 Int.MAX_VALUE라 canScrollForward가 true이므로 첫 프레임에 켜지지 않습니다.
    LaunchedEffect(scrollState) {
        snapshotFlow { !scrollState.canScrollForward }.collect { reachedEnd ->
            if (reachedEnd) hasReadToEnd = true
        }
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(content.title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .onGloballyPositioned { bodyBottom = it.positionInRoot().y + it.size.height }
                .verticalScroll(scrollState)
                .testTag("broadcastConsent.body"),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            content.sections.forEach { section ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(section.title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(section.body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(content.footer), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = {
                    externalLinkFailed = try {
                        uriHandler.openUri(privacyPolicyUrl)
                        false
                    } catch (_: ActivityNotFoundException) {
                        true
                    } catch (_: IllegalArgumentException) {
                        true
                    } catch (_: SecurityException) {
                        true
                    }
                },
                modifier = Modifier.testTag("broadcastConsent.privacyPolicy"),
            ) {
                // 접근성 이동처럼 마지막 항목인 전문 링크까지 보이게 스크롤한 경우도 끝까지 읽은 것으로 봅니다.
                // 버튼의 최소 터치 영역은 보이는 크기보다 크므로 문구 위치로 판단합니다.
                Text(
                    text = stringResource(R.string.broadcast_consent_privacy_policy),
                    modifier = Modifier.onGloballyPositioned { label ->
                        val labelBottom = label.positionInRoot().y + label.size.height
                        if (!bodyBottom.isNaN() && labelBottom <= bodyBottom + 1f) hasReadToEnd = true
                    },
                )
            }
            if (externalLinkFailed) {
                Text(
                    text = stringResource(R.string.account_consent_external_link_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (!hasReadToEnd) {
            Text(
                text = stringResource(content.scrollHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("broadcastConsent.cancel")) {
                Text(stringResource(R.string.broadcast_consent_cancel))
            }
            Button(
                onClick = onAccept,
                enabled = hasReadToEnd,
                modifier = Modifier.weight(1f).testTag("broadcastConsent.accept"),
            ) {
                Text(stringResource(content.accept))
            }
        }
    }
}

private data class ConsentSection(@param:StringRes val title: Int, @param:StringRes val body: Int)

private data class ConsentContent(
    @param:StringRes val title: Int,
    val sections: List<ConsentSection>,
    @param:StringRes val footer: Int,
    @param:StringRes val scrollHint: Int,
    @param:StringRes val accept: Int,
)

private val BroadcastConsentStep.content: ConsentContent
    get() = when (this) {
        BroadcastConsentStep.MEDIA_TRANSMISSION -> ConsentContent(
            title = R.string.broadcast_consent_media_title,
            sections = listOf(
                ConsentSection(R.string.broadcast_consent_media_sent_title, R.string.broadcast_consent_media_sent_body),
                ConsentSection(R.string.broadcast_consent_purpose, R.string.broadcast_consent_media_purpose_body),
                ConsentSection(
                    R.string.broadcast_consent_media_retention_title,
                    R.string.broadcast_consent_media_retention_body,
                ),
            ),
            footer = R.string.broadcast_consent_media_footer,
            scrollHint = R.string.broadcast_consent_media_scroll_hint,
            accept = R.string.broadcast_consent_media_accept,
        )
        BroadcastConsentStep.YOUTUBE_TRANSMISSION -> platformNotice(
            title = R.string.broadcast_consent_youtube_title,
            sentBody = R.string.broadcast_consent_youtube_sent_body,
            purposeBody = R.string.broadcast_consent_youtube_purpose_body,
            platformTitle = R.string.broadcast_consent_youtube_platform_title,
            platformBody = R.string.broadcast_consent_youtube_platform_body,
        )
        BroadcastConsentStep.CHZZK_TRANSMISSION -> platformNotice(
            title = R.string.broadcast_consent_chzzk_title,
            sentBody = R.string.broadcast_consent_chzzk_sent_body,
            purposeBody = R.string.broadcast_consent_chzzk_purpose_body,
            platformTitle = R.string.broadcast_consent_chzzk_platform_title,
            platformBody = R.string.broadcast_consent_chzzk_platform_body,
            permissions = ConsentSection(
                R.string.broadcast_consent_chzzk_permissions_title,
                R.string.broadcast_consent_chzzk_permissions_body,
            ),
        )
    }

private fun platformNotice(
    @StringRes title: Int,
    @StringRes sentBody: Int,
    @StringRes purposeBody: Int,
    @StringRes platformTitle: Int,
    @StringRes platformBody: Int,
    permissions: ConsentSection? = null,
) = ConsentContent(
    title = title,
    // iOS와 같이 요청 권한 안내는 전송 정보 다음에 둡니다.
    sections = listOfNotNull(
        ConsentSection(R.string.broadcast_consent_sent_information, sentBody),
        permissions,
        ConsentSection(R.string.broadcast_consent_purpose, purposeBody),
        ConsentSection(platformTitle, platformBody),
    ),
    footer = R.string.broadcast_consent_notice_footer,
    scrollHint = R.string.broadcast_consent_notice_scroll_hint,
    accept = R.string.broadcast_consent_notice_accept,
)
