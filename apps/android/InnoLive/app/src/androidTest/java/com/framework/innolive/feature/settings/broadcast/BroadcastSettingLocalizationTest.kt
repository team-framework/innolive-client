package com.framework.innolive.feature.settings.broadcast

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.framework.innolive.R
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BroadcastSettingLocalizationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun visibleLabelsAndDisabledReasonsFollowTheAppLanguageWithoutChangingExternalValues() {
        val context = mutableStateOf(localizedContext("ko"))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
            ) {
                BroadcastSetting(disabledProps(context.value))
            }
        }

        mapOf(
            "ko" to "방송 설정",
            "en" to "Broadcast settings",
            "ja" to "配信設定",
        ).forEach { (language, expectedTitle) ->
            val localized = localizedContext(language)
            composeRule.runOnIdle { context.value = localized }
            composeRule.onNodeWithText(expectedTitle).performScrollTo().assertIsDisplayed()
            listOf(
                R.string.label_broadcast_platform,
                R.string.label_broadcast_title,
                R.string.label_broadcast_description,
                R.string.label_broadcast_privacy,
                R.string.label_made_for_kids,
                R.string.label_youtube_category,
                R.string.label_broadcast_account,
            ).forEach { id ->
                composeRule.onNodeWithText(localized.getString(id)).assertExists()
            }
            composeRule.onNodeWithContentDescription(
                localized.getString(R.string.action_back),
            ).assertExists()

            val reconnect = composeRule.onNodeWithText(localized.getString(R.string.action_reconnect))
            reconnect.assertIsNotEnabled().assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    localized.getString(R.string.youtube_account_action_in_progress),
                ),
            )
            val save = composeRule.onNodeWithText(
                localized.getString(R.string.action_save_broadcast_settings),
            )
            save.performScrollTo().assertIsDisplayed().assertIsNotEnabled().assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    localized.getString(R.string.validation_audience),
                ),
            )

            composeRule.onNodeWithText("Creator Channel").assertExists()
            composeRule.onNodeWithText("User title / 東京").assertExists()
            composeRule.onNodeWithText("User description / 설명").assertExists()
            composeRule.onNodeWithText("Server status: retry later").assertExists()
            if (language != "ko") composeRule.onNodeWithText("방송 설정").assertDoesNotExist()
        }
    }

    @Test
    fun longTranslationsRemainReachableOnASmallScreenAtLargeFontScale() {
        val context = mutableStateOf(localizedContext("en"))
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides context.value,
                LocalConfiguration provides context.value.resources.configuration,
                LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f),
            ) {
                Box(Modifier.width(320.dp).height(560.dp)) {
                    BroadcastSetting(disabledProps(context.value))
                }
            }
        }

        listOf("en", "ja").forEach { language ->
            val localized = localizedContext(language)
            composeRule.runOnIdle { context.value = localized }
            composeRule.onNodeWithText(localized.getString(R.string.broadcast_settings_title))
                .performScrollTo()
                .assertIsDisplayed()
            composeRule.onNodeWithText("YouTube").performScrollTo().assertIsDisplayed()
            val platformLabel = composeRule.onNodeWithText(
                localized.getString(R.string.label_broadcast_platform),
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
            val platformValue = composeRule.onNodeWithText("YouTube", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertTrue(
                "$language platform value must be below its label",
                platformValue.top >= platformLabel.bottom,
            )

            composeRule.onNodeWithText("Creator Channel").performScrollTo().assertIsDisplayed()
            val account = composeRule.onNodeWithText(
                localized.getString(R.string.label_broadcast_account),
            ).getUnclippedBoundsInRoot()
            val channel = composeRule.onNodeWithText("Creator Channel")
                .getUnclippedBoundsInRoot()
            assertTrue("$language channel must be below account label", channel.top >= account.bottom)
            val reconnect = composeRule.onNodeWithText(localized.getString(R.string.action_reconnect))
                .performScrollTo().assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$language reconnect button must fit the screen", reconnect.right <= 320.dp)
            composeRule.onNodeWithText(localized.getString(R.string.action_save_broadcast_settings))
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    private fun disabledProps(context: Context) = BroadcastSettingProps(
        onBack = {},
        selectedPlatform = "YouTube",
        onOpenPlatformOptions = {},
        title = "User title / 東京",
        onTitleChanged = {},
        description = "User description / 설명",
        onDescriptionChanged = {},
        selectedPrivacy = context.getString(R.string.privacy_private),
        onOpenPrivacyOptions = {},
        selectedAudience = context.getString(R.string.audience_required),
        onOpenAudienceOptions = {},
        categoryId = "22",
        onCategoryIdChanged = {},
        youtubeChannelTitle = "Creator Channel",
        youtubeAccountStatus = "Server status: retry later",
        hasVerifiedYouTubeAccount = false,
        isYouTubeReconnectRequired = true,
        isYouTubeAccountActionInProgress = true,
        isYouTubeConnectEnabled = true,
        connectDisabledReasonRes = R.string.youtube_account_action_in_progress,
        onConnectYouTube = {},
        onSave = {},
        isSaveEnabled = false,
        saveDisabledReasonRes = R.string.validation_audience,
        statusMessage = "",
    )

    private fun localizedContext(language: String): Context {
        val base = composeRule.activity
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        return base.createConfigurationContext(configuration)
    }
}
