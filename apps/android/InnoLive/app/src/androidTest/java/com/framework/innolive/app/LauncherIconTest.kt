package com.framework.innolive.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.framework.innolive.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestLauncherIconsRenderLogoWithoutOpaqueMonochromeBackground() {
        val application = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertEquals(R.mipmap.ic_launcher, application.icon)

        listOf(application.icon, R.mipmap.ic_launcher_round).forEach { resourceId ->
            val icon = context.getDrawable(resourceId)
            assertTrue("Launcher icon must be adaptive", icon is AdaptiveIconDrawable)
            icon as AdaptiveIconDrawable
            val monochrome = icon.monochrome
            assertNotNull("Themed icons need a monochrome layer", monochrome)
            val bitmap = render(checkNotNull(monochrome))

            assertEquals("White image background must stay transparent", 0, Color.alpha(bitmap.getPixel(250, 100)))
            assertTrue("Inno stem must remain visible", Color.alpha(bitmap.getPixel(126, 250)) > 240)
            assertTrue("Live box must remain visible", Color.alpha(bitmap.getPixel(350, 220)) > 240)
            assertEquals("Live lettering must be a transparent cutout", 0, Color.alpha(bitmap.getPixel(268, 250)))
            assertEquals("Camera indicator must be a transparent cutout", 0, Color.alpha(bitmap.getPixel(375, 224)))
        }
    }

    private fun render(drawable: Drawable): Bitmap =
        Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
        }
}
