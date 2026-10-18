package com.framework.innolive.feature.live.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class YouTubeViewerChatHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun openChatFollowsNewVideoAndClosesWhenUrlDisappears() {
        val first = YouTubeViewerChatPolicy.url("dQw4w9WgXcQ")
        val next = YouTubeViewerChatPolicy.url("abcdefghijk")
        var url by mutableStateOf(first)
        var open by mutableStateOf(false)
        compose.setContent {
            YouTubeViewerChatHost(url = url, isOpen = open, onDismiss = { open = false }) { Text("chat:$it") }
        }
        compose.onNodeWithText("chat:$first").assertDoesNotExist()

        compose.runOnIdle { open = true }
        compose.onNodeWithText("chat:$first").assertExists()
        compose.runOnIdle { url = next }
        compose.onNodeWithText("chat:$next").assertExists()

        compose.runOnIdle { url = null }
        compose.waitForIdle()
        compose.onNodeWithText("chat:$next").assertDoesNotExist()
        compose.runOnIdle { assertFalse(open) }
        // 방송이 다시 시작돼도 사용자가 다시 열기 전에는 창을 띄우지 않는다.
        compose.runOnIdle { url = first }
        compose.onNodeWithText("chat:$first").assertDoesNotExist()
    }
}
