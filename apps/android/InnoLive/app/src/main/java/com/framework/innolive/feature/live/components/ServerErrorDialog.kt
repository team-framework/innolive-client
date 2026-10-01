package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R
import com.framework.innolive.ui.text.ServerErrorAction
import com.framework.innolive.ui.text.ServerErrorGuidance
import com.framework.innolive.ui.text.asString

@Composable
fun ServerErrorDialog(
    guidance: ServerErrorGuidance,
    onDismiss: () -> Unit,
    onAction: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    var helpUnavailable by remember(guidance) { mutableStateOf(false) }
    val actionLabel = when (guidance.action) {
        ServerErrorAction.CONNECT -> R.string.action_connect
        ServerErrorAction.RECONNECT -> R.string.action_reconnect
        ServerErrorAction.HELP -> R.string.action_open_help
        ServerErrorAction.CONFIRM_CONCURRENT -> R.string.action_continue_broadcast
        ServerErrorAction.RETRY -> R.string.action_retry
        ServerErrorAction.LOGIN -> R.string.action_sign_in_again
        else -> null
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.cappedDialogWidth(400.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.error_guidance_title), style = MaterialTheme.typography.titleLarge)
                Text(guidance.message.asString())
                if (helpUnavailable) Text(
                    stringResource(R.string.error_help_unavailable), color = MaterialTheme.colorScheme.error,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(if (guidance.action == ServerErrorAction.CONFIRM_CONCURRENT) {
                            R.string.action_cancel
                        } else R.string.action_confirm))
                    }
                    if (actionLabel != null && (onAction != null || guidance.action == ServerErrorAction.HELP)) {
                        Button(onClick = {
                            if (guidance.action == ServerErrorAction.HELP) {
                                runCatching { uriHandler.openUri(checkNotNull(guidance.helpUrl)) }
                                    .onSuccess { onDismiss() }.onFailure { helpUnavailable = true }
                            } else onAction?.invoke()
                        }) { Text(stringResource(actionLabel)) }
                    }
                }
            }
        }
    }
}
