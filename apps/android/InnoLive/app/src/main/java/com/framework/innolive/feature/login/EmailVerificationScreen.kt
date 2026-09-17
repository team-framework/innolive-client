package com.framework.innolive.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun EmailVerificationScreen(
    email: String,
    pending: Boolean,
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
            text = "이메일을 확인해 주세요",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(email, fontWeight = FontWeight.SemiBold)
        Text("위 주소로 보낸 6자리 인증 코드를 입력해 주세요.")
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
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        Button(
            onClick = { onVerify(code) },
            enabled = !pending && code.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("인증하고 시작하기")
        }
        Text(
            text = if (resendGeneration > 0) {
                "인증 코드를 다시 보냈어요."
            } else {
                "메일이 오지 않았다면 스팸함을 확인해 주세요."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onResend, enabled = !pending) {
            Text("인증 코드 다시 보내기")
        }
    }
}
