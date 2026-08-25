package com.framework.innolive.feature.live.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.framework.innolive.R

private data class PlatformOption(
    val id: String,
    val label: String,
    val iconResId: Int?,
)

private val platformOptions = listOf(
    PlatformOption(id = "chzzk", label = "Chzzk", iconResId = R.drawable.ic_chzzk),
    PlatformOption(id = "youtube", label = "Youtube", iconResId = R.drawable.ic_youtube),
    PlatformOption(id = "soop", label = "SOOP", iconResId = R.drawable.ic_soop),
)

@Composable
fun PlatformDialog(
    onDismissRequest: () -> Unit,
    onYouTubeSelected: () -> Unit,
) {
    val density = LocalDensity.current

    Popup(
        alignment = Alignment.TopCenter,
        offset = with(density) { IntOffset(0, (-200).dp.roundToPx()) },
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            modifier = Modifier.width(180.dp),
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
                                    contentDescription = "${platform.label} 아이콘",
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
