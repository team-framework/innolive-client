package com.framework.innolive

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import com.framework.innolive.feature.settings.selection.OptionSelectionScreen
import com.framework.innolive.feature.settings.selection.SettingOption
import com.framework.innolive.ui.text.UiText
import org.junit.Rule
import org.junit.Test

class OptionSelectionScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun duplicateOptionNamesRenderWithSingleSelection() {
        composeRule.setContent {
            OptionSelectionScreen(
                title = UiText.Dynamic("오디오 기기"),
                options = listOf(
                    SettingOption(key = "1", label = UiText.Dynamic("동일 기기")),
                    SettingOption(key = "2", label = UiText.Dynamic("동일 기기")),
                ),
                selectedKey = "2",
                onOptionSelected = {},
                onBack = {},
            )
        }

        composeRule.onAllNodesWithText("동일 기기").assertCountEquals(2)
        composeRule.onAllNodesWithContentDescription(
            composeRule.activity.getString(R.string.label_selected),
        ).assertCountEquals(1)
    }
}
