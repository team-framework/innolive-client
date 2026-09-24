package com.framework.innolive.feature.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraLensFacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceRegistrationPreviewImageTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mirrorsFrontPreviewAndRestoresBackPreviewWithoutChangingCaptureBitmap() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(50f, 0f, 100f, 100f, Paint().apply { color = Color.BLUE })
        }
        val facing = mutableStateOf(CameraLensFacing.FRONT)
        composeRule.setContent {
            Box(Modifier.size(100.dp)) {
                FaceRegistrationPreviewImage(bitmap, facing.value)
            }
        }

        assertPreviewColors(leftBlue = true)
        composeRule.runOnIdle { facing.value = CameraLensFacing.BACK }
        assertPreviewColors(leftBlue = false)
        assertEquals(Color.RED, bitmap.getPixel(20, 50))
        assertEquals(Color.BLUE, bitmap.getPixel(80, 50))
    }

    private fun assertPreviewColors(leftBlue: Boolean) {
        val preview = composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.content_description_face_registration_preview),
        )
        val pixels = preview.captureToImage().toPixelMap()
        val left = pixels[pixels.width / 4, pixels.height / 2]
        val right = pixels[pixels.width * 3 / 4, pixels.height / 2]
        if (leftBlue) {
            assertTrue(left.blue > left.red)
            assertTrue(right.red > right.blue)
        } else {
            assertTrue(left.red > left.blue)
            assertTrue(right.blue > right.red)
        }
    }
}
