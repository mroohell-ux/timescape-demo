package com.example.timescapedemo

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Choreographer
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.SeekBar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

internal data class CardFlipSnapshot(val bitmap: Bitmap, val contentHeight: Int, val cornerRadius: Float)

internal fun cardFlipAngle(progress: Float): Float {
    val distance = abs(progress)
    val angle = if (distance <= 1f) {
        90f * (1f - cos(distance * Math.PI).toFloat())
    } else {
        val overshoot = distance - 1f
        180f + 2f * (1f - exp(-40f * overshoot * overshoot))
    }
    return sign(progress) * angle
}

/** Exact damped integration keeps the same trajectory across uneven display frames. */
internal class CardFlipSpring(
    private val frequency: Float,
    private val damping: Float,
    private val tolerance: Float
) {
    var value = 0f
        private set
    var velocity = 0f
    var target = 0f
    val isAtRest: Boolean
        get() = abs(value - target) < tolerance && abs(velocity) < tolerance * frequency

    fun snap(value: Float) {
        this.value = value
        target = value
        velocity = 0f
    }

    fun advance(seconds: Float) {
        if (isAtRest) {
            snap(target)
            return
        }
        val displacement = value - target
        val decayRate = damping * frequency
        val oscillation = frequency * sqrt(1f - damping * damping)
        val decay = exp(-decayRate * seconds)
        val cosine = cos(oscillation * seconds)
        val sine = sin(oscillation * seconds)
        value = target + decay * (displacement * cosine +
            (velocity + decayRate * displacement) / oscillation * sine)
        velocity = decay * (velocity * cosine -
            (decayRate * velocity + frequency * frequency * displacement) / oscillation * sine)
    }
}

