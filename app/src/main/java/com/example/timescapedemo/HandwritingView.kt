package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.CornerPathEffect
import android.graphics.ComposePathEffect
import android.graphics.DiscretePathEffect
import android.graphics.Matrix
import android.graphics.PathDashPathEffect
import android.util.AttributeSet
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import android.view.ViewConfiguration
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.example.timescapedemo.HandwritingDrawingTool.ERASER
import com.example.timescapedemo.HandwritingDrawingTool.PEN
import com.example.timescapedemo.HandwritingDrawingTool.TEXT
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
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
        textSize = 18f * scaledDensity
    }
    private val textPreviewPaint = Paint(textPaint)
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 16f * density
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val eraserPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 16f * density
        color = ColorUtils.setAlphaComponent(Color.BLACK, (0.28f * 255).roundToInt())
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
    @ColorInt
    private var brushColorInt: Int = Color.BLACK
    @ColorInt
    private var textColorInt: Int = Color.BLACK
    private var paperStyle: HandwritingPaperStyle = HandwritingPaperStyle.PLAIN
    private var penType: HandwritingPenType = HandwritingPenType.ROUND
    private var eraserType: HandwritingEraserType = HandwritingEraserType.ROUND
    private var drawingTool: HandwritingDrawingTool = PEN
    private var targetAspectRatio: Float? = null
    private var exportWidth = 0
    private var exportHeight = 0

    private var textPosition: PointF = PointF()
    private var textContent: String = ""
    private var isDraggingText = false
    private val textDragOffset: PointF = PointF()
    private var lastTextTapTime = 0L
    private var preserveTextAfterStamp = false

    private var contentChangedListener: (() -> Unit)? = null

    private val maxHistory = 25

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        applyPenType(penType)
        updatePenColor()
        applyEraserType(eraserType)
        updateGuidePaintColor()
        updateTextColor()
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
        if (textPosition.x == 0f && textPosition.y == 0f) {
            textPosition.set(w / 2f, h / 2f)
        } else {
            clampTextPosition()
        }
        invalidate()
        notifyContentChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColorInt)
        drawPaperGuides(canvas, width.toFloat(), height.toFloat(), 1f)
        extraBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        canvas.drawPath(path, currentPreviewPaint())
        if (drawingTool == TEXT && textContent.isNotBlank()) {
            drawTextLines(canvas, textPreviewPaint, textPosition.x, textPosition.y)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        if (drawingTool == TEXT) {
            handleTextTouch(event.actionMasked, x, y)
        } else {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    disallowParentIntercept(true)
                    touchStart(x, y)
                }
                MotionEvent.ACTION_MOVE -> touchMove(x, y)
                MotionEvent.ACTION_UP -> {
                    touchUp()
                    disallowParentIntercept(false)
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchCancel()
                    disallowParentIntercept(false)
                }
            }
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
        brushColorInt = color
        updatePenColor()
    }

    fun setBrushSizeDp(sizeDp: Float) {
        val px = sizeDp * density
        setBrushSizePx(px)
    }

    fun setBrushSizePx(sizePx: Float) {
        penPaint.strokeWidth = sizePx
        applyPenType(penType)
        updatePenColor()
        if (drawingTool == PEN) {
            invalidate()
        }
    }

    fun getBrushSizeDp(): Float = penPaint.strokeWidth / density

    fun setTextContent(text: String) {
        textContent = text
        preserveTextAfterStamp = false
        if (drawingTool == TEXT) invalidate()
    }

    fun consumePreserveTextAfterStamp(): Boolean {
        val preserve = preserveTextAfterStamp
        preserveTextAfterStamp = false
        return preserve
    }

    fun setTextColor(@ColorInt color: Int) {
        textColorInt = color
        updateTextColor()
        if (drawingTool == TEXT) invalidate()
    }

    fun getTextColor(): Int = textColorInt

    fun setTextSizeSp(sizeSp: Float) {
        val px = sizeSp * scaledDensity
        textPaint.textSize = px
        textPreviewPaint.textSize = px
        if (drawingTool == TEXT) invalidate()
    }

    fun getTextSizeSp(): Float = textPaint.textSize / scaledDensity

    fun setPenType(type: HandwritingPenType) {
        if (penType == type) return
        penType = type
        applyPenType(type)
        updatePenColor()
    }

    fun getPenType(): HandwritingPenType = penType

    fun setEraserSizeDp(sizeDp: Float) {
        val px = sizeDp * density
        eraserPaint.strokeWidth = px
        eraserPreviewPaint.strokeWidth = px
        if (drawingTool == ERASER) invalidate()
    }

    fun getEraserSizeDp(): Float = eraserPaint.strokeWidth / density

    fun setEraserType(type: HandwritingEraserType) {
        if (eraserType == type) return
        eraserType = type
        applyEraserType(type)
        if (drawingTool == ERASER) invalidate()
    }

    fun getEraserType(): HandwritingEraserType = eraserType

    fun setDrawingTool(tool: HandwritingDrawingTool) {
        if (drawingTool == tool) return
        commitCurrentPath()
        drawingTool = tool
        isDraggingText = false
        invalidate()
    }

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

    private fun touchCancel() {
        path.reset()
    }

    private fun handleTextTouch(action: Int, x: Float, y: Float) {
        val isInsideText = currentTextBounds()?.contains(x, y) == true
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (isInsideText) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastTextTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                        preserveTextAfterStamp = true
                    }
                    lastTextTapTime = now
                } else {
                    lastTextTapTime = 0L
                    preserveTextAfterStamp = false
                }
                isDraggingText = true
                textDragOffset.set(x - textPosition.x, y - textPosition.y)
                disallowParentIntercept(true)
            }
            MotionEvent.ACTION_MOVE -> if (isDraggingText) updateTextPosition(x - textDragOffset.x, y - textDragOffset.y)
            MotionEvent.ACTION_UP -> {
                if (isDraggingText) {
                    updateTextPosition(x - textDragOffset.x, y - textDragOffset.y)
                    disallowParentIntercept(false)
                }
                isDraggingText = false
            }
            MotionEvent.ACTION_CANCEL -> {
                isDraggingText = false
                disallowParentIntercept(false)
            }
        }
    }

    fun placeTextStamp(addToHistory: Boolean = true): Boolean = commitTextStamp(addToHistory)

    private fun commitTextStamp(addToHistory: Boolean = true): Boolean {
        if (textContent.isBlank()) return false
        val canvas = extraCanvas ?: return false
        drawTextLines(canvas, textPaint, textPosition.x, textPosition.y)
        hasContent = true
        if (addToHistory) {
            pushCurrentState(true, hasBaseImage)
        }
        notifyContentChanged()
        invalidate()
        return true
    }

    private fun updateTextPosition(x: Float, y: Float) {
        textPosition.set(x, y)
        clampTextPosition()
        invalidate()
    }

    private fun commitCurrentPath(addToHistory: Boolean = true) {
        if (path.isEmpty) return
        val canvas = extraCanvas ?: return
        canvas.drawPath(path, currentCommitPaint())
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

    private fun drawTextLines(canvas: Canvas, paint: Paint, x: Float, y: Float) {
        if (textContent.isBlank()) return
        var currentY = y
        val lines = textContent.lines()
        for (line in lines) {
            canvas.drawText(line, x, currentY, paint)
            currentY += paint.fontSpacing
        }
    }

    private fun currentTextBounds(paint: Paint = textPreviewPaint): RectF? {
        if (textContent.isBlank()) return null
        val lines = textContent.lines()
        if (lines.isEmpty()) return null
        val metrics = paint.fontMetrics
        val maxWidth = lines.maxOf { max(1f, paint.measureText(it)) }
        val lineHeight = paint.fontSpacing
        val top = textPosition.y + metrics.ascent
        val bottom = textPosition.y + metrics.descent + (lines.size - 1) * lineHeight
        return RectF(textPosition.x, top, textPosition.x + maxWidth, bottom)
    }

    private fun applyPenType(type: HandwritingPenType) {
        penPaint.maskFilter = null
        penPaint.pathEffect = null
        penPaint.strokeMiter = 4f
        penPaint.style = Paint.Style.STROKE
        when (type) {
            HandwritingPenType.ROUND -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.pathEffect = null
            }
            HandwritingPenType.MARKER -> {
                penPaint.strokeCap = Paint.Cap.BUTT
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.pathEffect = createMarkerPathEffect()
            }
            HandwritingPenType.CALLIGRAPHY -> {
                penPaint.strokeCap = Paint.Cap.BUTT
                penPaint.strokeJoin = Paint.Join.MITER
                penPaint.strokeMiter = 12f
                penPaint.pathEffect = createCalligraphyPathEffect()
            }
            HandwritingPenType.HIGHLIGHTER -> {
                penPaint.strokeCap = Paint.Cap.SQUARE
                penPaint.strokeJoin = Paint.Join.BEVEL
                penPaint.pathEffect = createHighlighterPathEffect()
            }
        }
    }

    private fun createMarkerPathEffect(): android.graphics.PathEffect {
        val jitter = max(1f, penPaint.strokeWidth * 0.45f)
        val segment = max(1f, penPaint.strokeWidth * 0.9f)
        val texture = DiscretePathEffect(segment, jitter)
        val soften = CornerPathEffect(penPaint.strokeWidth * 0.4f)
        return ComposePathEffect(soften, texture)
    }

    private fun createCalligraphyPathEffect(): android.graphics.PathEffect {
        val nib = buildCalligraphyNibPath(penPaint.strokeWidth)
        val advance = max(1f, penPaint.strokeWidth * 0.28f)
        val dash = PathDashPathEffect(nib, advance, 0f, PathDashPathEffect.Style.ROTATE)
        val smooth = CornerPathEffect(penPaint.strokeWidth * 0.2f)
        return ComposePathEffect(smooth, dash)
    }

    private fun createHighlighterPathEffect(): android.graphics.PathEffect {
        val softenRadius = max(1f, penPaint.strokeWidth * 0.75f)
        return CornerPathEffect(softenRadius)
    }

    private fun updatePenColor() {
        val baseColor = brushColorInt
        val updatedColor = when (penType) {
            HandwritingPenType.HIGHLIGHTER -> {
                val targetAlpha = (Color.alpha(baseColor) * 0.55f).roundToInt().coerceIn(16, 255)
                val brightened = ColorUtils.blendARGB(baseColor, Color.WHITE, 0.2f)
                ColorUtils.setAlphaComponent(brightened, targetAlpha)
            }
            else -> baseColor
        }
        penPaint.color = updatedColor
    }

    private fun updateTextColor() {
        textPaint.color = textColorInt
        textPreviewPaint.color = ColorUtils.setAlphaComponent(textColorInt, (0.85f * 255).roundToInt())
    }

    private fun buildCalligraphyNibPath(strokeWidth: Float): Path {
        val nibLength = max(2f, strokeWidth * 1.35f)
        val nibThickness = max(1f, strokeWidth * 0.45f)
        val nibPath = Path().apply {
            moveTo(-nibLength / 2f, 0f)
            lineTo(0f, nibThickness / 2f)
            lineTo(nibLength / 2f, 0f)
            lineTo(0f, -nibThickness / 2f)
            close()
        }
        val matrix = Matrix().apply { setRotate(-45f) }
        nibPath.transform(matrix)
        return nibPath
    }

    private fun applyEraserType(type: HandwritingEraserType) {
        when (type) {
            HandwritingEraserType.ROUND -> {
                eraserPaint.strokeCap = Paint.Cap.ROUND
                eraserPaint.strokeJoin = Paint.Join.ROUND
                eraserPaint.pathEffect = null
                eraserPreviewPaint.strokeCap = Paint.Cap.ROUND
                eraserPreviewPaint.strokeJoin = Paint.Join.ROUND
                eraserPreviewPaint.pathEffect = null
            }
            HandwritingEraserType.BLOCK -> {
                eraserPaint.strokeCap = Paint.Cap.SQUARE
                eraserPaint.strokeJoin = Paint.Join.BEVEL
                eraserPaint.pathEffect = null
                eraserPreviewPaint.strokeCap = Paint.Cap.SQUARE
                eraserPreviewPaint.strokeJoin = Paint.Join.BEVEL
                eraserPreviewPaint.pathEffect = null
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

    private fun clampTextPosition() {
        if (width <= 0 || height <= 0) return
        val clampedX = textPosition.x.coerceIn(0f, width.toFloat())
        val clampedY = textPosition.y.coerceIn(0f, height.toFloat())
        textPosition.set(clampedX, clampedY)
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

    private fun disallowParentIntercept(disallow: Boolean) {
        var viewParent: ViewParent? = parent
        while (viewParent != null) {
            viewParent.requestDisallowInterceptTouchEvent(disallow)
            viewParent = viewParent.parent
        }
    }

    private fun currentCommitPaint(): Paint = when (drawingTool) {
        PEN -> penPaint
        ERASER -> eraserPaint
        TEXT -> textPaint
    }

    private fun currentPreviewPaint(): Paint = when (drawingTool) {
        PEN -> penPaint
        ERASER -> eraserPreviewPaint
        TEXT -> textPreviewPaint
    }
}
