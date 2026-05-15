package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathDashPathEffect
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.example.timescapedemo.HandwritingDrawingTool.ERASER
import com.example.timescapedemo.HandwritingDrawingTool.PEN
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class StrokePoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
    private data class HandwritingStroke(
        val points: MutableList<StrokePoint>,
        @ColorInt val color: Int,
        val width: Float,
        val tool: HandwritingDrawingTool,
        val penType: HandwritingPenType,
        val eraserType: HandwritingEraserType,
        val bounds: RectF = RectF()
    )

    private val density = resources.displayMetrics.density
    private val strokes = mutableListOf<HandwritingStroke>()
    private val undoneStrokes = mutableListOf<HandwritingStroke>()
    private var activeStroke: HandwritingStroke? = null
    private var activePath = Path()

    private var pendingBitmap: Bitmap? = null
    private var baseBitmap: Bitmap? = null
    private var hasBaseImage = false
    private var baseDrawRect = RectF()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastPanY = 0f
    private val touchTolerance = 3f * density

    private var viewportOffsetY = 0f
    private var virtualCanvasHeight = 0f
    private var exportWidth = 0
    private var exportHeight = 0
    private var targetAspectRatio: Float? = null

    @ColorInt private var backgroundColorInt: Int = Color.parseColor("#FFF8EA")
    @ColorInt private var brushColorInt: Int = Color.BLACK
    private var paperStyle: HandwritingPaperStyle = HandwritingPaperStyle.RULED
    private var penType: HandwritingPenType = HandwritingPenType.ROUND
    private var eraserType: HandwritingEraserType = HandwritingEraserType.ROUND
    private var drawingTool: HandwritingDrawingTool = PEN
    private var isPanning = false
    private var showExpansionHintUntil = 0L

    private var contentChangedListener: (() -> Unit)? = null

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 6f * density
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        strokeWidth = 16f * density
    }
    private val eraserPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 16f * density
        color = ColorUtils.setAlphaComponent(Color.BLACK, (0.22f * 255).roundToInt())
    }
    private val bitmapPaint = Paint(Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minimapTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val minimapThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8E6AD8")
        textAlign = Paint.Align.CENTER
        textSize = 12f * density
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        updatePenColor()
        applyPenType(penType)
        applyEraserType(eraserType)
        updateGuidePaintColor()
        minimapTrackPaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#8E6AD8"), 42)
        minimapThumbPaint.color = Color.parseColor("#9C73E6")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val ratio = targetAspectRatio
        if (ratio != null && ratio > 0f) {
            val width = measuredWidth
            if (width > 0) setMeasuredDimension(width, resolveSize((width * ratio).roundToInt(), heightMeasureSpec))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (virtualCanvasHeight <= 0f) {
            virtualCanvasHeight = max(h.toFloat() * 1.35f, (exportHeight.takeIf { it > 0 } ?: h).toFloat())
        }
        pendingBitmap?.let { bitmap ->
            installBaseBitmap(bitmap)
            pendingBitmap = null
        }
        clampViewport()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColorInt)
        drawPaperTexture(canvas)
        drawPaperGuides(canvas, width.toFloat(), height.toFloat(), 1f, viewportOffsetY)
        drawBaseBitmap(canvas)
        drawVisibleStrokes(canvas)
        activeStroke?.let { canvas.drawPath(activePath, paintForStroke(it, preview = true)) }
        drawInfiniteCanvasCues(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                disallowParentIntercept(true)
                isPanning = false
                lastPanY = event.y
                touchStart(event.x.coerceIn(0f, width.toFloat()), event.y.coerceIn(0f, height.toFloat()), event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isPanning = true
                activeStroke = null
                activePath.reset()
                lastPanY = averagePointerY(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning || event.pointerCount > 1) {
                    val panY = averagePointerY(event)
                    scrollByVirtual(lastPanY - panY)
                    lastPanY = panY
                } else {
                    touchMove(event.x.coerceIn(0f, width.toFloat()), event.y.coerceIn(0f, height.toFloat()), event)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> lastPanY = averagePointerY(event)
            MotionEvent.ACTION_UP -> {
                if (!isPanning) touchUp()
                isPanning = false
                disallowParentIntercept(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                touchCancel()
                isPanning = false
                disallowParentIntercept(false)
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
        activeStroke = null
        activePath.reset()
        strokes.clear()
        undoneStrokes.clear()
        baseBitmap?.recycle()
        baseBitmap = null
        pendingBitmap?.recycle()
        pendingBitmap = null
        hasBaseImage = false
        viewportOffsetY = 0f
        if (hadAnyContent) notifyContentChanged()
        invalidate()
    }

    fun undo(): Boolean {
        activeStroke?.let { stroke ->
            activeStroke = null
            activePath.reset()
            undoneStrokes.add(stroke)
            invalidate()
            notifyContentChanged()
            return true
        }
        if (strokes.isEmpty()) return false
        undoneStrokes.add(strokes.removeAt(strokes.lastIndex))
        invalidate()
        notifyContentChanged()
        return true
    }

    fun redo(): Boolean {
        if (undoneStrokes.isEmpty()) return false
        strokes.add(undoneStrokes.removeAt(undoneStrokes.lastIndex))
        invalidate()
        notifyContentChanged()
        return true
    }

    fun canUndo(): Boolean = activeStroke != null || strokes.isNotEmpty()
    fun canRedo(): Boolean = undoneStrokes.isNotEmpty()
    fun hasDrawing(): Boolean = hasBaseImage || strokes.isNotEmpty() || activeStroke != null

    fun setBitmap(bitmap: Bitmap?) {
        pendingBitmap?.recycle()
        pendingBitmap = null
        if (bitmap == null) {
            clear()
            return
        }
        val copy = bitmap.copy(Config.ARGB_8888, false)
        if (!bitmap.isRecycled) bitmap.recycle()
        if (width > 0 && height > 0) installBaseBitmap(copy) else pendingBitmap = copy
        invalidate()
        notifyContentChanged()
    }

    fun exportBitmap(): Bitmap? {
        commitActiveStroke()
        val targetW = exportWidth.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: return null
        val visibleRatioHeight = height.takeIf { it > 0 } ?: targetW
        val targetH = min(
            (virtualCanvasHeight * (targetW.toFloat() / width.coerceAtLeast(1).toFloat())).roundToInt().coerceAtLeast(visibleRatioHeight),
            4096
        )
        val result = Bitmap.createBitmap(targetW, targetH, Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(backgroundColorInt)
        val scale = targetW.toFloat() / width.coerceAtLeast(1).toFloat()
        canvas.save()
        canvas.scale(scale, scale)
        drawPaperGuides(canvas, width.toFloat(), targetH / scale, 1f, 0f)
        baseBitmap?.let { bitmap -> canvas.drawBitmap(bitmap, null, baseDrawRect, bitmapPaint) }
        drawStrokes(canvas, 0f, targetH / scale, targetH / scale)
        canvas.restore()
        return result
    }

    fun setCanvasBackgroundColor(@ColorInt color: Int) {
        backgroundColorInt = if (color == Color.WHITE) Color.parseColor("#FFF8EA") else color
        updateGuidePaintColor()
        invalidate()
    }

    fun setPaperStyle(style: HandwritingPaperStyle) {
        paperStyle = if (style == HandwritingPaperStyle.PLAIN) HandwritingPaperStyle.RULED else style
        invalidate()
    }

    fun getPaperStyle(): HandwritingPaperStyle = paperStyle

    fun setBrushColor(@ColorInt color: Int) { brushColorInt = color; updatePenColor() }
    fun setBrushSizeDp(sizeDp: Float) = setBrushSizePx(sizeDp * density)
    fun setBrushSizePx(sizePx: Float) { penPaint.strokeWidth = sizePx; applyPenType(penType); updatePenColor(); invalidate() }
    fun getBrushSizeDp(): Float = penPaint.strokeWidth / density
    fun setPenType(type: HandwritingPenType) { penType = type; applyPenType(type); updatePenColor(); invalidate() }
    fun getPenType(): HandwritingPenType = penType
    fun setEraserSizeDp(sizeDp: Float) { eraserPaint.strokeWidth = sizeDp * density; eraserPreviewPaint.strokeWidth = eraserPaint.strokeWidth; invalidate() }
    fun getEraserSizeDp(): Float = eraserPaint.strokeWidth / density
    fun setEraserType(type: HandwritingEraserType) { eraserType = type; applyEraserType(type); invalidate() }
    fun getEraserType(): HandwritingEraserType = eraserType

    fun setDrawingTool(tool: HandwritingDrawingTool) {
        if (drawingTool == tool) return
        commitActiveStroke()
        drawingTool = tool
        invalidate()
    }

    fun setCanvasSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        exportWidth = widthPx
        exportHeight = heightPx
        targetAspectRatio = null
        virtualCanvasHeight = max(virtualCanvasHeight, heightPx.toFloat())
        requestLayout()
        invalidate()
    }

    fun setOnContentChangedListener(listener: (() -> Unit)?) { contentChangedListener = listener }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        baseBitmap?.recycle()
        pendingBitmap?.recycle()
        baseBitmap = null
        pendingBitmap = null
    }

    private fun touchStart(x: Float, y: Float, event: MotionEvent) {
        // Convert viewport touch coordinates into virtual notebook coordinates so
        // strokes remain fixed on the infinite canvas while the viewport scrolls.
        val canvasY = y + viewportOffsetY
        val widthPx = if (drawingTool == PEN) penPaint.strokeWidth else eraserPaint.strokeWidth
        val color = if (drawingTool == PEN) penPaint.color else Color.TRANSPARENT
        activeStroke = HandwritingStroke(
            points = mutableListOf(StrokePoint(x, canvasY, event.getPressure(0), event.eventTime)),
            color = color,
            width = widthPx,
            tool = drawingTool,
            penType = penType,
            eraserType = eraserType
        ).also { it.bounds.set(x, canvasY, x, canvasY) }
        activePath.reset()
        activePath.moveTo(x, y)
        lastTouchX = x
        lastTouchY = y
        maybeExpandFor(canvasY)
    }

    private fun touchMove(x: Float, y: Float, event: MotionEvent) {
        val stroke = activeStroke ?: return
        val dx = abs(x - lastTouchX)
        val dy = abs(y - lastTouchY)
        if (dx >= touchTolerance || dy >= touchTolerance) {
            activePath.quadTo(lastTouchX, lastTouchY, (x + lastTouchX) / 2f, (y + lastTouchY) / 2f)
            val canvasY = y + viewportOffsetY
            stroke.points.add(StrokePoint(x, canvasY, event.getPressure(0), event.eventTime))
            stroke.bounds.union(x, canvasY)
            lastTouchX = x
            lastTouchY = y
            maybeExpandFor(canvasY)
        }
    }

    private fun touchUp() {
        activeStroke?.let { stroke ->
            val canvasY = lastTouchY + viewportOffsetY
            stroke.points.add(StrokePoint(lastTouchX, canvasY, 1f, System.currentTimeMillis()))
            activePath.lineTo(lastTouchX, lastTouchY)
        }
        commitActiveStroke()
    }

    private fun touchCancel() {
        activeStroke = null
        activePath.reset()
    }

    private fun commitActiveStroke() {
        val stroke = activeStroke ?: return
        if (stroke.points.isNotEmpty()) {
            strokes.add(stroke)
            undoneStrokes.clear()
        }
        activeStroke = null
        activePath.reset()
        notifyContentChanged()
    }

    private fun installBaseBitmap(bitmap: Bitmap) {
        baseBitmap?.recycle()
        baseBitmap = bitmap
        hasBaseImage = true
        val destRect = RectF(0f, 0f, width.toFloat(), max(height.toFloat(), virtualCanvasHeight))
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val scaledHeight = destRect.width() / srcRatio
        baseDrawRect.set(destRect.left, destRect.top, destRect.right, min(scaledHeight, virtualCanvasHeight))
        virtualCanvasHeight = max(virtualCanvasHeight, baseDrawRect.bottom)
        notifyContentChanged()
    }

    private fun drawBaseBitmap(canvas: Canvas) {
        val bitmap = baseBitmap ?: return
        val visible = RectF(0f, viewportOffsetY, width.toFloat(), viewportOffsetY + height)
        if (!RectF.intersects(baseDrawRect, visible)) return
        val dest = RectF(baseDrawRect).apply { offset(0f, -viewportOffsetY) }
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dest, bitmapPaint)
    }

    private fun drawVisibleStrokes(canvas: Canvas) {
        drawStrokes(canvas, viewportOffsetY, viewportOffsetY + height, height.toFloat())
    }

    private fun drawStrokes(canvas: Canvas, visibleTop: Float, visibleBottom: Float, layerHeight: Float) {
        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), layerHeight, null)
        strokes.forEach { stroke ->
            if (stroke.bounds.bottom + stroke.width < visibleTop || stroke.bounds.top - stroke.width > visibleBottom) return@forEach
            val path = buildScreenPath(stroke, visibleTop)
            canvas.drawPath(path, paintForStroke(stroke, preview = false))
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun buildScreenPath(stroke: HandwritingStroke, offsetY: Float): Path {
        val path = Path()
        val points = stroke.points
        if (points.isEmpty()) return path
        // Convert virtual notebook coordinates back into viewport coordinates for rendering.
        path.moveTo(points.first().x, points.first().y - offsetY)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val point = points[i]
            path.quadTo(previous.x, previous.y - offsetY, (point.x + previous.x) / 2f, ((point.y + previous.y) / 2f) - offsetY)
        }
        return path
    }

    private fun paintForStroke(stroke: HandwritingStroke, preview: Boolean): Paint {
        return if (stroke.tool == ERASER) {
            if (preview) eraserPreviewPaint else eraserPaint
        } else {
            penPaint.color = stroke.color
            penPaint.strokeWidth = stroke.width
            applyPenType(stroke.penType)
            penPaint
        }
    }

    private fun drawPaperTexture(canvas: Canvas) {
        texturePaint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), ColorUtils.setAlphaComponent(Color.WHITE, 55), ColorUtils.setAlphaComponent(Color.parseColor("#EEDDBD"), 30), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), texturePaint)
        texturePaint.shader = null
        texturePaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#B9855D"), 10)
        val step = 18f * density
        var y = (-(viewportOffsetY % step))
        while (y < height) {
            canvas.drawPoint((width * 0.18f + y * 3f) % width, y, texturePaint)
            canvas.drawPoint((width * 0.73f + y * 1.7f) % width, y + step / 2f, texturePaint)
            y += step
        }
    }

    private fun drawPaperGuides(canvas: Canvas, width: Float, height: Float, scale: Float, offsetY: Float) {
        val spacing = 31f * density * scale
        val stroke = max(1f, 0.75f * density * scale)
        guidePaint.strokeWidth = stroke
        marginPaint.strokeWidth = max(1f, 1f * density * scale)
        if (paperStyle == HandwritingPaperStyle.GRID) {
            var x = spacing
            while (x < width) { canvas.drawLine(x, 0f, x, height, guidePaint); x += spacing }
        }
        val firstLineIndex = floor(offsetY / spacing).toInt() - 1
        var y = firstLineIndex * spacing - offsetY
        while (y < height) {
            if (y >= 0f) canvas.drawLine(0f, y, width, y, guidePaint)
            y += spacing
        }
        val marginX = 42f * density * scale
        canvas.drawLine(marginX, 0f, marginX, height, marginPaint)
    }

    private fun drawInfiniteCanvasCues(canvas: Canvas) {
        fadePaint.shader = LinearGradient(0f, height - 96f * density, 0f, height.toFloat(), Color.TRANSPARENT, backgroundColorInt, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height - 96f * density, width.toFloat(), height.toFloat(), fadePaint)
        fadePaint.shader = null

        val trackHeight = height * 0.72f
        val trackTop = height * 0.12f
        val trackRight = width - 10f * density
        val trackWidth = 3f * density
        canvas.drawRoundRect(trackRight, trackTop, trackRight + trackWidth, trackTop + trackHeight, trackWidth, trackWidth, minimapTrackPaint)
        val viewportRatio = (height / virtualCanvasHeight.coerceAtLeast(height.toFloat())).coerceIn(0.12f, 1f)
        val thumbHeight = trackHeight * viewportRatio
        val scrollable = (virtualCanvasHeight - height).coerceAtLeast(1f)
        val thumbTop = trackTop + (trackHeight - thumbHeight) * (viewportOffsetY / scrollable).coerceIn(0f, 1f)
        canvas.drawRoundRect(trackRight - 1f * density, thumbTop, trackRight + trackWidth + 1f * density, thumbTop + thumbHeight, 4f * density, 4f * density, minimapThumbPaint)

        val shouldHint = System.currentTimeMillis() < showExpansionHintUntil || viewportOffsetY + height > virtualCanvasHeight - 360f * density
        if (shouldHint) {
            hintPaint.alpha = 170
            canvas.drawText("More space appears automatically ↓", width / 2f, height - 26f * density, hintPaint)
            hintPaint.alpha = 255
        }
    }

    private fun scrollByVirtual(deltaY: Float) {
        viewportOffsetY += deltaY
        clampViewport()
    }

    private fun clampViewport() {
        viewportOffsetY = viewportOffsetY.coerceIn(0f, (virtualCanvasHeight - height).coerceAtLeast(0f))
    }

    private fun maybeExpandFor(canvasY: Float) {
        val threshold = 240f * density
        if (canvasY > virtualCanvasHeight - threshold) {
            virtualCanvasHeight += 960f * density
            showExpansionHintUntil = System.currentTimeMillis() + 2200L
        }
    }

    private fun averagePointerY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getY(i)
        return total / event.pointerCount.coerceAtLeast(1)
    }

    private fun updatePenColor() {
        penPaint.color = when (penType) {
            HandwritingPenType.HIGHLIGHTER -> ColorUtils.setAlphaComponent(ColorUtils.blendARGB(brushColorInt, Color.WHITE, 0.2f), (Color.alpha(brushColorInt) * 0.55f).roundToInt().coerceIn(16, 255))
            else -> brushColorInt
        }
    }

    private fun applyPenType(type: HandwritingPenType) {
        penPaint.maskFilter = null
        penPaint.pathEffect = null
        penPaint.style = Paint.Style.STROKE
        when (type) {
            HandwritingPenType.ROUND -> { penPaint.strokeCap = Paint.Cap.ROUND; penPaint.strokeJoin = Paint.Join.ROUND }
            HandwritingPenType.MARKER -> { penPaint.strokeCap = Paint.Cap.BUTT; penPaint.strokeJoin = Paint.Join.BEVEL; penPaint.pathEffect = CornerPathEffect(penPaint.strokeWidth * 0.35f) }
            HandwritingPenType.CALLIGRAPHY -> { penPaint.strokeCap = Paint.Cap.BUTT; penPaint.strokeJoin = Paint.Join.MITER; penPaint.pathEffect = createCalligraphyPathEffect() }
            HandwritingPenType.HIGHLIGHTER -> { penPaint.strokeCap = Paint.Cap.SQUARE; penPaint.strokeJoin = Paint.Join.BEVEL; penPaint.pathEffect = CornerPathEffect(penPaint.strokeWidth * 0.75f) }
        }
    }

    private fun createCalligraphyPathEffect(): android.graphics.PathEffect {
        val nibLength = max(2f, penPaint.strokeWidth * 1.35f)
        val nibThickness = max(1f, penPaint.strokeWidth * 0.45f)
        val nibPath = Path().apply {
            moveTo(-nibLength / 2f, 0f); lineTo(0f, nibThickness / 2f); lineTo(nibLength / 2f, 0f); lineTo(0f, -nibThickness / 2f); close()
        }
        nibPath.transform(Matrix().apply { setRotate(-45f) })
        return PathDashPathEffect(nibPath, max(1f, penPaint.strokeWidth * 0.28f), 0f, PathDashPathEffect.Style.ROTATE)
    }

    private fun applyEraserType(type: HandwritingEraserType) {
        val cap = if (type == HandwritingEraserType.BLOCK) Paint.Cap.SQUARE else Paint.Cap.ROUND
        val join = if (type == HandwritingEraserType.BLOCK) Paint.Join.BEVEL else Paint.Join.ROUND
        eraserPaint.strokeCap = cap; eraserPaint.strokeJoin = join; eraserPaint.pathEffect = null
        eraserPreviewPaint.strokeCap = cap; eraserPreviewPaint.strokeJoin = join; eraserPreviewPaint.pathEffect = null
    }

    private fun updateGuidePaintColor() {
        guidePaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#9EB2C0"), 78)
        marginPaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#E79AA5"), 120)
    }

    private fun disallowParentIntercept(disallow: Boolean) {
        var parentView: ViewParent? = parent
        while (parentView != null) {
            parentView.requestDisallowInterceptTouchEvent(disallow)
            parentView = parentView.parent
        }
    }
}
