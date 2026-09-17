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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private const val INITIAL_EMAIL_HINT = "메일이 오지 않았다면 스팸함을 확인해 주세요."
private const val SENDING_EMAIL_HINT = "인증 메일 보내는 중..."
private const val RESENT_EMAIL_HINT = "인증 코드를 다시 보냈어요."
private const val VERIFIED_EMAIL_HINT = "이메일 인증을 마쳤습니다. 로그인을 다시 시도해 주세요."
private val FEEDBACK_LAYOUT_MESSAGES = listOf(
    INITIAL_EMAIL_HINT,
    SENDING_EMAIL_HINT,
    RESENT_EMAIL_HINT,
    VERIFIED_EMAIL_HINT,
    "요청이 많습니다. 잠시 후 다시 시도해 주세요.",
    "회원가입 인증 시간이 만료됐습니다. 다시 시작해 주세요.",
    "이메일 인증이 완료됐습니다. 로그인을 다시 시도해 주세요.",
    "이미 가입된 이메일입니다. 로그인해 주세요.",
    "인증 코드가 올바르지 않거나 만료됐습니다.",
    "인증 메일을 보낼 수 없습니다. 잠시 후 다시 시도해 주세요.",
    "이메일과 비밀번호를 확인해 주세요.",
    "이메일 또는 비밀번호를 확인해 주세요.",
    "요청을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.",
    "지금은 이메일로 로그인할 수 없습니다. 잠시 후 다시 시도해 주세요.",
    "로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.",
    "요청을 완료하지 못했습니다. 연결 상태를 확인하고 다시 시도해 주세요.",
)

@Composable
internal fun EmailVerificationScreen(
    email: String,
    pending: Boolean,
    verified: Boolean,
    isResending: Boolean,
    error: String?,
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
                contentDescription = "뒤로",
            )
        }
        Text(
            text = if (verified) "이메일 인증 완료" else "이메일을 확인해 주세요",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(email, fontWeight = FontWeight.SemiBold)
        Text(if (verified) "이메일 인증을 마쳤습니다. 로그인을 계속해 주세요." else "위 주소로 보낸 6자리 인증 코드를 입력해 주세요.")
        if (!verified) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
                label = { Text("인증 코드") },
                placeholder = { Text("6자리 숫자") },
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
            Text(if (verified) "로그인 다시 시도" else "인증하고 시작하기")
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
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0f)
                        .clearAndSetSemantics {},
                )
            }
            Text(
                text = statusMessage,
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
                Text("인증 코드 다시 보내기")
            }
        }
    }
}
