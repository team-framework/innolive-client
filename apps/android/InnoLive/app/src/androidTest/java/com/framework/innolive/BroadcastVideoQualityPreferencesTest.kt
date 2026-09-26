package com.framework.innolive

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.feature.live.BroadcastVideoQualityPreferences
import com.framework.innolive.feature.live.BroadcastVideoQualitySettings
import com.framework.innolive.feature.live.VideoLookPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BroadcastVideoQualityPreferencesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("broadcast_video_quality", Context.MODE_PRIVATE)
    private var originalValues: Map<String, *> = emptyMap<String, Any>()

    @Before fun preserveStoredSettings() {
        originalValues = preferences.all.toMap()
        preferences.edit().clear().commit()
    }

    @After fun restoreStoredSettings() {
        val editor = preferences.edit().clear()
        originalValues.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
            }
        }
        editor.commit()
    }

    @Test fun restoresSelectionAcrossStoreInstances() {
        val chosen = VideoLookPreset.BRIGHT.applyTo(BroadcastVideoQualitySettings(stabilizationEnabled = false))
        BroadcastVideoQualityPreferences(context).save(chosen)
        assertEquals(chosen, BroadcastVideoQualityPreferences(context).load())
    }

    @Test fun corruptedStoredTypesAndNonFiniteNumbersUseDefaults() {
        preferences.edit().putString("stabilization", "old value")
            .putFloat("exposure_ev", Float.NaN)
            .putInt("warmth", 123).putFloat("saturation", Float.POSITIVE_INFINITY).commit()
        assertEquals(BroadcastVideoQualitySettings(), BroadcastVideoQualityPreferences(context).load())
    }

    @Test fun savesOnlyNormalizedValues() {
        BroadcastVideoQualityPreferences(context).save(
            BroadcastVideoQualitySettings(exposureEV = 8f, warmth = -8f, saturation = 8f),
        )
        assertEquals(BroadcastVideoQualitySettings(exposureEV = 2f, warmth = -1f, saturation = 2f),
            BroadcastVideoQualityPreferences(context).load())
    }
}
