package com.example.timescapedemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
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
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class BubbleModeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class BubbleItem(
        val id: Long,
        val title: String,
        val frontText: String,
        val backText: String,
        val color: Int,
        val payload: Any? = null
    )

    private data class BubbleState(
        val item: BubbleItem,
        var x: Float,
        var y: Float,
        var radius: Float,
        var vx: Float,
        var vy: Float,
        var mass: Float,
        var depthBias: Float,
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val entryStartX: Float,
        val entryStartY: Float,
        var entryDelaySec: Float,
        var entryProgress: Float,
        val radiusScale: Float,
        val phase: Float,
        val indexPhase: Float,
        val driftAmplitude: Float,
        val transitionWaveScale: Float,
        val transitionArcScale: Float,
        val orbitRadiusX: Float,
        val orbitRadiusY: Float,
        val orbitSpeed: Float,
        val orbitTilt: Float
    )

    var onBubbleClick: ((BubbleItem) -> Unit)? = null
    var onSwipeGesture: (() -> Unit)? = null
    var onBubbleFocusChanged: ((BubbleItem?) -> Unit)? = null

    private val bubbleStates = mutableListOf<BubbleState>()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val balloonRopePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(150, 240, 240, 240)
    }
    private val balloonHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(15, 18, 28)
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
    private var areaCols = 1
    private var areaRows = 1
    private var areaX = 0
    private var areaY = 0
    private var velocityX = 0f
    private var velocityY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var pendingItems: List<BubbleItem> = emptyList()
    private var swipeDistancePx = 0f
    private var transitionProgress = 1f
    private var revealProgress = 1f
    private var focusedBubbleId: Long? = null
    private var flipProgress = 1f
    private var focusedShowingBack = false
    private var focusAnimProgress = 1f
    private var focusStartX = 0f
    private var focusStartY = 0f
    private var focusStartRadius = 0f
    private var reviewGestureBlocked = false
    private var bubbleShuffleSeed: Int = 0
    private var areaTransitionProgress = 1f
    private var areaTransitionDirX = 0f
    private var areaTransitionDirY = 0f
    private var areaTransitionDistancePx = 0f
    private var areaTransitionCurvePx = 0f

    private val random = Random(System.currentTimeMillis())
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val bubble = bubbleAt(e.x, e.y) ?: return false
            if (focusedBubbleId == bubble.item.id) {
                animateFlip()
            } else if (focusedBubbleId != null) {
                return true
            } else {
                focusedBubbleId = bubble.item.id
                focusedShowingBack = false
                flipProgress = 1f
                startFocusAnimation(bubble)
                onBubbleFocusChanged?.invoke(bubble.item)
                onBubbleClick?.invoke(bubble.item)
                invalidate()
            }
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
        val densityCurve = (items.size / 120f).coerceIn(0f, 1f)
        val bubbleSpaceWidthPx = width.toFloat() * (1.18f + (densityCurve * 1.95f))
        val bubbleSpaceHeightPx = height.toFloat() * (1.22f + (densityCurve * 2.15f))
        fieldWidth = max(width.toFloat(), bubbleSpaceWidthPx)
        fieldHeight = max(height.toFloat(), bubbleSpaceHeightPx)
        areaCols = ceil(fieldWidth / width.toFloat()).toInt().coerceAtLeast(1)
        areaRows = ceil(fieldHeight / height.toFloat()).toInt().coerceAtLeast(1)
        areaX = areaX.coerceIn(0, areaCols - 1)
        areaY = areaY.coerceIn(0, areaRows - 1)
        offsetX = areaX * width.toFloat()
        offsetY = areaY * height.toFloat()
        val sortedSlots = items.indices.sortedBy { idx ->
            val id = items[idx].id
            (id xor bubbleShuffleSeed.toLong())
        }
        val avgRadius = items.map { radiusForText(it) }.average().toFloat().coerceAtLeast(1f)
        val minSpacing = (avgRadius * 2f * (0.82f + (densityCurve * 0.30f))).coerceAtLeast(18f * density())
        val anchorCount = (items.size / 8).coerceIn(5, 16)
        val clusterAnchors = List(anchorCount) {
            val a = random.nextFloat() * (Math.PI * 2f).toFloat()
            val r = (0.12f + random.nextFloat() * 0.48f) * min(fieldWidth, fieldHeight)
            Pair(
                fieldWidth * 0.5f + kotlin.math.cos(a) * r,
                fieldHeight * 0.5f + kotlin.math.sin(a) * r
            )
        }
        items.forEachIndexed { index, item ->
            val seededRandom = Random((item.id xor bubbleShuffleSeed.toLong()).toInt())
            val radius = radiusForText(item)
            val minX = radius
            val maxX = fieldWidth - radius
            val minY = radius
            val maxY = fieldHeight - radius
            val slot = sortedSlots[index]
            val anchor = clusterAnchors[(slot + index) % clusterAnchors.size]
            val orbitAngle = seededRandom.nextFloat() * (Math.PI * 2f).toFloat()
            val orbitRadius = (18f + seededRandom.nextFloat() * min(fieldWidth, fieldHeight) * 0.22f)
            val centerBiasX = (anchor.first + cos(orbitAngle) * orbitRadius).coerceIn(minX, maxX)
            val centerBiasY = (anchor.second + sin(orbitAngle) * orbitRadius).coerceIn(minY, maxY)
            val maxAllowedCoverage = 0.66f
            var targetX = centerBiasX
            var targetY = centerBiasY
            var bestCoverage = Float.MAX_VALUE
            var bestNearestDistance = Float.NEGATIVE_INFINITY
            val attempts = 68
            repeat(attempts) {
                val t = it.toFloat() / attempts.toFloat()
                val anchorWeight = (1f - t * 0.8f).coerceIn(0.28f, 1f)
                val jitterScale = lerp(0.18f, 0.52f, t)
                val candidateX = (
                    centerBiasX * anchorWeight +
                        (fieldWidth * (0.18f + seededRandom.nextFloat() * 0.64f)) * (1f - anchorWeight) +
                        (seededRandom.nextFloat() - 0.5f) * fieldWidth * jitterScale
                    ).coerceIn(minX, maxX)
                val candidateY = (
                    centerBiasY * anchorWeight +
                        (fieldHeight * (0.16f + seededRandom.nextFloat() * 0.68f)) * (1f - anchorWeight) +
                        (seededRandom.nextFloat() - 0.5f) * fieldHeight * jitterScale
                    ).coerceIn(minY, maxY)
                var peakCoverage = 0f
                var nearestDistance = Float.MAX_VALUE
                for (existing in bubbleStates) {
                    nearestDistance = min(nearestDistance, hypot(candidateX - existing.x, candidateY - existing.y))
                    val intersection = circleIntersectionArea(
                        x1 = candidateX, y1 = candidateY, r1 = radius,
                        x2 = existing.x, y2 = existing.y, r2 = existing.radius
                    )
                    if (intersection <= 0f) continue
                    val candidateCoverage = (intersection / (PI.toFloat() * radius * radius)).coerceIn(0f, 1f)
                    val existingCoverage = (intersection / (PI.toFloat() * existing.radius * existing.radius)).coerceIn(0f, 1f)
                    peakCoverage = max(peakCoverage, max(candidateCoverage, existingCoverage))
                    if (peakCoverage > maxAllowedCoverage) break
                }
                if (nearestDistance >= minSpacing && peakCoverage <= maxAllowedCoverage) {
                    targetX = candidateX
                    targetY = candidateY
                    bestCoverage = peakCoverage
                    return@repeat
                }
                if (peakCoverage < bestCoverage || (peakCoverage <= maxAllowedCoverage && nearestDistance > bestNearestDistance)) {
                    bestCoverage = peakCoverage
                    bestNearestDistance = nearestDistance
                    targetX = candidateX
                    targetY = candidateY
                }
            }
            val radiusScale = seededRandom.nextFloat()
            bubbleStates += BubbleState(
                item = item,
                x = targetX.coerceIn(minX, maxX),
                y = targetY.coerceIn(minY, maxY),
                radius = radius,
                vx = 0f,
                vy = 0f,
                mass = (radius * radius).coerceAtLeast(1f),
                depthBias = random.nextFloat() * 0.2f - 0.1f,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                entryStartX = width * 0.5f + seededRandom.nextFloat() * width * 0.22f - width * 0.11f,
                entryStartY = -radius * 2f - seededRandom.nextFloat() * (height * 0.35f),
                entryDelaySec = index * 0.018f,
                entryProgress = 0f,
                radiusScale = radiusScale,
                phase = seededRandom.nextFloat() * (Math.PI * 2.0).toFloat(),
                indexPhase = ((index * 0.21f) % (Math.PI * 2.0)).toFloat(),
                driftAmplitude = 0.35f + (1f - (items.size / 120f).coerceIn(0f, 1f)) * 0.65f
                ,
                transitionWaveScale = 0.55f + seededRandom.nextFloat() * 1.35f,
                transitionArcScale = 0.5f + seededRandom.nextFloat() * 1.5f,
                orbitRadiusX = (10f + seededRandom.nextFloat() * 28f) * density(),
                orbitRadiusY = (7f + seededRandom.nextFloat() * 24f) * density(),
                orbitSpeed = 0.42f + seededRandom.nextFloat() * 0.95f,
                orbitTilt = seededRandom.nextFloat() * (Math.PI * 2f).toFloat()
            )
        }
        revealProgress = 0f
        velocityX = 0f
        velocityY = 0f
        transitionProgress = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                transitionProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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
                val touched = bubbleAt(event.x, event.y)
                reviewGestureBlocked = focusedBubbleId != null && touched?.item?.id != focusedBubbleId
                if (reviewGestureBlocked) {
                    return true
                }
                lastTouchX = event.x
                lastTouchY = event.y
                swipeDistancePx = 0f
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (reviewGestureBlocked) return true
                velocityTracker?.addMovement(event)
                val dx = lastTouchX - event.x
                val dy = lastTouchY - event.y
                swipeDistancePx += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                velocityX += dx
                velocityY += dy
                lastTouchX = event.x
                lastTouchY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (reviewGestureBlocked) {
                    reviewGestureBlocked = false
                    return true
                }
                velocityTracker?.apply {
                    addMovement(event)
                    computeCurrentVelocity(1000)
                    velocityX += xVelocity * -0.12f
                    velocityY += yVelocity * -0.12f
                    recycle()
                }
                velocityTracker = null
                if (swipeDistancePx > 48f * density()) {
                    focusedBubbleId = null
                    focusAnimProgress = 1f
                    onBubbleFocusChanged?.invoke(null)
                    val absX = kotlin.math.abs(velocityX)
                    val absY = kotlin.math.abs(velocityY)
                    if (absX >= absY) {
                        if (velocityX > 0) {
                            areaX = (areaX + 1).coerceAtMost(areaCols - 1)
                            areaTransitionDirX = -1f
                            areaTransitionDirY = 0f
                        } else {
                            areaX = (areaX - 1).coerceAtLeast(0)
                            areaTransitionDirX = 1f
                            areaTransitionDirY = 0f
                        }
                    } else {
                        if (velocityY > 0) {
                            areaY = (areaY + 1).coerceAtMost(areaRows - 1)
                            areaTransitionDirX = 0f
                            areaTransitionDirY = -1f
                        } else {
                            areaY = (areaY - 1).coerceAtLeast(0)
                            areaTransitionDirX = 0f
                            areaTransitionDirY = 1f
                        }
                    }
                    val speed = hypot(velocityX, velocityY)
                    val distanceBoost = (speed * 0.028f).coerceIn(0f, 120f * density())
                    areaTransitionDistancePx = 48f * density() + distanceBoost + random.nextFloat() * 34f * density()
                    areaTransitionCurvePx = (18f + random.nextFloat() * 56f) * density() *
                        if (random.nextBoolean()) 1f else -1f
                    beginAreaRevealAnimation()
                }
                velocityX = 0f
                velocityY = 0f
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        if (bubbleStates.isEmpty() && pendingItems.isNotEmpty()) {
            Log.d("BubbleModeView", "onDraw with empty state despite pending=${pendingItems.size}")
        }
        val cx = width / 2f
        val cy = height / 2f
        val visible = visibleBubbleIndices()
        val focused = bubbleStates.firstOrNull { it.item.id == focusedBubbleId }
        val hasFocused = focused != null
        bubbleStates.forEach { bubble ->
            if (bubble.item.id !in visible) return@forEach
            if (bubble.item.id == focusedBubbleId) return@forEach
            drawBubble(canvas, bubble, cx, cy, isFocused = false, dimmed = hasFocused)
        }
        if (hasFocused) {
            canvas.drawColor(Color.argb(66, 0, 0, 0))
            focused?.let { drawBubble(canvas, it, cx, cy, isFocused = true, dimmed = false) }
        }
    }

    private fun drawBubble(
        canvas: Canvas,
        bubble: BubbleState,
        cx: Float,
        cy: Float,
        isFocused: Boolean,
        dimmed: Boolean
    ) {
        val entryT = easeOutCubic(bubble.entryProgress.coerceIn(0f, 1f))
        val timeSec = SystemClock.uptimeMillis().toFloat() / 1000f
        val orbitalAngle = timeSec * bubble.orbitSpeed + bubble.phase
        val localX = kotlin.math.cos(orbitalAngle) * bubble.orbitRadiusX * bubble.driftAmplitude
        val localY = kotlin.math.sin(orbitalAngle + bubble.indexPhase * 0.35f) * bubble.orbitRadiusY * bubble.driftAmplitude
        val ct = kotlin.math.cos(bubble.orbitTilt)
        val st = kotlin.math.sin(bubble.orbitTilt)
        val driftX = localX * ct - localY * st
        val driftY = localX * st + localY * ct
        val pulse = 1f
        var worldX = lerp(bubble.entryStartX, bubble.x, entryT) + driftX * entryT
        var worldY = lerp(bubble.entryStartY, bubble.y, entryT) + driftY * entryT
        val delay = ((bubble.phase / (Math.PI * 2f).toFloat()) * 0.24f).coerceIn(0f, 0.24f)
        val localTransition = ((areaTransitionProgress - delay) / (1f - delay).coerceAtLeast(0.1f)).coerceIn(0f, 1f)
        val settleT = easeOutCubic(localTransition)
        val fallbackDistance = (18f + bubble.radiusScale * 22f) * density()
        val transitionOffsetBase = (1f - settleT) * max(fallbackDistance, areaTransitionDistancePx)
        val wave = kotlin.math.sin((1f - localTransition) * Math.PI.toFloat() * 2f + bubble.indexPhase)
        val wobble = wave * (8f + bubble.radiusScale * 12f) * density() * bubble.transitionWaveScale
        val arc = kotlin.math.sin((1f - localTransition) * Math.PI.toFloat()) *
            (areaTransitionCurvePx * (0.45f + bubble.radiusScale * 0.7f) * bubble.transitionArcScale)
        worldX += areaTransitionDirX * transitionOffsetBase + areaTransitionDirY * (wobble + arc)
        worldY += areaTransitionDirY * transitionOffsetBase - areaTransitionDirX * (wobble + arc)
        val drawX = worldX - offsetX
        val drawY = worldY - offsetY
        if (drawX + bubble.radius < 0f || drawX - bubble.radius > width || drawY + bubble.radius < 0f || drawY - bubble.radius > height) return

        val distNorm = (hypot(drawX - cx, drawY - cy) / hypot(cx, cy)).coerceIn(0f, 1.3f)
        val baseRadius = bubble.radius * (1.05f - distNorm * 0.28f + bubble.depthBias).coerceIn(0.78f, 1.0f)
        val reviewRadius = 96f * density()
        if (isFocused) {
            val centerWorldX = offsetX + width * 0.5f
            val centerWorldY = offsetY + height * 0.5f
            worldX = lerp(focusStartX, centerWorldX, focusAnimProgress)
            worldY = lerp(focusStartY, centerWorldY, focusAnimProgress)
        }
        val renderX = worldX - offsetX
        val renderY = worldY - offsetY
        val radius = if (isFocused) lerp(focusStartRadius, reviewRadius, focusAnimProgress) else baseRadius * pulse
        val dimFactor = if (dimmed) 0.35f else 1f
        val alpha = ((0.68f + (1f - distNorm) * 0.30f) * transitionProgress * entryT * dimFactor * revealProgress * (0.42f + settleT * 0.58f)).coerceIn(0.05f, 0.98f)
        val alphaInt = (alpha * 255).toInt()
        bubblePaint.shader = createBubbleGradient(
            item = bubble.item,
            centerX = renderX,
            centerY = renderY,
            radius = radius,
            alpha = alphaInt
        )
        canvas.drawCircle(renderX, renderY, radius, bubblePaint)
        bubblePaint.shader = null
        if (!isFocused) {
            val ropeLength = radius * (0.65f + bubble.radiusScale * 0.45f)
            balloonRopePaint.strokeWidth = (1.6f + bubble.radiusScale * 1.8f) * density()
            canvas.drawLine(
                renderX + radius * 0.08f,
                renderY + radius * 0.88f,
                renderX + kotlin.math.sin(bubble.phase) * radius * 0.18f,
                renderY + radius + ropeLength,
                balloonRopePaint
            )
        }
        balloonHighlightPaint.shader = LinearGradient(
            renderX - radius * 0.35f,
            renderY - radius * 0.72f,
            renderX + radius * 0.18f,
            renderY - radius * 0.1f,
            intArrayOf(
                Color.argb((alphaInt * 0.38f).toInt(), 255, 255, 255),
                Color.argb((alphaInt * 0.07f).toInt(), 255, 255, 255),
                Color.argb(0, 255, 255, 255)
            ),
            floatArrayOf(0f, 0.56f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            renderX - radius * 0.46f,
            renderY - radius * 0.72f,
            renderX + radius * 0.08f,
            renderY - radius * 0.05f,
            balloonHighlightPaint
        )
        balloonHighlightPaint.shader = null

        val maxTextWidth = radius * 1.62f
        val text = if (isFocused) {
            if (focusedShowingBack) bubble.item.backText else bubble.item.frontText
        } else {
            bubble.item.title
        }.ifBlank { "Untitled" }
        val maxLines = if (isFocused) 7 else 4
        val lines = buildWrappedLines(text, textPaint, maxTextWidth, maxLines)
        textPaint.alpha = ((180 + ((1f - distNorm) * 75f).toInt()) * dimFactor).toInt().coerceIn(70, 255)
        if (isFocused) {
            val effective = flipProgress.coerceIn(0f, 1f)
            val sx = kotlin.math.abs(cos(Math.PI * effective)).toFloat().coerceAtLeast(0.05f)
            canvas.save()
            canvas.scale(sx, 1f, renderX, renderY)
            drawCenteredMultilineText(canvas, lines, renderX, renderY)
            canvas.restore()
        } else {
            drawCenteredMultilineText(canvas, lines, renderX, renderY)
        }
    }

    private fun stepPhysics(dt: Float) {
        if (bubbleStates.isEmpty()) return
        val spring = (dt * 9.8f).coerceIn(0f, 1f)
        val targetOffsetX = areaX * width.toFloat()
        val targetOffsetY = areaY * height.toFloat()
        offsetX = lerp(offsetX, targetOffsetX, spring)
        offsetY = lerp(offsetY, targetOffsetY, spring)
        revealProgress = (revealProgress + dt * 2.4f).coerceAtMost(1f)
        areaTransitionProgress = (areaTransitionProgress + dt * 2.8f).coerceAtMost(1f)

        bubbleStates.forEach { bubble ->
            if (bubble.entryDelaySec > 0f) {
                bubble.entryDelaySec = (bubble.entryDelaySec - dt).coerceAtLeast(0f)
            } else if (bubble.entryProgress < 1f) {
                bubble.entryProgress = (bubble.entryProgress + dt * 2.3f).coerceAtMost(1f)
            }
        }
    }

    private fun visibleBubbleIndices(): Set<Long> {
        val radiusPadding = 130f * density()
        val left = offsetX - radiusPadding
        val right = offsetX + width + radiusPadding
        val top = offsetY - radiusPadding
        val bottom = offsetY + height + radiusPadding
        return bubbleStates
            .asSequence()
            .filter { it.x in left..right && it.y in top..bottom }
            .map { it.item.id }
            .toSet()
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

    private fun beginAreaRevealAnimation() {
        revealProgress = 0.72f
        areaTransitionProgress = 0f
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
            (0.70f + saturation * 0.28f).coerceIn(0f, 1f),
            (0.45f + value * 0.28f).coerceIn(0f, 1f)
        )
        val midTone = floatArrayOf(
            hue,
            (0.82f + saturation * 0.22f).coerceIn(0f, 1f),
            (0.32f + value * 0.24f).coerceIn(0f, 1f)
        )
        val edgeTone = floatArrayOf(
            hue,
            (0.92f + saturation * 0.14f).coerceIn(0f, 1f),
            (0.16f + value * 0.12f).coerceIn(0f, 1f)
        )
        val rimTone = floatArrayOf(
            hue,
            (0.95f + saturation * 0.08f).coerceIn(0f, 1f),
            (0.08f + value * 0.05f).coerceIn(0f, 1f)
        )
        val centerColor = ColorUtils.setAlphaComponent(Color.HSVToColor(centerTone), alpha)
        val midColor = ColorUtils.setAlphaComponent(Color.HSVToColor(midTone), alpha)
        val edgeColor = ColorUtils.setAlphaComponent(Color.HSVToColor(edgeTone), alpha)
        val rimColor = ColorUtils.setAlphaComponent(Color.HSVToColor(rimTone), alpha)
        return RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(centerColor, midColor, edgeColor, rimColor),
            floatArrayOf(0f, 0.55f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun radiusForText(item: BubbleItem): Float {
        val baseText = max(item.frontText.length, item.backText.length).coerceAtLeast(item.title.length)
        val normalized = (baseText / 240f).coerceIn(0f, 1.6f)
        val minRadius = 52f * density()
        val maxRadius = 122f * density()
        val target = minRadius + (maxRadius - minRadius) * kotlin.math.sqrt(normalized.coerceIn(0f, 1f))
        val jitter = (random.nextFloat() - 0.5f) * (8f * density())
        return (target + jitter).coerceIn(minRadius, maxRadius)
    }

    private fun circleIntersectionArea(
        x1: Float,
        y1: Float,
        r1: Float,
        x2: Float,
        y2: Float,
        r2: Float
    ): Float {
        val d = hypot(x2 - x1, y2 - y1)
        if (d >= r1 + r2) return 0f
        val minRadius = min(r1, r2)
        if (d <= abs(r1 - r2)) return PI.toFloat() * minRadius * minRadius
        val dD = d.toDouble()
        val r1D = r1.toDouble()
        val r2D = r2.toDouble()
        val alpha = 2.0 * acos(((dD * dD) + (r1D * r1D) - (r2D * r2D)) / (2.0 * dD * r1D))
        val beta = 2.0 * acos(((dD * dD) + (r2D * r2D) - (r1D * r1D)) / (2.0 * dD * r2D))
        val area1 = 0.5 * r1D * r1D * (alpha - kotlin.math.sin(alpha))
        val area2 = 0.5 * r2D * r2D * (beta - kotlin.math.sin(beta))
        return (area1 + area2).toFloat()
    }

    private fun buildWrappedLines(
        rawText: String,
        paint: TextPaint,
        maxWidth: Float,
        maxLines: Int
    ): List<String> {
        val words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth || current.isEmpty()) {
                current = candidate
            } else {
                lines += current
                current = word
            }
            if (lines.size >= maxLines) return@forEach
        }
        if (current.isNotEmpty() && lines.size < maxLines) lines += current
        if (lines.size > maxLines) return lines.take(maxLines)
        if (words.joinToString(" ").length > lines.joinToString(" ").length && lines.isNotEmpty()) {
            val last = lines.last()
            lines[lines.lastIndex] = trimToWidth(last + "…", paint, maxWidth)
        }
        return lines
    }

    private fun drawCenteredMultilineText(
        canvas: Canvas,
        lines: List<String>,
        centerX: Float,
        centerY: Float
    ) {
        if (lines.isEmpty()) return
        val lineHeight = textPaint.fontMetrics.let { it.descent - it.ascent }
        val blockHeight = lineHeight * lines.size
        var y = centerY - blockHeight / 2f - textPaint.fontMetrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, centerX, y, textPaint)
            y += lineHeight
        }
    }

    private fun trimToWidth(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val candidate = text.take(mid)
            if (paint.measureText(candidate) <= maxWidth) low = mid else high = mid - 1
        }
        return text.take(low)
    }

    private fun density(): Float = resources.displayMetrics.density

    private fun lerp(start: Float, end: Float, t: Float): Float = start + (end - start) * t

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    private fun animateFlip() {
        val toBack = !focusedShowingBack
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                flipProgress = t
                focusedShowingBack = if (toBack) t > 0.5f else t < 0.5f
                invalidate()
            }
            start()
        }
    }

    private fun startFocusAnimation(bubble: BubbleState) {
        focusStartX = bubble.x
        focusStartY = bubble.y
        focusStartRadius = bubble.radius * 0.95f
        focusAnimProgress = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                focusAnimProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun clearFocusedBubble() {
        focusedBubbleId = null
        focusAnimProgress = 1f
        focusedShowingBack = false
        onBubbleFocusChanged?.invoke(null)
        invalidate()
    }
}
