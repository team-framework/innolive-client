package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** 자식에게 남은 너비를 채우게 하기 전에 다이얼로그 너비를 제한합니다. */
internal fun Modifier.cappedDialogWidth(maxWidth: Dp): Modifier =
    widthIn(max = maxWidth).fillMaxWidth(0.9f)
