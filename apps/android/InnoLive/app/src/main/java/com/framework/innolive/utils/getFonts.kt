package com.framework.innolive.utils

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.framework.innolive.R

@OptIn(ExperimentalTextApi::class)
private fun suitFont(weight: FontWeight) = Font(
    resId = R.font.suit_variable,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
    ),
)

fun getFonts(): FontFamily = FontFamily(
    suitFont(FontWeight.Thin),
    suitFont(FontWeight.ExtraLight),
    suitFont(FontWeight.Light),
    suitFont(FontWeight.Normal),
    suitFont(FontWeight.Medium),
    suitFont(FontWeight.SemiBold),
    suitFont(FontWeight.Bold),
    suitFont(FontWeight.ExtraBold),
    suitFont(FontWeight.Black),
)