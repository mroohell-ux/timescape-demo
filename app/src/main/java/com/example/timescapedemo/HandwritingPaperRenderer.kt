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
            HandwritingPaperStyle.DOTTED -> drawDottedGuides(canvas, width.toFloat(), height.toFloat(), spacing, guidePaint)
            HandwritingPaperStyle.NOTEBOOK -> drawNotebookGuides(canvas, width.toFloat(), height.toFloat(), spacing, guidePaint, marginPaint, density * scale)
            HandwritingPaperStyle.CORNELL -> drawCornellGuides(canvas, width.toFloat(), height.toFloat(), spacing, guidePaint, marginPaint, density * scale)
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

    private fun drawDottedGuides(canvas: Canvas, width: Float, height: Float, spacing: Float, guidePaint: Paint) {
        val dotPaint = Paint(guidePaint).apply {
            style = Paint.Style.FILL
            strokeWidth = 1f
            alpha = (guidePaint.alpha * 0.8f).roundToInt().coerceIn(0, 255)
        }
        val radius = max(1f, guidePaint.strokeWidth * 1.15f)
        var y = spacing
        while (y < height) {
            var x = spacing
            while (x < width) {
                canvas.drawCircle(x, y, radius, dotPaint)
                x += spacing
            }
            y += spacing
        }
    }

    private fun drawNotebookGuides(canvas: Canvas, width: Float, height: Float, spacing: Float, guidePaint: Paint, marginPaint: Paint, marginScale: Float) {
        drawRuledGuides(canvas, width, height, spacing, guidePaint, marginPaint, marginScale)
        val headerY = spacing * 1.55f
        canvas.drawLine(0f, headerY, width, headerY, marginPaint)
    }

    private fun drawCornellGuides(canvas: Canvas, width: Float, height: Float, spacing: Float, guidePaint: Paint, marginPaint: Paint, marginScale: Float) {
        drawRuledGuides(canvas, width, height, spacing, guidePaint, marginPaint, marginScale)
        val cueX = width * 0.32f
        val summaryY = height - spacing * 2.8f
        canvas.drawLine(cueX, 0f, cueX, summaryY, marginPaint)
        canvas.drawLine(0f, summaryY, width, summaryY, marginPaint)
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
