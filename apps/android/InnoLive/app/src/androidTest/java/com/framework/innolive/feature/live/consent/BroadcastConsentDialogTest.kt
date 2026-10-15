package com.framework.innolive.feature.live.consent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.settings.privacyPolicyUrlForLanguage
import com.framework.innolive.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BroadcastConsentDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearConsents() {
        BroadcastConsentStore(context).clearAll()
    }

    @Test
    fun acceptStaysDisabledUntilTheBodyIsScrolledToTheEnd() {
        var accepted = 0
        setDialog(BroadcastConsentStep.MEDIA_TRANSMISSION, onAccept = { accepted++ })

        composeRule.onNodeWithTag(ACCEPT).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.broadcast_consent_media_scroll_hint)).assertIsDisplayed()

        composeRule.onNodeWithTag(PRIVACY_POLICY).performScrollTo()
        composeRule.onNodeWithTag(ACCEPT).assertIsEnabled()
        // 다시 위로 올려도 끝까지 읽은 상태는 유지합니다.
        composeRule.onNodeWithTag(BODY).performTouchInput { swipeDown() }
        composeRule.onNodeWithTag(ACCEPT).assertIsEnabled()
        assertEquals(0, accepted)

        composeRule.onNodeWithTag(ACCEPT).performClick()
        assertEquals(1, accepted)
    }

    @Test
    fun swipingTheBodyToTheEndEnablesAccept() {
        setDialog(BroadcastConsentStep.YOUTUBE_TRANSMISSION)

        composeRule.onNodeWithTag(ACCEPT).assertIsNotEnabled()
        repeat(6) { composeRule.onNodeWithTag(BODY).performTouchInput { swipeUp() } }

        composeRule.onNodeWithTag(ACCEPT).assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.broadcast_consent_notice_scroll_hint)).assertDoesNotExist()
    }

    @Test
    fun acceptIsEnabledRightAwayWhenTheWholeBodyFits() {
        setDialog(BroadcastConsentStep.CHZZK_TRANSMISSION, height = 3000.dp)

        composeRule.onNodeWithTag(ACCEPT).assertIsEnabled()
        composeRule.onNodeWithText(context.getString(R.string.broadcast_consent_notice_scroll_hint)).assertDoesNotExist()
    }

    @Test
    fun cancelDismissesWithoutAccepting() {
        var accepted = false
        var dismissed = false
        setDialog(BroadcastConsentStep.YOUTUBE_TRANSMISSION, onAccept = { accepted = true }, onDismiss = { dismissed = true })

        composeRule.onNodeWithTag(CANCEL).performClick()

        assertTrue(dismissed)
        assertFalse(accepted)
    }

    @Test
    fun eachStepShowsItsTitleAndAcceptLabel() {
        val expectations = mapOf(
            BroadcastConsentStep.MEDIA_TRANSMISSION to
                (R.string.broadcast_consent_media_title to R.string.broadcast_consent_media_accept),
            BroadcastConsentStep.YOUTUBE_TRANSMISSION to
                (R.string.broadcast_consent_youtube_title to R.string.broadcast_consent_notice_accept),
            BroadcastConsentStep.CHZZK_TRANSMISSION to
                (R.string.broadcast_consent_chzzk_title to R.string.broadcast_consent_notice_accept),
        )
        val current = mutableStateOf(BroadcastConsentStep.MEDIA_TRANSMISSION)
        composeRule.setContent {
            MyApplicationTheme { BroadcastConsentDialog(current.value, onAccept = {}, onDismiss = {}) }
        }
        expectations.forEach { (step, labels) ->
            composeRule.runOnIdle { current.value = step }
            composeRule.onNodeWithTag("broadcastConsent.${step.name}").assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(labels.first)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(labels.second)).assertExists()
        }
    }

    @Test
    fun privacyPolicyLinkOpensTheFullPolicyForTheAppLanguage() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        setDialog(BroadcastConsentStep.CHZZK_TRANSMISSION, uriHandler = uriHandler)

        composeRule.onNodeWithTag(PRIVACY_POLICY).performScrollTo().performClick()

        val language = context.resources.configuration.locales[0].language
        assertEquals(listOf(privacyPolicyUrlForLanguage(language)), opened)
    }

    @Test
    fun storeKeepsEachConsentUntilItIsCleared() {
        val store = BroadcastConsentStore(context)
        store.clearAll()

        store.record(BroadcastConsentStep.MEDIA_TRANSMISSION)
        store.record(BroadcastConsentStep.YOUTUBE_TRANSMISSION)
        store.record(BroadcastConsentStep.CHZZK_TRANSMISSION)
        store.clear(BroadcastConsentStep.YOUTUBE_TRANSMISSION)

        val reloaded = BroadcastConsentStore(context)
        assertTrue(reloaded.hasAccepted(BroadcastConsentStep.MEDIA_TRANSMISSION))
        assertFalse(reloaded.hasAccepted(BroadcastConsentStep.YOUTUBE_TRANSMISSION))
        assertTrue(reloaded.hasAccepted(BroadcastConsentStep.CHZZK_TRANSMISSION))

        reloaded.clearAll()
        BroadcastConsentStep.entries.forEach { assertFalse(store.hasAccepted(it)) }
    }

    /** 본문이 넘치는 낮은 창([height])과 다 들어가는 높은 창을 기기 화면 크기와 관계없이 재현합니다. */
    private fun setDialog(
        step: BroadcastConsentStep,
        onAccept: () -> Unit = {},
        onDismiss: () -> Unit = {},
        uriHandler: UriHandler? = null,
        height: Dp = 360.dp,
    ) {
        composeRule.setContent {
            val content = @Composable {
                MyApplicationTheme {
                    Box(Modifier.requiredHeight(height)) { BroadcastConsentContent(step, onAccept, onDismiss) }
                }
            }
            if (uriHandler == null) content() else {
                CompositionLocalProvider(LocalUriHandler provides uriHandler, content = content)
            }
        }
    }

    private companion object {
        const val ACCEPT = "broadcastConsent.accept"
        const val CANCEL = "broadcastConsent.cancel"
        const val BODY = "broadcastConsent.body"
        const val PRIVACY_POLICY = "broadcastConsent.privacyPolicy"
    }
}
