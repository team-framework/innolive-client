package com.framework.innolive.ui.theme

import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** 모달은 시스템 테마와 관계없이 Figma의 밝은 유리 창처럼 흰 바탕에 검은 글자로 보여 줍니다. */
internal val ModalColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2F2F2),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF2B2B2B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F8),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE9E9E9),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE2E2E2),
)

/** Figma Dropdown의 반투명 흰 바탕입니다. 뒤 화면이 흐리게 비칩니다. */
internal val ModalGlassColor = Color.White.copy(alpha = 0.9f)
internal val ModalShape: Shape = RoundedCornerShape(24.dp)
private val ModalBorder = BorderStroke(0.5.dp, Color.Black.copy(alpha = 0.08f))
private val ModalBlurRadius = 28.dp

/** 모달 안쪽을 밝은 색 구성으로 그립니다. 모달에서 연 하위 창도 같은 색을 이어받습니다. */
@Composable
fun ModalTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ModalColorScheme, typography = MaterialTheme.typography, content = content)
}

/** 뒤 화면을 흐리게 하는 밝은 모달입니다. 흐림을 지원하지 않는 기기에서는 옅게 어둡게만 합니다. */
@Composable
fun InnoLiveDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        DialogBackgroundBlur()
        ModalTheme(content)
    }
}

/** 흰 유리 모달 바탕입니다. 크기와 여백은 각 모달이 정합니다. */
@Composable
fun ModalSurface(
    modifier: Modifier = Modifier,
    shape: Shape = ModalShape,
    color: Color = ModalGlassColor,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = ModalColorScheme.onSurface,
        // 반투명 바탕 아래로 그림자가 비쳐 회색이 되므로 그림자 대신 얇은 테두리로 경계를 그립니다.
        border = ModalBorder,
        content = content,
    )
}

/** Material 확인 창과 같은 구성(제목·본문·버튼)을 흰 유리 모달로 보여 줍니다. */
@Composable
fun InnoLiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    InnoLiveDialog(onDismissRequest = onDismissRequest) {
        ModalSurface(modifier = modifier.widthIn(min = 280.dp, max = 400.dp)) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                title?.let { ProvideTextStyle(MaterialTheme.typography.titleLarge, it) }
                text?.let {
                    ProvideTextStyle(
                        MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        it,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

/** 모달 창 뒤를 흐리게 합니다. Android 12 미만이거나 기기가 흐림을 꺼 두면 옅게 어둡게만 합니다. */
@Composable
fun DialogBackgroundBlur(radius: Dp = ModalBlurRadius) {
    val view = LocalView.current
    val radiusPx = with(LocalDensity.current) { radius.roundToPx() }
    DisposableEffect(view, radiusPx) {
        val window = view.dialogWindow() ?: return@DisposableEffect onDispose {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.windowManager.isCrossWindowBlurEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
            window.setDimAmount(BlurredDimAmount)
        } else {
            window.setDimAmount(FallbackDimAmount)
        }
        onDispose {}
    }
}

private const val BlurredDimAmount = 0.1f
private const val FallbackDimAmount = 0.4f

private fun View.dialogWindow(): android.view.Window? {
    var current: Any? = this
    while (current is View) {
        if (current is DialogWindowProvider) return current.window
        current = current.parent
    }
    return null
}
