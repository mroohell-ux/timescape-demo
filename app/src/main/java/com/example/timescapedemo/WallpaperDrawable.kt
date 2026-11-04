package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/** Display-only matte trimming and centered, aspect-preserving viewport coverage. */
class WallpaperDrawable(source: Bitmap) : Drawable() {
    val bitmap: Bitmap
    private val matrix = Matrix()
    private val shader: BitmapShader
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        val content = contentBounds(source)
        bitmap = if (content.width() == source.width && content.height() == source.height) source
        else Bitmap.createBitmap(source, content.left, content.top, content.width(), content.height())
        shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        paint.shader = shader
    }

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) return
        val scale = max(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        matrix.setScale(scale, scale)
        matrix.postTranslate(
            bounds.exactCenterX() - bitmap.width * scale / 2f,
            bounds.exactCenterY() - bitmap.height * scale / 2f
        )
        shader.setLocalMatrix(matrix)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Drawable opacity is no longer used for rendering")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun fromUri(context: Context, uri: Uri, maxEdgePx: Int): WallpaperDrawable {
            require(maxEdgePx > 0)
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > maxEdgePx) {
                    val scale = maxEdgePx.toFloat() / longEdge
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
            return WallpaperDrawable(bitmap)
        }

        internal fun contentBounds(bitmap: Bitmap): Rect {
            fun matteLine(horizontal: Boolean, position: Int): Boolean {
                val length = if (horizontal) bitmap.width else bitmap.height
                val samples = minOf(96, length)
                var matte = 0
                for (sample in 0 until samples) {
                    val along = if (samples == 1) 0 else sample * (length - 1) / (samples - 1)
                    val pixel = if (horizontal) bitmap.getPixel(along, position) else bitmap.getPixel(position, along)
                    if (Color.alpha(pixel) <= 16 ||
                        (Color.alpha(pixel) >= 240 && maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) <= 16)
                    ) matte++
                }
                // Screenshot status/navigation glyphs must not terminate an otherwise black matte.
                return matte * 10 >= samples * 7
            }

            fun trim(horizontal: Boolean, reverse: Boolean): Int {
                val length = if (horizontal) bitmap.height else bitmap.width
                val limit = length / 5
                if (limit == 0 || !matteLine(horizontal, if (reverse) length - 1 else 0)) return 0
                // Ignore short glyph interruptions; actual image content must persist across rows/columns.
                val contentRunLength = (length * .02f).roundToInt().coerceAtLeast(2)
                var contentRun = 0
                for (offset in 0 until minOf(length, limit + contentRunLength)) {
                    if (matteLine(horizontal, if (reverse) length - 1 - offset else offset)) {
                        contentRun = 0
                    } else {
                        contentRun++
                        if (contentRun == contentRunLength) return offset - contentRunLength + 1
                    }
                }
                // Keep all-dark images and broad dark regions instead of treating them as framing.
                return 0
            }

            return Rect(
                trim(horizontal = false, reverse = false),
                trim(horizontal = true, reverse = false),
                bitmap.width - trim(horizontal = false, reverse = true),
                bitmap.height - trim(horizontal = true, reverse = true)
            )
        }
    }
}
