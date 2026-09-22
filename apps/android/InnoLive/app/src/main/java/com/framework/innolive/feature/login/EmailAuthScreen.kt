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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.asString

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
    errorMessage: UiText? = null,
    noticeMessage: UiText? = null,
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
    val backBlocked = isSubmitting && !isSignIn
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
        if (!backBlocked) {
            if (isSignIn) onBack() else changeMode(EmailAuthMode.SIGN_IN)
        }
    }
    BackHandler(onBack = handleBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = handleBack, enabled = !backBlocked) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
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
                    text = stringResource(
                        if (isSignIn) R.string.email_sign_in_title else R.string.email_sign_up_title,
                    ),
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = stringResource(
                        if (isSignIn) {
                            R.string.email_sign_in_description
                        } else {
                            R.string.email_sign_up_description
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                AuthenticationTextField(
                    label = stringResource(R.string.label_email),
                    value = email,
                    onValueChange = { email = it },
                    enabled = !isSubmitting,
                    placeholder = stringResource(R.string.email_placeholder),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
                AuthenticationTextField(
                    label = stringResource(R.string.label_password),
                    value = password,
                    onValueChange = { password = it },
                    enabled = !isSubmitting,
                    placeholder = stringResource(R.string.password_hint),
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
                            label = stringResource(R.string.label_password),
                            onClick = { passwordVisible = !passwordVisible },
                        )
                    },
                )
                if (!isSignIn) {
                    AuthenticationTextField(
                        label = stringResource(R.string.label_password_confirmation),
                        value = passwordConfirmation,
                        onValueChange = { passwordConfirmation = it },
                        enabled = !isSubmitting,
                        placeholder = stringResource(R.string.password_hint),
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
                                label = stringResource(R.string.label_password_confirmation),
                                onClick = {
                                    passwordConfirmationVisible = !passwordConfirmationVisible
                                },
                            )
                        },
                    )
                    Text(
                        text = stringResource(R.string.password_requirements),
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
                Text(
                    text = stringResource(
                        when {
                            isSubmitting && isSignIn -> R.string.action_signing_in
                            isSubmitting -> R.string.action_sending_verification_email
                            isSignIn -> R.string.action_sign_in
                            else -> R.string.action_send_verification_email
                        },
                    ),
                )
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage.asString(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }

            if (isSignIn && noticeMessage != null) {
                Text(
                    text = noticeMessage.asString(),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(
                        if (isSignIn) R.string.new_to_innolive else R.string.already_have_account,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    enabled = !isSubmitting,
                    onClick = {
                        changeMode(if (isSignIn) EmailAuthMode.SIGN_UP else EmailAuthMode.SIGN_IN)
                    },
                ) {
                    Text(
                        text = stringResource(
                            if (isSignIn) R.string.action_sign_up else R.string.action_sign_in,
                        ),
                    )
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
            contentDescription = stringResource(
                if (visible) R.string.content_description_hide_value
                else R.string.content_description_show_value,
                label,
            ),
        )
    }
}

private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
