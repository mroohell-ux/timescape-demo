package com.example.timescapedemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.withSave

/**
 * ImageView that keeps the center of the bitmap opaque while softly fading the
 * perimeter to transparent so the card can show the app background through the edges.
 */
class RadialFadeImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val contentBounds = RectF()
    private var gradient: RadialGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val radius = kotlin.math.hypot(w / 2f, h / 2f)
            gradient = RadialGradient(
                w / 2f,
                h / 2f,
                radius,
                intArrayOf(0x00000000, 0x15000000, 0x33000000, 0x7F000000.toInt()),
                floatArrayOf(0f, 0.65f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
            maskPaint.shader = gradient
            contentBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        } else {
            gradient = null
            maskPaint.shader = null
            contentBounds.setEmpty()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.saveLayer(null, null)
        super.onDraw(canvas)
        gradient?.let {
            canvas.withSave {
                drawRect(contentBounds, maskPaint)
            }
        }
        canvas.restoreToCount(checkpoint)
    }
}
