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
import androidx.annotation.ColorInt
import kotlin.math.abs
import kotlin.math.roundToInt

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
    @ColorInt
    private var backgroundColorInt: Int = Color.WHITE
    private var targetAspectRatio: Float? = null
    private var exportWidth = 0
    private var exportHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val ratio = targetAspectRatio
        if (ratio != null && ratio > 0f) {
            val width = measuredWidth
            if (width > 0) {
                val desiredHeight = (width * ratio).roundToInt()
                val resolvedHeight = resolveSize(desiredHeight, heightMeasureSpec)
                if (resolvedHeight != measuredHeight) {
                    setMeasuredDimension(width, resolvedHeight)
                }
            }
        }
    }

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
        newCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        extraBitmap?.recycle()
        extraBitmap = newBitmap
        extraCanvas = newCanvas
        pendingBitmap?.let { existing ->
            drawBitmapOntoCanvas(existing)
            if (!existing.isRecycled) existing.recycle()
            pendingBitmap = null
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColorInt)
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
        extraCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
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
        val source = extraBitmap ?: return null
        val targetW = exportWidth.takeIf { it > 0 } ?: source.width
        val targetH = exportHeight.takeIf { it > 0 } ?: source.height
        return if (targetW == source.width && targetH == source.height) {
            val copy = source.copy(Config.ARGB_8888, false)
            Canvas(copy).drawColor(backgroundColorInt, PorterDuff.Mode.DST_OVER)
            copy
        } else {
            val bitmap = Bitmap.createBitmap(targetW, targetH, Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(backgroundColorInt)
            val destRect = Rect(0, 0, targetW, targetH)
            canvas.drawBitmap(source, null, destRect, null)
            bitmap
        }
    }

    fun setCanvasBackgroundColor(@ColorInt color: Int) {
        if (backgroundColorInt == color) return
        backgroundColorInt = color
        invalidate()
    }

    fun setBrushColor(@ColorInt color: Int) {
        drawPaint.color = color
    }

    fun setBrushSizeDp(sizeDp: Float) {
        val px = sizeDp * resources.displayMetrics.density
        setBrushSizePx(px)
    }

    fun setBrushSizePx(sizePx: Float) {
        drawPaint.strokeWidth = sizePx
    }

    fun getBrushSizeDp(): Float = drawPaint.strokeWidth / resources.displayMetrics.density

    fun setCanvasSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == exportWidth && heightPx == exportHeight) return
        val hadContent = hasDrawing()
        commitCurrentPath()
        val snapshot = if (hadContent) extraBitmap?.copy(Config.ARGB_8888, false) else null
        if (snapshot != null) {
            pendingBitmap?.recycle()
            pendingBitmap = snapshot
        }
        hasContent = hadContent
        exportWidth = widthPx
        exportHeight = heightPx
        targetAspectRatio = heightPx.toFloat() / widthPx.toFloat()
        requestLayout()
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
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
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
