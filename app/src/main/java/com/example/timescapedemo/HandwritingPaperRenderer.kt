package com.example.timescapedemo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.roundToInt

object HandwritingPaperRenderer {
    fun renderPlaceholder(
        options: HandwritingOptions,
        targetWidth: Int,
        targetHeight: Int,
        density: Float
    ): Bitmap {
        val width = targetWidth.coerceAtLeast(1)
        val height = targetHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(options.backgroundColor)
        if (options.paperStyle == HandwritingPaperStyle.PLAIN) {
            return bitmap
        }
        val scale = computeScale(options, width, height)
        val strokeWidth = max(1f, 1.2f * density * scale)
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
            this.strokeWidth = strokeWidth
            color = guideColor(options.backgroundColor)
        }
        val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
            this.strokeWidth = strokeWidth * 1.2f
            color = marginColor(options.backgroundColor)
        }
        val spacing = 28f * density * scale
        when (options.paperStyle) {
            HandwritingPaperStyle.RULED -> drawRuledGuides(canvas, width.toFloat(), height.toFloat(), spacing, guidePaint, marginPaint, density * scale)
            HandwritingPaperStyle.GRID -> drawGridGuides(canvas, width.toFloat(), height.toFloat(), spacing, guidePaint)
            HandwritingPaperStyle.PLAIN -> Unit
        }
        return bitmap
    }

    private fun computeScale(options: HandwritingOptions, width: Int, height: Int): Float {
        val widthScale = if (options.canvasWidth > 0) width.toFloat() / options.canvasWidth.toFloat() else 1f
        val heightScale = if (options.canvasHeight > 0) height.toFloat() / options.canvasHeight.toFloat() else 1f
        val candidates = listOf(widthScale, heightScale).filter { it.isFinite() && it > 0f }
        return candidates.minOrNull() ?: 1f
    }

    private fun drawRuledGuides(
        canvas: Canvas,
        width: Float,
        height: Float,
        spacing: Float,
        guidePaint: Paint,
        marginPaint: Paint,
        marginScale: Float
    ) {
        var y = spacing
        while (y < height) {
            canvas.drawLine(0f, y, width, y, guidePaint)
            y += spacing
        }
        val marginX = 36f * marginScale
        canvas.drawLine(marginX, 0f, marginX, height, marginPaint)
    }

    private fun drawGridGuides(
        canvas: Canvas,
        width: Float,
        height: Float,
        spacing: Float,
        guidePaint: Paint
    ) {
        var y = spacing
        while (y < height) {
            canvas.drawLine(0f, y, width, y, guidePaint)
            y += spacing
        }
        var x = spacing
        while (x < width) {
            canvas.drawLine(x, 0f, x, height, guidePaint)
            x += spacing
        }
    }

    @ColorInt
    private fun guideColor(@ColorInt backgroundColor: Int): Int {
        val luminance = ColorUtils.calculateLuminance(backgroundColor)
        val base = if (luminance < 0.5f) Color.WHITE else Color.BLACK
        return ColorUtils.setAlphaComponent(base, (0.28f * 255).roundToInt())
    }

    @ColorInt
    private fun marginColor(@ColorInt backgroundColor: Int): Int {
        val accent = ColorUtils.blendARGB(backgroundColor, Color.parseColor("#2962FF"), 0.55f)
        return ColorUtils.setAlphaComponent(accent, (0.65f * 255).roundToInt())
    }
}
