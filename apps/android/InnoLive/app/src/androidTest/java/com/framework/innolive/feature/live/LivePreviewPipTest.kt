package com.framework.innolive.feature.live

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LivePreviewPipTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var pip: LivePreviewPipState
    private lateinit var pager: PagerState
    private var homeClicks = 0

    private fun showStudio() {
        compose.setContent {
            MaterialTheme {
                pip = rememberLivePreviewPipState()
                pager = rememberPagerState(initialPage = LiveStudioPage.HOME.ordinal) { 2 }
                val density = LocalDensity.current
                LiveStudioPager(
                    pagerState = pager,
                    settings = null,
                    canSwipe = true,
                    pinnedToHome = false,
                    pipState = pip,
                    preview = {
                        Box(Modifier.fillMaxSize().testTag("studio")) {
                            val area = with(density) {
                                LivePreviewPipArea(Size(120.dp.toPx(), 200.dp.toPx()), 12.dp.toPx(),
                                    (360 - 132).dp.toPx(), 72.dp.toPx(), 400.dp.toPx())
                            }
                            SideEffect {
                                pip.area = area
                                pip.isVisible = true
                            }
                            Box(Modifier.offset { pip.offset.round() }.size(120.dp, 200.dp).testTag("pip"))
                        }
                    },
                    topBar = {},
                    cameraPage = {},
                    // PIP 기본 위치(왼쪽 위)와 겹치는 버튼입니다.
                    homePage = {
                        Text("home", Modifier.align(Alignment.TopStart).padding(top = 120.dp, start = 30.dp)
                            .size(80.dp).testTag("button").clickable { homeClicks++ })
                    },
                )
            }
        }
    }

    @Test fun draggingPipMovesItToTheNearestEdgeWithoutChangingPage() {
        showStudio()
        val start = compose.onNodeWithTag("pip").getUnclippedBoundsInRoot()
        compose.onRoot().performTouchInput {
            val from = Offset(
                ((start.left + start.right) / 2).toPx(), ((start.top + start.bottom) / 2).toPx(),
            )
            down(from)
            repeat(10) { moveBy(Offset(30.dp.toPx(), 15.dp.toPx())) }
            up()
        }
        compose.waitForIdle()
        val end = compose.onNodeWithTag("pip").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            assertTrue(pip.alignEnd)
            assertEquals(LiveStudioPage.HOME.ordinal, pager.currentPage)
        }
        assertEquals(360f - 12f, end.right.value, 1f)
        assertTrue(end.top > start.top)
    }

    @Test fun tappingOnPipStillReachesTheControlBelow() {
        showStudio()
        compose.onNodeWithTag("button").performClick()
        compose.runOnIdle { assertEquals(1, homeClicks) }
    }
}
