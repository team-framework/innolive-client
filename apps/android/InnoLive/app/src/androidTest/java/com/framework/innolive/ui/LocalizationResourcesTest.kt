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
            assertTrue(context.getString(R.string.email_sign_in_title).isNotBlank())
            assertTrue(context.getString(R.string.action_resend_verification).isNotBlank())
            assertTrue(context.getString(R.string.content_description_hide_value, "Password").isNotBlank())
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
        val emailError = UiText.Resource(R.string.error_email_credentials)
        val serverMessage = UiText.Dynamic("Account deletion is already in progress. Retry shortly.")

        assertEquals("방송 준비 완료", status.resolve(localizedContext("ko")))
        assertEquals("Broadcast prepared", status.resolve(localizedContext("en")))
        assertEquals("配信の準備完了", status.resolve(localizedContext("ja")))
        assertEquals("Broadcast settings saved.", saved.resolve(localizedContext("en")))
        assertEquals("We could not delete your account. Please try again later.", error.resolve(localizedContext("en")))
        assertEquals("Check your email or password.", emailError.resolve(localizedContext("en")))
        assertEquals("メールアドレスまたはパスワードを確認してください。", emailError.resolve(localizedContext("ja")))
        assertEquals(serverMessage.value, serverMessage.resolve(localizedContext("en")))
        assertEquals(serverMessage.value, serverMessage.resolve(localizedContext("ja")))
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
}
