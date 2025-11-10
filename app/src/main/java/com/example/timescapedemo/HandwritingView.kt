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
import androidx.core.graphics.ColorUtils
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class StateSnapshot(val bitmap: Bitmap, val hasDrawing: Boolean, val hasBase: Boolean)

    private val density = resources.displayMetrics.density
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * density
    }
    private val bitmapPaint = Paint(Paint.DITHER_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val path = Path()
    private val history = ArrayDeque<StateSnapshot>()

    private var extraBitmap: Bitmap? = null
    private var extraCanvas: Canvas? = null
    private var pendingBitmap: Bitmap? = null
    private var pendingHasContent = false
    private var pendingHasBase = false

    private var currentX = 0f
    private var currentY = 0f
    private val touchTolerance = 4f

    private var hasContent = false
    private var hasBaseImage = false

    @ColorInt
    private var backgroundColorInt: Int = Color.WHITE
    private var paperStyle: HandwritingPaperStyle = HandwritingPaperStyle.PLAIN
    private var penType: HandwritingPenType = HandwritingPenType.ROUND
    private var targetAspectRatio: Float? = null
    private var exportWidth = 0
    private var exportHeight = 0

    private var contentChangedListener: (() -> Unit)? = null

    private val maxHistory = 25

    init {
        applyPenType(penType)
        updateGuidePaintColor()
    }

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
            recycleHistory()
            history.clear()
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

        recycleHistory()
        history.clear()
        if (pendingBitmap != null) {
            pendingBitmap?.let { bitmap ->
                drawBitmapOntoCanvas(bitmap, recycleAfter = true)
            }
            hasBaseImage = pendingHasBase
            hasContent = pendingHasContent || pendingHasBase
            pushCurrentState(hasContent, hasBaseImage)
            pendingBitmap = null
        } else {
            hasBaseImage = false
            hasContent = false
            pushCurrentState(false, false)
        }
        pendingHasContent = false
        pendingHasBase = false
        invalidate()
        notifyContentChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColorInt)
        drawPaperGuides(canvas, width.toFloat(), height.toFloat(), 1f)
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
        val hadAnyContent = hasDrawing()
        commitCurrentPath()
        extraCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        hasContent = false
        hasBaseImage = false
        if (hadAnyContent) {
            pushCurrentState(false, false)
        } else {
            if (history.isEmpty()) {
                pushCurrentState(false, false)
            } else {
                replaceHistoryWithCurrent(false, false)
            }
        }
        pendingBitmap?.recycle()
        pendingBitmap = null
        pendingHasContent = false
        pendingHasBase = false
        invalidate()
        notifyContentChanged()
    }

    fun undo(): Boolean {
        if (!path.isEmpty) {
            path.reset()
            invalidate()
            return true
        }
        if (history.size <= 1) return false
        val current = history.removeLast()
        if (!current.bitmap.isRecycled) current.bitmap.recycle()
        val previous = history.last()
        extraCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        extraCanvas?.drawBitmap(previous.bitmap, 0f, 0f, null)
        hasBaseImage = previous.hasBase
        hasContent = previous.hasDrawing
        invalidate()
        notifyContentChanged()
        return true
    }

    fun canUndo(): Boolean = !path.isEmpty || history.size > 1

    fun hasDrawing(): Boolean = hasContent || !path.isEmpty

    fun setBitmap(bitmap: Bitmap?) {
        pendingBitmap?.recycle()
        pendingBitmap = null
        pendingHasContent = false
        pendingHasBase = false
        if (bitmap == null) {
            clear()
            return
        }
        val copy = bitmap.copy(Config.ARGB_8888, false)
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
        if (width > 0 && height > 0 && extraCanvas != null) {
            drawBitmapOntoCanvas(copy, recycleAfter = true)
            hasBaseImage = true
            hasContent = true
            replaceHistoryWithCurrent(true, true)
            invalidate()
            notifyContentChanged()
        } else {
            pendingBitmap = copy
            pendingHasContent = true
            pendingHasBase = true
        }
    }

    fun exportBitmap(): Bitmap? {
        commitCurrentPath(addToHistory = false)
        val source = extraBitmap ?: return null
        val targetW = exportWidth.takeIf { it > 0 } ?: source.width
        val targetH = exportHeight.takeIf { it > 0 } ?: source.height
        val result = Bitmap.createBitmap(targetW, targetH, Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(backgroundColorInt)
        val scale = if (width > 0) targetW.toFloat() / width.toFloat() else 1f
        drawPaperGuides(canvas, targetW.toFloat(), targetH.toFloat(), scale)
        val destRect = Rect(0, 0, targetW, targetH)
        canvas.drawBitmap(source, null, destRect, null)
        return result
    }

    fun setCanvasBackgroundColor(@ColorInt color: Int) {
        if (backgroundColorInt == color) return
        backgroundColorInt = color
        updateGuidePaintColor()
        invalidate()
    }

    fun setPaperStyle(style: HandwritingPaperStyle) {
        if (paperStyle == style) return
        paperStyle = style
        invalidate()
    }

    fun getPaperStyle(): HandwritingPaperStyle = paperStyle

    fun setBrushColor(@ColorInt color: Int) {
        drawPaint.color = color
    }

    fun setBrushSizeDp(sizeDp: Float) {
        val px = sizeDp * density
        setBrushSizePx(px)
    }

    fun setBrushSizePx(sizePx: Float) {
        drawPaint.strokeWidth = sizePx
    }

    fun getBrushSizeDp(): Float = drawPaint.strokeWidth / density

    fun setPenType(type: HandwritingPenType) {
        if (penType == type) return
        penType = type
        applyPenType(type)
    }

    fun getPenType(): HandwritingPenType = penType

    fun setCanvasSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        if (widthPx == exportWidth && heightPx == exportHeight) return
        commitCurrentPath()
        val snapshot = extraBitmap?.copy(Config.ARGB_8888, false)
        pendingBitmap?.recycle()
        pendingBitmap = snapshot
        pendingHasContent = hasContent
        pendingHasBase = hasBaseImage
        exportWidth = widthPx
        exportHeight = heightPx
        targetAspectRatio = heightPx.toFloat() / widthPx.toFloat()
        requestLayout()
    }

    fun setOnContentChangedListener(listener: (() -> Unit)?) {
        contentChangedListener = listener
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        recycleHistory()
        history.clear()
        extraBitmap?.recycle()
        extraBitmap = null
        extraCanvas = null
        pendingBitmap?.recycle()
        pendingBitmap = null
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

    private fun commitCurrentPath(addToHistory: Boolean = true) {
        if (path.isEmpty) return
        val canvas = extraCanvas ?: return
        canvas.drawPath(path, drawPaint)
        path.reset()
        hasContent = true
        if (addToHistory) {
            pushCurrentState(true, hasBaseImage)
        }
        notifyContentChanged()
    }

    private fun drawBitmapOntoCanvas(bitmap: Bitmap, recycleAfter: Boolean = false) {
        val canvas = extraCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        val destRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val destRatio = if (destRect.height() == 0f) 1f else destRect.width() / destRect.height()
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
        if (recycleAfter && !bitmap.isRecycled) {
            bitmap.recycle()
        }
    }

    private fun drawPaperGuides(canvas: Canvas, width: Float, height: Float, scale: Float) {
        if (paperStyle == HandwritingPaperStyle.PLAIN) return
        val spacing = 28f * density * scale
        val stroke = max(1f, 1.2f * density * scale)
        guidePaint.strokeWidth = stroke
        marginPaint.strokeWidth = stroke * 1.2f
        when (paperStyle) {
            HandwritingPaperStyle.RULED -> {
                var y = spacing
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, guidePaint)
                    y += spacing
                }
                val marginX = 36f * density * scale
                canvas.drawLine(marginX, 0f, marginX, height, marginPaint)
            }
            HandwritingPaperStyle.GRID -> {
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
            HandwritingPaperStyle.PLAIN -> Unit
        }
    }

    private fun applyPenType(type: HandwritingPenType) {
        when (type) {
            HandwritingPenType.ROUND -> {
                drawPaint.strokeCap = Paint.Cap.ROUND
                drawPaint.strokeJoin = Paint.Join.ROUND
                drawPaint.pathEffect = null
            }
            HandwritingPenType.MARKER -> {
                drawPaint.strokeCap = Paint.Cap.SQUARE
                drawPaint.strokeJoin = Paint.Join.BEVEL
                drawPaint.pathEffect = null
            }
            HandwritingPenType.CALLIGRAPHY -> {
                drawPaint.strokeCap = Paint.Cap.BUTT
                drawPaint.strokeJoin = Paint.Join.ROUND
                drawPaint.pathEffect = android.graphics.CornerPathEffect(16f * density)
            }
        }
    }

    private fun updateGuidePaintColor() {
        val luminance = ColorUtils.calculateLuminance(backgroundColorInt)
        val baseColor = if (luminance < 0.5) Color.WHITE else Color.BLACK
        val lineColor = ColorUtils.setAlphaComponent(baseColor, (0.28f * 255).roundToInt())
        guidePaint.color = lineColor
        val accent = ColorUtils.blendARGB(backgroundColorInt, Color.parseColor("#2962FF"), 0.55f)
        marginPaint.color = ColorUtils.setAlphaComponent(accent, (0.65f * 255).roundToInt())
    }

    private fun pushCurrentState(hasDrawing: Boolean, hasBase: Boolean) {
        val source = extraBitmap ?: return
        val snapshot = source.copy(Config.ARGB_8888, false)
        history.addLast(StateSnapshot(snapshot, hasDrawing, hasBase))
        trimHistory()
    }

    private fun replaceHistoryWithCurrent(hasDrawing: Boolean, hasBase: Boolean) {
        recycleHistory()
        history.clear()
        pushCurrentState(hasDrawing, hasBase)
    }

    private fun trimHistory() {
        while (history.size > maxHistory) {
            val removed = history.removeFirst()
            if (!removed.bitmap.isRecycled) removed.bitmap.recycle()
        }
    }

    private fun recycleHistory() {
        history.forEach { snapshot ->
            if (!snapshot.bitmap.isRecycled) snapshot.bitmap.recycle()
        }
    }

    private fun notifyContentChanged() {
        contentChangedListener?.invoke()
    }
}
