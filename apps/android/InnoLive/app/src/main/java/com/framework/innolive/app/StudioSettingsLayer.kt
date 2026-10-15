package com.framework.innolive.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.framework.innolive.R

@Composable
internal fun StudioSettingsLayer(
    showStudio: Boolean,
    showSettings: Boolean,
    onDismissSettings: () -> Unit,
    modifier: Modifier = Modifier,
    studio: @Composable () -> Unit,
    navigation: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        // Keep capture under the Activity lifecycle; a settings NavEntry must not stop it.
        if (showStudio) {
            Box(Modifier.fillMaxSize().semantics {
                if (showSettings) hideFromAccessibility()
            }) { studio() }
        }
        if (showSettings) {
            Box(Modifier.fillMaxSize().clickable(
                interactionSource = null,
                indication = null,
                onClick = onDismissSettings,
            ))
            val title = stringResource(R.string.settings_title)
            Surface(
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(start = 16.dp, end = 16.dp, top = 58.dp, bottom = 12.dp)
                    .widthIn(max = 600.dp).fillMaxSize()
                    .semantics { paneTitle = title },
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shadowElevation = 4.dp,
            ) { navigation() }
        } else if (!showStudio) {
            navigation()
        }
    }
}
