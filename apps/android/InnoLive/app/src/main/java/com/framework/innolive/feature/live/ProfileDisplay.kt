package com.framework.innolive.feature.live

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.SignInMethod

/** 이름이 없을 때 로그인 방식에 맞는 표시명입니다. 이메일도 없으면 방식과 관계없이 `사용자`입니다. */
@StringRes
internal fun profileFallbackName(email: String, signInMethod: SignInMethod): Int = when {
    email.isBlank() -> R.string.profile_default_name
    signInMethod == SignInMethod.GOOGLE -> R.string.google_user
    signInMethod == SignInMethod.EMAIL -> R.string.email_user
    else -> R.string.profile_default_name
}

@Composable
fun ProfileDisplay(
    name: String,
    email: String,
    modifier: Modifier = Modifier,
    signInMethod: SignInMethod = SignInMethod.UNKNOWN,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountCircle,
            contentDescription = stringResource(R.string.content_description_profile),
            modifier = Modifier.size(40.dp),
        )
        Column {
            Text(
                text = name.ifBlank { stringResource(profileFallbackName(email, signInMethod)) },
                style = MaterialTheme.typography.titleMedium,
            )
            if (email.isNotBlank()) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
