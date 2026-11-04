package com.example.timescapedemo

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Fine glass edging for text, or a shallow bevel for original media. */
class CardEdgeDrawable(
    private val density: Float,
    private val cornerRadius: Float,
    private val glass: Boolean = false
) : Drawable() {
    private val bevel = Path()
    private val outer = RectF()
    private val inner = RectF()
    private val rim = RectF()
    private var radius = 0f
    private var innerRadius = 0f
    private var edgeAlpha = 255
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = .8f * density
    }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = .5f * density
    }

    override fun onBoundsChange(bounds: Rect) {
        bevel.rewind()
        bevel.fillType = Path.FillType.EVEN_ODD
        if (bounds.isEmpty) return
        outer.set(bounds)
        radius = cornerRadius.coerceIn(0f, minOf(outer.width(), outer.height()) / 2f)
        rim.set(outer)
        rim.inset(rimPaint.strokeWidth / 2f, rimPaint.strokeWidth / 2f)
        if (glass) {
            val thickness = minOf(1.2f * density, outer.width() / 2f, outer.height() / 2f)
            inner.set(outer)
            inner.inset(thickness, thickness)
            innerRadius = (radius - thickness).coerceAtLeast(0f)
            bevel.addRoundRect(outer, radius, radius, Path.Direction.CW)
            bevel.addRoundRect(inner, innerRadius, innerRadius, Path.Direction.CW)
            glassPaint.shader = LinearGradient(
                outer.left, 0f, outer.right, 0f,
                intArrayOf(0x20DBF6FF, 0x00DBF6FF, 0x00DBF6FF, 0x20DBF6FF),
                floatArrayOf(0f, .18f, .82f, 1f), Shader.TileMode.CLAMP
            )
            bevelPaint.shader = LinearGradient(
                0f, outer.top, 0f, outer.bottom,
                intArrayOf(0x48E5F8FF, 0x0CBCE7FB, 0x60E5FAFF),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
            )
            rimPaint.shader = LinearGradient(
                outer.left, outer.top, outer.right, outer.bottom,
                intArrayOf(0xB8FFFFFF.toInt(), 0x68EAF8FF, 0xA0FFFFFF.toInt()),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP
            )
            innerPaint.shader = LinearGradient(
                0f, outer.top, 0f, outer.bottom,
                intArrayOf(0x38FFFFFF, 0x0CD7F3FF, 0x40E5FAFF),
                floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP
            )
            return
        }
        val thickness = minOf(2.2f * density, outer.width() / 2f, outer.height() / 2f)
        inner.set(outer)
        inner.inset(thickness, thickness)
        innerRadius = (radius - thickness).coerceAtLeast(0f)
        bevel.addRoundRect(outer, radius, radius, Path.Direction.CW)
        bevel.addRoundRect(inner, innerRadius, innerRadius, Path.Direction.CW)
        bevelPaint.shader = LinearGradient(
            0f, outer.top, 0f, outer.bottom,
            intArrayOf(0x338EB0C5, 0x15263544, 0x995C839C.toInt(), 0xD01B3348.toInt()),
            floatArrayOf(0f, .65f, .9f, 1f), Shader.TileMode.CLAMP
        )
        rimPaint.shader = LinearGradient(
            0f, outer.top, 0f, outer.bottom,
            intArrayOf(0x66E5F7FF, 0x2E86AABD, 0xCCDCFAFF.toInt()),
            floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP
        )
        innerPaint.shader = LinearGradient(
            0f, outer.top, 0f, outer.bottom,
            intArrayOf(0x0084B9D4, 0x0084B9D4, 0x5584B9D4),
            floatArrayOf(0f, .7f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (glass) canvas.drawRoundRect(outer, radius, radius, glassPaint)
        canvas.drawPath(bevel, bevelPaint)
        canvas.drawRoundRect(inner, innerRadius, innerRadius, innerPaint)
        val rimRadius = (radius - rimPaint.strokeWidth / 2f).coerceAtLeast(0f)
        canvas.drawRoundRect(rim, rimRadius, rimRadius, rimPaint)
    }

    override fun setAlpha(alpha: Int) {
        if (edgeAlpha == alpha) return
        edgeAlpha = alpha
        glassPaint.alpha = alpha
        bevelPaint.alpha = alpha
        rimPaint.alpha = alpha
        innerPaint.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha() = edgeAlpha

    override fun setColorFilter(colorFilter: ColorFilter?) {
        glassPaint.colorFilter = colorFilter
        bevelPaint.colorFilter = colorFilter
        rimPaint.colorFilter = colorFilter
        innerPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
