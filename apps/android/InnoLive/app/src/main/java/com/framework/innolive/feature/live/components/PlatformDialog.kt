package com.framework.innolive.feature.live.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.framework.innolive.R

private data class PlatformOption(
    val id: String,
    val label: String,
    val iconResId: Int?,
)

private val platformOptions = listOf(
    PlatformOption(id = "chzzk", label = "CHZZK", iconResId = R.drawable.ic_chzzk),
    PlatformOption(id = "youtube", label = "YouTube", iconResId = R.drawable.ic_youtube),
    PlatformOption(id = "soop", label = "SOOP", iconResId = R.drawable.ic_soop),
)

@Composable
fun PlatformDialog(
    onDismissRequest: () -> Unit,
    onYouTubeSelected: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .widthIn(max = 360.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.9f),
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                platformOptions.forEach { platform ->
                    PlatformItem(
                        name = platform.label,
                        leadingContent = {
                            platform.iconResId?.let { iconResId ->
                                Image(
                                    painter = painterResource(iconResId),
                                    contentDescription = stringResource(
                                        R.string.content_description_platform_icon,
                                        platform.label,
                                    ),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        },
                        onClick = {
                            if (platform.id == "youtube") {
                                onYouTubeSelected()
                            }
                            onDismissRequest()
                        },
                    )
                }
            }
        }
    }
}
