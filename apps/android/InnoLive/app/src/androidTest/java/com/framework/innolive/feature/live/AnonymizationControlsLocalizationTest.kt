package com.framework.innolive.feature.live

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.ui.text.resolve
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AnonymizationControlsLocalizationTest {
    @Test
    fun stateDescriptionsNameAnonymizationOnlyOnceInEveryLocale() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        val expectedLabels = mapOf(
            "ko" to listOf(
                "비식별화 On",
                "비식별화 On 상태로 연결 중",
                "연결 시 비식별화 On",
                "비식별화 Off",
            ),
            "en" to listOf(
                "Anonymization on",
                "Connecting with de-identification on",
                "De-identification on when connected",
                "Anonymization off",
            ),
            "ja" to listOf(
                "匿名化オン",
                "匿名化オンで接続中",
                "接続時に匿名化オン",
                "匿名化オフ",
            ),
        )

        for ((language, labels) in expectedLabels) {
            val configuration = Configuration(baseContext.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            }
            val context = baseContext.createConfigurationContext(configuration)

            assertEquals(
                language,
                labels[0],
                state(WebRtcConnectionState.CONNECTED).label.resolve(context),
            )
            assertEquals(
                language,
                labels[1],
                state(WebRtcConnectionState.CONNECTING).label.resolve(context),
            )
            assertEquals(
                language,
                labels[2],
                state(WebRtcConnectionState.IDLE).label.resolve(context),
            )
            assertEquals(
                language,
                labels[3],
                state(WebRtcConnectionState.CONNECTED, AnonymizationState.DISABLED).label.resolve(context),
            )
        }
    }

    private fun state(
        connection: WebRtcConnectionState,
        confirmed: AnonymizationState = AnonymizationState.ENABLED,
    ) = anonymizationControlsState(
        connection = connection,
        confirmed = confirmed,
        selected = true,
        loaded = true,
        change = AnonymizationChange(),
    )
}
