package com.framework.innolive.feature.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

/** YouTube는 한 채널만 연결한다: 새로 연결한 채널 외의 YouTube 연결만 해제 대상이다. */
class YouTubeSingleAccountTest {
    @Test fun selectsEveryOtherYouTubeConnectionForRemoval() {
        val accounts = listOf(
            StreamingAccount("youtube", "old-a", "A", false, accountId = "a"),
            StreamingAccount("youtube", "new-channel", "새 채널", false, accountId = "new"),
            StreamingAccount("chzzk", "chzzk-channel", "치지직", false),
            StreamingAccount("youtube", "old-b", "B", true, accountId = "b"),
        )

        assertEquals(listOf("a", "b"), otherYouTubeAccountIds(accounts, "new-channel"))
    }

    @Test fun legacyResponseWithoutConnectionIdRemovesNothing() {
        val accounts = listOf(StreamingAccount("youtube", "old-channel", "Old", false))

        assertEquals(emptyList<String>(), otherYouTubeAccountIds(accounts, "new-channel"))
    }
}
