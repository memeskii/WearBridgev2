package com.wearos.ancsbridge.ancs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat

/**
 * Renders crisp, full-color circular brand icons or smart stylized monograms on Wear OS.
 *
 * Uses Android Canvas + Paint with anti-aliasing and a thread-safe LruCache (50 entries)
 * to avoid memory re-allocations during incoming notification bursts.
 */
object AppIconRenderer {

    private const val TAG = "AppIconRenderer"
    private const val CACHE_SIZE = 50

    // In-memory LRU cache keyed by "$bundleId-$sizePx"
    private val bitmapCache = LruCache<String, Bitmap>(CACHE_SIZE)

    /**
     * Clear all cached icon bitmaps (e.g., on low memory or theme change).
     */
    fun clearCache() {
        bitmapCache.evictAll()
    }

    /**
     * Get a cached bitmap if available without generating a new one.
     */
    fun getCachedBitmap(bundleId: String, sizePx: Int = 96): Bitmap? {
        val cacheKey = "$bundleId-$sizePx"
        return bitmapCache.get(cacheKey)
    }

    /**
     * Creates or retrieves a cached circular full-color brand icon for an iOS bundle ID.
     *
     * @param context Android context for loading vector resources
     * @param bundleId iOS appIdentifier bundle ID (e.g. "net.whatsapp.WhatsApp")
     * @param sizePx Pixel dimension for the circular icon (default 96px for Wear OS notifications)
     * @return Anti-aliased circular Bitmap with brand gradient and vector glyph or stylized monogram
     */
    fun createLargeIconBitmap(context: Context, bundleId: String, sizePx: Int = 96): Bitmap {
        val cacheKey = "$bundleId-$sizePx"
        bitmapCache.get(cacheKey)?.let { return it }

        val brandInfo = AppIconResolver.resolve(bundleId)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val radius = sizePx / 2f

        // 1. Draw circular background with subtle diagonal brand gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f, 0f, sizePx.toFloat(), sizePx.toFloat(),
                brandInfo.primaryColorHex,
                brandInfo.secondaryColorHex,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(radius, radius, radius, bgPaint)

        // 2. Draw subtle inner highlight border for depth on dark Wear OS AMOLED screens
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (sizePx * 0.025f).coerceAtLeast(1f)
            color = Color.argb(45, 255, 255, 255)
        }
        canvas.drawCircle(radius, radius, radius - (rimPaint.strokeWidth / 2f), rimPaint)

        // 3. Render brand vector glyph or stylized monogram
        var drewGlyph = false
        if (brandInfo.glyphType == GlyphType.VECTOR_DRAWABLE && brandInfo.iconResId != null) {
            try {
                val drawable = ContextCompat.getDrawable(context, brandInfo.iconResId)
                if (drawable != null) {
                    val mutated = drawable.mutate()
                    mutated.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                    // 23% padding creates well-proportioned icon inside circular badge
                    val padding = (sizePx * 0.23f).toInt()
                    mutated.setBounds(padding, padding, sizePx - padding, sizePx - padding)
                    mutated.draw(canvas)
                    drewGlyph = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to render vector drawable for $bundleId: ${e.message}")
            }
        }

        if (!drewGlyph) {
            drawMonogram(canvas, sizePx, brandInfo.monogramText.ifEmpty { "A" })
        }

        // 4. Cache and return
        bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * Draw a bold, vertically centered monogram in the circular badge.
     */
    private fun drawMonogram(canvas: Canvas, sizePx: Int, text: String) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = when (text.length) {
                1 -> sizePx * 0.46f
                2 -> sizePx * 0.38f
                else -> sizePx * 0.28f
            }
        }

        val x = sizePx / 2f
        // Precise vertical centering using FontMetrics ascent and descent
        val y = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, x, y, textPaint)
    }
}
