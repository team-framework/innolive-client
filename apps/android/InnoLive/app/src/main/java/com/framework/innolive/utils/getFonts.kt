package com.framework.innolive.utils

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.framework.innolive.R

fun getFonts(): FontFamily = FontFamily(
    Font(R.font.suit_variable, FontWeight(100)),
    Font(R.font.suit_variable, FontWeight(200)),
    Font(R.font.suit_variable, FontWeight(300)),
    Font(R.font.suit_variable, FontWeight(400)),
    Font(R.font.suit_variable, FontWeight(500)),
    Font(R.font.suit_variable, FontWeight(600)),
    Font(R.font.suit_variable, FontWeight(700)),
    Font(R.font.suit_variable, FontWeight(800)),
    Font(R.font.suit_variable, FontWeight(900)),
)