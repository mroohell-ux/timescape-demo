package com.example.timescapedemo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/** Displays receipt handwriting on warm paper with printed cut lines and a torn lower edge. */
class ThermalReceiptImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 253, 248)
        style = Paint.Style.FILL
    }
    private val cutLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(105, 45, 45, 45)
        style = Paint.Style.STROKE
        strokeWidth = density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val paperPath = Path()

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        buildPaperPath()
        val checkpoint = canvas.save()
        canvas.clipPath(paperPath)
        canvas.drawPath(paperPath, paperPaint)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)

        val inset = 12f * density
        canvas.drawLine(inset, 9f * density, width - inset, 9f * density, cutLinePaint)
        canvas.drawLine(
            inset,
            height - 13f * density,
            width - inset,
            height - 13f * density,
            cutLinePaint
        )
    }

    private fun buildPaperPath() {
        val toothWidth = 12f * density
        val toothDepth = 7f * density
        val bottom = height.toFloat()
        val toothTop = (bottom - toothDepth).coerceAtLeast(0f)
        paperPath.reset()
        paperPath.moveTo(0f, 0f)
        paperPath.lineTo(width.toFloat(), 0f)
        paperPath.lineTo(width.toFloat(), toothTop)
        var x = width.toFloat()
        while (x > 0f) {
            val midpoint = (x - toothWidth / 2f).coerceAtLeast(0f)
            val end = (x - toothWidth).coerceAtLeast(0f)
            paperPath.lineTo(midpoint, bottom)
            paperPath.lineTo(end, toothTop)
            x = end
        }
        paperPath.close()
    }
}
