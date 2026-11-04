package com.example.timescapedemo

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlin.math.min
import kotlin.math.roundToInt

class FlowDockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MaterialCardView(context, attrs) {
    private val density = resources.displayMetrics.density
    private lateinit var row: ViewGroup
    private lateinit var chips: FlowDockChipGroup
    private lateinit var hint: TextView
    private var emphasized: Boolean? = null
    private val glassPaint = Paint()
    private val rimPaint = Paint()

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0) return
        val fade = min(56 * density / w, .32f)
        val stops = floatArrayOf(0f, fade, 1f - fade, 1f)
        fun gradient(color: Int) = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(ColorUtils.setAlphaComponent(color, 0), color, color,
                ColorUtils.setAlphaComponent(color, 0)),
            stops, Shader.TileMode.CLAMP
        )
        glassPaint.shader = gradient(ContextCompat.getColor(context, R.color.flow_dock_surface))
        rimPaint.shader = gradient(ContextCompat.getColor(context, R.color.flow_dock_stroke))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 4 * density
        val rim = .6f * density
        canvas.drawRect(0f, inset, width.toFloat(), height - inset, glassPaint)
        canvas.drawRect(0f, inset, width.toFloat(), inset + rim, rimPaint)
        canvas.drawRect(0f, height - inset - rim, width.toFloat(), height - inset, rimPaint)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        row = findViewById(R.id.flowDockRow)
        chips = findViewById(R.id.flowChips)
        hint = findViewById(R.id.flowDragHint)
        clipChildren = false
        clipToPadding = false
        clipToOutline = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else MeasureSpec.getSize(widthMeasureSpec)
        val limit = min(available, (600 * density).roundToInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST), heightMeasureSpec)
        val scroll = findViewById<View>(R.id.flowChipScroll)
        val chrome = row.measuredWidth - scroll.measuredWidth + paddingLeft + paddingRight
        val width = min(limit, chips.measuredWidth + chrome)
        // The floating hint must never change the dock's height or drag coordinates.
        val height = resolveSize(row.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        hint.translationY = (-hint.height - 22 * density).roundToInt().toFloat()
    }

    fun setEmphasized(value: Boolean) {
        if (emphasized == value) return
        emphasized = value
        val target = if (value) 1f else .86f
        animate().cancel()
        if (isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            animate().alpha(target).setDuration(180).start()
        } else alpha = target
    }

    override fun onDetachedFromWindow() {
        animate().cancel()
        super.onDetachedFromWindow()
    }
}

class FlowDockScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    init {
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength((18 * resources.displayMetrics.density).roundToInt())
    }

    override fun getLeftFadingEdgeStrength(): Float = 1f
    override fun getRightFadingEdgeStrength(): Float = 1f
    override fun getSolidColor(): Int = Color.TRANSPARENT
}

