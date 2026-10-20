package com.framework.innolive.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test

class BroadcastMenuAccountSummaryTest {
    private fun summary(checking: Boolean = false, channel: String? = null) = broadcastMenuAccountSummary(
        checking = checking,
        connectedChannelTitle = channel,
        checkingLabel = "계정 확인 중",
        status = "연결된 계정이 없습니다.",
    )

    @Test fun connectedAccountShowsOnlyChannelTitle() {
        assertEquals("내 채널", summary(channel = "내 채널"))
    }

    @Test fun checkingShowsShortLabelEvenWithCachedChannel() {
        assertEquals("계정 확인 중", summary(checking = true, channel = "내 채널"))
    }

    @Test fun otherStatesKeepTheDialogStatus() {
        assertEquals("연결된 계정이 없습니다.", summary())
        assertEquals("연결된 계정이 없습니다.", summary(channel = " "))
    }
}
