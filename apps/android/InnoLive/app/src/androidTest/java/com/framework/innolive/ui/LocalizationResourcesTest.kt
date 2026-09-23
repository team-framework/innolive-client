package com.framework.innolive.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import com.framework.innolive.ui.text.resolve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationResourcesTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun supportedLanguagesResolveCoreUiAndPluralResources() {
        val samples = mapOf(
            "ko" to "Google로 계속하기",
            "en" to "Continue with Google",
            "ja" to "Googleで続行",
        )

        samples.forEach { (language, expectedGoogleLabel) ->
            val context = localizedContext(language)
            assertEquals(expectedGoogleLabel, context.getString(R.string.continue_with_google))
            assertTrue(context.resources.getQuantityString(R.plurals.registered_face_count, 1, 1).isNotBlank())
            assertTrue(context.resources.getQuantityString(R.plurals.registered_face_count, 2, 2).isNotBlank())
            assertTrue(context.getString(R.string.error_preview_connect).isNotBlank())
        }
    }

    @Test
    fun unsupportedLanguageUsesKoreanBaseResources() {
        assertEquals(
            "Google로 계속하기",
            localizedContext("fr").getString(R.string.continue_with_google),
        )
    }

    @Test
    fun retainedUiTextResolvesInTheCurrentAppLanguage() {
        val status = UiText.Resource(R.string.broadcast_state_prepared)
        val saved = UiText.Resource(R.string.broadcast_settings_saved)
        val error = UiText.Resource(R.string.error_account_deletion)

        assertEquals("방송 준비 완료", status.resolve(localizedContext("ko")))
        assertEquals("Broadcast prepared", status.resolve(localizedContext("en")))
        assertEquals("配信の準備完了", status.resolve(localizedContext("ja")))
        assertEquals("Broadcast settings saved.", saved.resolve(localizedContext("en")))
        assertEquals("We could not delete your account. Please try again later.", error.resolve(localizedContext("en")))
    }

    @Test
    fun liveAndYouTubeDisplayValuesResolveWhileWireAndServerValuesStayIntact() {
        val expectations = mapOf(
            "ko" to LiveExpectation("공개", "YouTube 채널: Creator", "20260917 InnoLive 방송"),
            "en" to LiveExpectation("Public", "YouTube channel: Creator", "20260917 InnoLive broadcast"),
            "ja" to LiveExpectation("公開", "YouTubeチャンネル: Creator", "20260917 InnoLive 配信"),
        )

        expectations.forEach { (language, expected) ->
            val context = localizedContext(language)
            assertEquals(expected.publicLabel, context.getString(R.string.privacy_public))
            assertEquals(
                expected.channelStatus,
                UiText.Resource(R.string.youtube_status_channel, listOf("Creator")).resolve(context),
            )
            assertEquals(
                expected.defaultTitle,
                context.getString(R.string.default_youtube_broadcast_title, "20260917"),
            )
            assertEquals(
                "streaming_reconnect_required",
                UiText.Dynamic("streaming_reconnect_required").resolve(context),
            )
        }
    }

    @Test
    fun localeConfigExposesOnlySupportedSystemAndAppLanguages() {
        val parser = appContext.resources.getXml(R.xml.locales_config)
        val locales = buildList {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                    add(parser.getAttributeValue(ANDROID_NAMESPACE, "name"))
                }
                parser.next()
            }
        }

        assertEquals(listOf("ko", "en", "ja"), locales)
    }

    private fun localizedContext(language: String): Context {
        val configuration = Configuration(appContext.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language))
        return appContext.createConfigurationContext(configuration)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }

    private data class LiveExpectation(
        val publicLabel: String,
        val channelStatus: String,
        val defaultTitle: String,
    )
}