class FlowDockChipGroup @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ChipGroup(context, attrs) {
    private val density = resources.displayMetrics.density
    private val chipContext = ContextThemeWrapper(context, R.style.ThemeOverlay_TimescapeDemo_FlowDock)
    internal val accentColor = ContextCompat.getColor(context, R.color.flow_dock_accent)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.flow_dock_selection)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val selectedRect = RectF()
    private val targetRect = RectF()
    private val ringPath = Path()
    private val progressPath = Path()
    private val pathMeasure = PathMeasure()
    private var selectionAnimator: ValueAnimator? = null
    private var mergeTarget: Chip? = null
    private var insertion: Int? = null
    internal var mergeProgress = 0f
        private set
    internal val selectionBounds: RectF get() = RectF(selectedRect)

    init {
        clipChildren = false
        clipToPadding = false
        setChipSpacingHorizontal((4 * density).roundToInt())
        setOnCheckedStateChangeListener { _, _ -> updateSelection(true) }
    }

    fun createChip(flowId: Long, title: CharSequence, selected: Boolean): Chip =
        Chip(chipContext).apply {
            tag = flowId
            text = title
            contentDescription = title
            isCheckable = true
            isCheckedIconVisible = false
            setEnsureMinTouchTargetSize(false)
            minHeight = (48 * density).roundToInt()
            chipMinHeight = 48 * density
            maxWidth = (160 * density).roundToInt()
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            chipStartPadding = 14 * density
            chipEndPadding = 14 * density
            textStartPadding = 0f
            textEndPadding = 0f
            chipStrokeWidth = 0f
            chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
            rippleColor = ColorStateList.valueOf(Color.argb(32, 220, 242, 255))
            setShadowLayer(.8f * density, 0f, density, 0x9905152D.toInt())
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(
                    ContextCompat.getColor(context, R.color.flow_dock_text_selected),
                    ContextCompat.getColor(context, R.color.flow_dock_text)
                )
            ))
            isChecked = selected
        }

    private fun selectedChip(): Chip? =
        (0 until childCount).mapNotNull { getChildAt(it) as? Chip }.firstOrNull { it.isChecked }

    private fun updateSelection(animate: Boolean) {
        val chip = selectedChip()
        if (chip == null || chip.width == 0) {
            selectionAnimator?.cancel()
            selectedRect.setEmpty()
            invalidate()
            return
        }
        val destination = RectF(chip.left.toFloat(), chip.top + 6 * density,
            chip.right.toFloat(), chip.bottom - 6 * density)
        if (destination == selectedRect) return
        selectionAnimator?.cancel()
        if (!animate || selectedRect.isEmpty || !ValueAnimator.areAnimatorsEnabled()) {
            selectedRect.set(destination)
            invalidate()
            return
        }
        val initial = RectF(selectedRect)
        selectionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = PathInterpolator(.2f, .8f, .2f, 1f)
            addUpdateListener {
                val t = it.animatedValue as Float
                selectedRect.set(
                    initial.left + (destination.left - initial.left) * t,
                    initial.top + (destination.top - initial.top) * t,
                    initial.right + (destination.right - initial.right) * t,
                    initial.bottom + (destination.bottom - initial.bottom) * t
                )
                invalidate()
            }
            start()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateSelection(false)
    }

    internal fun showInsertion(index: Int?) {
        if (insertion == index) return
        insertion = index
        val direction = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        for (i in 0 until childCount) {
            val offset = when {
                index == null -> 0f
                i == index - 1 -> -5 * density * direction
                i == index -> 5 * density * direction
                else -> 0f
            }
            getChildAt(i).animate().translationX(offset).setDuration(140).start()
        }
    }

    internal fun showMerge(chip: Chip?, progress: Float = 0f) {
        mergeTarget = chip
        mergeProgress = if (chip == null) 0f else progress.coerceIn(0f, 1f)
        invalidate()
    }

    internal fun clearDragFeedback() {
        insertion = null
        showMerge(null)
        for (i in 0 until childCount) {
            getChildAt(i).apply {
                animate().cancel()
                translationX = 0f
            }
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val selectedOffset = selectedChip()?.translationX ?: 0f
        canvas.save()
        canvas.translate(selectedOffset, 0f)
        canvas.drawRoundRect(selectedRect, 18 * density, 18 * density, selectedPaint)
        canvas.restore()
        mergeTarget?.let { chip ->
            targetRect.set(chip.left + chip.translationX - 2 * density, chip.top + 4 * density,
                chip.right + chip.translationX + 2 * density, chip.bottom - 4 * density)
            targetPaint.alpha = if (mergeProgress >= 1f) 36 else 20
            canvas.drawRoundRect(targetRect, 22 * density, 22 * density, targetPaint)
        }
        super.dispatchDraw(canvas)
        if (mergeTarget != null) {
            ringPath.reset()
            ringPath.addRoundRect(targetRect, 22 * density, 22 * density, Path.Direction.CW)
            ringPaint.alpha = 38
            canvas.drawPath(ringPath, ringPaint)
            pathMeasure.setPath(ringPath, false)
            progressPath.reset()
            pathMeasure.getSegment(0f, pathMeasure.length * mergeProgress, progressPath, true)
            ringPaint.alpha = 255
            canvas.drawPath(progressPath, ringPaint)
        }
    }

    override fun onDetachedFromWindow() {
        selectionAnimator?.cancel()
        clearDragFeedback()
        super.onDetachedFromWindow()
    }
}

internal class FlowDockDragShadow(private val chip: Chip) : View.DragShadowBuilder(chip) {
    private val density = chip.resources.displayMetrics.density
    private val padding = 8 * density
    private val lift = 28 * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(
            ContextCompat.getColor(chip.context, R.color.flow_dock_surface), 200
        )
        setShadowLayer(6 * density, 0f, 3 * density, Color.argb(64, 0, 13, 35))
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(chip.context, R.color.flow_dock_stroke)
        style = Paint.Style.STROKE
        strokeWidth = .7f * density
    }
    private val bounds = RectF(0f, 6 * density, chip.width.toFloat(), chip.height - 6 * density)

    override fun onProvideShadowMetrics(size: Point, touch: Point) {
        size.set((chip.width + padding * 2).roundToInt(), (chip.height + padding * 2 + lift).roundToInt())
        touch.set((chip.width / 2f + padding).roundToInt(), (chip.height / 2f + padding + lift).roundToInt())
    }

    override fun onDrawShadow(canvas: Canvas) {
        canvas.save()
        canvas.translate(padding, padding)
        canvas.drawRoundRect(bounds, 18 * density, 18 * density, paint)
        canvas.drawRoundRect(bounds, 18 * density, 18 * density, rimPaint)
        chip.draw(canvas)
        canvas.restore()
    }
}
