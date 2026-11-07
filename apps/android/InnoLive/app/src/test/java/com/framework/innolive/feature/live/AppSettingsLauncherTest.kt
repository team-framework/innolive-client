package com.framework.innolive.feature.live

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsLauncherTest {
    @Test
    fun opensTheFirstScreenAndSkipsTheFallback() {
        val opened = mutableListOf<String>()

        assertTrue(startFirstAvailable(listOf("details", "settings")) { opened += it })
        assertEquals(listOf("details"), opened)
    }

    @Test
    fun fallsBackWhenTheFirstScreenIsMissingOrBlocked() {
        val missingDetails = mutableListOf<String>()
        val blockedDetails = mutableListOf<String>()

        assertTrue(startFirstAvailable(listOf("details", "settings")) {
            if (it == "details") throw ActivityNotFoundException()
            missingDetails += it
        })
        assertTrue(startFirstAvailable(listOf("details", "settings")) {
            if (it == "details") throw SecurityException("blocked")
            blockedDetails += it
        })
        assertEquals(listOf("settings"), missingDetails)
        assertEquals(listOf("settings"), blockedDetails)
    }

    @Test
    fun reportsFailureInsteadOfCrashingWhenNothingOpens() {
        val attempted = mutableListOf<String>()

        assertFalse(startFirstAvailable(listOf("details", "settings")) {
            attempted += it
            throw ActivityNotFoundException()
        })
        assertEquals(listOf("details", "settings"), attempted)
    }
}
