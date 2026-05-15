package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewParent
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.example.timescapedemo.HandwritingDrawingTool.ERASER
import com.example.timescapedemo.HandwritingDrawingTool.PEN
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class StateSnapshot(
        val bitmap: Bitmap,
        val hasDrawing: Boolean,
        val hasBase: Boolean,
        val viewportScale: Float,
        val viewportOffsetX: Float,
        val viewportOffsetY: Float
    )

    private data class PointFCompat(val x: Float, val y: Float)

    private val density = resources.displayMetrics.density
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * density
    }
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
    private var paperStyle: HandwritingPaperStyle = HandwritingPaperStyle.PLAIN
    private var penType: HandwritingPenType = HandwritingPenType.ROUND
    private var eraserType: HandwritingEraserType = HandwritingEraserType.ROUND
    private var drawingTool: HandwritingDrawingTool = PEN
    private var targetAspectRatio: Float? = null
    private var exportWidth = 0
    private var exportHeight = 0
    private var viewportScale = 1f
    private var viewportOffsetX = 0f
    private var viewportOffsetY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var isScaling = false
    private var isMultiTouchGesture = false
    private var lastGestureFocusX = 0f
    private var lastGestureFocusY = 0f

    private var contentChangedListener: (() -> Unit)? = null
    private var canvasSizeChangedListener: ((Int, Int) -> Unit)? = null

    private val maxHistory = 25
    private val minViewportScale = 0.35f
    private val maxViewportScale = 4f
    private val edgeExpansionThreshold = 32f * density
    private val canvasExpansionPadding = 256f * density
    private val maxBitmapDimension = 8192
    private val historyBitmapPixelBudget = 48_000_000L

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
                commitCurrentPath()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                isScaling = false
            }
        }
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        applyPenType(penType)
        updatePenColor()
        applyEraserType(eraserType)
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
        if (extraBitmap == null) {
            val initialWidth = exportWidth.takeIf { it > 0 } ?: w
            val initialHeight = exportHeight.takeIf { it > 0 } ?: h
            createBlankBitmap(initialWidth, initialHeight)
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
        }
        fitViewportIfNeeded()
        invalidate()
        notifyContentChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColorInt)
        val bitmap = extraBitmap
        if (bitmap != null) {
            canvas.save()
            canvas.translate(viewportOffsetX, viewportOffsetY)
            canvas.scale(viewportScale, viewportScale)
            drawPaperGuides(canvas, bitmap.width.toFloat(), bitmap.height.toFloat(), 1f)
            canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
            canvas.drawPath(path, currentPreviewPaint())
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        disallowParentIntercept(true)
        if (event.pointerCount > 1) {
            isMultiTouchGesture = true
        }
        if (event.pointerCount > 1 || isScaling || isMultiTouchGesture) {
            scaleGestureDetector.onTouchEvent(event)
            handleTwoFingerPan(event)
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.actionMasked == MotionEvent.ACTION_DOWN) {
                commitCurrentPath()
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                isScaling = false
                isMultiTouchGesture = false
                disallowParentIntercept(false)
            }
            invalidate()
            return true
        }

        val contentPoint = screenToContent(event.x, event.y)
        val x = contentPoint.x
        val y = contentPoint.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                touchStart(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex >= 0) {
                    val point = screenToContent(event.getX(pointerIndex), event.getY(pointerIndex))
                    touchMove(point.x, point.y)
                }
            }
            MotionEvent.ACTION_UP -> {
                val point = screenToContent(event.x, event.y)
                touchMove(point.x, point.y)
                touchUp()
                performClick()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                disallowParentIntercept(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                touchCancel()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                disallowParentIntercept(false)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                commitCurrentPath()
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
        restoreSnapshot(previous)
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
        val targetW = source.width
        val targetH = source.height
        val result = Bitmap.createBitmap(targetW, targetH, Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(backgroundColorInt)
        drawPaperGuides(canvas, targetW.toFloat(), targetH.toFloat(), 1f)
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
        invalidate()
    }

    fun setCanvasSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        val newWidth = widthPx.coerceAtMost(maxBitmapDimension)
        val newHeight = heightPx.coerceAtMost(maxBitmapDimension)
        if (newWidth == exportWidth && newHeight == exportHeight && extraBitmap?.width == newWidth && extraBitmap?.height == newHeight) return
        commitCurrentPath()
        resizeBitmapToFit(newWidth, newHeight)
        if (history.isEmpty()) {
            pushCurrentState(hasContent, hasBaseImage)
        }
        targetAspectRatio = newHeight.toFloat() / newWidth.toFloat()
        requestLayout()
    }

    fun getCanvasContentSize(): Pair<Int, Int> {
        val bitmap = extraBitmap
        return if (bitmap != null) bitmap.width to bitmap.height else exportWidth to exportHeight
    }

    fun setOnContentChangedListener(listener: (() -> Unit)?) {
        contentChangedListener = listener
    }

    fun setOnCanvasSizeChangedListener(listener: ((Int, Int) -> Unit)?) {
        canvasSizeChangedListener = listener
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
        val adjusted = ensurePointInsideExpandableCanvas(x, y)
        path.moveTo(adjusted.x, adjusted.y)
        currentX = adjusted.x
        currentY = adjusted.y
    }

    private fun touchMove(x: Float, y: Float) {
        val adjusted = ensurePointInsideExpandableCanvas(x, y)
        val dx = abs(adjusted.x - currentX)
        val dy = abs(adjusted.y - currentY)
        if (dx >= touchTolerance || dy >= touchTolerance) {
            path.quadTo(currentX, currentY, (adjusted.x + currentX) / 2, (adjusted.y + currentY) / 2)
            currentX = adjusted.x
            currentY = adjusted.y
        }
    }

    private fun touchUp() {
        ensurePointInsideExpandableCanvas(currentX, currentY)
        path.lineTo(currentX, currentY)
        commitCurrentPath()
    }

    private fun touchCancel() {
        path.reset()
    }

    private fun commitCurrentPath(addToHistory: Boolean = true) {
        if (path.isEmpty) return
        val canvas = extraCanvas ?: return
        canvas.drawPath(path, currentCommitPaint())
        path.reset()
        hasContent = true
        extraBitmap?.let { bitmap ->
            exportWidth = bitmap.width
            exportHeight = bitmap.height
        }
        if (addToHistory) {
            pushCurrentState(true, hasBaseImage)
        }
        notifyContentChanged()
    }

    private fun createBlankBitmap(widthPx: Int, heightPx: Int) {
        val safeWidth = widthPx.coerceIn(1, maxBitmapDimension)
        val safeHeight = heightPx.coerceIn(1, maxBitmapDimension)
        val newBitmap = Bitmap.createBitmap(safeWidth, safeHeight, Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        extraBitmap?.recycle()
        extraBitmap = newBitmap
        extraCanvas = newCanvas
        exportWidth = safeWidth
        exportHeight = safeHeight
        targetAspectRatio = safeHeight.toFloat() / safeWidth.toFloat()
        notifyCanvasSizeChanged()
    }

    private fun resizeBitmap(
        widthPx: Int,
        heightPx: Int,
        offsetX: Int,
        offsetY: Int,
        addToHistory: Boolean
    ): Boolean {
        val oldBitmap = extraBitmap
        val safeWidth = widthPx.coerceIn(1, maxBitmapDimension)
        val safeHeight = heightPx.coerceIn(1, maxBitmapDimension)
        if (oldBitmap != null && oldBitmap.width == safeWidth && oldBitmap.height == safeHeight && offsetX == 0 && offsetY == 0) {
            return false
        }
        val newBitmap = Bitmap.createBitmap(safeWidth, safeHeight, Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        oldBitmap?.let { newCanvas.drawBitmap(it, offsetX.toFloat(), offsetY.toFloat(), null) }
        if (offsetX != 0 || offsetY != 0) {
            path.offset(offsetX.toFloat(), offsetY.toFloat())
            currentX += offsetX
            currentY += offsetY
            viewportOffsetX -= offsetX * viewportScale
            viewportOffsetY -= offsetY * viewportScale
        }
        if (oldBitmap != null && !oldBitmap.isRecycled) oldBitmap.recycle()
        extraBitmap = newBitmap
        extraCanvas = newCanvas
        exportWidth = safeWidth
        exportHeight = safeHeight
        targetAspectRatio = safeHeight.toFloat() / safeWidth.toFloat()
        clampViewport()
        if (addToHistory) pushCurrentState(hasContent, hasBaseImage)
        notifyCanvasSizeChanged()
        return true
    }

    private fun resizeBitmapToFit(widthPx: Int, heightPx: Int) {
        val oldBitmap = extraBitmap
        val safeWidth = widthPx.coerceIn(1, maxBitmapDimension)
        val safeHeight = heightPx.coerceIn(1, maxBitmapDimension)
        val newBitmap = Bitmap.createBitmap(safeWidth, safeHeight, Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        oldBitmap?.let { source ->
            val destRect = RectF(0f, 0f, safeWidth.toFloat(), safeHeight.toFloat())
            val srcRect = Rect(0, 0, source.width, source.height)
            val srcRatio = source.width.toFloat() / source.height.toFloat()
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
            newCanvas.drawBitmap(source, srcRect, drawRect, null)
        }
        if (oldBitmap != null && !oldBitmap.isRecycled) oldBitmap.recycle()
        extraBitmap = newBitmap
        extraCanvas = newCanvas
        exportWidth = safeWidth
        exportHeight = safeHeight
        targetAspectRatio = safeHeight.toFloat() / safeWidth.toFloat()
        viewportScale = 1f
        viewportOffsetX = 0f
        viewportOffsetY = 0f
        fitViewportIfNeeded()
        notifyCanvasSizeChanged()
    }

    private fun restoreSnapshot(snapshot: StateSnapshot) {
        val restored = snapshot.bitmap.copy(Config.ARGB_8888, false)
        extraBitmap?.recycle()
        extraBitmap = restored
        extraCanvas = Canvas(restored)
        exportWidth = restored.width
        exportHeight = restored.height
        targetAspectRatio = restored.height.toFloat() / restored.width.toFloat()
        viewportScale = snapshot.viewportScale
        viewportOffsetX = snapshot.viewportOffsetX
        viewportOffsetY = snapshot.viewportOffsetY
        clampViewport()
        notifyCanvasSizeChanged()
    }

    private fun ensurePointInsideExpandableCanvas(x: Float, y: Float): PointFCompat {
        val bitmap = extraBitmap ?: return PointFCompat(x, y)
        var addLeft = 0
        var addTop = 0
        var addRight = 0
        var addBottom = 0
        if (x < edgeExpansionThreshold && bitmap.width < maxBitmapDimension) {
            addLeft = min(canvasExpansionPadding.roundToInt(), maxBitmapDimension - bitmap.width)
        }
        if (y < edgeExpansionThreshold && bitmap.height < maxBitmapDimension) {
            addTop = min(canvasExpansionPadding.roundToInt(), maxBitmapDimension - bitmap.height)
        }
        if (x > bitmap.width - edgeExpansionThreshold && bitmap.width < maxBitmapDimension) {
            addRight = min(canvasExpansionPadding.roundToInt(), maxBitmapDimension - bitmap.width)
        }
        if (y > bitmap.height - edgeExpansionThreshold && bitmap.height < maxBitmapDimension) {
            addBottom = min(canvasExpansionPadding.roundToInt(), maxBitmapDimension - bitmap.height)
        }
        if (addLeft != 0 || addTop != 0 || addRight != 0 || addBottom != 0) {
            resizeBitmap(
                widthPx = bitmap.width + addLeft + addRight,
                heightPx = bitmap.height + addTop + addBottom,
                offsetX = addLeft,
                offsetY = addTop,
                addToHistory = false
            )
            requestLayout()
            return PointFCompat(x + addLeft, y + addTop)
        }
        return PointFCompat(x.coerceIn(0f, bitmap.width.toFloat()), y.coerceIn(0f, bitmap.height.toFloat()))
    }

    private fun screenToContent(x: Float, y: Float): PointFCompat = PointFCompat(
        ((x - viewportOffsetX) / viewportScale),
        ((y - viewportOffsetY) / viewportScale)
    )

    private fun zoomBy(scaleFactor: Float, focusX: Float, focusY: Float) {
        val oldScale = viewportScale
        val newScale = (viewportScale * scaleFactor).coerceIn(minViewportScale, maxViewportScale)
        if (newScale == oldScale) return
        val contentFocusX = (focusX - viewportOffsetX) / oldScale
        val contentFocusY = (focusY - viewportOffsetY) / oldScale
        viewportScale = newScale
        viewportOffsetX = focusX - contentFocusX * newScale
        viewportOffsetY = focusY - contentFocusY * newScale
        clampViewport()
    }

    private fun handleTwoFingerPan(event: MotionEvent) {
        if (event.pointerCount < 2) return
        val focusX = (event.getX(0) + event.getX(1)) / 2f
        val focusY = (event.getY(0) + event.getY(1)) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_DOWN -> {
                lastGestureFocusX = focusX
                lastGestureFocusY = focusY
            }
            MotionEvent.ACTION_MOVE -> {
                viewportOffsetX += focusX - lastGestureFocusX
                viewportOffsetY += focusY - lastGestureFocusY
                lastGestureFocusX = focusX
                lastGestureFocusY = focusY
                clampViewport()
            }
        }
    }

    private fun fitViewportIfNeeded() {
        val bitmap = extraBitmap ?: return
        if (width <= 0 || height <= 0) return
        val fitScale = min(width.toFloat() / bitmap.width.toFloat(), height.toFloat() / bitmap.height.toFloat())
            .takeIf { it.isFinite() && it > 0f }
            ?: 1f
        if (viewportScale == 1f && viewportOffsetX == 0f && viewportOffsetY == 0f) {
            viewportScale = fitScale.coerceIn(minViewportScale, maxViewportScale)
            viewportOffsetX = (width - bitmap.width * viewportScale) / 2f
            viewportOffsetY = (height - bitmap.height * viewportScale) / 2f
        }
        clampViewport()
    }

    private fun clampViewport() {
        val bitmap = extraBitmap ?: return
        if (width <= 0 || height <= 0) return
        val scaledWidth = bitmap.width * viewportScale
        val scaledHeight = bitmap.height * viewportScale
        viewportOffsetX = if (scaledWidth <= width) {
            (width - scaledWidth) / 2f
        } else {
            viewportOffsetX.coerceIn(width - scaledWidth, 0f)
        }
        viewportOffsetY = if (scaledHeight <= height) {
            (height - scaledHeight) / 2f
        } else {
            viewportOffsetY.coerceIn(height - scaledHeight, 0f)
        }
    }

    private fun drawBitmapOntoCanvas(bitmap: Bitmap, recycleAfter: Boolean = false) {
        val canvas = extraCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        val destRect = extraBitmap?.let { RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
            ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
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

    private fun pushCurrentState(hasDrawing: Boolean, hasBase: Boolean) {
        val source = extraBitmap ?: return
        val snapshot = source.copy(Config.ARGB_8888, false)
        history.addLast(StateSnapshot(snapshot, hasDrawing, hasBase, viewportScale, viewportOffsetX, viewportOffsetY))
        trimHistory()
    }

    private fun replaceHistoryWithCurrent(hasDrawing: Boolean, hasBase: Boolean) {
        recycleHistory()
        history.clear()
        pushCurrentState(hasDrawing, hasBase)
    }

    private fun trimHistory() {
        while (history.size > maxHistory || historyPixelCount() > historyBitmapPixelBudget) {
            if (history.size <= 1) return
            val removed = history.removeFirst()
            if (!removed.bitmap.isRecycled) removed.bitmap.recycle()
        }
    }

    private fun historyPixelCount(): Long = history.sumOf { snapshot ->
        snapshot.bitmap.width.toLong() * snapshot.bitmap.height.toLong()
    }

    private fun recycleHistory() {
        history.forEach { snapshot ->
            if (!snapshot.bitmap.isRecycled) snapshot.bitmap.recycle()
        }
    }

    private fun notifyCanvasSizeChanged() {
        canvasSizeChangedListener?.invoke(exportWidth, exportHeight)
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
    }

    private fun currentPreviewPaint(): Paint = when (drawingTool) {
        PEN -> penPaint
        ERASER -> eraserPreviewPaint
    }
}
