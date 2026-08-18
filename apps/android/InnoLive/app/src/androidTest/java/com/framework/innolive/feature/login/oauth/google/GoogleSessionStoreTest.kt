package com.framework.innolive.feature.login.oauth.google

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleSessionStoreTest {
    @Test
    fun saveLoadAndClearSession() {
        val store = GoogleSessionStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        val existingSession = store.load()

        try {
            store.clear()
            val expected = GoogleSessionStore.Session(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                tokenType = "Bearer",
                expiresIn = 3600,
                refreshExpiresIn = 86400,
                profileName = "InnoLive User",
                profileEmail = "user@example.com",
            )

            store.save(expected)
            assertEquals(expected, store.load())

            store.clear()
            assertNull(store.load())
        } finally {
            store.clear()
            existingSession?.let(store::save)
        }
    }
}
