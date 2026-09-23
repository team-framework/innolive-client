package com.framework.innolive.feature.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.GoogleSignInState
import com.framework.innolive.ui.text.asString

@Composable
fun LoginScreen(props: LoginScreenProps) {
    var showEmailAuthentication by rememberSaveable { mutableStateOf(false) }
    val isGoogleLoginInProgress = props.googleSignInState is GoogleSignInState.InProgress
    val googleLoginError = (props.googleSignInState as? GoogleSignInState.Failed)?.error

    LaunchedEffect(props.googleSignInState) {
        if (props.googleSignInState is GoogleSignInState.Succeeded) {
            props.onLogin()
            props.onGoogleSignInSuccess()
        }
    }

    if (showEmailAuthentication) {
        EmailLoginScreen(
            onBack = { showEmailAuthentication = false },
            onLogin = props.onLogin,
            signIn = props.onEmailLogin,
            signUp = props.onEmailSignUp,
            verifyEmail = props.onEmailVerification,
            resendSignup = props.onEmailSignupResend,
            cancelSignup = props.onEmailSignupCancel,
            hasPendingSignup = props.hasPendingEmailSignup,
            isSignupVerified = props.isEmailSignupVerified,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(
            space = 28.dp,
            alignment = Alignment.CenterVertically
        ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.login_headline),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(
                space = 8.dp,
            )) {
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, Color.Black),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color.Black,
                ),
                enabled = !isGoogleLoginInProgress,
                onClick = {
                    props.onGoogleLogin()
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 8.dp,
                        alignment = Alignment.Start
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.google),
                        contentDescription = stringResource(R.string.content_description_google_logo),
                        modifier = Modifier.height(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.continue_with_google),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.offset(y = (-2).dp),
                    )
                }
            }
            if (googleLoginError != null) {
                Text(
                    text = googleLoginError.asString(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, Color.Black),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                ),
                enabled = !isGoogleLoginInProgress,
                onClick = {
                    showEmailAuthentication = true
                },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        space = 8.dp,
                        alignment = Alignment.Start
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Email,
                        contentDescription = stringResource(R.string.content_description_email_icon),
                        modifier = Modifier.height(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.continue_with_email),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.offset(y = (-2).dp),
                    )
                }
            }
        }
    }
}
