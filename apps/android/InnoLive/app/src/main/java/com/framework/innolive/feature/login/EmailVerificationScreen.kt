package com.framework.innolive.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun EmailVerificationScreen(
    email: String,
    pending: Boolean,
    error: String?,
    onVerify: (String) -> Unit,
    onRestart: () -> Unit,
    onSignIn: () -> Unit,
) {
    // 인증 코드와 가입 토큰은 화면 복원 데이터나 디스크에 저장하지 않는다.
    var code by remember { mutableStateOf("") }
    BackHandler(onBack = onRestart)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("이메일 인증", style = MaterialTheme.typography.headlineLarge)
        Text("$email 으로 보낸 6자리 인증 코드를 입력해 주세요.")
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { char -> char in '0'..'9' }.take(6) },
            label = { Text("인증 코드") },
            singleLine = true,
            enabled = !pending,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
        Button(
            onClick = { onVerify(code) },
            enabled = !pending && code.length == 6,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (pending) "인증 확인 중…" else "인증 완료") }
        TextButton(onClick = onRestart) { Text("인증 메일 다시 요청") }
        TextButton(onClick = onSignIn) { Text("로그인으로 돌아가기") }
    }
}
