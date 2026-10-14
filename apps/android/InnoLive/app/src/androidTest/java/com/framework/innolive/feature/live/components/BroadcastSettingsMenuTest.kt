package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** 방송 설정 메뉴가 세 항목을 보여 주고 각각 해당 설정을 열며, 준비는 메뉴에서만 시작하는지 확인합니다. */
class BroadcastSettingsMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val opened = mutableListOf<String>()
    private var prepared = 0

    private fun show(canPrepare: Boolean) {
        compose.setContent {
            MaterialTheme {
                BroadcastSettingsMenu(
                    items = listOf(
                        BroadcastSettingsMenuItem("YouTube", "채널", { opened += "youtube" }),
                        BroadcastSettingsMenuItem("치지직", "송출 안 함", { opened += "chzzk" }),
                        BroadcastSettingsMenuItem("화질 및 플랫폼", "FHD · YouTube", { opened += "mode" }),
                    ),
                    canPrepare = canPrepare,
                    onPrepare = { prepared++ },
                    onDismiss = {},
                )
            }
        }
    }

    private val prepareButton get() = compose.onNode(
        hasText(compose.activity.getString(R.string.action_prepare_broadcast)) and hasClickAction() and
            hasAnyAncestor(isDialog()),
    )

    @Test fun eachItemOpensItsSettingsAndPrepareStartsFromTheMenu() {
        show(canPrepare = true)
        compose.onNodeWithText(compose.activity.getString(R.string.broadcast_settings_title)).assertIsDisplayed()
        compose.onNodeWithText("송출 안 함").assertIsDisplayed()
        compose.onNodeWithText("YouTube").performClick()
        compose.onNodeWithText("치지직").performClick()
        compose.onNodeWithText("화질 및 플랫폼").performClick()
        prepareButton.performClick()
        compose.runOnIdle {
            assertEquals(listOf("youtube", "chzzk", "mode"), opened)
            assertEquals(1, prepared)
        }
    }

    @Test fun prepareIsDisabledUntilAccountsAreReady() {
        show(canPrepare = false)
        prepareButton.assertIsNotEnabled()
    }
}
