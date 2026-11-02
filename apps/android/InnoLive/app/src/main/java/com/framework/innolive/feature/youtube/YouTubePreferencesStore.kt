package com.framework.innolive.feature.youtube

import android.content.Context
import com.framework.innolive.R
import com.framework.innolive.feature.live.BroadcastSettings
import com.framework.innolive.feature.live.components.MAX_YOUTUBE_DESCRIPTION_LENGTH
import com.framework.innolive.feature.live.components.MAX_YOUTUBE_TITLE_LENGTH
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 다시 적용해도 안전한 YouTube UI 환경설정만 저장합니다.
 *
 * OAuth 자격 증명과 서버 세션 소유 토큰은 의도적으로 전용 보안 저장소에 두며, 여기에 추가하면
 * 안 됩니다. 저장된 연결 정보는 표시용 캐시이며, 연결 상태의 기준은 성공한
 * `GET /streaming-accounts` 응답입니다.
 */
class YouTubePreferencesStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val resourceContext = context
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    fun loadConnection(): StreamingAccount? {
        val provider = preferences.getString(CONNECTION_PROVIDER, null)
            ?.takeIf { it.equals(YOUTUBE_PROVIDER, ignoreCase = true) }
            ?: return null
        val channelId = preferences.getString(CONNECTION_CHANNEL_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return StreamingAccount(
            provider = provider,
            channelId = channelId,
            channelTitle = preferences.getString(CONNECTION_CHANNEL_TITLE, "").orEmpty(),
            reconnectRequired = preferences.getBoolean(CONNECTION_RECONNECT_REQUIRED, false),
        )
    }

    fun saveConnection(account: StreamingAccount) {
        require(account.provider.equals(YOUTUBE_PROVIDER, ignoreCase = true)) {
            "Only YouTube account metadata can be stored."
        }
        require(account.channelId.isNotBlank()) { "YouTube channel ID is required." }

        check(
            preferences.edit()
                .putString(CONNECTION_PROVIDER, YOUTUBE_PROVIDER)
                .putString(CONNECTION_CHANNEL_ID, account.channelId.trim())
                .putString(CONNECTION_CHANNEL_TITLE, account.channelTitle)
                .putBoolean(CONNECTION_RECONNECT_REQUIRED, account.reconnectRequired)
                .commit(),
        ) { "Unable to persist YouTube connection metadata." }
    }

    fun removeConnection() {
        check(
            preferences.edit()
                .remove(CONNECTION_PROVIDER)
                .remove(CONNECTION_CHANNEL_ID)
                .remove(CONNECTION_CHANNEL_TITLE)
                .remove(CONNECTION_RECONNECT_REQUIRED)
                .commit(),
        ) { "Unable to clear YouTube connection metadata." }
    }

    fun loadBroadcastSettings(): BroadcastSettings = normalizeYouTubeBroadcastSettings(
        BroadcastSettings(
            title = preferences.getString(BROADCAST_TITLE, "").orEmpty(),
            description = preferences.getString(BROADCAST_DESCRIPTION, "").orEmpty(),
            privacy = preferences.getString(BROADCAST_PRIVACY, DEFAULT_PRIVACY).orEmpty(),
            madeForKids = preferences.getString(BROADCAST_AUDIENCE, null).toAudience(),
            categoryId = preferences.getString(BROADCAST_CATEGORY_ID, "").orEmpty(),
        ),
        defaultTitle = defaultYouTubeBroadcastTitle(resourceContext),
    )

    fun hasSavedBroadcastTitle(): Boolean = preferences.contains(BROADCAST_TITLE)

    fun saveBroadcastSettings(
        settings: BroadcastSettings,
        titleIsGeneratedDefault: Boolean = false,
    ) {
        val normalized = normalizeYouTubeBroadcastSettings(
            settings,
            defaultTitle = defaultYouTubeBroadcastTitle(resourceContext),
        )
        val editor = preferences.edit()
            .putString(BROADCAST_DESCRIPTION, normalized.description)
            .putString(BROADCAST_PRIVACY, normalized.privacy)
            .putString(BROADCAST_CATEGORY_ID, normalized.categoryId)

        if (titleIsGeneratedDefault) {
            editor.remove(BROADCAST_TITLE)
        } else {
            editor.putString(BROADCAST_TITLE, normalized.title)
        }

        if (normalized.madeForKids == null) {
            editor.remove(BROADCAST_AUDIENCE)
        } else {
            editor.putString(BROADCAST_AUDIENCE, normalized.madeForKids.toAudience())
        }

        editor.apply()
    }

    /** 사용자가 직접 로그아웃하거나 계정을 삭제하면 민감하지 않은 캐시를 지웁니다. */
    fun clearAccountData() {
        check(preferences.edit().clear().commit()) { "Unable to clear YouTube preferences." }
    }

    private companion object {
        const val PREFERENCES_NAME = "innolive_youtube_preferences"
        const val YOUTUBE_PROVIDER = "youtube"
        const val CONNECTION_PROVIDER = "connection_provider"
        const val CONNECTION_CHANNEL_ID = "connection_channel_id"
        const val CONNECTION_CHANNEL_TITLE = "connection_channel_title"
        const val CONNECTION_RECONNECT_REQUIRED = "connection_reconnect_required"
        const val BROADCAST_TITLE = "broadcast_title"
        const val BROADCAST_DESCRIPTION = "broadcast_description"
        const val BROADCAST_PRIVACY = "broadcast_privacy"
        const val BROADCAST_AUDIENCE = "broadcast_audience"
        const val BROADCAST_CATEGORY_ID = "broadcast_category_id"
        const val DEFAULT_PRIVACY = "private"
    }
}

internal fun normalizeYouTubeBroadcastSettings(
    settings: BroadcastSettings,
    defaultTitle: String = defaultYouTubeBroadcastTitle(),
): BroadcastSettings = settings.copy(
    title = settings.title.trim().take(MAX_YOUTUBE_TITLE_LENGTH).ifBlank { defaultTitle },
    description = settings.description.take(MAX_YOUTUBE_DESCRIPTION_LENGTH),
    privacy = settings.privacy.takeIf { it in YOUTUBE_PRIVACY_VALUES } ?: "private",
    categoryId = settings.categoryId.filter(Char::isDigit),
)

internal fun defaultYouTubeBroadcastTitle(
    context: Context,
    today: LocalDate = LocalDate.now(),
): String = context.getString(
    R.string.default_youtube_broadcast_title,
    today.format(DateTimeFormatter.BASIC_ISO_DATE),
)

/** 로케일과 무관한 테스트용 대체값입니다. 운영 코드는 항상 Context를 전달합니다. */
internal fun defaultYouTubeBroadcastTitle(today: LocalDate = LocalDate.now()): String =
    "${today.format(DateTimeFormatter.BASIC_ISO_DATE)} InnoLive"

private fun String?.toAudience(): Boolean? = when (this) {
    "true" -> true
    "false" -> false
    else -> null
}

private fun Boolean.toAudience(): String = toString()

private val YOUTUBE_PRIVACY_VALUES = setOf("public", "unlisted", "private")
