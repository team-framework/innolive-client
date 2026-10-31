package com.framework.innolive.feature.live.privacy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.framework.innolive.feature.live.AIProcessingPreference
import org.json.JSONObject

/** Worker-owned sprite compositor; input must already have privacy blur applied. */
internal class PrivacyAnimalSprite(private val context: Context) {
    private val preferences = context.getSharedPreferences("privacy_animal", Context.MODE_PRIVATE)
    private val unavailable = mutableSetOf<String>()
    private val cache = mutableMapOf<String, Pair<Bitmap, RectF>>()
    fun asset(): Pair<Bitmap, RectF>? {
        if (!AIProcessingPreference(context).onDevice) return null
        val animal = preferences.getString("selected", "blur")
        if (animal !in listOf("rabbit", "owl")) return null
        if (animal in unavailable) return null
        return cache[animal] ?: runCatching {
            unavailable.add(checkNotNull(animal))
            val json = context.assets.open("$animal.json").bufferedReader().use { JSONObject(it.readText()) }
            val values = json.getJSONArray("faceRect")
            require(values.length() == 4)
            val f = List(4) { values.getDouble(it).toFloat() }
            require(f.all { it.isFinite() && it in 0f..1f } && f[2] > 0 && f[3] > 0 && f[0]+f[2] <= 1 && f[1]+f[3] <= 1)
            val image = context.assets.open("$animal.png").use { checkNotNull(BitmapFactory.decodeStream(it)) }
            image to RectF(f[0]*image.width, f[1]*image.height,
                (f[0]+f[2])*image.width, (f[1]+f[3])*image.height)
        }.getOrNull()?.also { unavailable.remove(animal); cache[checkNotNull(animal)] = it } ?: return null
    }
    fun composite(protected: Bitmap, boxes: List<RectF>): Bitmap {
        if (boxes.isEmpty()) return protected
        val asset = asset() ?: return protected
        val (image, face) = asset
        val output = protected.copy(Bitmap.Config.ARGB_8888, true) ?: return protected
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        boxes.filter { !it.isEmpty }.forEach { box ->
            val sx = box.width()/face.width(); val sy = box.height()/face.height()
            val left = box.left-face.left*sx; val top = box.top-face.top*sy
            canvas.drawBitmap(image, null, RectF(left,top,left+image.width*sx,top+image.height*sy),paint)
        }
        return output
    }
    fun close() { cache.values.forEach { it.first.recycle() }; cache.clear() }
}
