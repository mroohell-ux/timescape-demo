package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * resources.displayMetrics.density
    }
    private val bitmapPaint = Paint(Paint.DITHER_FLAG)
    private var extraBitmap: Bitmap? = null
    private var extraCanvas: Canvas? = null
    private val path = Path()
    private var currentX = 0f
    private var currentY = 0f
    private val touchTolerance = 4f
    private var hasContent = false
    private var pendingBitmap: Bitmap? = null
    private val backgroundColor = Color.WHITE

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) {
            extraBitmap?.recycle()
            extraBitmap = null
            extraCanvas = null
            return
        }
        val newBitmap = Bitmap.createBitmap(w, h, Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(backgroundColor)
        extraBitmap?.recycle()
        extraBitmap = newBitmap
        extraCanvas = newCanvas
        pendingBitmap?.let { existing ->
            drawBitmapOntoCanvas(existing)
            pendingBitmap = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColor)
        extraBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        canvas.drawPath(path, drawPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.action) {
            MotionEvent.ACTION_DOWN -> touchStart(x, y)
            MotionEvent.ACTION_MOVE -> touchMove(x, y)
            MotionEvent.ACTION_UP -> touchUp()
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun clear() {
        extraCanvas?.drawColor(backgroundColor, PorterDuff.Mode.SRC)
        path.reset()
        hasContent = false
        pendingBitmap?.recycle()
        pendingBitmap = null
        invalidate()
    }

    fun hasDrawing(): Boolean = hasContent || !path.isEmpty

    fun setBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            clear()
            pendingBitmap = null
            return
        }
        if (width > 0 && height > 0 && extraCanvas != null) {
            drawBitmapOntoCanvas(bitmap)
        } else {
            pendingBitmap?.recycle()
            pendingBitmap = bitmap.copy(Config.ARGB_8888, false)
            hasContent = true
        }
        invalidate()
    }

    fun exportBitmap(): Bitmap? {
        commitCurrentPath()
        return extraBitmap?.copy(Config.ARGB_8888, false)
    }

    private fun touchStart(x: Float, y: Float) {
        path.reset()
        path.moveTo(x, y)
        currentX = x
        currentY = y
    }

    private fun touchMove(x: Float, y: Float) {
        val dx = abs(x - currentX)
        val dy = abs(y - currentY)
        if (dx >= touchTolerance || dy >= touchTolerance) {
            path.quadTo(currentX, currentY, (x + currentX) / 2, (y + currentY) / 2)
            currentX = x
            currentY = y
        }
    }

    private fun touchUp() {
        path.lineTo(currentX, currentY)
        commitCurrentPath()
    }

    private fun commitCurrentPath() {
        if (!path.isEmpty) {
            extraCanvas?.drawPath(path, drawPaint)
            path.reset()
            hasContent = true
        }
    }

    private fun drawBitmapOntoCanvas(bitmap: Bitmap) {
        val canvas = extraCanvas ?: return
        canvas.drawColor(backgroundColor, PorterDuff.Mode.SRC)
        val destRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val srcRatio = bitmap.width.toFloat() / bitmap.height
        val destRatio = destRect.width() / destRect.height()
        val drawRect = RectF()
        if (srcRatio > destRatio) {
            val scaledHeight = destRect.width() / srcRatio
            val top = destRect.centerY() - scaledHeight / 2f
            drawRect.set(destRect.left, top, destRect.right, top + scaledHeight)
        } else {
            val scaledWidth = destRect.height() * srcRatio
            val left = destRect.centerX() - scaledWidth / 2f
            drawRect.set(left, destRect.top, left + scaledWidth, destRect.bottom)
        }
        canvas.drawBitmap(bitmap, srcRect, drawRect, null)
        hasContent = true
    }
}
