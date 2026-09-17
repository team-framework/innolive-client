package com.framework.innolive.feature.youtube

import com.framework.innolive.feature.live.BroadcastSettings
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            defaultAudience = false,
        )

        assertEquals("20260917 InnoLive 방송", normalized.title)
        assertEquals("private", normalized.privacy)
        assertFalse(checkNotNull(normalized.madeForKids))
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
            defaultAudience = null,
        )

        assertEquals(100, normalized.title.length)
        assertEquals(5_000, normalized.description.length)
        assertEquals("unlisted", normalized.privacy)
        assertEquals(true, normalized.madeForKids)
    }
}
