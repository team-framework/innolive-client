package com.framework.innolive.feature.live.consent

import com.framework.innolive.feature.live.BroadcastProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BroadcastConsentTest {
    @Test
    fun youtubeOnlyAsksMediaConsentThenYouTubeNotice() {
        assertEquals(
            listOf(BroadcastConsentStep.MEDIA_TRANSMISSION, BroadcastConsentStep.YOUTUBE_TRANSMISSION),
            requiredBroadcastConsentSteps(setOf(BroadcastProvider.YOUTUBE)),
        )
    }

    @Test
    fun chzzkOnlyAsksMediaConsentThenChzzkNotice() {
        assertEquals(
            listOf(BroadcastConsentStep.MEDIA_TRANSMISSION, BroadcastConsentStep.CHZZK_TRANSMISSION),
            requiredBroadcastConsentSteps(setOf(BroadcastProvider.CHZZK)),
        )
    }

    @Test
    fun simulcastAsksMediaConsentThenEachPlatformNoticeInOrder() {
        assertEquals(
            BroadcastConsentStep.entries,
            requiredBroadcastConsentSteps(setOf(BroadcastProvider.CHZZK, BroadcastProvider.YOUTUBE)),
        )
    }

    @Test
    fun mediaConsentComesBeforePlatformNoticeEvenWhenNoticeWasAccepted() {
        val accepted = setOf(BroadcastConsentStep.YOUTUBE_TRANSMISSION)

        assertEquals(
            BroadcastConsentStep.MEDIA_TRANSMISSION,
            nextBroadcastConsentStep(setOf(BroadcastProvider.YOUTUBE), accepted::contains),
        )
    }

    @Test
    fun nextStepSkipsAcceptedStepsAndStopsWhenAllAreAccepted() {
        val accepted = mutableSetOf(BroadcastConsentStep.MEDIA_TRANSMISSION)
        val simulcast = setOf(BroadcastProvider.YOUTUBE, BroadcastProvider.CHZZK)

        assertEquals(BroadcastConsentStep.YOUTUBE_TRANSMISSION, nextBroadcastConsentStep(simulcast, accepted::contains))
        accepted += BroadcastConsentStep.YOUTUBE_TRANSMISSION
        assertEquals(BroadcastConsentStep.CHZZK_TRANSMISSION, nextBroadcastConsentStep(simulcast, accepted::contains))
        accepted += BroadcastConsentStep.CHZZK_TRANSMISSION
        assertNull(nextBroadcastConsentStep(simulcast, accepted::contains))
    }

    @Test
    fun platformNoticeOfAnUnselectedPlatformIsNotRequired() {
        val accepted = setOf(BroadcastConsentStep.MEDIA_TRANSMISSION, BroadcastConsentStep.CHZZK_TRANSMISSION)

        assertNull(nextBroadcastConsentStep(setOf(BroadcastProvider.CHZZK), accepted::contains))
        assertEquals(
            BroadcastConsentStep.YOUTUBE_TRANSMISSION,
            nextBroadcastConsentStep(setOf(BroadcastProvider.YOUTUBE), accepted::contains),
        )
    }

    @Test
    fun storageKeysAreVersionedAndDistinct() {
        val keys = BroadcastConsentStep.entries.map { it.storageKey }

        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assert(it.endsWith(".v1")) }
    }
}
