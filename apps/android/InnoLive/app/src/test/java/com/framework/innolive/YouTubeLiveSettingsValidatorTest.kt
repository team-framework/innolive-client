package com.framework.innolive

import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.components.MAX_YOUTUBE_DESCRIPTION_LENGTH
import com.framework.innolive.feature.live.components.MAX_YOUTUBE_TITLE_LENGTH
import com.framework.innolive.feature.live.components.validateYouTubeLiveSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLiveSettingsValidatorTest {
    @Test
    fun blankTitleDescriptionAndUnsetAudienceAreInvalid() {
        val validation = validateYouTubeLiveSettings(
            settings(title = " \n", description = "\t", madeForKids = null),
        )

        assertTrue(validation.titleError)
        assertTrue(validation.descriptionError)
        assertTrue(validation.audienceError)
        assertFalse(validation.isValid)
    }

    @Test
    fun oneCharacterFieldsAndEitherAudienceValueAreValidAtTheLowerBoundary() {
        assertTrue(
            validateYouTubeLiveSettings(
                settings(title = "제", description = "설", madeForKids = true),
            ).isValid,
        )
        assertTrue(
            validateYouTubeLiveSettings(
                settings(title = "제", description = "설", madeForKids = false),
            ).isValid,
        )
    }

    @Test
    fun maximumInputLengthsRemainValid() {
        val validation = validateYouTubeLiveSettings(
            settings(
                title = "제목".repeat(MAX_YOUTUBE_TITLE_LENGTH / 2),
                description = "설명".repeat(MAX_YOUTUBE_DESCRIPTION_LENGTH / 2),
                madeForKids = false,
            ),
        )

        assertFalse(validation.titleError)
        assertFalse(validation.descriptionError)
        assertFalse(validation.audienceError)
        assertTrue(validation.isValid)
    }

    @Test
    fun validInputClearsEachPreviouslyReportedError() {
        val validation = validateYouTubeLiveSettings(
            settings(title = "방송 제목", description = "방송 설명", madeForKids = true),
        )

        assertFalse(validation.titleError)
        assertFalse(validation.descriptionError)
        assertFalse(validation.audienceError)
    }

    private fun settings(
        title: String,
        description: String,
        madeForKids: Boolean?,
    ) = BroadcastSettings(
        title = title,
        description = description,
        privacy = "public",
        madeForKids = madeForKids,
        categoryId = "",
    )
}
