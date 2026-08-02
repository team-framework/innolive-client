package com.example.innolive

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.example.innolive.feature.settings.selection.OptionSelectionScreen
import org.junit.Rule
import org.junit.Test

class OptionSelectionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun duplicateOptionNamesRenderWithoutKeyCollision() {
        composeRule.setContent {
            OptionSelectionScreen(
                title = "오디오 기기",
                options = listOf("동일 기기", "동일 기기"),
                onOptionSelected = {},
                onBack = {},
            )
        }

        composeRule.onAllNodesWithText("동일 기기").assertCountEquals(2)
    }
}
