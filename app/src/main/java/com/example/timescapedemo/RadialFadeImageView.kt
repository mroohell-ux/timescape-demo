package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.hypot

class RadialFadeImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var maskBitmap: Bitmap? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            maskBitmap?.recycle()
            maskBitmap = null
            return
        }

        if (maskBitmap?.width == w && maskBitmap?.height == h) return

        maskBitmap?.recycle()
        maskBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(maskBitmap!!)

        val radius = hypot(w.toDouble(), h.toDouble()).toFloat() * 0.82f
        val centerX = w / 2f
        val centerY = h / 2f
        val shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(
                0x00000000,
                0x0F000000,
                0x48000000,
                0xB0000000.toInt(),
                0xFF000000.toInt()
            ),
            floatArrayOf(0f, 0.58f, 0.78f, 0.92f, 1f),
            Shader.TileMode.CLAMP
        )
        gradientPaint.shader = shader
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradientPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        maskBitmap?.recycle()
        maskBitmap = null
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.saveLayer(null, null)
        super.onDraw(canvas)
        maskBitmap?.let { canvas.drawBitmap(it, 0f, 0f, erasePaint) }
        canvas.restoreToCount(checkpoint)
    }
}
