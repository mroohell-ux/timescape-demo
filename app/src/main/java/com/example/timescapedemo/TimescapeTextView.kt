package com.example.timescapedemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils

/** Crisp lettering with a shallow lit edge and a shadow on the photograph below. */
class TimescapeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private val savedPaint = TextPaint()
    private var faceLight: Shader? = null
    private var faceColor = Color.TRANSPARENT
    var depthAmount = 1f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            faceLight = null
            invalidate()
        }

    init {
        val density = resources.displayMetrics.density
        setShadowLayer(0.6f * density, 0f, 0f, 0x8ACDEBFF.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFaceLight()
    }

    private fun updateFaceLight() {
        faceColor = currentTextColor
        val opaqueFace = ColorUtils.setAlphaComponent(faceColor, 255)
        faceLight = LinearGradient(
            0f, 0f, 0f, height.coerceAtLeast(1).toFloat(),
            intArrayOf(
                ColorUtils.blendARGB(opaqueFace, Color.WHITE, 0.15f * depthAmount),
                opaqueFace,
                ColorUtils.blendARGB(opaqueFace, 0xFF89AEC7.toInt(), 0.2f * depthAmount)
            ),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
    }

    private fun depthColor(color: Int): Int = ColorUtils.setAlphaComponent(
        color, (Color.alpha(color) * (Color.alpha(currentTextColor) / 255f) * depthAmount).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        if (depthAmount == 0f) {
            super.onDraw(canvas)
            return
        }
        if (faceLight == null || faceColor != currentTextColor) updateFaceLight()
        val textLayout = layout
        if (textLayout != null) {
            val density = resources.displayMetrics.density * depthAmount
            savedPaint.set(paint)
            val checkpoint = canvas.save()
            try {
                canvas.translate(
                    compoundPaddingLeft.toFloat() - scrollX,
                    baseline.toFloat() - textLayout.getLineBaseline(0) - scrollY
                )
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = depthColor(0x950B2039.toInt())
                paint.setShadowLayer(2.4f * density, 0f, 0.8f * density, depthColor(0x8A031222.toInt()))
                canvas.translate(1.2f * density, 3.2f * density)
                textLayout.draw(canvas)
                canvas.translate(-1.2f * density, -3.2f * density)
                paint.clearShadowLayer()
                for (step in 2 downTo 1) {
                    paint.color = depthColor(if (step == 2) 0xFF738FA5.toInt() else 0xFFB1C9DA.toInt())
                    val dx = step * 0.38f * density
                    val dy = step * 0.55f * density
                    canvas.translate(dx, dy)
                    textLayout.draw(canvas)
                    canvas.translate(-dx, -dy)
                }
            } finally {
                paint.set(savedPaint)
                canvas.restoreToCount(checkpoint)
            }
        }
        val previousShader = paint.shader
        paint.shader = faceLight
        try {
            super.onDraw(canvas)
        } finally {
            paint.shader = previousShader
        }
    }
}
