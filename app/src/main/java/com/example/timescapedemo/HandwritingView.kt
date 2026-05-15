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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.UUID

class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class StrokePoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
    private data class NoteStroke(
        val id: String = UUID.randomUUID().toString(),
        val points: MutableList<StrokePoint>,
        @ColorInt val color: Int,
        val width: Float,
        val tool: NoteEditorTool,
        val penType: HandwritingPenType,
        val eraserType: HandwritingEraserType,
        val bounds: RectF = RectF()
    )

    private enum class BlockType { TEXT, IMAGE, SHAPE }

    private data class CanvasBlock(
        val id: String = UUID.randomUUID().toString(),
        val type: BlockType,
        var x: Float,
        var y: Float,
        var width: Float,
        var height: Float,
        var rotation: Float = 0f,
        var scale: Float = 1f,
        var zIndex: Int = 0,
        var text: String = "",
        var imagePath: String? = null,
        @ColorInt var strokeColor: Int = Color.parseColor("#8E6AD8"),
        @ColorInt var fillColor: Int = ColorUtils.setAlphaComponent(Color.parseColor("#BFA7F2"), 46)
    ) {
        fun bounds(): RectF = RectF(x, y, x + width * scale, y + height * scale)
    }

    private sealed class NoteCommand {
        abstract fun undo(view: HandwritingView)
        abstract fun redo(view: HandwritingView)
    }

    private data class AddStrokeCommand(val stroke: NoteStroke) : NoteCommand() {
        override fun undo(view: HandwritingView) { view.strokes.remove(stroke) }
        override fun redo(view: HandwritingView) { view.strokes.add(stroke) }
    }

    private data class RemoveStrokeCommand(val stroke: NoteStroke, val index: Int) : NoteCommand() {
        override fun undo(view: HandwritingView) { view.strokes.add(index.coerceIn(0, view.strokes.size), stroke) }
        override fun redo(view: HandwritingView) { view.strokes.remove(stroke) }
    }

    private data class AddBlockCommand(val block: CanvasBlock) : NoteCommand() {
        override fun undo(view: HandwritingView) { view.blocks.remove(block); if (view.selectedBlockId == block.id) view.selectedBlockId = null }
        override fun redo(view: HandwritingView) { view.blocks.add(block) }
    }

    private data class MoveBlockCommand(val blockId: String, val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : NoteCommand() {
        override fun undo(view: HandwritingView) { view.findBlock(blockId)?.let { it.x = fromX; it.y = fromY } }
        override fun redo(view: HandwritingView) { view.findBlock(blockId)?.let { it.x = toX; it.y = toY } }
    }

    private val density = resources.displayMetrics.density
    private val strokes = mutableListOf<NoteStroke>()
    private val blocks = mutableListOf<CanvasBlock>()
    private val undoStack = mutableListOf<NoteCommand>()
    private val redoStack = mutableListOf<NoteCommand>()
    private var activeStroke: NoteStroke? = null

    private var pendingBitmap: Bitmap? = null
    private var baseBitmap: Bitmap? = null
    private var hasBaseImage = false
    private var baseDrawRect = RectF()

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastCanvasX = 0f
    private var lastCanvasY = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var selectedBlockStartX = 0f
    private var selectedBlockStartY = 0f
    private var initialPinchDistance = 0f
    private var initialPinchZoom = 1f
    private var pinchFocusCanvasX = 0f
    private var pinchFocusCanvasY = 0f
    private val touchTolerance = 3f * density
    private var brushWidthPx = 6f * density

    private var viewportOffsetX = 0f
    private var viewportOffsetY = 0f
    private var zoom = 1f
    private var virtualCanvasWidth = 0f
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
    private var editorTool: NoteEditorTool = NoteEditorTool.PEN
    private var selectedBlockId: String? = null
    private var isDraggingSelectedBlock = false
    private var showExpansionHintUntil = 0L

    private var contentChangedListener: (() -> Unit)? = null

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        strokeWidth = brushWidthPx
    }
    private val highlighterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.SQUARE
        strokeWidth = brushWidthPx * 2.2f
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
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C314A")
        textSize = 18f * density
    }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#8E6AD8")
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
            val measuredW = measuredWidth
            if (measuredW > 0) setMeasuredDimension(measuredW, resolveSize((measuredW * ratio).roundToInt(), heightMeasureSpec))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (virtualCanvasWidth <= 0f) virtualCanvasWidth = max(w.toFloat() * 1.35f, (exportWidth.takeIf { it > 0 } ?: w).toFloat())
        if (virtualCanvasHeight <= 0f) virtualCanvasHeight = max(h.toFloat() * 1.8f, (exportHeight.takeIf { it > 0 } ?: h).toFloat())
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
        drawPaperGuides(canvas, width.toFloat(), height.toFloat(), zoom, viewportOffsetX, viewportOffsetY)
        drawBaseBitmap(canvas)
        drawVisibleStrokes(canvas)
        activeStroke?.let { canvas.drawPath(buildScreenPath(it, viewportOffsetX, viewportOffsetY, zoom), paintForStroke(it, zoom)) }
        drawBlocks(canvas)
        drawInfiniteCanvasCues(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                disallowParentIntercept(true)
                handleDown(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> startPinch(event)
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) updatePinch(event) else handleMove(event)
            }
            MotionEvent.ACTION_POINTER_UP -> startPinch(event)
            MotionEvent.ACTION_UP -> {
                handleUp(event)
                disallowParentIntercept(false)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                activeStroke = null
                isDraggingSelectedBlock = false
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
        strokes.clear()
        blocks.clear()
        undoStack.clear()
        redoStack.clear()
        selectedBlockId = null
        baseBitmap?.recycle()
        baseBitmap = null
        pendingBitmap?.recycle()
        pendingBitmap = null
        hasBaseImage = false
        viewportOffsetX = 0f
        viewportOffsetY = 0f
        zoom = 1f
        if (hadAnyContent) notifyContentChanged()
        invalidate()
    }

    fun undo(): Boolean {
        activeStroke?.let {
            activeStroke = null
            invalidate()
            notifyContentChanged()
            return true
        }
        if (undoStack.isEmpty()) return false
        val command = undoStack.removeAt(undoStack.lastIndex)
        command.undo(this)
        redoStack.add(command)
        invalidate()
        notifyContentChanged()
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val command = redoStack.removeAt(redoStack.lastIndex)
        command.redo(this)
        undoStack.add(command)
        invalidate()
        notifyContentChanged()
        return true
    }

    fun canUndo(): Boolean = activeStroke != null || undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()
    fun hasDrawing(): Boolean = hasBaseImage || strokes.isNotEmpty() || blocks.isNotEmpty() || activeStroke != null

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
        val contentBounds = contentBounds().takeIf { !it.isEmpty } ?: visibleCanvasRect()
        val aspectHeight = (contentBounds.height() * (targetW.toFloat() / contentBounds.width().coerceAtLeast(1f))).roundToInt()
        val minTargetH = (height.takeIf { it > 0 } ?: targetW).coerceAtMost(4096)
        val targetH = aspectHeight.coerceIn(minTargetH, 4096)
        val result = Bitmap.createBitmap(targetW, targetH, Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(backgroundColorInt)
        val exportZoom = min(targetW / contentBounds.width().coerceAtLeast(1f), targetH / contentBounds.height().coerceAtLeast(1f))
        drawPaperGuides(canvas, targetW.toFloat(), targetH.toFloat(), exportZoom, contentBounds.left, contentBounds.top)
        baseBitmap?.let { bitmap ->
            if (RectF.intersects(baseDrawRect, contentBounds)) {
                val dest = canvasToExportRect(baseDrawRect, contentBounds, exportZoom)
                canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dest, bitmapPaint)
            }
        }
        drawStrokes(canvas, contentBounds, exportZoom, contentBounds.left, contentBounds.top)
        drawBlocks(canvas, contentBounds.left, contentBounds.top, exportZoom)
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
    fun setBrushColor(@ColorInt color: Int) { brushColorInt = color; updatePenColor(); invalidate() }
    fun setBrushSizeDp(sizeDp: Float) = setBrushSizePx(sizeDp * density)
    fun setBrushSizePx(sizePx: Float) { brushWidthPx = sizePx; penPaint.strokeWidth = brushWidthPx; highlighterPaint.strokeWidth = brushWidthPx * 2.2f; applyPenType(penType); updatePenColor(); invalidate() }
    fun getBrushSizeDp(): Float = brushWidthPx / density
    fun setPenType(type: HandwritingPenType) { penType = type; applyPenType(type); updatePenColor(); invalidate() }
    fun getPenType(): HandwritingPenType = penType
    fun setEraserSizeDp(sizeDp: Float) { eraserWidthPx = sizeDp * density; invalidate() }
    fun getEraserSizeDp(): Float = eraserWidthPx / density
    fun setEraserType(type: HandwritingEraserType) { eraserType = type; applyEraserType(type); invalidate() }
    fun getEraserType(): HandwritingEraserType = eraserType

    fun setDrawingTool(tool: HandwritingDrawingTool) {
        drawingTool = tool
        setEditorTool(if (tool == ERASER) NoteEditorTool.ERASER else NoteEditorTool.PEN)
    }

    fun setEditorTool(tool: NoteEditorTool) {
        commitActiveStroke()
        editorTool = tool
        drawingTool = if (tool == NoteEditorTool.ERASER) ERASER else PEN
        selectedBlockId = selectedBlockId.takeIf { tool == NoteEditorTool.SELECT }
        invalidate()
    }

    fun setCanvasSize(widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        exportWidth = widthPx
        exportHeight = heightPx
        targetAspectRatio = null
        virtualCanvasWidth = max(virtualCanvasWidth, widthPx.toFloat())
        virtualCanvasHeight = max(virtualCanvasHeight, heightPx.toFloat())
        requestLayout()
        invalidate()
    }

    fun setOnContentChangedListener(listener: (() -> Unit)?) { contentChangedListener = listener }

    fun currentDocumentSnapshot(title: String = "Handwriting"): NoteDocument {
        val now = System.currentTimeMillis()
        val elements = mutableListOf<NoteDocumentElement>()
        strokes.forEachIndexed { index, stroke ->
            elements.add(
                StrokeElement(
                    id = stroke.id,
                    x = stroke.bounds.left,
                    y = stroke.bounds.top,
                    width = stroke.bounds.width(),
                    height = stroke.bounds.height(),
                    zIndex = index,
                    points = stroke.points.map { StrokePointElement(it.x, it.y, it.pressure, it.time) },
                    color = stroke.color,
                    strokeWidth = stroke.width,
                    toolType = stroke.tool
                )
            )
        }
        blocks.forEach { block ->
            elements.add(
                when (block.type) {
                    BlockType.TEXT -> TextElement(block.id, block.x, block.y, block.width, block.height, block.rotation, block.scale, block.zIndex, block.text, 18f, Color.parseColor("#3C314A"))
                    BlockType.IMAGE -> ImageElement(block.id, block.x, block.y, block.width, block.height, block.rotation, block.scale, block.zIndex, block.imagePath)
                    BlockType.SHAPE -> ShapeElement(block.id, block.x, block.y, block.width, block.height, block.rotation, block.scale, block.zIndex, block.strokeColor, block.fillColor)
                }
            )
        }
        return NoteDocument(
            id = "handwriting-${hashCode()}",
            title = title,
            createdAt = now,
            updatedAt = now,
            canvasWidth = virtualCanvasWidth,
            canvasHeight = virtualCanvasHeight,
            viewportState = NoteViewportState(viewportOffsetX, viewportOffsetY, zoom),
            elements = elements.sortedBy { it.zIndex }
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        baseBitmap?.recycle()
        pendingBitmap?.recycle()
        baseBitmap = null
        pendingBitmap = null
    }

    private var eraserWidthPx = 18f * density

    private fun handleDown(event: MotionEvent) {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        val canvasX = screenToCanvasX(x)
        val canvasY = screenToCanvasY(y)
        lastTouchX = x
        lastTouchY = y
        lastCanvasX = canvasX
        lastCanvasY = canvasY
        when (editorTool) {
            NoteEditorTool.PEN, NoteEditorTool.HIGHLIGHTER -> startStroke(canvasX, canvasY, event)
            NoteEditorTool.ERASER -> eraseStrokeAt(canvasX, canvasY)
            NoteEditorTool.TEXT -> addTextBlock(canvasX, canvasY)
            NoteEditorTool.IMAGE -> addImageBlock(canvasX, canvasY)
            NoteEditorTool.SHAPE -> addShapeBlock(canvasX, canvasY)
            NoteEditorTool.SELECT -> startSelection(canvasX, canvasY)
            NoteEditorTool.PAN, NoteEditorTool.MORE, NoteEditorTool.COLOR, NoteEditorTool.WIDTH, NoteEditorTool.LASSO -> startSelection(canvasX, canvasY)
        }
    }

    private fun handleMove(event: MotionEvent) {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        val canvasX = screenToCanvasX(x)
        val canvasY = screenToCanvasY(y)
        when (editorTool) {
            NoteEditorTool.PEN, NoteEditorTool.HIGHLIGHTER -> moveStroke(canvasX, canvasY, event)
            NoteEditorTool.ERASER -> eraseStrokeAt(canvasX, canvasY)
            NoteEditorTool.SELECT, NoteEditorTool.MORE, NoteEditorTool.COLOR, NoteEditorTool.WIDTH, NoteEditorTool.LASSO -> dragSelection(canvasX, canvasY)
            NoteEditorTool.PAN -> panByScreen(lastTouchX - x, lastTouchY - y)
            else -> Unit
        }
        lastTouchX = x
        lastTouchY = y
        lastCanvasX = canvasX
        lastCanvasY = canvasY
    }

    private fun handleUp(event: MotionEvent) {
        when (editorTool) {
            NoteEditorTool.PEN, NoteEditorTool.HIGHLIGHTER -> commitActiveStroke()
            NoteEditorTool.SELECT, NoteEditorTool.MORE, NoteEditorTool.COLOR, NoteEditorTool.WIDTH, NoteEditorTool.LASSO -> endSelectionDrag()
            else -> Unit
        }
        isDraggingSelectedBlock = false
    }

    private fun startPinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        activeStroke = null
        initialPinchDistance = pointerDistance(event)
        initialPinchZoom = zoom
        val focusX = averagePointerX(event)
        val focusY = averagePointerY(event)
        pinchFocusCanvasX = screenToCanvasX(focusX)
        pinchFocusCanvasY = screenToCanvasY(focusY)
    }

    private fun updatePinch(event: MotionEvent) {
        if (event.pointerCount < 2 || initialPinchDistance <= 0f) return
        val focusX = averagePointerX(event)
        val focusY = averagePointerY(event)
        val newZoom = (initialPinchZoom * (pointerDistance(event) / initialPinchDistance)).coerceIn(0.45f, 2.8f)
        zoom = newZoom
        viewportOffsetX = pinchFocusCanvasX - focusX / zoom
        viewportOffsetY = pinchFocusCanvasY - focusY / zoom
        clampViewport()
    }

    private fun startStroke(canvasX: Float, canvasY: Float, event: MotionEvent) {
        val strokeTool = editorTool
        val widthPx = if (strokeTool == NoteEditorTool.HIGHLIGHTER) brushWidthPx * 2.2f else brushWidthPx
        val color = if (strokeTool == NoteEditorTool.HIGHLIGHTER) {
            ColorUtils.setAlphaComponent(ColorUtils.blendARGB(brushColorInt, Color.YELLOW, 0.18f), 105)
        } else {
            penPaint.color
        }
        activeStroke = NoteStroke(
            points = mutableListOf(StrokePoint(canvasX, canvasY, event.getPressure(0), event.eventTime)),
            color = color,
            width = widthPx,
            tool = strokeTool,
            penType = penType,
            eraserType = eraserType
        ).also { it.bounds.set(canvasX, canvasY, canvasX, canvasY) }
        maybeExpandFor(canvasX, canvasY)
    }

    private fun moveStroke(canvasX: Float, canvasY: Float, event: MotionEvent) {
        val stroke = activeStroke ?: return
        val dx = abs(canvasX - lastCanvasX)
        val dy = abs(canvasY - lastCanvasY)
        if (dx >= touchTolerance / zoom || dy >= touchTolerance / zoom) {
            stroke.points.add(StrokePoint(canvasX, canvasY, event.getPressure(0), event.eventTime))
            stroke.bounds.union(canvasX, canvasY)
            maybeExpandFor(canvasX, canvasY)
        }
    }

    private fun commitActiveStroke() {
        val stroke = activeStroke ?: return
        activeStroke = null
        if (stroke.points.size < 2) {
            stroke.points.add(StrokePoint(stroke.points.first().x + 0.01f, stroke.points.first().y + 0.01f, 1f, System.currentTimeMillis()))
        }
        strokes.add(stroke)
        pushUndo(AddStrokeCommand(stroke))
        notifyContentChanged()
    }

    private fun eraseStrokeAt(canvasX: Float, canvasY: Float) {
        val hitIndex = strokes.indexOfLast { stroke -> strokeHitTest(stroke, canvasX, canvasY, eraserWidthPx / zoom) }
        if (hitIndex < 0) return
        val removed = strokes.removeAt(hitIndex)
        pushUndo(RemoveStrokeCommand(removed, hitIndex))
        notifyContentChanged()
    }

    private fun addTextBlock(canvasX: Float, canvasY: Float) {
        val block = CanvasBlock(
            type = BlockType.TEXT,
            x = canvasX,
            y = canvasY,
            width = 220f * density,
            height = 72f * density,
            zIndex = nextBlockZ(),
            text = "Tap to edit memory…"
        )
        blocks.add(block)
        selectedBlockId = block.id
        pushUndo(AddBlockCommand(block))
        notifyContentChanged()
    }

    private fun addImageBlock(canvasX: Float, canvasY: Float) {
        val block = CanvasBlock(
            type = BlockType.IMAGE,
            x = canvasX,
            y = canvasY,
            width = 180f * density,
            height = 130f * density,
            zIndex = nextBlockZ(),
            text = "Image"
        )
        blocks.add(block)
        selectedBlockId = block.id
        pushUndo(AddBlockCommand(block))
        notifyContentChanged()
    }

    private fun addShapeBlock(canvasX: Float, canvasY: Float) {
        val block = CanvasBlock(
            type = BlockType.SHAPE,
            x = canvasX,
            y = canvasY,
            width = 160f * density,
            height = 96f * density,
            zIndex = nextBlockZ()
        )
        blocks.add(block)
        selectedBlockId = block.id
        pushUndo(AddBlockCommand(block))
        notifyContentChanged()
    }

    private fun startSelection(canvasX: Float, canvasY: Float) {
        val block = blocks.sortedByDescending { it.zIndex }.firstOrNull { it.bounds().contains(canvasX, canvasY) }
        selectedBlockId = block?.id
        if (block != null) {
            isDraggingSelectedBlock = true
            dragStartX = canvasX
            dragStartY = canvasY
            selectedBlockStartX = block.x
            selectedBlockStartY = block.y
        }
    }

    private fun dragSelection(canvasX: Float, canvasY: Float) {
        if (!isDraggingSelectedBlock) return
        val block = selectedBlockId?.let { findBlock(it) } ?: return
        block.x = selectedBlockStartX + (canvasX - dragStartX)
        block.y = selectedBlockStartY + (canvasY - dragStartY)
        maybeExpandFor(block.x + block.width, block.y + block.height)
    }

    private fun endSelectionDrag() {
        val block = selectedBlockId?.let { findBlock(it) } ?: return
        if (abs(block.x - selectedBlockStartX) > 0.5f || abs(block.y - selectedBlockStartY) > 0.5f) {
            pushUndo(MoveBlockCommand(block.id, selectedBlockStartX, selectedBlockStartY, block.x, block.y))
            notifyContentChanged()
        }
    }

    private fun installBaseBitmap(bitmap: Bitmap) {
        baseBitmap?.recycle()
        baseBitmap = bitmap
        hasBaseImage = true
        val destRect = RectF(0f, 0f, width.toFloat(), max(height.toFloat(), virtualCanvasHeight))
        val srcRatio = bitmap.width.toFloat() / bitmap.height.toFloat().coerceAtLeast(1f)
        val scaledHeight = destRect.width() / srcRatio
        baseDrawRect.set(destRect.left, destRect.top, destRect.right, min(scaledHeight, virtualCanvasHeight))
        virtualCanvasWidth = max(virtualCanvasWidth, baseDrawRect.right)
        virtualCanvasHeight = max(virtualCanvasHeight, baseDrawRect.bottom)
        notifyContentChanged()
    }

    private fun drawBaseBitmap(canvas: Canvas) {
        val bitmap = baseBitmap ?: return
        val visible = visibleCanvasRect()
        if (!RectF.intersects(baseDrawRect, visible)) return
        val dest = canvasToScreenRect(baseDrawRect)
        canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), dest, bitmapPaint)
    }

    private fun drawVisibleStrokes(canvas: Canvas) {
        drawStrokes(canvas, visibleCanvasRect(), zoom, viewportOffsetX, viewportOffsetY)
    }

    private fun drawStrokes(canvas: Canvas, visible: RectF, scale: Float, offsetX: Float, offsetY: Float) {
        strokes.forEach { stroke ->
            if (stroke.bounds.right + stroke.width < visible.left || stroke.bounds.left - stroke.width > visible.right || stroke.bounds.bottom + stroke.width < visible.top || stroke.bounds.top - stroke.width > visible.bottom) return@forEach
            canvas.drawPath(buildScreenPath(stroke, offsetX, offsetY, scale), paintForStroke(stroke, scale))
        }
    }

    private fun buildScreenPath(stroke: NoteStroke, offsetX: Float, offsetY: Float, scale: Float): Path {
        val path = Path()
        val points = stroke.points
        if (points.isEmpty()) return path
        path.moveTo((points.first().x - offsetX) * scale, (points.first().y - offsetY) * scale)
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val point = points[i]
            path.quadTo(
                (previous.x - offsetX) * scale,
                (previous.y - offsetY) * scale,
                (((point.x + previous.x) / 2f) - offsetX) * scale,
                (((point.y + previous.y) / 2f) - offsetY) * scale
            )
        }
        return path
    }

    private fun paintForStroke(stroke: NoteStroke, scale: Float): Paint {
        return if (stroke.tool == NoteEditorTool.HIGHLIGHTER) {
            highlighterPaint.color = stroke.color
            highlighterPaint.strokeWidth = stroke.width * scale
            highlighterPaint
        } else {
            penPaint.color = stroke.color
            penPaint.strokeWidth = stroke.width * scale
            applyPenType(stroke.penType)
            penPaint
        }
    }

    private fun drawBlocks(canvas: Canvas) = drawBlocks(canvas, viewportOffsetX, viewportOffsetY, zoom)

    private fun drawBlocks(canvas: Canvas, offsetX: Float, offsetY: Float, scale: Float) {
        blocks.sortedBy { it.zIndex }.forEach { block ->
            val screen = canvasToScreenRect(block.bounds(), offsetX, offsetY, scale)
            if (!RectF.intersects(screen, RectF(0f, 0f, width.toFloat(), height.toFloat()))) return@forEach
            when (block.type) {
                BlockType.TEXT -> drawTextBlock(canvas, block, screen)
                BlockType.IMAGE -> drawImageBlock(canvas, block, screen)
                BlockType.SHAPE -> drawShapeBlock(canvas, block, screen)
            }
            if (block.id == selectedBlockId) drawSelection(canvas, screen)
        }
    }

    private fun drawTextBlock(canvas: Canvas, block: CanvasBlock, screen: RectF) {
        blockPaint.style = Paint.Style.FILL
        blockPaint.color = ColorUtils.setAlphaComponent(Color.WHITE, 205)
        canvas.drawRoundRect(screen, 18f * density, 18f * density, blockPaint)
        blockPaint.style = Paint.Style.STROKE
        blockPaint.strokeWidth = 1f * density
        blockPaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#8E6AD8"), 55)
        canvas.drawRoundRect(screen, 18f * density, 18f * density, blockPaint)
        textPaint.textSize = 18f * density * zoom
        textPaint.color = Color.parseColor("#3C314A")
        canvas.drawText(block.text, screen.left + 14f * density * zoom, screen.top + 32f * density * zoom, textPaint)
    }

    private fun drawImageBlock(canvas: Canvas, block: CanvasBlock, screen: RectF) {
        blockPaint.style = Paint.Style.FILL
        blockPaint.shader = LinearGradient(screen.left, screen.top, screen.right, screen.bottom, Color.parseColor("#F8ECFF"), Color.parseColor("#FFF2D8"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(screen, 22f * density, 22f * density, blockPaint)
        blockPaint.shader = null
        blockPaint.style = Paint.Style.STROKE
        blockPaint.strokeWidth = 1.2f * density
        blockPaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#8E6AD8"), 100)
        canvas.drawRoundRect(screen, 22f * density, 22f * density, blockPaint)
        textPaint.textSize = 14f * density * zoom
        textPaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#7358B7"), 190)
        canvas.drawText("Image block", screen.left + 18f * density * zoom, screen.centerY(), textPaint)
    }

    private fun drawShapeBlock(canvas: Canvas, block: CanvasBlock, screen: RectF) {
        blockPaint.shader = null
        blockPaint.style = Paint.Style.FILL
        blockPaint.color = block.fillColor
        canvas.drawRoundRect(screen, 20f * density, 20f * density, blockPaint)
        blockPaint.style = Paint.Style.STROKE
        blockPaint.strokeWidth = 2f * density
        blockPaint.color = block.strokeColor
        canvas.drawRoundRect(screen, 20f * density, 20f * density, blockPaint)
    }

    private fun drawSelection(canvas: Canvas, screen: RectF) {
        canvas.drawRoundRect(screen, 18f * density, 18f * density, selectionPaint)
        blockPaint.style = Paint.Style.FILL
        blockPaint.color = Color.WHITE
        val r = 5f * density
        listOf(screen.left to screen.top, screen.right to screen.top, screen.left to screen.bottom, screen.right to screen.bottom).forEach { (x, y) ->
            canvas.drawCircle(x, y, r, blockPaint)
            canvas.drawCircle(x, y, r, selectionPaint)
        }
    }

    private fun drawPaperTexture(canvas: Canvas) {
        texturePaint.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), ColorUtils.setAlphaComponent(Color.WHITE, 55), ColorUtils.setAlphaComponent(Color.parseColor("#EEDDBD"), 30), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), texturePaint)
        texturePaint.shader = null
        texturePaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#B9855D"), 10)
        val step = 18f * density * zoom
        var y = (-(viewportOffsetY * zoom) % step)
        while (y < height) {
            canvas.drawPoint((width * 0.18f + y * 3f) % width, y, texturePaint)
            canvas.drawPoint((width * 0.73f + y * 1.7f) % width, y + step / 2f, texturePaint)
            y += step
        }
    }

    private fun drawPaperGuides(canvas: Canvas, screenWidth: Float, screenHeight: Float, scale: Float, offsetX: Float, offsetY: Float) {
        val spacing = 31f * density * scale
        val stroke = max(1f, 0.75f * density * scale)
        guidePaint.strokeWidth = stroke
        marginPaint.strokeWidth = max(1f, 1f * density * scale)
        if (paperStyle == HandwritingPaperStyle.GRID) {
            val firstColumnIndex = floor(offsetX * scale / spacing).toInt() - 1
            var x = firstColumnIndex * spacing - (offsetX * scale % spacing)
            while (x < screenWidth) {
                if (x >= 0f) canvas.drawLine(x, 0f, x, screenHeight, guidePaint)
                x += spacing
            }
        }
        val firstLineIndex = floor(offsetY * scale / spacing).toInt() - 1
        var y = firstLineIndex * spacing - (offsetY * scale % spacing)
        while (y < screenHeight) {
            if (y >= 0f) canvas.drawLine(0f, y, screenWidth, y, guidePaint)
            y += spacing
        }
        val marginX = (42f * density - offsetX) * scale
        canvas.drawLine(marginX, 0f, marginX, screenHeight, marginPaint)
    }

    private fun drawInfiniteCanvasCues(canvas: Canvas) {
        fadePaint.shader = LinearGradient(0f, height - 96f * density, 0f, height.toFloat(), Color.TRANSPARENT, backgroundColorInt, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height - 96f * density, width.toFloat(), height.toFloat(), fadePaint)
        fadePaint.shader = null
        val trackHeight = height * 0.68f
        val trackTop = height * 0.14f
        val trackRight = width - 10f * density
        val trackWidth = 3f * density
        canvas.drawRoundRect(trackRight, trackTop, trackRight + trackWidth, trackTop + trackHeight, trackWidth, trackWidth, minimapTrackPaint)
        val viewportRatio = ((height / zoom) / virtualCanvasHeight.coerceAtLeast(height / zoom)).coerceIn(0.10f, 1f)
        val thumbHeight = trackHeight * viewportRatio
        val scrollable = (virtualCanvasHeight - height / zoom).coerceAtLeast(1f)
        val thumbTop = trackTop + (trackHeight - thumbHeight) * (viewportOffsetY / scrollable).coerceIn(0f, 1f)
        canvas.drawRoundRect(trackRight - density, thumbTop, trackRight + trackWidth + density, thumbTop + thumbHeight, 4f * density, 4f * density, minimapThumbPaint)
        hintPaint.alpha = 176
        canvas.drawText("${(zoom * 100).roundToInt()}% · two fingers pan/zoom", width / 2f, 24f * density, hintPaint)
        val shouldHint = System.currentTimeMillis() < showExpansionHintUntil || viewportOffsetY + height / zoom > virtualCanvasHeight - 360f * density
        if (shouldHint) canvas.drawText("More space appears automatically ↓", width / 2f, height - 26f * density, hintPaint)
        hintPaint.alpha = 255
    }

    private fun pushUndo(command: NoteCommand) {
        undoStack.add(command)
        redoStack.clear()
        invalidate()
    }

    private fun panByScreen(deltaScreenX: Float, deltaScreenY: Float) {
        viewportOffsetX += deltaScreenX / zoom
        viewportOffsetY += deltaScreenY / zoom
        clampViewport()
    }

    private fun clampViewport() {
        viewportOffsetX = viewportOffsetX.coerceIn(0f, (virtualCanvasWidth - width / zoom).coerceAtLeast(0f))
        viewportOffsetY = viewportOffsetY.coerceIn(0f, (virtualCanvasHeight - height / zoom).coerceAtLeast(0f))
    }

    private fun maybeExpandFor(canvasX: Float, canvasY: Float) {
        val threshold = 240f * density
        var expanded = false
        if (canvasX > virtualCanvasWidth - threshold) {
            virtualCanvasWidth += 720f * density
            expanded = true
        }
        if (canvasY > virtualCanvasHeight - threshold) {
            virtualCanvasHeight += 960f * density
            expanded = true
        }
        if (expanded) showExpansionHintUntil = System.currentTimeMillis() + 2200L
    }

    private fun visibleCanvasRect(): RectF = RectF(viewportOffsetX, viewportOffsetY, viewportOffsetX + width / zoom, viewportOffsetY + height / zoom)

    private fun contentBounds(): RectF {
        val bounds = RectF()
        if (hasBaseImage) bounds.union(baseDrawRect)
        strokes.forEach { bounds.union(it.bounds) }
        blocks.forEach { bounds.union(it.bounds()) }
        if (bounds.isEmpty) bounds.set(visibleCanvasRect()) else bounds.inset(-48f * density, -48f * density)
        bounds.left = max(0f, bounds.left)
        bounds.top = max(0f, bounds.top)
        return bounds
    }

    private fun screenToCanvasX(screenX: Float): Float = (screenX / zoom) + viewportOffsetX
    private fun screenToCanvasY(screenY: Float): Float = (screenY / zoom) + viewportOffsetY
    private fun canvasToScreenRect(rect: RectF): RectF = canvasToScreenRect(rect, viewportOffsetX, viewportOffsetY, zoom)
    private fun canvasToScreenRect(rect: RectF, offsetX: Float, offsetY: Float, scale: Float): RectF = RectF((rect.left - offsetX) * scale, (rect.top - offsetY) * scale, (rect.right - offsetX) * scale, (rect.bottom - offsetY) * scale)
    private fun canvasToExportRect(rect: RectF, bounds: RectF, scale: Float): RectF = RectF((rect.left - bounds.left) * scale, (rect.top - bounds.top) * scale, (rect.right - bounds.left) * scale, (rect.bottom - bounds.top) * scale)

    private fun strokeHitTest(stroke: NoteStroke, x: Float, y: Float, radius: Float): Boolean {
        if (!RectF(stroke.bounds).apply { inset(-radius, -radius) }.contains(x, y)) return false
        val points = stroke.points
        if (points.size < 2) return false
        for (i in 1 until points.size) {
            if (distanceToSegment(x, y, points[i - 1].x, points[i - 1].y, points[i].x, points[i].y) <= radius + stroke.width / 2f) return true
        }
        return false
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        if (dx == 0f && dy == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun averagePointerX(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getX(i)
        return total / event.pointerCount.coerceAtLeast(1)
    }

    private fun averagePointerY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) total += event.getY(i)
        return total / event.pointerCount.coerceAtLeast(1)
    }

    private fun findBlock(id: String): CanvasBlock? = blocks.firstOrNull { it.id == id }
    private fun nextBlockZ(): Int = (blocks.map { it.zIndex }.maxOrNull() ?: 0) + 1

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
        eraserType = type
    }

    private fun updateGuidePaintColor() {
        guidePaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#9EB2C0"), 78)
        marginPaint.color = ColorUtils.setAlphaComponent(Color.parseColor("#E79AA5"), 120)
    }

    private fun notifyContentChanged() {
        contentChangedListener?.invoke()
    }

    private fun disallowParentIntercept(disallow: Boolean) {
        var parentView: ViewParent? = parent
        while (parentView != null) {
            parentView.requestDisallowInterceptTouchEvent(disallow)
            parentView = parentView.parent
        }
    }
}
