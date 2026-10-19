package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveStudioPagerTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var previewTaps = 0
    private var buttonTaps = 0
    private val interactive = mutableStateOf(false)

    private fun showPager() {
        compose.setContent {
            MaterialTheme {
                LiveStudioPager(
                    pagerState = rememberPagerState(initialPage = LiveStudioPage.HOME.ordinal) { 2 },
                    settings = null,
                    canSwipe = true,
                    pinnedToHome = interactive.value,
                    previewInteractive = interactive.value,
                    preview = { Box(Modifier.fillMaxSize().clickable { previewTaps++ }) },
                    topBar = {},
                    cameraPage = {},
                    homePage = {
                        Text("button", Modifier.align(Alignment.BottomCenter).size(80.dp).testTag("button")
                            .clickable { buttonTaps++ })
                    },
                )
            }
        }
    }

    /** 얼굴 선택 중에는 버튼 밖을 누르면 아래 미리보기가, 버튼을 누르면 버튼이 받습니다. */
    @Test fun previewReceivesTapsOutsideControlsWhileInteractive() {
        showPager()
        compose.runOnIdle { interactive.value = true }
        compose.onRoot().performTouchInput { click(center) }
        compose.onNodeWithTag("button").performClick()
        compose.runOnIdle {
            assertEquals(1, previewTaps)
            assertEquals(1, buttonTaps)
        }
    }

    /** 평소에는 넘기는 영역이 터치를 받으므로 미리보기로 탭이 가지 않습니다. */
    @Test fun pagerKeepsTapsWhileNotInteractive() {
        showPager()
        compose.onRoot().performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, previewTaps) }
    }
}