/** Horizontal ownership is decided before either the vertical ribbon or outer pager can intercept. */
class GestureCardFlipLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    var gestureEnabled = true
        set(value) {
            if (field == value) return
            field = value
            if (!value && direction != Direction.NONE) cancelInteraction()
        }
    var canFlip: () -> Boolean = { false }
    var onPrepare: () -> Unit = {}
    var onFlipStarted: () -> Unit = {}
    var onFaceSettled: (Boolean, () -> Unit) -> Unit = { _, complete -> complete() }
    var onFlipFinished: () -> Unit = {}
    val flipProgress: Float get() = turn.value
    val isFlipping: Boolean get() = active
    internal val liftFraction: Float get() = lift.value
    internal val turnVelocity: Float get() = turn.velocity
    internal val tiltX: Float get() = lift.value * (-gripY.value * 5.5f + offsetY.value / density * .14f)
    internal val isLanding: Boolean get() = landing

    private val density = resources.displayMetrics.density
    private val configuration = ViewConfiguration.get(context)
    private val choreographer = Choreographer.getInstance()
    private val turn = CardFlipSpring(34f, .9f, .0006f)
    private val lift = CardFlipSpring(27f, .88f, .001f)
    private val offsetX = CardFlipSpring(32f, .88f, .12f)
    private val offsetY = CardFlipSpring(32f, .88f, .12f)
    private val gripX = CardFlipSpring(30f, .9f, .001f)
    private val gripY = CardFlipSpring(30f, .9f, .001f)
    private val occupancy = CardFlipSpring(26f, .95f, .35f)
    private val poseSprings = arrayOf(lift, offsetX, offsetY, gripX, gripY)
    private val camera = Camera()
    private val projection = Matrix()
    private val destination = RectF()
    private val faceBounds = RectF()
    private val faceClip = Path()
    private val controlBounds = Rect()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lighting = Array(25) { level ->
        ColorMatrixColorFilter(ColorMatrix().apply {
            val brightness = 1f - level * .004f
            setScale(brightness, brightness, brightness, 1f)
        })
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(18, 0, 0, 0)
        setShadowLayer(14f * density, 0f, 9f * density, 0x38000000)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = .7f * density
        style = Paint.Style.STROKE
    }
    private var current: CardFlipSnapshot? = null
    private var other: CardFlipSnapshot? = null
    private var preparedWidth = 0
    private var preparing = false
    private var active = false
    private var finishing = false
    private var generation = 0
    private var target = 0f
    private var pendingTarget: Float? = null
    private var pendingVelocity = 0f
    private var held = false
    private var settling = false
    private var landing = false
    private var framePosted = false
    private var previousFrame = 0L
    private var tracker: VelocityTracker? = null
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downProgress = 0f
    private var direction = Direction.NONE

    private enum class Direction { NONE, UNDECIDED, HORIZONTAL, VERTICAL, CANCELLED }

    init {
        clipChildren = false
        clipToPadding = false
    }

    private val frame = Choreographer.FrameCallback { time -> advanceFrame(time) }

    internal fun setFaces(current: CardFlipSnapshot, other: CardFlipSnapshot) {
        this.current = current
        this.other = other
        preparedWidth = width
        preparing = false
        invalidate()
        pendingTarget?.let {
            pendingTarget = null
            settle(it, pendingVelocity)
        }
    }

    fun preparationCancelled() {
        preparing = false
        cancelInteraction()
    }

    internal fun invalidatePreparedFaces() {
        if (active) return
        current = null
        other = null
    }

    fun reset() {
        generation++
        stopFrames()
        releaseTouch()
        current = null
        other = null
        preparing = false
        active = false
        finishing = false
        pendingTarget = null
        clearMotion()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        requestLayout()
        invalidate()
    }

    fun cancelInteraction() {
        releaseTouch()
        if (!active && pendingTarget == null) return
        generation++
        stopFrames()
        active = false
        finishing = false
        pendingTarget = null
        clearMotion()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
        onFlipFinished()
        requestLayout()
        invalidate()
    }

    fun flip(): Boolean {
        if (!canFlip() || finishing) return false
        val next = if (active && abs(target) > 0.5f) 0f else {
            if (flipProgress < 0f) -1f else 1f
        }
        held = false
        beginFlip()
        prepare()
        settle(next, turn.velocity)
        return true
    }

    private fun prepare() {
        if (current != null || preparing) return
        preparing = true
        onPrepare()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!active && current != null &&
            (preparedWidth != width || current?.contentHeight != getChildAt(0)?.height)
        ) {
            current = null
            other = null
        }
        updateEligibility()
    }

    internal fun updateEligibility() {
        if (active && !canFlip()) cancelInteraction()
        if (!active && !preparing && current == null && canFlip()) post {
            if (!active && canFlip()) prepare()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val seekBar = findViewById<SeekBar>(R.id.videoSeekBar)
        val onSeekBar = !active && event.actionMasked == MotionEvent.ACTION_DOWN && seekBar?.isShown == true &&
            run {
                seekBar.getDrawingRect(controlBounds)
                offsetDescendantRectToMyCoords(seekBar, controlBounds)
                controlBounds.contains(event.x.toInt(), event.y.toInt())
            }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && gestureEnabled &&
            canFlip() && !finishing && !onSeekBar
        ) {
            pendingTarget = null
            pointerId = event.getPointerId(0)
            downX = event.x
            downY = event.y
            downProgress = flipProgress
            if (active) {
                held = true
                settling = false
                landing = false
                turn.snap(flipProgress)
                occupancy.snap(height.toFloat())
                lift.target = 1f
                updateGrip()
                scheduleFrame()
            }
            direction = Direction.UNDECIDED
            tracker?.recycle()
            tracker = VelocityTracker.obtain()
            parent?.requestDisallowInterceptTouchEvent(true)
            prepare()
        }
        tracker?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && direction == Direction.UNDECIDED) {
            val index = event.findPointerIndex(pointerId)
            if (index >= 0) {
                val dx = event.getX(index) - downX
                val dy = event.getY(index) - downY
                if (abs(dx) > configuration.scaledTouchSlop && abs(dx) > abs(dy) * 1.2f) {
                    // A scrolling descendant owns vertical motion, but must not suppress a turn.
                    super.requestDisallowInterceptTouchEvent(false)
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && direction != Direction.NONE) {
            direction = Direction.CANCELLED
            if (active) settle(0f)
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && direction == Direction.HORIZONTAL) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            releaseTouch()
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (finishing || direction == Direction.CANCELLED) return true
        decideDirection(event)
        return direction == Direction.HORIZONTAL || active
    }

    private fun decideDirection(event: MotionEvent) {
        if (direction == Direction.UNDECIDED && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val index = event.findPointerIndex(pointerId)
            if (index < 0) return
            val dx = event.getX(index) - downX
            val dy = event.getY(index) - downY
            if (max(abs(dx), abs(dy)) > configuration.scaledTouchSlop) {
                if (abs(dx) > abs(dy) * 1.2f) {
                    direction = Direction.HORIZONTAL
                    held = true
                    beginFlip()
                    updateDrag(event, index)
                } else {
                    direction = Direction.VERTICAL
                    if (active) cancelInteraction()
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (finishing || direction == Direction.CANCELLED) return true
        decideDirection(event)
        if (direction == Direction.UNDECIDED && active) {
            if (event.actionMasked == MotionEvent.ACTION_UP) settle(target)
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) settle(0f)
            return true
        }
        if (direction != Direction.HORIZONTAL) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) updateDrag(event, index)
            }
            MotionEvent.ACTION_UP -> {
                tracker?.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
                val velocity = tracker?.getXVelocity(pointerId) ?: 0f
                val threshold = max(
                    configuration.scaledMinimumFlingVelocity * 4f,
                    600f * resources.displayMetrics.density
                )
                val destination = if (abs(velocity) >= threshold) {
                    if (flipProgress * velocity < 0f && abs(flipProgress) > 0.05f) 0f else sign(velocity)
                } else if (abs(flipProgress) >= 0.5f) sign(flipProgress) else 0f
                settle(destination, velocity / dragDistance())
            }
            MotionEvent.ACTION_CANCEL -> settle(0f)
        }
        return true
    }

    private fun dragDistance() = (width * 0.85f).coerceAtLeast(1f)

    private fun beginFlip() {
        if (active) return
        active = true
        occupancy.snap(height.toFloat())
        lift.target = 1f
        updateGrip()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        onFlipStarted()
        scheduleFrame()
    }

    private fun updateGrip() {
        gripX.target = if (pointerId >= 0) (downX / width * 2f - 1f).coerceIn(-1f, 1f) else .25f
        gripY.target = if (pointerId >= 0) (downY / height * 2f - 1f).coerceIn(-1f, 1f) else -.12f
    }

    private fun updateDrag(event: MotionEvent, index: Int) {
        val dx = event.getX(index) - downX
        val dy = event.getY(index) - downY
        turn.snap((downProgress + dx / dragDistance()).coerceIn(min(-1f, downProgress), max(1f, downProgress)))
        offsetX.target = (dx * .12f).coerceIn(-28f * density, 28f * density)
        offsetY.target = (dy * .12f).coerceIn(-14f * density, 14f * density)
        invalidate()
        scheduleFrame()
    }

    private fun settle(destination: Float, velocity: Float = 0f) {
        target = destination
        previousFrame = 0L
        held = false
        settling = true
        landing = false
        if (current == null || other == null) {
            pendingTarget = destination
            pendingVelocity = velocity
            return
        }
        turn.target = destination
        turn.velocity = velocity
        offsetX.target = 0f
        offsetY.target = 0f
        gripX.target = 0f
        gripY.target = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {
            turn.snap(destination)
            occupancy.snap(targetHeight())
            poseSprings.forEach { it.snap(0f) }
            finishFlip()
        } else {
            scheduleFrame()
        }
    }

    private fun targetHeight(): Float =
        (if (abs(target) > .5f) other else current)?.contentHeight?.toFloat() ?: height.toFloat()

    private fun scheduleFrame() {
        if (framePosted || !active || finishing) return
        framePosted = true
        choreographer.postFrameCallback(frame)
    }

    private fun advanceFrame(time: Long) {
        framePosted = false
        if (!active || finishing) return
        // Asset preparation or a stalled frame must not consume the whole visible turn.
        val seconds = if (previousFrame == 0L) 0f else
            ((time - previousFrame) / 1_000_000_000f).coerceIn(0f, 1f / 30f)
        previousFrame = time
        val oldHeight = occupancy.value.roundToInt()
        poseSprings.forEach { it.advance(seconds) }
        if (settling && !held && pendingTarget == null) {
            turn.advance(seconds)
            if (!landing && abs(turn.value - target) < .035f && abs(turn.velocity) < .65f) {
                landing = true
                occupancy.target = targetHeight()
            }
            if (landing) {
                occupancy.advance(seconds)
                if (abs(turn.value - target) < .012f && abs(turn.velocity) < .3f) lift.target = 0f
                if (turn.isAtRest && occupancy.isAtRest && poseSprings.all { it.isAtRest }) {
                    finishFlip()
                    return
                }
            }
        }
        if (occupancy.value.roundToInt() != oldHeight) requestLayout()
        invalidate()
        if (poseSprings.any { !it.isAtRest } || (settling && pendingTarget == null)) scheduleFrame()
        else previousFrame = 0L
    }

    private fun finishFlip() {
        stopFrames()
        finishing = true
        settling = false
        val token = generation
        onFaceSettled(abs(target) > .5f) {
            if (generation == token) {
                if (abs(target) > .5f) {
                    val previous = current
                    current = other
                    other = previous
                }
                active = false
                finishing = false
                clearMotion()
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
                onFlipFinished()
                requestLayout()
                invalidate()
            }
        }
    }

    private fun stopFrames() {
        choreographer.removeFrameCallback(frame)
        framePosted = false
        previousFrame = 0L
    }

    private fun clearMotion() {
        turn.snap(0f)
        poseSprings.forEach { it.snap(0f) }
        held = false
        settling = false
        landing = false
    }

    private fun releaseTouch() {
        tracker?.recycle()
        tracker = null
        pointerId = -1
        direction = Direction.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!active) return
        setMeasuredDimension(measuredWidth, resolveSize(occupancy.value.roundToInt(), heightMeasureSpec))
    }

    override fun dispatchDraw(canvas: Canvas) {
        val first = current
        val second = other
        if (!active || first == null || second == null) {
            super.dispatchDraw(canvas)
            return
        }
        val progress = abs(flipProgress)
        val face = if (progress < 0.5f) first else second
        val angle = cardFlipAngle(flipProgress) -
            if (progress >= 0.5f) sign(flipProgress) * 180f else 0f
        val pickup = lift.value.coerceIn(0f, 1.04f)
        val pivotX = width / 2f + gripX.value * width * .16f * pickup
        val pivotY = height / 2f + gripY.value * min(height * .2f, 48f * density) * pickup
        camera.save()
        camera.setLocation(0f, 0f, -18f * density)
        camera.rotateX(tiltX)
        camera.rotateY(angle)
        camera.rotateZ(-gripY.value * (gripX.value * 1.1f + flipProgress * .8f) * pickup)
        camera.getMatrix(projection)
        camera.restore()
        projection.preTranslate(-pivotX, -pivotY)
        projection.postTranslate(pivotX, pivotY)
        projection.postScale(1f + .025f * pickup, 1f + .025f * pickup, width / 2f, height / 2f)
        val lightingWeight = abs(sin(angle * Math.PI / 180)).toFloat()
        projection.postTranslate(offsetX.value * lightingWeight, offsetY.value - 10f * density * pickup)
        val checkpoint = canvas.save()
        canvas.concat(projection)
        val visibleHeight = min(face.contentHeight.toFloat(), height.toFloat())
        faceBounds.set(0f, (height - visibleHeight) / 2f, width.toFloat(), (height + visibleHeight) / 2f)
        destination.set(0f, faceBounds.top, width.toFloat(), faceBounds.top + face.contentHeight)
        shadowPaint.alpha = (pickup * 22f).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(faceBounds, face.cornerRadius, face.cornerRadius, shadowPaint)
        faceClip.rewind()
        faceClip.addRoundRect(faceBounds, face.cornerRadius, face.cornerRadius, Path.Direction.CW)
        canvas.clipPath(faceClip)
        bitmapPaint.colorFilter = lighting[(lightingWeight * lighting.lastIndex).roundToInt()]
        canvas.drawBitmap(face.bitmap, null, destination, bitmapPaint)
        edgePaint.alpha = (lightingWeight * 70f).roundToInt()
        canvas.drawRoundRect(faceBounds, face.cornerRadius, face.cornerRadius, edgePaint)
        canvas.restoreToCount(checkpoint)
    }

    override fun onDetachedFromWindow() {
        cancelInteraction()
        super.onDetachedFromWindow()
    }
}
