package com.example.timescapedemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class BubbleModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class BubbleItem(
        val id: Long,
        val title: String,
        val color: Int,
        val payload: Any? = null
    )

    private data class BubbleState(
        val item: BubbleItem,
        var x: Float,
        var y: Float,
        var radius: Float,
        var driftX: Float,
        var driftY: Float,
        var depthBias: Float,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float
    )

    var onBubbleClick: ((BubbleItem) -> Unit)? = null

    private val bubbleStates = mutableListOf<BubbleState>()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * resources.displayMetrics.density
        color = Color.argb(70, 255, 255, 255)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 14f * resources.displayMetrics.scaledDensity
    }

    private var fieldWidth = 0f
    private var fieldHeight = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var velocityX = 0f
    private var velocityY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pendingItems: List<BubbleItem> = emptyList()

    private val random = Random(System.currentTimeMillis())
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val bubble = bubbleAt(e.x, e.y) ?: return false
            onBubbleClick?.invoke(bubble.item)
            return true
        }
    })

    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            if (lastFrameNanos == 0L) {
                lastFrameNanos = frameTimeNanos
            }
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos
            stepPhysics(dt)
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun submitBubbles(items: List<BubbleItem>) {
        pendingItems = items
        bubbleStates.clear()
        if (width == 0 || height == 0 || items.isEmpty()) {
            Log.d("BubbleModeView", "submit deferred width=$width height=$height items=${items.size}")
            invalidate()
            return
        }
        val bubblesPerPage = 30
        val pageCount = (items.size + bubblesPerPage - 1) / bubblesPerPage
        fieldWidth = width.toFloat() * pageCount.coerceAtLeast(1)
        fieldHeight = height.toFloat()
        items.forEachIndexed { index, item ->
            val pageIndex = index / bubblesPerPage
            val pageLeft = pageIndex * width.toFloat()
            val pageRight = pageLeft + width.toFloat()
            val radius = random.nextInt((46 * density()).toInt(), (88 * density()).toInt()).toFloat()
            val minX = pageLeft + radius
            val maxX = pageRight - radius
            val minY = radius
            val maxY = fieldHeight - radius
            bubbleStates += BubbleState(
                item = item,
                x = random.nextFloat() * (maxX - minX).coerceAtLeast(1f) + minX,
                y = random.nextFloat() * (maxY - minY).coerceAtLeast(1f) + minY,
                radius = radius,
                driftX = random.nextFloat() * 26f - 13f,
                driftY = random.nextFloat() * 22f - 11f,
                depthBias = random.nextFloat() * 0.2f - 0.1f,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY
            )
        }
        offsetX = 0f
        offsetY = 0f
        velocityX = 0f
        velocityY = 0f
        Log.d("BubbleModeView", "submit complete rendered=${bubbleStates.size} field=${fieldWidth}x$fieldHeight")
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pendingItems.isNotEmpty()) {
            Log.d("BubbleModeView", "onSizeChanged replay items=${pendingItems.size} size=${w}x$h")
            submitBubbles(pendingItems)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                velocityX = 0f
                velocityY = 0f
                lastTouchX = event.x
                lastTouchY = event.y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val dx = lastTouchX - event.x
                val dy = lastTouchY - event.y
                offsetX += dx
                offsetY += dy
                lastTouchX = event.x
                lastTouchY = event.y
                clampOffsets(withResistance = true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.apply {
                    addMovement(event)
                    computeCurrentVelocity(1000)
                    velocityX = xVelocity * -0.9f
                    velocityY = yVelocity * -0.9f
                    recycle()
                }
                velocityTracker = null
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbleStates.isEmpty() && pendingItems.isNotEmpty()) {
            Log.d("BubbleModeView", "onDraw with empty state despite pending=${pendingItems.size}")
        }
        val cx = width / 2f
        val cy = height / 2f
        bubbleStates.forEach { bubble ->
            val drawX = bubble.x - offsetX
            val drawY = bubble.y - offsetY
            if (drawX + bubble.radius < 0f || drawX - bubble.radius > width || drawY + bubble.radius < 0f || drawY - bubble.radius > height) return@forEach

            val distNorm = (hypot(drawX - cx, drawY - cy) / hypot(cx, cy)).coerceIn(0f, 1.3f)
            val prominence = (1.05f - distNorm * 0.28f + bubble.depthBias).coerceIn(0.78f, 1.0f)
            val alpha = (0.68f + (1f - distNorm) * 0.30f).coerceIn(0.55f, 0.98f)
            val radius = bubble.radius * prominence
            val alphaInt = (alpha * 255).toInt()
            bubblePaint.shader = createBubbleGradient(
                item = bubble.item,
                centerX = drawX,
                centerY = drawY,
                radius = radius,
                alpha = alphaInt
            )

            canvas.drawCircle(drawX, drawY, radius, bubblePaint)
            canvas.drawCircle(drawX, drawY, radius, bubbleStrokePaint)
            bubblePaint.shader = null

            val maxTextWidth = radius * 1.45f
            val text = bubble.item.title.ifBlank { "Untitled" }
            val clipped = ellipsize(text, textPaint, maxTextWidth)
            textPaint.alpha = (180 + ((1f - distNorm) * 75f).toInt()).coerceIn(110, 255)
            canvas.drawText(clipped, drawX, drawY + textPaint.textSize * 0.28f, textPaint)
        }
    }

    private fun stepPhysics(dt: Float) {
        if (bubbleStates.isEmpty()) return
        val friction = 0.93f
        offsetX += velocityX * dt
        offsetY += velocityY * dt
        velocityX *= friction
        velocityY *= friction
        if (abs(velocityX) < 2f) velocityX = 0f
        if (abs(velocityY) < 2f) velocityY = 0f
        clampOffsets(withResistance = false)

        bubbleStates.forEach { bubble ->
            bubble.x += bubble.driftX * dt
            bubble.y += bubble.driftY * dt
            if (bubble.x < bubble.minX || bubble.x > bubble.maxX) {
                bubble.driftX *= -1f
                bubble.x = bubble.x.coerceIn(bubble.minX, bubble.maxX)
            }
            if (bubble.y < bubble.minY || bubble.y > bubble.maxY) {
                bubble.driftY *= -1f
                bubble.y = bubble.y.coerceIn(bubble.minY, bubble.maxY)
            }
        }
    }

    private fun clampOffsets(withResistance: Boolean) {
        val maxOffsetX = max(0f, fieldWidth - width)
        val maxOffsetY = max(0f, fieldHeight - height)
        if (withResistance) {
            if (offsetX < 0f) offsetX *= 0.45f
            if (offsetY < 0f) offsetY *= 0.45f
            if (offsetX > maxOffsetX) offsetX = maxOffsetX + (offsetX - maxOffsetX) * 0.45f
            if (offsetY > maxOffsetY) offsetY = maxOffsetY + (offsetY - maxOffsetY) * 0.45f
        } else {
            if (offsetX < 0f || offsetX > maxOffsetX || offsetY < 0f || offsetY > maxOffsetY) {
                val startX = offsetX
                val startY = offsetY
                val endX = offsetX.coerceIn(0f, maxOffsetX)
                val endY = offsetY.coerceIn(0f, maxOffsetY)
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 260
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { anim ->
                        val t = anim.animatedValue as Float
                        offsetX = startX + (endX - startX) * t
                        offsetY = startY + (endY - startY) * t
                        invalidate()
                    }
                    start()
                }
                velocityX = 0f
                velocityY = 0f
            }
        }
    }

    private fun bubbleAt(x: Float, y: Float): BubbleState? {
        val worldX = x + offsetX
        val worldY = y + offsetY
        return bubbleStates.lastOrNull { bubble ->
            hypot(worldX - bubble.x, worldY - bubble.y) <= bubble.radius * 1.15f
        }
    }

    private fun createBubbleGradient(
        item: BubbleItem,
        centerX: Float,
        centerY: Float,
        radius: Float,
        alpha: Int
    ): Shader {
        val baseHsv = FloatArray(3)
        Color.colorToHSV(item.color, baseHsv)
        val hueShift = (((item.id % 11L) - 5L).toFloat()) * 1.8f
        val hue = (baseHsv[0] + hueShift + 360f) % 360f
        val saturation = baseHsv[1].coerceIn(0f, 1f)
        val value = baseHsv[2].coerceIn(0f, 1f)

        val centerTone = floatArrayOf(
            hue,
            (saturation * 0.95f + 0.05f).coerceIn(0f, 1f),
            (value * 0.98f + 0.02f).coerceIn(0f, 1f)
        )
        val midTone = floatArrayOf(
            hue,
            (saturation * 1.12f).coerceIn(0f, 1f),
            (value * 0.66f).coerceIn(0f, 1f)
        )
        val edgeTone = floatArrayOf(
            hue,
            (saturation * 1.35f).coerceIn(0f, 1f),
            (value * 0.28f).coerceIn(0f, 1f)
        )
        val centerColor = ColorUtils.setAlphaComponent(Color.HSVToColor(centerTone), alpha)
        val midColor = ColorUtils.setAlphaComponent(Color.HSVToColor(midTone), alpha)
        val edgeColor = ColorUtils.setAlphaComponent(Color.HSVToColor(edgeTone), alpha)
        return RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(centerColor, midColor, edgeColor),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun ellipsize(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = text.take(mid) + "…"
            if (paint.measureText(candidate) <= maxWidth) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return text.take(low) + "…"
    }

    private fun density(): Float = resources.displayMetrics.density
}
