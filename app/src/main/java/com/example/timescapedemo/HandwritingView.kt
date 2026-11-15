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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewParent
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import com.example.timescapedemo.HandwritingDrawingTool.ERASER
import com.example.timescapedemo.HandwritingDrawingTool.LASSO
import com.example.timescapedemo.HandwritingDrawingTool.PEN
import com.example.timescapedemo.HandwritingDrawingTool.TEXT
import com.google.mlkit.vision.digitalink.recognition.Ink
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class StateSnapshot(val bitmap: Bitmap, val hasDrawing: Boolean, val hasBase: Boolean)
    private data class InkStrokePoint(val x: Float, val y: Float, val time: Long)
    data class InsertedHandwritingObject(
        val id: String,
        val type: String = "handwritingText",
        val x: Float,
        val y: Float,
        val insertSize: Float,
        val lineHeight: Float,
        val bounds: RectF,
        val scale: Float,
        val createdAt: Long
    )
    data class PlacedImageSnapshot(
        val bitmap: Bitmap,
        val bounds: RectF
    )

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
        color = ColorUtils.setAlphaComponent(Color.BLACK, (0.16f * 255).roundToInt())
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
    private val insertionMarkerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#2962FF")
    }
    private val path = Path()
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.parseColor("#2962FF"), 0xCC)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
    }
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(Color.parseColor("#2962FF"), 0x18)
        style = Paint.Style.FILL
    }
    private val pathBounds = RectF()
    private val history = ArrayDeque<StateSnapshot>()
    private val recognitionStrokes = mutableListOf<List<InkStrokePoint>>()
    private var activeRecognitionStroke: MutableList<InkStrokePoint>? = null

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
    private var placedImageBitmap: Bitmap? = null
    private var placedImageRect: RectF? = null
    private var imageTouchMode = 0
    private var imageTouchStartX = 0f
    private var imageTouchStartY = 0f
    private var imageStartRect = RectF()
    private var imageStartDistance = 0f
    private var imagePlacementActive = false
    private var selectedBitmap: Bitmap? = null
    private var selectedRect: RectF? = null
    private var selectionTouchMode = 0
    private var selectionStartRect = RectF()
    private var selectionStartDistance = 0f

    private var contentChangedListener: (() -> Unit)? = null
    private var textInsertionTapListener: ((x: Float, y: Float) -> Unit)? = null
    private var strokeActiveListener: ((active: Boolean) -> Unit)? = null
    private var strokePreviewChangedListener: (() -> Unit)? = null
    private val insertedHandwritingObjects = mutableListOf<InsertedHandwritingObject>()
    private var insertionMarker: Pair<Float, Float>? = null

    private val maxHistory = 25

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
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
        val previousBitmap = extraBitmap
        val previousHadContent = hasContent
        val previousHadBase = hasBaseImage
        val newBitmap = Bitmap.createBitmap(w, h, Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
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
        } else if (previousBitmap != null && !previousBitmap.isRecycled) {
            val src = Rect(0, 0, previousBitmap.width, previousBitmap.height)
            val dest = Rect(0, 0, w, h)
            newCanvas.drawBitmap(previousBitmap, src, dest, bitmapPaint)
            hasBaseImage = previousHadBase
            hasContent = previousHadContent || previousHadBase
            pushCurrentState(hasContent, hasBaseImage)
        } else {
            hasBaseImage = false
            hasContent = false
            pushCurrentState(false, false)
        }
        if (previousBitmap != null && previousBitmap !== newBitmap && !previousBitmap.isRecycled) {
            previousBitmap.recycle()
        }
        pendingHasContent = false
        pendingHasBase = false
        invalidate()
        notifyContentChanged()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(backgroundColorInt)
        drawPaperTexture(canvas, width.toFloat(), height.toFloat(), 1f)
        drawPaperGuides(canvas, width.toFloat(), height.toFloat(), 1f)
        drawPlacedImage(canvas, showHandles = false)
        extraBitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        drawSelection(canvas)
        canvas.drawPath(path, currentPreviewPaint())
        if (imagePlacementActive) drawPlacedImageHandles(canvas)
        insertionMarker?.let { (x, y) -> drawInsertionMarker(canvas, x, y) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        if (selectedBitmap != null && handleSelectionTouch(event)) {
            invalidate()
            return true
        }
        if (placedImageBitmap != null && handlePlacedImageTouch(event)) {
            invalidate()
            return true
        }
        if (drawingTool == TEXT) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    disallowParentIntercept(true)
                    performClick()
                    setTextInsertionPreview(x, y)
                    textInsertionTapListener?.invoke(x, y)
                }
                MotionEvent.ACTION_UP -> disallowParentIntercept(false)
                MotionEvent.ACTION_CANCEL -> disallowParentIntercept(false)
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                disallowParentIntercept(true)
                strokeActiveListener?.invoke(true)
                touchStart(x, y)
                notifyStrokePreviewChanged()
            }
            MotionEvent.ACTION_MOVE -> {
                touchMove(x, y)
                notifyStrokePreviewChanged()
            }
            MotionEvent.ACTION_UP -> {
                if (drawingTool == LASSO) finishLassoSelection() else touchUp()
                notifyStrokePreviewChanged()
                strokeActiveListener?.invoke(false)
                disallowParentIntercept(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                touchCancel()
                notifyStrokePreviewChanged()
                strokeActiveListener?.invoke(false)
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
        insertedHandwritingObjects.clear()
        recognitionStrokes.clear()
        activeRecognitionStroke = null
        selectedBitmap?.recycle()
        selectedBitmap = null
        selectedRect = null
        placedImageBitmap?.recycle()
        placedImageBitmap = null
        placedImageRect = null
        imagePlacementActive = false
        invalidate()
        notifyContentChanged()
    }

    fun placeImage(bitmap: Bitmap) {
        commitCurrentPath()
        placedImageBitmap?.recycle()
        placedImageBitmap = bitmap.copy(Config.ARGB_8888, false)
        if (!bitmap.isRecycled) bitmap.recycle()
        val viewWidth = width.takeIf { it > 0 } ?: exportWidth.takeIf { it > 0 } ?: 1
        val viewHeight = height.takeIf { it > 0 } ?: exportHeight.takeIf { it > 0 } ?: 1
        val image = placedImageBitmap ?: return
        val maxWidth = viewWidth * 0.62f
        val maxHeight = viewHeight * 0.42f
        val scale = min(maxWidth / image.width.toFloat(), maxHeight / image.height.toFloat())
            .takeIf { it.isFinite() && it > 0f } ?: 1f
        val placedWidth = image.width * scale
        val placedHeight = image.height * scale
        placedImageRect = RectF(
            (viewWidth - placedWidth) / 2f,
            (viewHeight - placedHeight) / 2f,
            (viewWidth + placedWidth) / 2f,
            (viewHeight + placedHeight) / 2f
        )
        imagePlacementActive = true
        hasContent = true
        notifyContentChanged()
        invalidate()
    }

    fun undo(): Boolean {
        if (!path.isEmpty) {
            path.reset()
            invalidate()
            return true
        }
        if (selectedBitmap != null) {
            selectedBitmap?.recycle()
            selectedBitmap = null
            selectedRect = null
            invalidate()
            notifyContentChanged()
            return true
        }
        if (placedImageBitmap != null) {
            placedImageBitmap?.recycle()
            placedImageBitmap = null
            placedImageRect = null
            imagePlacementActive = false
            hasContent = history.lastOrNull()?.hasDrawing ?: false
            invalidate()
            notifyContentChanged()
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

    fun canUndo(): Boolean = !path.isEmpty || selectedBitmap != null || history.size > 1

    fun hasDrawing(): Boolean = hasContent || !path.isEmpty || selectedBitmap != null || placedImageBitmap != null

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
        commitSelection(addToHistory = false)
        commitCurrentPath(addToHistory = false)
        val source = extraBitmap ?: return null
        val targetW = exportWidth.takeIf { it > 0 } ?: source.width
        val targetH = exportHeight.takeIf { it > 0 } ?: source.height
        val result = Bitmap.createBitmap(targetW, targetH, Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(backgroundColorInt)
        val scale = if (width > 0) targetW.toFloat() / width.toFloat() else 1f
        drawPaperTexture(canvas, targetW.toFloat(), targetH.toFloat(), scale)
        drawPaperGuides(canvas, targetW.toFloat(), targetH.toFloat(), scale)
        drawPlacedImage(canvas, showHandles = false, scale = scale)
        val destRect = Rect(0, 0, targetW, targetH)
        canvas.drawBitmap(source, null, destRect, null)
        return result
    }

    fun exportEditLayerBitmap(): Bitmap? {
        commitSelection(addToHistory = false)
        commitCurrentPath(addToHistory = false)
        val source = extraBitmap ?: return null
        return source.copy(Config.ARGB_8888, false)
    }

    fun placedImageSnapshot(): PlacedImageSnapshot? {
        val image = placedImageBitmap ?: return null
        val rect = placedImageRect ?: return null
        return PlacedImageSnapshot(image.copy(Config.ARGB_8888, false), RectF(rect))
    }

    fun restorePlacedImage(bitmap: Bitmap, bounds: RectF) {
        placedImageBitmap?.recycle()
        placedImageBitmap = bitmap.copy(Config.ARGB_8888, false)
        if (!bitmap.isRecycled) bitmap.recycle()
        placedImageRect = RectF(bounds)
        imagePlacementActive = true
        invalidate()
        notifyContentChanged()
    }

    fun exportContentBitmap(targetHeightPx: Int, paddingPx: Int): Bitmap? {
        commitSelection(addToHistory = false)
        commitCurrentPath(addToHistory = false)
        val source = extraBitmap ?: return null
        val contentBounds = findContentBounds(source) ?: return null
        val paddedBounds = Rect(
            (contentBounds.left - paddingPx).coerceAtLeast(0),
            (contentBounds.top - paddingPx).coerceAtLeast(0),
            (contentBounds.right + paddingPx).coerceAtMost(source.width),
            (contentBounds.bottom + paddingPx).coerceAtMost(source.height)
        )
        val sourceWidth = paddedBounds.width().coerceAtLeast(1)
        val sourceHeight = paddedBounds.height().coerceAtLeast(1)
        val safeTargetHeight = targetHeightPx.coerceAtLeast(1)
        val scale = safeTargetHeight.toFloat() / sourceHeight.toFloat()
        val targetWidth = max(1, (sourceWidth * scale).roundToInt())
        val result = Bitmap.createBitmap(targetWidth, safeTargetHeight, Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
            drawBitmap(source, paddedBounds, Rect(0, 0, targetWidth, safeTargetHeight), bitmapPaint)
        }
        return result
    }

    fun exportContentBitmapScaled(scale: Float, paddingPx: Int): Bitmap? {
        commitSelection(addToHistory = false)
        commitCurrentPath(addToHistory = false)
        val source = extraBitmap ?: return null
        val contentBounds = findContentBounds(source) ?: return null
        val paddedBounds = Rect(
            (contentBounds.left - paddingPx).coerceAtLeast(0),
            (contentBounds.top - paddingPx).coerceAtLeast(0),
            (contentBounds.right + paddingPx).coerceAtMost(source.width),
            (contentBounds.bottom + paddingPx).coerceAtMost(source.height)
        )
        val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val targetWidth = max(1, (paddedBounds.width() * safeScale).roundToInt())
        val targetHeight = max(1, (paddedBounds.height() * safeScale).roundToInt())
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
            drawBitmap(source, paddedBounds, Rect(0, 0, targetWidth, targetHeight), bitmapPaint)
        }
        return result
    }

    fun exportPreviewContentBitmapScaled(scale: Float, paddingPx: Int): Bitmap? {
        val base = extraBitmap ?: return null
        val source = base.copy(Config.ARGB_8888, true)
        Canvas(source).drawPath(path, currentPreviewPaint())
        val contentBounds = findContentBounds(source) ?: run {
            source.recycle()
            return null
        }
        val paddedBounds = Rect(
            (contentBounds.left - paddingPx).coerceAtLeast(0),
            (contentBounds.top - paddingPx).coerceAtLeast(0),
            (contentBounds.right + paddingPx).coerceAtMost(source.width),
            (contentBounds.bottom + paddingPx).coerceAtMost(source.height)
        )
        val safeScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val targetWidth = max(1, (paddedBounds.width() * safeScale).roundToInt())
        val targetHeight = max(1, (paddedBounds.height() * safeScale).roundToInt())
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
            drawBitmap(source, paddedBounds, Rect(0, 0, targetWidth, targetHeight), bitmapPaint)
        }
        source.recycle()
        return result
    }

    fun exportInsertionViewportBitmap(
        markerX: Float,
        markerY: Float,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap? {
        val source = extraBitmap ?: return null
        if (width <= 0 || height <= 0 || outputWidth <= 0 || outputHeight <= 0) return null
        val result = Bitmap.createBitmap(outputWidth, outputHeight, Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(backgroundColorInt)
        val scale = outputWidth.toFloat() / width.toFloat()
        drawPaperTexture(canvas, outputWidth.toFloat(), outputHeight.toFloat(), scale)
        val viewportHeight = outputHeight / scale
        val top = (markerY - viewportHeight / 2f).coerceIn(0f, (height - viewportHeight).coerceAtLeast(0f))

        canvas.save()
        canvas.scale(scale, scale)
        canvas.translate(0f, -top)
        drawPaperGuides(canvas, width.toFloat(), height.toFloat(), 1f)
        canvas.drawBitmap(source, 0f, 0f, bitmapPaint)
        canvas.restore()

        val boundaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#6D4BD8"), 0xCC)
            strokeWidth = (2f * density).coerceAtLeast(1f)
            style = Paint.Style.STROKE
        }
        canvas.drawLine(0f, 0f, 0f, outputHeight.toFloat(), boundaryPaint)
        canvas.drawLine(outputWidth.toFloat(), 0f, outputWidth.toFloat(), outputHeight.toFloat(), boundaryPaint)

        val previewMarkerX = markerX.coerceIn(0f, width.toFloat()) * scale
        val previewMarkerY = (markerY.coerceIn(0f, height.toFloat()) - top) * scale
        val markerRadius = 7f * density
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6D4BD8")
            strokeWidth = 2f * density
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#6D4BD8"), 0x22)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(previewMarkerX, previewMarkerY, markerRadius * 1.8f, markerFillPaint)
        canvas.drawCircle(previewMarkerX, previewMarkerY, markerRadius, markerPaint)
        canvas.drawLine(previewMarkerX - markerRadius * 1.7f, previewMarkerY, previewMarkerX + markerRadius * 1.7f, previewMarkerY, markerPaint)
        canvas.drawLine(previewMarkerX, previewMarkerY - markerRadius * 1.7f, previewMarkerX, previewMarkerY + markerRadius * 1.7f, markerPaint)
        return result
    }

    fun contentBounds(): Rect? {
        commitSelection(addToHistory = false)
        commitCurrentPath(addToHistory = false)
        val source = extraBitmap ?: return null
        return findContentBounds(source)
    }

    private fun findContentBounds(bitmap: Bitmap): Rect? {
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                if (Color.alpha(bitmap.getPixel(x, y)) != 0) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x + 1)
                    bottom = max(bottom, y + 1)
                }
                x++
            }
            y++
        }
        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
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
        if (drawingTool == tool && !imagePlacementActive && selectedBitmap == null) return
        commitSelection()
        commitCurrentPath()
        drawingTool = tool
        imagePlacementActive = false
        invalidate()
    }


    fun placeTextBlock(
        text: String,
        x: Float,
        y: Float,
        @ColorInt color: Int = brushColorInt,
        textSizePx: Float = 28f * density
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        commitSelection()
        commitCurrentPath()
        val maxWidth = ((width.takeIf { it > 0 } ?: exportWidth.takeIf { it > 0 } ?: 320) * 0.72f)
            .roundToInt()
            .coerceAtLeast((120f * density).roundToInt())
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSizePx.coerceAtLeast(8f * density)
            typeface = android.graphics.Typeface.DEFAULT
        }
        val layout = StaticLayout.Builder.obtain(trimmed, 0, trimmed.length, textPaint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(true)
            .build()
        val horizontalPadding = (12f * density).roundToInt()
        val verticalPadding = (8f * density).roundToInt()
        val bitmap = Bitmap.createBitmap(
            (layout.width + horizontalPadding * 2).coerceAtLeast(1),
            (layout.height + verticalPadding * 2).coerceAtLeast(1),
            Config.ARGB_8888
        )
        Canvas(bitmap).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
            translate(horizontalPadding.toFloat(), verticalPadding.toFloat())
            layout.draw(this)
        }
        selectedBitmap?.recycle()
        selectedBitmap = null
        selectedRect = null
        placedImageBitmap?.recycle()
        placedImageBitmap = bitmap
        val left = x.coerceIn(0f, width.toFloat())
        val top = y.coerceIn(0f, height.toFloat())
        placedImageRect = RectF(left, top, left + bitmap.width, top + bitmap.height).also(::clampPlacedImageRect)
        imagePlacementActive = true
        hasContent = true
        invalidate()
        notifyContentChanged()
        return true
    }

    fun hasActiveLassoSelection(): Boolean = selectedBitmap != null

    fun deleteLassoSelection(): Boolean {
        val bitmap = selectedBitmap ?: return false
        if (!bitmap.isRecycled) bitmap.recycle()
        selectedBitmap = null
        selectedRect = null
        hasContent = findContentBounds(extraBitmap ?: return true) != null || hasBaseImage
        pushCurrentState(hasContent, hasBaseImage)
        invalidate()
        notifyContentChanged()
        return true
    }

    fun hasPlacedImage(): Boolean = placedImageBitmap != null

    fun isImagePlacementActive(): Boolean = imagePlacementActive

    fun selectPlacedImage(): Boolean {
        if (placedImageBitmap == null) return false
        commitCurrentPath()
        imagePlacementActive = true
        invalidate()
        return true
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

    fun setOnTextInsertionTapListener(listener: ((x: Float, y: Float) -> Unit)?) {
        textInsertionTapListener = listener
    }

    fun setOnStrokeActiveListener(listener: ((active: Boolean) -> Unit)?) {
        strokeActiveListener = listener
    }

    fun setOnStrokePreviewChangedListener(listener: (() -> Unit)?) {
        strokePreviewChangedListener = listener
    }

    fun recognitionInk(): Ink? {
        val strokes = recognitionStrokes.toList()
        if (strokes.isEmpty()) return null
        val inkBuilder = Ink.builder()
        strokes.forEach { stroke ->
            if (stroke.isNotEmpty()) {
                val strokeBuilder = Ink.Stroke.builder()
                stroke.forEach { point ->
                    strokeBuilder.addPoint(Ink.Point.create(point.x, point.y, point.time))
                }
                inkBuilder.addStroke(strokeBuilder.build())
            }
        }
        return inkBuilder.build()
    }

    fun clearRecognitionInk() {
        recognitionStrokes.clear()
        activeRecognitionStroke = null
    }

    fun setTextInsertionPreview(x: Float, y: Float) {
        insertionMarker = x to y
        invalidate()
    }

    fun clearTextInsertionPreview() {
        insertionMarker = null
        invalidate()
    }

    fun insertBitmapAt(
        bitmap: Bitmap,
        x: Float,
        y: Float,
        insertSizePx: Float,
        lineHeightPx: Float,
        bounds: RectF,
        scale: Float
    ) {
        commitCurrentPath()
        val canvas = extraCanvas ?: return
        canvas.drawBitmap(bitmap, x, y, bitmapPaint)
        clearTextInsertionPreview()
        insertedHandwritingObjects += InsertedHandwritingObject(
            id = UUID.randomUUID().toString(),
            x = x,
            y = y,
            insertSize = insertSizePx,
            lineHeight = lineHeightPx,
            bounds = bounds,
            scale = scale,
            createdAt = System.currentTimeMillis()
        )
        hasContent = true
        pushCurrentState(true, hasBaseImage)
        invalidate()
        notifyContentChanged()
    }

    private fun drawInsertionMarker(canvas: Canvas, x: Float, y: Float) {
        val radius = 10f * density
        canvas.drawCircle(x, y, radius, insertionMarkerPaint)
        canvas.drawLine(x - radius * 1.5f, y, x + radius * 1.5f, y, insertionMarkerPaint)
        canvas.drawLine(x, y - radius * 1.5f, x, y + radius * 1.5f, insertionMarkerPaint)
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
        selectedBitmap?.recycle()
        selectedBitmap = null
        selectedRect = null
        placedImageBitmap?.recycle()
        placedImageBitmap = null
        placedImageRect = null
        imagePlacementActive = false
    }

    private fun touchStart(x: Float, y: Float) {
        ensureDrawingSurface()
        if (!path.isEmpty) {
            commitCurrentPath()
        }
        path.reset()
        if (drawingTool == LASSO) commitSelection()
        if (drawingTool == PEN) {
            activeRecognitionStroke = mutableListOf(InkStrokePoint(x, y, System.currentTimeMillis()))
        }
        path.moveTo(x, y)
        currentX = x
        currentY = y
    }

    private fun touchMove(x: Float, y: Float) {
        val dx = abs(x - currentX)
        val dy = abs(y - currentY)
        if (dx >= touchTolerance || dy >= touchTolerance) {
            path.quadTo(currentX, currentY, (x + currentX) / 2, (y + currentY) / 2)
            if (drawingTool == PEN) {
                activeRecognitionStroke?.add(InkStrokePoint(x, y, System.currentTimeMillis()))
            }
            currentX = x
            currentY = y
        }
    }

    private fun touchUp() {
        path.lineTo(currentX, currentY)
        if (drawingTool == PEN) {
            activeRecognitionStroke?.add(InkStrokePoint(currentX, currentY, System.currentTimeMillis()))
            activeRecognitionStroke?.takeIf { it.isNotEmpty() }?.let { recognitionStrokes += it.toList() }
            activeRecognitionStroke = null
        }
        commitCurrentPath()
    }

    private fun touchCancel() {
        if (drawingTool != LASSO && !path.isEmpty) {
            commitCurrentPath()
        } else {
            path.reset()
        }
        activeRecognitionStroke = null
    }

    private fun notifyStrokePreviewChanged() {
        strokePreviewChangedListener?.invoke()
    }

    private fun commitCurrentPath(addToHistory: Boolean = true) {
        if (path.isEmpty) return
        val canvas = ensureDrawingSurface() ?: return
        if (drawingTool == LASSO) return
        canvas.drawPath(path, currentCommitPaint())
        path.reset()
        hasContent = true
        if (addToHistory) {
            pushCurrentState(true, hasBaseImage)
        }
        notifyContentChanged()
    }

    private fun ensureDrawingSurface(): Canvas? {
        if (width <= 0 || height <= 0) return extraCanvas
        val existing = extraBitmap
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height && extraCanvas != null) {
            return extraCanvas
        }
        val newBitmap = Bitmap.createBitmap(width, height, Config.ARGB_8888)
        val newCanvas = Canvas(newBitmap)
        newCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
        if (existing != null && !existing.isRecycled) {
            newCanvas.drawBitmap(existing, null, Rect(0, 0, width, height), bitmapPaint)
            existing.recycle()
        }
        extraBitmap = newBitmap
        extraCanvas = newCanvas
        if (history.isEmpty()) {
            pushCurrentState(hasContent, hasBaseImage)
        }
        return newCanvas
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

    private fun commitPlacedImageToCanvas(addToHistory: Boolean = true): Boolean {
        val image = placedImageBitmap ?: return false
        val rect = placedImageRect ?: return false
        val canvas = ensureDrawingSurface() ?: return false
        canvas.drawBitmap(image, null, rect, bitmapPaint)
        selectedBitmap?.recycle()
        selectedBitmap = null
        selectedRect = null
        placedImageBitmap?.recycle()
        placedImageBitmap = null
        placedImageRect = null
        imagePlacementActive = false
        hasContent = true
        if (addToHistory) pushCurrentState(true, hasBaseImage)
        notifyContentChanged()
        invalidate()
        return true
    }

    private fun finishLassoSelection() {
        path.close()
        selectLassoContent()
        path.reset()
        strokeActiveListener?.invoke(false)
        disallowParentIntercept(false)
        invalidate()
    }

    private fun selectLassoContent() {
        val source = extraBitmap ?: return
        if (source.isRecycled || path.isEmpty) return
        path.computeBounds(pathBounds, true)
        val bounds = Rect(
            pathBounds.left.roundToInt().coerceIn(0, source.width),
            pathBounds.top.roundToInt().coerceIn(0, source.height),
            pathBounds.right.roundToInt().coerceIn(0, source.width),
            pathBounds.bottom.roundToInt().coerceIn(0, source.height)
        )
        if (bounds.width() <= 1 || bounds.height() <= 1) return
        val picked = Bitmap.createBitmap(bounds.width(), bounds.height(), Config.ARGB_8888)
        Canvas(picked).apply {
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.SRC)
            save()
            translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            clipPath(path)
            drawBitmap(source, 0f, 0f, bitmapPaint)
            restore()
        }
        if (findContentBounds(picked) == null) {
            picked.recycle()
            return
        }
        selectedBitmap?.recycle()
        selectedBitmap = picked
        selectedRect = RectF(bounds)
        extraCanvas?.apply {
            save()
            clipPath(path)
            drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            restore()
        }
        hasContent = true
        notifyContentChanged()
    }

    private fun commitSelection(addToHistory: Boolean = true): Boolean {
        val bitmap = selectedBitmap ?: return false
        val rect = selectedRect ?: return false
        val canvas = ensureDrawingSurface() ?: return false
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        if (!bitmap.isRecycled) bitmap.recycle()
        selectedBitmap = null
        selectedRect = null
        hasContent = true
        if (addToHistory) pushCurrentState(true, hasBaseImage)
        notifyContentChanged()
        invalidate()
        return true
    }

    private fun drawSelection(canvas: Canvas) {
        val bitmap = selectedBitmap ?: return
        val rect = selectedRect ?: return
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, selectionFillPaint)
        canvas.drawRoundRect(rect, 10f * density, 10f * density, lassoPaint)
        canvas.drawCircle(rect.right, rect.bottom, 9f * density, selectionFillPaint)
        canvas.drawCircle(rect.right, rect.bottom, 9f * density, lassoPaint)
    }

    private fun handleSelectionTouch(event: MotionEvent): Boolean {
        val rect = selectedRect ?: return false
        val hitRect = RectF(rect).apply { inset(-12f * density, -12f * density) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitRect.contains(event.x, event.y)) return false
                disallowParentIntercept(true)
                selectionTouchMode = if (hypot(event.x - rect.right, event.y - rect.bottom) <= 22f * density) 3 else 1
                imageTouchStartX = event.x
                imageTouchStartY = event.y
                selectionStartRect.set(rect)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                selectionTouchMode = 2
                selectionStartDistance = pointerDistance(event)
                selectionStartRect.set(rect)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectionTouchMode == 1) {
                    rect.set(selectionStartRect)
                    rect.offset(event.x - imageTouchStartX, event.y - imageTouchStartY)
                    clampPlacedImageRect(rect)
                    return true
                }
                if (selectionTouchMode == 2 && event.pointerCount >= 2) {
                    val scale = (pointerDistance(event) / selectionStartDistance).takeIf { it.isFinite() && it > 0f } ?: 1f
                    val halfWidth = (selectionStartRect.width() * scale / 2f).coerceAtLeast(16f * density)
                    val halfHeight = (selectionStartRect.height() * scale / 2f).coerceAtLeast(16f * density)
                    rect.set(selectionStartRect.centerX() - halfWidth, selectionStartRect.centerY() - halfHeight, selectionStartRect.centerX() + halfWidth, selectionStartRect.centerY() + halfHeight)
                    clampPlacedImageRect(rect)
                    return true
                }
                if (selectionTouchMode == 3) {
                    val aspect = (selectionStartRect.width() / selectionStartRect.height()).takeIf { it.isFinite() && it > 0f } ?: 1f
                    val newWidth = (event.x - selectionStartRect.left).coerceAtLeast(24f * density)
                    val newHeight = (newWidth / aspect).coerceAtLeast(24f * density)
                    rect.set(selectionStartRect.left, selectionStartRect.top, selectionStartRect.left + newWidth, selectionStartRect.top + newHeight)
                    clampPlacedImageRect(rect)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectionTouchMode = 0
                disallowParentIntercept(false)
                notifyContentChanged()
                return true
            }
        }
        return selectionTouchMode != 0
    }

    private fun drawPlacedImage(canvas: Canvas, showHandles: Boolean, scale: Float = 1f) {
        val image = placedImageBitmap ?: return
        val rect = placedImageRect ?: return
        val drawRect = RectF(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
        canvas.drawBitmap(image, null, drawRect, bitmapPaint)
        if (showHandles) drawPlacedImageHandles(canvas, scale)
    }

    private fun drawPlacedImageHandles(canvas: Canvas, scale: Float = 1f) {
        val rect = placedImageRect ?: return
        val drawRect = RectF(rect.left * scale, rect.top * scale, rect.right * scale, rect.bottom * scale)
        val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#8A6040"), 0xAA)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density * scale
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(Color.WHITE, 0xCC)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(drawRect, 12f * density * scale, 12f * density * scale, handlePaint)
        canvas.drawCircle(drawRect.right, drawRect.bottom, 8f * density * scale, fillPaint)
        canvas.drawCircle(drawRect.right, drawRect.bottom, 8f * density * scale, handlePaint)
    }

    private fun handlePlacedImageTouch(event: MotionEvent): Boolean {
        val rect = placedImageRect ?: return false
        val hitRect = RectF(rect).apply { inset(-8f * density, -8f * density) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hitRect.contains(event.x, event.y)) {
                    if (imagePlacementActive) {
                        imagePlacementActive = false
                        invalidate()
                    }
                    return false
                }
                imagePlacementActive = true
                disallowParentIntercept(true)
                val handleRadius = 18f * density
                imageTouchMode = if (hypot(event.x - rect.right, event.y - rect.bottom) <= handleRadius) 3 else 1
                imageTouchStartX = event.x
                imageTouchStartY = event.y
                imageStartRect.set(rect)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount < 2) return false
                imageTouchMode = 2
                imageStartDistance = pointerDistance(event)
                imageStartRect.set(rect)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (imageTouchMode == 1) {
                    rect.set(imageStartRect)
                    rect.offset(event.x - imageTouchStartX, event.y - imageTouchStartY)
                    clampPlacedImageRect(rect)
                    return true
                }
                if (imageTouchMode == 2 && event.pointerCount >= 2) {
                    val distance = pointerDistance(event)
                    val scale = (distance / imageStartDistance).takeIf { it.isFinite() && it > 0f } ?: 1f
                    val centerX = imageStartRect.centerX()
                    val centerY = imageStartRect.centerY()
                    val halfWidth = (imageStartRect.width() * scale / 2f).coerceAtLeast(32f * density)
                    val halfHeight = (imageStartRect.height() * scale / 2f).coerceAtLeast(32f * density)
                    rect.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
                    clampPlacedImageRect(rect)
                    return true
                }
                if (imageTouchMode == 3) {
                    val aspect = (imageStartRect.width() / imageStartRect.height()).takeIf { it.isFinite() && it > 0f } ?: 1f
                    val newWidth = (event.x - imageStartRect.left).coerceAtLeast(64f * density)
                    val newHeight = (newWidth / aspect).coerceAtLeast(64f * density)
                    rect.set(imageStartRect.left, imageStartRect.top, imageStartRect.left + newWidth, imageStartRect.top + newHeight)
                    clampPlacedImageRect(rect)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                imageTouchMode = 0
                disallowParentIntercept(false)
                notifyContentChanged()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                imageTouchMode = 1
                imageStartRect.set(rect)
                imageTouchStartX = event.x
                imageTouchStartY = event.y
                return true
            }
        }
        return imageTouchMode != 0
    }

    private fun pointerDistance(event: MotionEvent): Float =
        if (event.pointerCount >= 2) hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0)) else 1f

    private fun clampPlacedImageRect(rect: RectF) {
        if (width <= 0 || height <= 0) return
        val minVisible = 36f * density
        if (rect.right < minVisible) rect.offset(minVisible - rect.right, 0f)
        if (rect.left > width - minVisible) rect.offset(width - minVisible - rect.left, 0f)
        if (rect.bottom < minVisible) rect.offset(0f, minVisible - rect.bottom)
        if (rect.top > height - minVisible) rect.offset(0f, height - minVisible - rect.top)
    }

    private fun drawPaperTexture(canvas: Canvas, width: Float, height: Float, scale: Float) {
        val luminance = ColorUtils.calculateLuminance(backgroundColorInt)
        val fiberColor = if (luminance < 0.5f) Color.WHITE else Color.parseColor("#7D5C3D")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(fiberColor, if (luminance < 0.5f) 10 else 12)
            strokeWidth = max(1f, 0.55f * density * scale)
        }
        val step = max(18f * density * scale, 24f * density * scale)
        var y = step * 0.6f
        var index = 0
        while (y < height) {
            val startX = if (index % 2 == 0) width * 0.08f else width * 0.2f
            val endX = (startX + width * 0.18f).coerceAtMost(width - 8f * density * scale)
            canvas.drawLine(startX, y, endX, y + (index % 3 - 1) * density * scale, paint)
            y += step
            index++
        }
    }

    private fun drawPaperGuides(canvas: Canvas, width: Float, height: Float, scale: Float) {
        if (paperStyle == HandwritingPaperStyle.PLAIN) return
        val spacing = 28f * density * scale
        val stroke = max(1f, 1.2f * density * scale)
        guidePaint.strokeWidth = stroke
        marginPaint.strokeWidth = stroke * 1.2f
        when (paperStyle) {
            HandwritingPaperStyle.RULED -> drawRuledGuides(canvas, width, height, spacing, scale)
            HandwritingPaperStyle.GRID -> drawGridGuides(canvas, width, height, spacing)
            HandwritingPaperStyle.DOTTED -> drawDottedGuides(canvas, width, height, spacing)
            HandwritingPaperStyle.NOTEBOOK -> {
                drawRuledGuides(canvas, width, height, spacing, scale)
                canvas.drawLine(0f, spacing * 1.55f, width, spacing * 1.55f, marginPaint)
            }
            HandwritingPaperStyle.CORNELL -> {
                drawRuledGuides(canvas, width, height, spacing, scale)
                val summaryY = height - spacing * 2.8f
                canvas.drawLine(width * 0.32f, 0f, width * 0.32f, summaryY, marginPaint)
                canvas.drawLine(0f, summaryY, width, summaryY, marginPaint)
            }
            HandwritingPaperStyle.VINTAGE -> drawVintageNotebookGuides(canvas, width, height, spacing, scale)
            HandwritingPaperStyle.PLAIN -> Unit
        }
    }

    private fun drawVintageNotebookGuides(canvas: Canvas, width: Float, height: Float, spacing: Float, scale: Float) {
        val linePaint = Paint(guidePaint).apply {
            color = ColorUtils.setAlphaComponent(Color.parseColor("#6F7F8B"), 0x2A)
            strokeWidth = max(1f, guidePaint.strokeWidth * 0.75f)
        }
        var y = spacing * 1.8f
        while (y < height - spacing) {
            canvas.drawLine(width * 0.1f, y, width * 0.94f, y, linePaint)
            y += spacing * 0.92f
        }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, 1.8f * density * scale)
            color = ColorUtils.setAlphaComponent(Color.parseColor("#8C6F4D"), 0x24)
        }
        val inset = 10f * density * scale
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, 22f * density * scale, 22f * density * scale, edgePaint)
        canvas.drawLine(18f * density * scale, 28f * density * scale, 12f * density * scale, height - 30f * density * scale, edgePaint)
    }

    private fun drawRuledGuides(canvas: Canvas, width: Float, height: Float, spacing: Float, scale: Float) {
        var y = spacing
        while (y < height) {
            canvas.drawLine(0f, y, width, y, guidePaint)
            y += spacing
        }
        val marginX = 36f * density * scale
        canvas.drawLine(marginX, 0f, marginX, height, marginPaint)
    }

    private fun drawGridGuides(canvas: Canvas, width: Float, height: Float, spacing: Float) {
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

    private fun drawDottedGuides(canvas: Canvas, width: Float, height: Float, spacing: Float) {
        val dotPaint = Paint(guidePaint).apply {
            style = Paint.Style.FILL
            strokeWidth = 1f
            alpha = (guidePaint.alpha * 0.8f).roundToInt().coerceIn(0, 255)
        }
        val radius = max(1f, guidePaint.strokeWidth * 1.15f)
        var y = spacing
        while (y < height) {
            var x = spacing
            while (x < width) {
                canvas.drawCircle(x, y, radius, dotPaint)
                x += spacing
            }
            y += spacing
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
            HandwritingPenType.PENCIL -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.pathEffect = createPencilPathEffect()
            }
            HandwritingPenType.FOUNTAIN -> {
                penPaint.strokeCap = Paint.Cap.BUTT
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.pathEffect = createFountainPathEffect()
            }
            HandwritingPenType.GEL -> {
                penPaint.strokeCap = Paint.Cap.ROUND
                penPaint.strokeJoin = Paint.Join.ROUND
                penPaint.pathEffect = CornerPathEffect(penPaint.strokeWidth * 0.32f)
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

    private fun createPencilPathEffect(): android.graphics.PathEffect {
        val segment = max(1f, penPaint.strokeWidth * 0.55f)
        val jitter = max(1f, penPaint.strokeWidth * 0.35f)
        val texture = DiscretePathEffect(segment, jitter)
        val soften = CornerPathEffect(penPaint.strokeWidth * 0.18f)
        return ComposePathEffect(soften, texture)
    }

    private fun createFountainPathEffect(): android.graphics.PathEffect {
        val nib = buildCalligraphyNibPath(penPaint.strokeWidth * 0.82f)
        val advance = max(1f, penPaint.strokeWidth * 0.22f)
        val dash = PathDashPathEffect(nib, advance, 0f, PathDashPathEffect.Style.MORPH)
        val smooth = CornerPathEffect(penPaint.strokeWidth * 0.3f)
        return ComposePathEffect(smooth, dash)
    }

    private fun updatePenColor() {
        val baseColor = brushColorInt
        val updatedColor = when (penType) {
            HandwritingPenType.HIGHLIGHTER -> {
                val targetAlpha = (Color.alpha(baseColor) * 0.55f).roundToInt().coerceIn(16, 255)
                val brightened = ColorUtils.blendARGB(baseColor, Color.WHITE, 0.2f)
                ColorUtils.setAlphaComponent(brightened, targetAlpha)
            }
            HandwritingPenType.PENCIL -> {
                val targetAlpha = (Color.alpha(baseColor) * 0.72f).roundToInt().coerceIn(32, 255)
                ColorUtils.setAlphaComponent(ColorUtils.blendARGB(baseColor, Color.GRAY, 0.24f), targetAlpha)
            }
            HandwritingPenType.GEL -> ColorUtils.blendARGB(baseColor, Color.WHITE, 0.06f)
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
        val lineColor = ColorUtils.setAlphaComponent(baseColor, (0.16f * 255).roundToInt())
        guidePaint.color = lineColor
        val accent = ColorUtils.blendARGB(backgroundColorInt, Color.parseColor("#2962FF"), 0.55f)
        marginPaint.color = ColorUtils.setAlphaComponent(accent, (0.36f * 255).roundToInt())
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
        TEXT -> penPaint
        LASSO -> lassoPaint
    }

    private fun currentPreviewPaint(): Paint = when (drawingTool) {
        PEN -> penPaint
        ERASER -> eraserPreviewPaint
        TEXT -> penPaint
        LASSO -> lassoPaint
    }
}
