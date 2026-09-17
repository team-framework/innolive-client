package com.framework.innolive.feature.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

internal enum class EmailAuthMode {
    SIGN_IN,
    SIGN_UP,
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EmailAuthScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSignIn: ((email: String, password: String) -> Unit)? = null,
    onSignUp: ((email: String, password: String) -> Unit)? = null,
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    noticeMessage: String? = null,
    initialEmail: String = "",
    startWithSignUp: Boolean = false,
    onModeChanged: () -> Unit = {},
) {
    var mode by rememberSaveable { mutableStateOf(if (startWithSignUp) EmailAuthMode.SIGN_UP else EmailAuthMode.SIGN_IN) }
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var password by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var passwordConfirmationVisible by rememberSaveable { mutableStateOf(false) }

    val isSignIn = mode == EmailAuthMode.SIGN_IN
    val normalizedEmail = email.trim()
    val isEmailValid = EMAIL_PATTERN.matches(normalizedEmail)
    val isPasswordValid = isSignUpPasswordValid(password)
    val canSubmit = if (isSignIn) {
        isEmailValid && password.isNotEmpty() && onSignIn != null
    } else {
        isEmailValid && isPasswordValid && password == passwordConfirmation && onSignUp != null
    }

    fun changeMode(nextMode: EmailAuthMode) {
        onModeChanged()
        mode = nextMode
        password = ""
        passwordConfirmation = ""
        passwordVisible = false
        passwordConfirmationVisible = false
    }

    val handleBack = {
        if (isSignIn) onBack() else changeMode(EmailAuthMode.SIGN_IN)
    }
    BackHandler(onBack = handleBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "뒤로",
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (isSignIn) "이메일로 로그인" else "계정 만들기",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = if (isSignIn) {
                        "InnoLive에서 라이브를 이어가세요."
                    } else {
                        "이메일 인증 후 라이브를 시작할 수 있어요."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AuthenticationTextField(
                    label = "이메일",
                    value = email,
                    onValueChange = { email = it },
                    enabled = !isSubmitting,
                    placeholder = "name@example.com",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
                AuthenticationTextField(
                    label = "비밀번호",
                    value = password,
                    onValueChange = { password = it },
                    enabled = !isSubmitting,
                    placeholder = "비밀번호 입력",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (isSignIn) ImeAction.Done else ImeAction.Next,
                    ),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        PasswordVisibilityButton(
                            visible = passwordVisible,
                            label = "비밀번호",
                            onClick = { passwordVisible = !passwordVisible },
                        )
                    },
                )
                if (!isSignIn) {
                    AuthenticationTextField(
                        label = "비밀번호 확인",
                        value = passwordConfirmation,
                        onValueChange = { passwordConfirmation = it },
                        enabled = !isSubmitting,
                        placeholder = "비밀번호 입력",
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        visualTransformation = if (passwordConfirmationVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            PasswordVisibilityButton(
                                visible = passwordConfirmationVisible,
                                label = "비밀번호 확인",
                                onClick = {
                                    passwordConfirmationVisible = !passwordConfirmationVisible
                                },
                            )
                        },
                    )
                    Text(
                        text = "영문·숫자 기준 8~72자",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = canSubmit && !isSubmitting,
                onClick = {
                    if (isSignIn) {
                        onSignIn?.invoke(normalizedEmail, password)
                    } else {
                        onSignUp?.invoke(normalizedEmail, password)
                    }
                },
            ) {
                Text(if (isSubmitting) { if (isSignIn) "로그인 중…" else "인증 메일 보내는 중…" } else if (isSignIn) "로그인" else "인증 메일 보내기")
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            if (isSignIn && noticeMessage != null) {
                Text(noticeMessage, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = if (isSignIn) {
                        "InnoLive가 처음이신가요?"
                    } else {
                        "이미 계정이 있으신가요?"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = !isSubmitting,
                    onClick = {
                        changeMode(if (isSignIn) EmailAuthMode.SIGN_UP else EmailAuthMode.SIGN_IN)
                    },
                ) {
                    Text(if (isSignIn) "회원가입" else "로그인")
                }
            }
        }
    }
}

@Composable
private fun AuthenticationTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions,
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        TextField(
            value = value,
            enabled = enabled,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun PasswordVisibilityButton(
    visible: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = if (visible) "$label 가리기" else "$label 보기",
        )
    }
}

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
