package com.framework.innolive.feature.live.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Caps the dialog before asking its child to fill the available width. */
internal fun Modifier.cappedDialogWidth(maxWidth: Dp): Modifier =
    widthIn(max = maxWidth).fillMaxWidth(0.9f)
