package com.framework.innolive.feature.youtube

import com.framework.innolive.feature.live.BroadcastSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePreferencesNormalizationTest {
    @Test
    fun blankTitleAndInvalidPrivacyUseSafeDefaults() {
        val normalized = normalizeYouTubeBroadcastSettings(
            settings = BroadcastSettings(
                title = "   ",
                description = "설명",
                privacy = "friends-only",
                madeForKids = null,
                categoryId = "2a2",
            ),
            defaultTitle = defaultYouTubeBroadcastTitle(LocalDate.of(2026, 9, 17)),
        )

        assertEquals("20260917 InnoLive", normalized.title)
        assertEquals("private", normalized.privacy)
        assertNull(normalized.madeForKids)
        assertEquals("22", normalized.categoryId)
    }

    @Test
    fun titleIsTrimmedAndLengthLimited() {
        val normalized = normalizeYouTubeBroadcastSettings(
            settings = BroadcastSettings(
                title = "  ${"a".repeat(101)}  ",
                description = "d".repeat(5_001),
                privacy = "unlisted",
                madeForKids = true,
                categoryId = "24",
            ),
            defaultTitle = "unused",
        )

        assertEquals(100, normalized.title.length)
        assertEquals(5_000, normalized.description.length)
        assertEquals("unlisted", normalized.privacy)
        assertEquals(true, normalized.madeForKids)
    }

    @Test
    fun unsetAudienceRemainsUnsetInDebugBuild() {
        val normalized = normalizeYouTubeBroadcastSettings(
            BroadcastSettings(
                title = "방송 제목",
                description = "방송 설명",
                privacy = "private",
                madeForKids = null,
                categoryId = "22",
            ),
        )

        assertNull(normalized.madeForKids)
    }

    @Test
    fun cachedAccountIsUnavailableUntilServerVerificationSucceeds() {
        val cachedAccount = StreamingAccount(
            provider = "youtube",
            channelId = "cached-channel",
            channelTitle = "Cached Channel",
            reconnectRequired = false,
        )

        assertFalse(
            hasVerifiedYouTubeAccount(
                cachedAccount,
                YouTubeAccountVerificationState.UNVERIFIED,
                verifiedProfileEmail = null,
                currentProfileEmail = "current@example.com",
            ),
        )
        assertFalse(
            hasVerifiedYouTubeAccount(
                cachedAccount,
                YouTubeAccountVerificationState.CHECKING,
                verifiedProfileEmail = null,
                currentProfileEmail = "current@example.com",
            ),
        )
        assertEquals(
            true,
            hasVerifiedYouTubeAccount(
                cachedAccount,
                YouTubeAccountVerificationState.VERIFIED,
                verifiedProfileEmail = "current@example.com",
                currentProfileEmail = "current@example.com",
            ),
        )
        assertFalse(
            hasVerifiedYouTubeAccount(
                cachedAccount,
                YouTubeAccountVerificationState.VERIFIED,
                verifiedProfileEmail = "previous@example.com",
                currentProfileEmail = "current@example.com",
            ),
        )
    }
}
