package com.framework.innolive.feature.live.components

import com.framework.innolive.feature.live.BroadcastSettings

const val MAX_YOUTUBE_TITLE_LENGTH = 100
const val MAX_YOUTUBE_DESCRIPTION_LENGTH = 5_000

/**
 * 얼굴 합성 세션의 설명 한도입니다. 서버가 설명 앞에 AI 합성 안내문과 빈 줄(36자)을 붙여 보내므로
 * 유튜브 한도에서 그만큼 뺍니다. 넘으면 서버가 400 `description`으로 거절합니다.
 */
const val MAX_FACE_SWAP_YOUTUBE_DESCRIPTION_LENGTH = 4_964

fun maxYouTubeDescriptionLength(faceSwap: Boolean): Int =
    if (faceSwap) MAX_FACE_SWAP_YOUTUBE_DESCRIPTION_LENGTH else MAX_YOUTUBE_DESCRIPTION_LENGTH

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
    faceSwap: Boolean = false,
): YouTubeLiveSettingsValidation = YouTubeLiveSettingsValidation(
    titleError = settings.title.isBlank() || settings.title.length > MAX_YOUTUBE_TITLE_LENGTH,
    descriptionError = settings.description.isBlank() ||
        settings.description.length > maxYouTubeDescriptionLength(faceSwap),
    audienceError = settings.madeForKids == null,
)
