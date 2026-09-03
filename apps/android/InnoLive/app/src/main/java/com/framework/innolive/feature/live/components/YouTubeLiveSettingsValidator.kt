package com.framework.innolive.feature.live.components

import com.framework.innolive.feature.live.BroadcastSettings

const val MAX_YOUTUBE_TITLE_LENGTH = 100
const val MAX_YOUTUBE_DESCRIPTION_LENGTH = 5_000

data class YouTubeLiveSettingsValidation(
    val titleError: Boolean = false,
    val descriptionError: Boolean = false,
    val audienceError: Boolean = false,
) {
    val isValid: Boolean
        get() = !titleError && !descriptionError && !audienceError
}

fun validateYouTubeLiveSettings(
    settings: BroadcastSettings,
): YouTubeLiveSettingsValidation = YouTubeLiveSettingsValidation(
    titleError = settings.title.isBlank(),
    descriptionError = settings.description.isBlank(),
    audienceError = settings.madeForKids == null,
)
