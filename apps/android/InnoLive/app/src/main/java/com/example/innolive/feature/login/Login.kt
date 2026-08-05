package com.example.innolive.feature.login

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.innolive.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(props: LoginScreenProps) {
    val coroutineScope = rememberCoroutineScope()
    var isGoogleLoginInProgress by remember { mutableStateOf(false) }
    var googleLoginFailed by remember { mutableStateOf(false) }

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
            Text(text = "라이브 방송을 안전하게\n만드는 쉬운 방법", style = MaterialTheme.typography.headlineLarge, color = Color.White)
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
                    containerColor = Color.White
                ),
                enabled = !isGoogleLoginInProgress,
                onClick = {
                    if (!isGoogleLoginInProgress) {
                        isGoogleLoginInProgress = true
                        googleLoginFailed = false
                        coroutineScope.launch {
                            try {
                                props.onGoogleLogin()
                                props.onLogin()
                            } catch (_: GetCredentialCancellationException) {
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Exception) {
                                googleLoginFailed = true
                            } finally {
                                isGoogleLoginInProgress = false
                            }
                        }
                    }
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
                        contentDescription = "Google 트레이드마크",
                        modifier = Modifier.height(20.dp)
                    )
                    Text(text = "Google로 계속하기", color = Color.Black, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.offset(y = (-2).dp))
                }
            }
            if (isGoogleLoginInProgress) {
                Text(
                    text = "Google 로그인 중…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (googleLoginFailed) {
                Text(
                    text = "Google 로그인에 실패했습니다. 다시 시도해 주세요.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                border = BorderStroke(1.dp, Color.Black),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black
                ),
                enabled = !isGoogleLoginInProgress,
                onClick = {
                    props.onLogin()
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
                        contentDescription = "이메일 아이콘",
                        tint = Color.White,
                        modifier = Modifier.height(20.dp)
                    )
                    Text(text = "이메일로 계속하기", color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.offset(y = (-2).dp))
                }
            }
        }
    }
}
