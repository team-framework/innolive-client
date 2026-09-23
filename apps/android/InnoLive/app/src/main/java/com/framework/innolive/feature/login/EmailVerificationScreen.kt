package com.framework.innolive.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString

private val INITIAL_EMAIL_HINT = UiText.Resource(R.string.email_hint_initial)
private val SENDING_EMAIL_HINT = UiText.Resource(R.string.email_hint_sending)
private val RESENT_EMAIL_HINT = UiText.Resource(R.string.email_hint_resent)
private val VERIFIED_EMAIL_HINT = UiText.Resource(R.string.email_hint_verified)
private val FEEDBACK_LAYOUT_MESSAGES = listOf(
    INITIAL_EMAIL_HINT,
    SENDING_EMAIL_HINT,
    RESENT_EMAIL_HINT,
    VERIFIED_EMAIL_HINT,
    UiText.Resource(R.string.error_too_many_requests),
    UiText.Resource(R.string.error_email_signup_expired),
    UiText.Resource(R.string.email_hint_verified),
    UiText.Resource(R.string.error_email_already_registered),
    UiText.Resource(R.string.error_email_verification_code),
    UiText.Resource(R.string.error_email_delivery),
    UiText.Resource(R.string.error_email_signup_credentials),
    UiText.Resource(R.string.error_email_credentials),
    UiText.Resource(R.string.error_request_failed),
    UiText.Resource(R.string.error_sign_in_attempts),
    UiText.Resource(R.string.error_sign_in_unavailable),
    UiText.Resource(R.string.error_request_connection_failed),
)

@Composable
internal fun EmailVerificationScreen(
    email: String,
    pending: Boolean,
    verified: Boolean,
    isResending: Boolean,
    error: UiText?,
    resendGeneration: Int,
    onVerify: (String) -> Unit,
    onResend: () -> Unit,
    onBack: () -> Unit,
) {
    // 인증 코드와 가입 자격정보는 화면 복원 데이터나 디스크에 저장하지 않는다.
    var code by remember { mutableStateOf("") }
    LaunchedEffect(resendGeneration) {
        if (resendGeneration > 0) code = ""
    }
    BackHandler(enabled = !pending, onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        IconButton(onClick = onBack, enabled = !pending) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
        Text(
            text = stringResource(
                if (verified) R.string.verification_complete_title else R.string.verification_title,
            ),
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(email, fontWeight = FontWeight.SemiBold)
        Text(
            text = stringResource(
                if (verified) {
                    R.string.verification_complete_description
                } else {
                    R.string.verification_description
                },
            ),
        )
        if (!verified) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
                label = { Text(stringResource(R.string.label_verification_code)) },
                placeholder = { Text(stringResource(R.string.verification_code_hint)) },
                singleLine = true,
                enabled = !pending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Button(
            onClick = { onVerify(code) },
            enabled = !pending && (verified || code.length == 6),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (verified) R.string.action_sign_in_again else R.string.action_verify_and_start,
                ),
            )
        }
        val statusMessage = when {
            isResending -> SENDING_EMAIL_HINT
            error != null -> error
            verified -> VERIFIED_EMAIL_HINT
            resendGeneration > 0 -> RESENT_EMAIL_HINT
            else -> INITIAL_EMAIL_HINT
        }
        val isErrorMessage = error != null && !isResending
        Box(modifier = Modifier.fillMaxWidth()) {
            // 현재 표시 가능한 모든 안내·오류의 높이를 확보해 버튼 위치를 고정한다.
            FEEDBACK_LAYOUT_MESSAGES.forEach { message ->
                Text(
                    text = message.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0f)
                        .clearAndSetSemantics {},
                )
            }
            Text(
                text = statusMessage.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isErrorMessage) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        liveRegion = if (isErrorMessage) LiveRegionMode.Assertive else LiveRegionMode.Polite
                    },
            )
        }
        if (!verified) {
            TextButton(onClick = onResend, enabled = !pending) {
                Text(stringResource(R.string.action_resend_verification))
            }
        }
    }
}
