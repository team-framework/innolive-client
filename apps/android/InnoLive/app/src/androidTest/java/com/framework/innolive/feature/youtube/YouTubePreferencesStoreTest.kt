package com.framework.innolive.feature.youtube

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.BroadcastSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YouTubePreferencesStoreTest {
    @Test
    fun savesOnlyConnectionMetadataAndClearsAccountData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "youtube_preferences_test_${System.nanoTime()}"
        val store = YouTubePreferencesStore(context, preferencesName)

        try {
            val account = StreamingAccount(
                provider = "youtube",
                channelId = "channel-123",
                channelTitle = "InnoLive Channel",
                reconnectRequired = true,
            )
            val settings = BroadcastSettings(
                title = "방송 제목",
                description = "방송 설명",
                privacy = "unlisted",
                madeForKids = false,
                categoryId = "22",
            )

            store.saveConnection(account)
            store.saveBroadcastSettings(settings)

            assertEquals(account, store.loadConnection())
            assertEquals(settings, store.loadBroadcastSettings())
            assertTrue(
                context.getSharedPreferences(preferencesName, 0).all.keys.none { key ->
                    key.contains("token", ignoreCase = true) || key.contains("secret", ignoreCase = true)
                },
            )

            store.clearAccountData()
            assertNull(store.loadConnection())
        } finally {
            store.clearAccountData()
        }
    }
}
