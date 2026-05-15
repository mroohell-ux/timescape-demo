package com.example.timescapedemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen infinite notebook viewport for Timescape handwriting notes.
 *
 * This view intentionally does not maintain a giant backing bitmap.  The screen
 * is a viewport into [document]'s virtual canvas, handwriting is stored as
 * vector [StrokeElement]s, and paper lines are generated from the current
 * viewport offset/zoom every frame.
 */
class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val editor = NoteEditorViewModel()
    private val document: NoteDocument get() = editor.document
    private val viewport: ViewportState get() = document.viewportState

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, density)
        color = ColorUtils.setAlphaComponent(Color.rgb(152, 132, 176), 60)
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1.2f, density * 1.2f)
        color = ColorUtils.setAlphaComponent(Color.rgb(154, 92, 132), 105)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val minimapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ColorUtils.setAlphaComponent(Color.rgb(117, 91, 208), 190)
    }

    private var currentStroke: StrokeElement? = null
    private var activeTool: NoteTool = NoteTool.PEN
    private var drawingTool: HandwritingDrawingTool = HandwritingDrawingTool.PEN
    private var brushColorInt: Int = Color.rgb(42, 36, 56)
    private var backgroundColorInt: Int = Color.rgb(255, 249, 235)
    private var brushSizeDp: Float = 4.5f
    private var eraserSizeDp: Float = 18f
    private var paperStyle: HandwritingPaperStyle = HandwritingPaperStyle.RULED
    private var penType: HandwritingPenType = HandwritingPenType.ROUND
    private var eraserType: HandwritingEraserType = HandwritingEraserType.ROUND
    private var exportWidth = 0
    private var exportHeight = 0
    private var targetAspectRatio: Float? = null
    private var contentChangedListener: (() -> Unit)? = null

    private var lastPanX = 0f
    private var lastPanY = 0f
    private var isPanning = false
    private var didMove = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAround(detector.focusX, detector.focusY, detector.scaleFactor)
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusable = true
        isClickable = true
        paperPaint.color = backgroundColorInt
        editor.toolState.color = brushColorInt
        editor.toolState.strokeWidth = brushSizeDp * density
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val ratio = targetAspectRatio
        if (ratio != null && ratio > 0f) {
            val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val desiredHeight = (width * ratio).roundToInt().coerceAtLeast(suggestedMinimumHeight)
            setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawPaper(canvas)
        drawVisibleElements(canvas)
        drawMinimap(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastPanX = event.x
                lastPanY = event.y
                didMove = false
                isPanning = shouldPan(event)
                if (!isPanning && !scaleDetector.isInProgress) handleCanvasDown(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                isPanning = true
                currentStroke = null
                lastPanX = event.x
                lastPanY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || event.pointerCount > 1 || isPanning) {
                    val dx = event.x - lastPanX
                    val dy = event.y - lastPanY
                    viewport.offsetX -= dx / viewport.zoom
                    viewport.offsetY -= dy / viewport.zoom
                    lastPanX = event.x
                    lastPanY = event.y
                    didMove = true
                    invalidate()
                } else {
                    handleCanvasMove(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isPanning) handleCanvasUp(event)
                isPanning = false
                currentStroke = null
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                currentStroke = null
                isPanning = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun clear() {
        if (document.elements.isEmpty()) return
        document.elements.toList().forEach { editor.removeElement(it) }
        editor.undoRedoManager.clear()
        invalidate()
        notifyContentChanged()
    }

    fun undo(): Boolean {
        val undone = editor.undoRedoManager.undo(document)
        if (undone) {
            invalidate()
            notifyContentChanged()
        }
        return undone
    }

    fun redo(): Boolean {
        val redone = editor.undoRedoManager.redo(document)
        if (redone) {
            invalidate()
            notifyContentChanged()
        }
        return redone
    }

    fun canUndo(): Boolean = editor.undoRedoManager.canUndo()
    fun canRedo(): Boolean = editor.undoRedoManager.canRedo()
    fun hasDrawing(): Boolean = document.elements.isNotEmpty() || currentStroke != null

    fun setBitmap(bitmap: Bitmap?) {
        clear()
        if (bitmap == null) return
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (!bitmap.isRecycled) bitmap.recycle()
        val maxWidth = min(1400f, copy.width.toFloat())
        val scale = maxWidth / copy.width.toFloat()
        val image = RuntimeBitmapElement(
            bitmap = copy,
            x = 96f,
            y = 96f,
            width = copy.width * scale,
            height = copy.height * scale
        )
        document.elements.add(image)
        notifyContentChanged()
        invalidate()
    }

    fun exportBitmap(): Bitmap? {
        val targetW = (exportWidth.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?: 1080).coerceAtMost(1600)
        val targetH = (exportHeight.takeIf { it > 0 } ?: height.takeIf { it > 0 } ?: 1440).coerceAtMost(2200)
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val exportCanvas = Canvas(bitmap)
        val savedViewport = viewport.copy()
        val contentBounds = contentBounds().takeIf { !it.isEmpty }
        viewport.offsetX = contentBounds?.left?.minus(48f) ?: savedViewport.offsetX
        viewport.offsetY = contentBounds?.top?.minus(48f) ?: savedViewport.offsetY
        viewport.zoom = min(targetW / ((contentBounds?.width() ?: targetW.toFloat()) + 96f), targetH / ((contentBounds?.height() ?: targetH.toFloat()) + 96f))
            .coerceIn(0.25f, 1.25f)
        drawPaper(exportCanvas, targetW, targetH)
        drawVisibleElements(exportCanvas, targetW, targetH, includeMinimap = false)
        viewport.offsetX = savedViewport.offsetX
        viewport.offsetY = savedViewport.offsetY
        viewport.zoom = savedViewport.zoom
        return bitmap
    }

    fun exportDocumentJson(): String = document.toJson().toString()

    fun setCanvasBackgroundColor(@ColorInt color: Int) {
        backgroundColorInt = color
        paperPaint.color = color
        invalidate()
    }

    fun setPaperStyle(style: HandwritingPaperStyle) {
        paperStyle = style
        invalidate()
    }

    fun getPaperStyle(): HandwritingPaperStyle = paperStyle

    fun setBrushColor(@ColorInt color: Int) {
        brushColorInt = color
        editor.toolState.color = color
        invalidate()
    }

    fun setBrushSizeDp(sizeDp: Float) {
        brushSizeDp = sizeDp.coerceIn(1f, 48f)
        editor.toolState.strokeWidth = brushSizeDp * density
    }

    fun getBrushSizeDp(): Float = brushSizeDp

    fun setPenType(type: HandwritingPenType) {
        penType = type
        activeTool = if (type == HandwritingPenType.HIGHLIGHTER) NoteTool.HIGHLIGHTER else NoteTool.PEN
        editor.toolState.activeTool = activeTool
    }

    fun getPenType(): HandwritingPenType = penType

    fun setEraserSizeDp(sizeDp: Float) {
        eraserSizeDp = sizeDp.coerceIn(4f, 96f)
    }

    fun getEraserSizeDp(): Float = eraserSizeDp

    fun setEraserType(type: HandwritingEraserType) {
        eraserType = type
    }

    fun getEraserType(): HandwritingEraserType = eraserType

    fun setDrawingTool(tool: HandwritingDrawingTool) {
        drawingTool = tool
        activeTool = if (tool == HandwritingDrawingTool.ERASER) NoteTool.ERASER else if (penType == HandwritingPenType.HIGHLIGHTER) NoteTool.HIGHLIGHTER else NoteTool.PEN
        editor.toolState.activeTool = activeTool
        invalidate()
    }

    fun setCanvasSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        exportWidth = widthPx
        exportHeight = heightPx
        document.canvasWidth = max(document.canvasWidth, widthPx.toFloat())
        document.canvasHeight = max(document.canvasHeight, heightPx.toFloat() * 4f)
        targetAspectRatio = null
        requestLayout()
    }

    fun setOnContentChangedListener(listener: (() -> Unit)?) {
        contentChangedListener = listener
    }

    private fun handleCanvasDown(event: MotionEvent) {
        val point = viewport.screenToCanvas(event.x, event.y).withPressure(event)
        if (activeTool == NoteTool.ERASER) {
            eraseAt(point.x, point.y)
        } else {
            currentStroke = StrokeElement(
                color = if (activeTool == NoteTool.HIGHLIGHTER) ColorUtils.setAlphaComponent(brushColorInt, 90) else brushColorInt,
                strokeWidth = brushSizeDp * density / viewport.zoom,
                tool = activeTool,
                zIndex = document.elements.size
            ).also { it.points.add(point) }
        }
        invalidate()
    }

    private fun handleCanvasMove(event: MotionEvent) {
        didMove = true
        val point = viewport.screenToCanvas(event.x, event.y).withPressure(event)
        if (activeTool == NoteTool.ERASER) {
            eraseAt(point.x, point.y)
        } else {
            currentStroke?.points?.add(point)
        }
        invalidate()
    }

    private fun handleCanvasUp(event: MotionEvent) {
        if (activeTool == NoteTool.ERASER) return
        currentStroke?.let { stroke ->
            if (stroke.points.size == 1) {
                stroke.points.add(viewport.screenToCanvas(event.x + 0.1f, event.y + 0.1f).withPressure(event))
            }
            stroke.recomputeBounds()
            editor.addElement(stroke)
            document.canvasWidth = max(document.canvasWidth, stroke.x + stroke.width + 512f)
            document.canvasHeight = max(document.canvasHeight, stroke.y + stroke.height + 1024f)
            notifyContentChanged()
        }
        currentStroke = null
        invalidate()
    }

    private fun eraseAt(canvasX: Float, canvasY: Float) {
        val radius = eraserSizeDp * density / viewport.zoom
        val hit = document.elements
            .filterIsInstance<StrokeElement>()
            .lastOrNull { stroke ->
                RectF(stroke.x - radius, stroke.y - radius, stroke.x + stroke.width + radius, stroke.y + stroke.height + radius).contains(canvasX, canvasY) &&
                    stroke.points.any { point -> hypot(point.x - canvasX, point.y - canvasY) <= radius }
            }
        if (hit != null) {
            editor.removeElement(hit)
            notifyContentChanged()
            invalidate()
        }
    }

    private fun shouldPan(event: MotionEvent): Boolean {
        val stylusDrawing = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS && activeTool != NoteTool.ERASER
        return event.pointerCount > 1 || activeTool == NoteTool.PAN || (!stylusDrawing && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && event.buttonState == MotionEvent.BUTTON_SECONDARY)
    }

    private fun zoomAround(screenX: Float, screenY: Float, scaleFactor: Float) {
        val before = viewport.screenToCanvas(screenX, screenY)
        viewport.zoom = (viewport.zoom * scaleFactor).coerceIn(0.35f, 3f)
        viewport.offsetX = before.x - screenX / viewport.zoom
        viewport.offsetY = before.y - screenY / viewport.zoom
        invalidate()
    }

    private fun drawPaper(canvas: Canvas, targetWidth: Int = width, targetHeight: Int = height) {
        canvas.drawColor(backgroundColorInt)
        if (paperStyle == HandwritingPaperStyle.PLAIN) return
        val spacing = 32f * density * viewport.zoom
        val offsetY = -((viewport.offsetY * viewport.zoom) % spacing)
        val offsetX = -((viewport.offsetX * viewport.zoom) % spacing)
        if (paperStyle == HandwritingPaperStyle.RULED) {
            var y = offsetY
            while (y < targetHeight + spacing) {
                canvas.drawLine(0f, y, targetWidth.toFloat(), y, guidePaint)
                y += spacing
            }
            val marginX = (72f * density - viewport.offsetX) * viewport.zoom
            if (marginX in -16f..(targetWidth + 16f).toFloat()) canvas.drawLine(marginX, 0f, marginX, targetHeight.toFloat(), marginPaint)
        } else {
            var y = offsetY
            while (y < targetHeight + spacing) {
                canvas.drawLine(0f, y, targetWidth.toFloat(), y, guidePaint)
                y += spacing
            }
            var x = offsetX
            while (x < targetWidth + spacing) {
                canvas.drawLine(x, 0f, x, targetHeight.toFloat(), guidePaint)
                x += spacing
            }
        }
    }

    private fun drawVisibleElements(canvas: Canvas, targetWidth: Int = width, targetHeight: Int = height, includeMinimap: Boolean = true) {
        val visible = RectF(viewport.offsetX, viewport.offsetY, viewport.offsetX + targetWidth / viewport.zoom, viewport.offsetY + targetHeight / viewport.zoom)
        val sorted = document.elements.sortedBy { it.zIndex }
        sorted.forEach { element ->
            if (!RectF(element.x, element.y, element.x + element.width, element.y + element.height).intersect(visible)) return@forEach
            when (element) {
                is StrokeElement -> drawStroke(canvas, element)
                is RuntimeBitmapElement -> drawRuntimeBitmap(canvas, element)
                is TextElement -> Unit
                is ImageElement -> Unit
                is ShapeElement -> Unit
            }
            if (includeMinimap && element.selected) drawSelection(canvas, element)
        }
        currentStroke?.let { drawStroke(canvas, it) }
    }

    private fun drawStroke(canvas: Canvas, stroke: StrokeElement) {
        if (stroke.points.isEmpty()) return
        strokePaint.color = stroke.color
        strokePaint.strokeWidth = max(1f, stroke.strokeWidth * viewport.zoom)
        strokePaint.alpha = if (stroke.tool == NoteTool.HIGHLIGHTER) 110 else Color.alpha(stroke.color)
        val path = Path()
        stroke.points.forEachIndexed { index, point ->
            val screen = viewport.canvasToScreen(point.x, point.y)
            if (index == 0) path.moveTo(screen.x, screen.y) else path.lineTo(screen.x, screen.y)
        }
        canvas.drawPath(path, strokePaint)
        strokePaint.alpha = 255
    }

    private fun drawRuntimeBitmap(canvas: Canvas, element: RuntimeBitmapElement) {
        val leftTop = viewport.canvasToScreen(element.x, element.y)
        val rightBottom = viewport.canvasToScreen(element.x + element.width, element.y + element.height)
        canvas.drawBitmap(element.bitmap, null, RectF(leftTop.x, leftTop.y, rightBottom.x, rightBottom.y), bitmapPaint)
    }

    private fun drawSelection(canvas: Canvas, element: NoteElement) {
        val leftTop = viewport.canvasToScreen(element.x, element.y)
        val rightBottom = viewport.canvasToScreen(element.x + element.width, element.y + element.height)
        canvas.drawRoundRect(RectF(leftTop.x, leftTop.y, rightBottom.x, rightBottom.y), 12f * density, 12f * density, selectionPaint)
    }

    private fun drawMinimap(canvas: Canvas) {
        val mapWidth = 54f * density
        val mapHeight = 92f * density
        val left = width - mapWidth - 18f * density
        val top = 88f * density
        minimapPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 150)
        canvas.drawRoundRect(RectF(left, top, left + mapWidth, top + mapHeight), 18f * density, 18f * density, minimapPaint)
        minimapPaint.color = ColorUtils.setAlphaComponent(Color.rgb(114, 91, 170), 80)
        val content = contentBounds().takeIf { !it.isEmpty } ?: RectF(0f, 0f, document.canvasWidth, document.canvasHeight)
        val visibleTop = ((viewport.offsetY - content.top) / max(1f, content.height())).coerceIn(0f, 1f)
        val visibleHeight = (height / viewport.zoom / max(1f, content.height())).coerceIn(0.08f, 1f)
        canvas.drawRoundRect(
            RectF(left + 8f * density, top + 8f * density + visibleTop * (mapHeight - 16f * density), left + mapWidth - 8f * density, top + 8f * density + (visibleTop + visibleHeight) * (mapHeight - 16f * density)),
            10f * density,
            10f * density,
            minimapPaint
        )
    }

    private fun contentBounds(): RectF {
        val bounds = RectF()
        document.elements.forEachIndexed { index, element ->
            val rect = RectF(element.x, element.y, element.x + element.width, element.y + element.height)
            if (index == 0) bounds.set(rect) else bounds.union(rect)
        }
        return bounds
    }

    private fun CanvasPoint.withPressure(event: MotionEvent): CanvasPoint = copy(pressure = event.pressure.coerceAtLeast(0.1f))

    private fun notifyContentChanged() {
        document.updatedAt = System.currentTimeMillis()
        contentChangedListener?.invoke()
    }

    private data class RuntimeBitmapElement(
        val bitmap: Bitmap,
        override val id: String = java.util.UUID.randomUUID().toString(),
        override var x: Float,
        override var y: Float,
        override var width: Float,
        override var height: Float,
        override var rotation: Float = 0f,
        override var scale: Float = 1f,
        override var zIndex: Int = 0,
        override var selected: Boolean = false
    ) : NoteElement(id, NoteElementType.IMAGE_BLOCK, x, y, width, height, rotation, scale, zIndex, selected) {
        override fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put("id", id)
            put("type", type.name)
            put("imageUri", "runtime-imported-bitmap")
            put("x", x.toDouble())
            put("y", y.toDouble())
            put("width", width.toDouble())
            put("height", height.toDouble())
            put("rotation", rotation.toDouble())
            put("scale", scale.toDouble())
            put("zIndex", zIndex)
        }
    }
}
