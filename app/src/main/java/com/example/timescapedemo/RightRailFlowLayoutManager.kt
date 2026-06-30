package com.example.timescapedemo

import android.animation.ValueAnimator
import android.graphics.PointF
import android.view.View
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Timescape-like vertical flow with variable-height cards:
 *  - Height grows with text (wrap-content), capped at 2/3 of the screen.
 *  - Width still follows center "gain" + focus animation.
 *  - Only near neighbors overlap slightly (itemPitchPx).
 *  - Cards remain STRAIGHT (no rotation).
 */
class RightRailFlowLayoutManager(
    private val baseSidePx: Int,          // width near center (non-focused)
    private val focusSidePx: Int,         // width when focused (main)
    private val itemPitchPx: Int,         // visual spacing (center-to-center)
    private val rightInsetPx: Int,
    private val topInsetPx: Int = 0,
    private val bottomInsetPx: Int = 0,

    // Visual tuning
    private val minEdgeScale: Float = 0.72f,
    private val edgeAlphaMin: Float = 0.30f,
    private val depthScaleDrop: Float = 0.05f,

    // Horizontal rail shaping (controls inflow/outflow direction)
    private val edgeRightShiftPx: Int = 48,     // far cards to the RIGHT by this many px
    private val centerLeftShiftPx: Int = 0,     // center card stays centered on the rail
    private val railCurvePow: Float = 1.1f,     // curvature; 1 = linear, >1 stronger S-curve

    // S-curve styling
    private val curveRotationRadiusItems: Float = 4.5f,
    private val curveRotationPow: Float = 1.12f,
    private val curveMaxRotationDeg: Float = 9f,
    private val curveExtraRightShiftPx: Int = 32
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    // focus animation state
    private var selectedIndex: Int? = null
    private var focusProgress: Float = 0f
    private var focusAnimator: ValueAnimator? = null
    private val interp = FastOutSlowInInterpolator()

    init {
        setItemPrefetchEnabled(true)
    }

    // scroll state
    private var scrollYPx = 0f
    private var pendingRestore: PendingRestore? = null
    private var settleAnimator: ValueAnimator? = null

    var selectionListener: ((Int?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentSelectionIndex())
        }

    // Precomputed helpers to reduce per-frame work
    private val gainLookup = GainLookup(itemPitchPx * 0.95f)
    private val widthQuantizer = IntQuantizer()
    private val heightQuantizer = IntQuantizer(stepPx = 12)
    private val scatterCalculator = ScatterCalculator()
    private val layoutOverscan = 2

    private data class CurvePlacement(
        val extraX: Float,
        val rotationDeg: Float
    )

    private data class ScatterOffsets(
        val shiftX: Float,
        val shiftY: Float,
        val rotationDeg: Float
    )

    private data class PendingRestore(
        val index: Int,
        val focus: Boolean
    )

    override fun canScrollVertically() = true
    override fun canScrollHorizontally() = false
    override fun isAutoMeasureEnabled() = true
    override fun generateDefaultLayoutParams() =
        RecyclerView.LayoutParams(baseSidePx, RecyclerView.LayoutParams.WRAP_CONTENT)

    // ---- Public helpers ----
    fun isFocused(index: Int) = selectedIndex == index && focusProgress >= 0.999f

    /** Center index given current scroll (rounded to nearest). */
    fun nearestIndex(): Int {
        val idx = ((scrollYPx + screenCenter() - yTop()) / itemPitchPx).roundToInt()
        return idx.coerceIn(0, max(0, itemCount - 1))
    }

    /** Pixel delta required to move `index` to vertical screen center. */
    fun offsetTo(index: Int): Int {
        val desiredScroll = yTop() + index * itemPitchPx - screenCenter()
        return (desiredScroll - scrollYPx).toInt()
    }

    fun currentSelectionIndex(): Int? {
        val sel = selectedIndex ?: return null
        if (itemCount == 0) return null
        return sel.coerceIn(0, itemCount - 1)
    }

    fun hasSelection(): Boolean = selectedIndex != null

    fun focus(index: Int) {
        if (itemCount == 0) return
        val clamped = index.coerceIn(0, max(0, itemCount - 1))
        if (selectedIndex == clamped && focusProgress >= 0.999f) return
        val changed = selectedIndex != clamped
        selectedIndex = clamped
        if (changed) notifySelectionChanged()
        animateFocus(1f)
    }
    fun clearFocus(immediate: Boolean = false) {
        if (selectedIndex == null && focusProgress == 0f) return
        if (immediate) {
            val hadSelection = selectedIndex != null
            focusAnimator?.cancel()
            focusAnimator = null
            focusProgress = 0f
            selectedIndex = null
            requestLayout()
            if (hadSelection) notifySelectionChanged()
        } else {
            animateFocus(0f) {
                val hadSelection = selectedIndex != null
                selectedIndex = null
                if (hadSelection) notifySelectionChanged()
            }
        }
    }

    private fun animateFocus(to: Float, end: (() -> Unit)? = null) {
        focusAnimator?.cancel()
        val from = focusProgress
        focusAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 280 else 220
            interpolator = interp
            addUpdateListener { focusProgress = it.animatedValue as Float; requestLayout() }
            if (end != null) {
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (focusProgress <= 0.001f) end()
                    }
                })
            }
            start()
        }
    }

    // ---- Rail geometry ----
    private fun screenCenter() = height / 2f
    private fun yTop() = paddingTop + topInsetPx + baseSidePx / 2f
    private fun yBottom() = height - paddingBottom - bottomInsetPx - baseSidePx / 2f
    private fun railX() = width - paddingRight - rightInsetPx - baseSidePx / 2f

    /** Allow first/last items to be centered without dead space. */
    private fun minScroll() = yTop() - screenCenter()
    private fun maxScroll(): Float {
        val lastCentered = yTop() + (itemCount - 1).coerceAtLeast(0) * itemPitchPx - screenCenter()
        return max(minScroll(), lastCentered)
    }

    private val baseOverScrollLimitPx = max(48f, itemPitchPx * 1.1f)

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0 || width == 0 || height == 0) return
        pendingRestore?.let {
            if (applyRestore(it.index, it.focus)) pendingRestore = null
        }
        layoutAll(recycler)
    }

    private fun measureCardWithWidthAndCap(
        child: View,
        widthPx: Int,
        maxHeightPx: Int,
        cache: ChildCache,
        forceMeasure: Boolean
    ) {
        val quantizedHeightCap = heightQuantizer.snap(maxHeightPx)
        val lp = child.layoutParams as RecyclerView.LayoutParams
        if (!forceMeasure && !child.isLayoutRequested &&
            cache.lastQuantizedSide == widthPx && cache.lastMaxHeight == quantizedHeightCap
        ) {
            cache.lastMeasuredWidth = widthPx
            cache.lastQuantizedSide = widthPx
            return
        }

        var needsMeasure = forceMeasure || child.isLayoutRequested
        if (lp.width != widthPx || lp.height != RecyclerView.LayoutParams.WRAP_CONTENT) {
            lp.width = widthPx
            lp.height = RecyclerView.LayoutParams.WRAP_CONTENT
            child.layoutParams = lp
            needsMeasure = true
        }

        if (cache.lastMaxHeight != quantizedHeightCap) {
            cache.container?.setMaxHeightPx(quantizedHeightCap)
            cache.lastMaxHeight = quantizedHeightCap
            needsMeasure = true
        }

        if (needsMeasure || cache.lastMeasuredWidth != widthPx) {
            measureChildWithMargins(child, 0, 0)
            cache.lastMeasuredWidth = widthPx
        }
        cache.lastQuantizedSide = widthPx
    }

    private fun applyTextByGain(
        cache: ChildCache,
        gain: Float,
        focused: Boolean
    ): Boolean {
        val snippet = cache.snippet ?: return false
        val targetBand = gainBandFor(gain, focused)
        if (cache.lastGainBand == targetBand) return false

        val desiredLines = snippetLinesFor(targetBand)
        if (snippet.maxLines != desiredLines) {
            snippet.maxLines = desiredLines
        }
        cache.lastSnippetMaxLines = desiredLines
        cache.lastGainBand = targetBand
        return true
    }

    private fun gainBandFor(gain: Float, focused: Boolean): GainBand {
        if (focused) return GainBand.FOCUSED
        return when {
            gain >= 0.78f -> GainBand.PRIMARY
            gain >= 0.55f -> GainBand.SECONDARY
            else -> GainBand.TERTIARY
        }
    }

    private fun snippetLinesFor(band: GainBand): Int = when (band) {
        GainBand.FOCUSED -> Integer.MAX_VALUE
        GainBand.PRIMARY -> 8
        GainBand.SECONDARY -> 5
        GainBand.TERTIARY -> 3
    }

    private fun ensureCache(child: View): ChildCache {
        val existing = child.getTag(R.id.tag_card_cache) as? ChildCache
        if (existing != null) return existing
        val cache = ChildCache(
            container = child.findViewById(R.id.card_content),
            snippet = child.findViewById(R.id.snippet)
        )
        child.setTag(R.id.tag_card_cache, cache)
        return cache
    }

    private data class ChildCache(
        val container: MaxHeightLinearLayout?,
        val snippet: TextView?,
        var lastMaxHeight: Int = -1,
        var lastMeasuredWidth: Int = -1,
        var lastSnippetMaxLines: Int = -1,
        var lastQuantizedSide: Int = -1,
        var lastGainBand: GainBand? = null
    )

    private fun layoutWindow(scroll: Float): IntRange {
        val yT = yTop(); val yB = yBottom()
        val firstIdx = max(
            0,
            floor(((scroll + (yT - itemPitchPx)) - yT) / itemPitchPx).toInt() - layoutOverscan
        )
        val lastIdx = min(
            itemCount - 1,
            ceil(((scroll + (yB - yT) + itemPitchPx) - yT) / itemPitchPx).toInt() + layoutOverscan
        )
        return firstIdx..lastIdx
    }

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cy = screenCenter()
        val baseX = railX()
        val yT = yTop(); val yB = yBottom()

        val layoutWindow = layoutWindow(scrollYPx)
        val firstIdx = layoutWindow.first
        val lastIdx = layoutWindow.last

        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            val pos = getPosition(child)
            if (pos !in firstIdx..lastIdx) {
                removeAndRecycleView(child, recycler)
            }
        }

        // Size growth radius (how quickly a card "blooms" near center)
        val heightCap = (height * 2f / 3f).roundToInt()

        val nearest = nearestIndex()
        val nearestY = yT + nearest * itemPitchPx - scrollYPx

        for (i in firstIdx..lastIdx) {
            val child = findViewByPosition(i) ?: recycler.getViewForPosition(i).also { addView(it) }
            val cache = ensureCache(child)

            // Y position of item center for index i
            val py = yT + i * itemPitchPx - scrollYPx
            val dist = abs(py - cy)

            // Gain ~ 1 at center, ~ 0 at edges
            val gain = gainLookup.gainFor(dist)

            // ---- X rail shaping: edges right, center left (S-curve) ----
            val toRight = (1f - gain).pow(railCurvePow) * edgeRightShiftPx
            val toLeft  = gain * centerLeftShiftPx
            val curve = computeCurvePlacement(py, cy)
            val px = baseX + toRight - toLeft + curve.extraX

            val isSelected = (selectedIndex == i)

            // Width: far small -> base -> (if selected) focused
            val edgeSide = (baseSidePx * minEdgeScale).roundToInt()
            val easedGain = interp.getInterpolation(gain)
            var side = (edgeSide + (baseSidePx - edgeSide) * easedGain).roundToInt()
            if (isSelected) side = (side + (focusSidePx - side) * focusProgress).roundToInt()
            side = widthQuantizer.snap(side)

            // Text size influences measurement, so adjust before measuring
            val textChanged = applyTextByGain(cache, gain, isSelected && focusProgress > 0.5f)

            // Measure: fixed width, wrap-content height, capped to 2/3 screen
            measureCardWithWidthAndCap(child, side, heightCap, cache, textChanged)

            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val l = (px - w / 2f).toInt()
            val t = (py - h / 2f).toInt()
            layoutDecoratedWithMargins(child, l, t, l + w, t + h)

            // Alpha / depth (z-order so nearer items render above)
            child.alpha = 0.92f + 0.08f * gain
            val dIdx = abs(nearestY - py) / itemPitchPx
            val depthS = max(0.94f, 1f - depthScaleDrop * dIdx)
            child.scaleX = depthS
            child.scaleY = depthS
            val scatter = scatterCalculator.compute(
                index = i,
                gain = gain,
                normalizedScroll = scrollYPx / itemPitchPx,
                isSelected = isSelected,
                focusProgress = focusProgress
            )
            child.translationX = scatter.shiftX
            child.translationY = scatter.shiftY
            child.rotation = curve.rotationDeg + scatter.rotationDeg
            val z = if (isSelected) 6000f else (1000f - dist / 10f)
            child.translationZ = z; child.elevation = z
        }
    }

    override fun collectAdjacentPrefetchPositions(
        dx: Int,
        dy: Int,
        state: RecyclerView.State,
        layoutPrefetchRegistry: RecyclerView.LayoutManager.LayoutPrefetchRegistry
    ) {
        if (!isItemPrefetchEnabled || itemCount == 0 || height == 0 || dy == 0 || state.isPreLayout) return

        val direction = when {
            dy > 0 -> 1
            dy < 0 -> -1
            else -> 0
        }
        if (direction == 0) return

        val currentWindow = layoutWindow(scrollYPx)
        val targetWindow = layoutWindow(applyOverScrollResistance(scrollYPx + dy))

        if (direction > 0) {
            val start = (currentWindow.last + 1).coerceAtLeast(0)
            val end = min(targetWindow.last, itemCount - 1)
            if (start <= end) {
                for (i in start..end) {
                    val centerY = yTop() + i * itemPitchPx - scrollYPx
                    val distance = max(0f, centerY - yBottom()).toInt()
                    layoutPrefetchRegistry.addPosition(i, distance)
                }
            }
        } else {
            val start = targetWindow.first.coerceAtLeast(0)
            val end = (currentWindow.first - 1).coerceAtMost(itemCount - 1)
            if (start <= end) {
                for (i in end downTo start) {
                    val centerY = yTop() + i * itemPitchPx - scrollYPx
                    val distance = max(0f, yTop() - centerY).toInt()
                    layoutPrefetchRegistry.addPosition(i, distance)
                }
            }
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (itemCount == 0 || height == 0) return 0
        if (dy != 0 && selectedIndex != null && focusProgress > 0f) {
            clearFocus(immediate = true)
        }
        if (dy != 0) cancelSettleAnimation()

        val old = scrollYPx
        val newY = applyOverScrollResistance(old + dy)
        val consumed = (newY - old).toInt()
        scrollYPx = newY

        layoutAll(recycler)
        return consumed
    }

    fun settleScrollIfNeeded(onSettled: (() -> Unit)? = null) {
        if (itemCount == 0 || height == 0) {
            onSettled?.invoke()
            return
        }
        val min = minScroll()
        val max = maxScroll()
        val target = scrollYPx.coerceIn(min, max)
        if (abs(target - scrollYPx) < 0.5f) {
            scrollYPx = target
            onSettled?.invoke()
            return
        }
        cancelSettleAnimation()
        settleAnimator = ValueAnimator.ofFloat(scrollYPx, target).apply {
            duration = 260
            interpolator = interp
            addUpdateListener {
                scrollYPx = it.animatedValue as Float
                requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (settleAnimator === animation) {
                        settleAnimator = null
                        onSettled?.invoke()
                    }
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (settleAnimator === animation) settleAnimator = null
                }
            })
            start()
        }
    }

    fun restoreState(index: Int, focus: Boolean) {
        if (applyRestore(index, focus)) {
            pendingRestore = null
            requestLayout()
        } else {
            pendingRestore = PendingRestore(index, focus)
            requestLayout()
        }
    }

    private fun applyRestore(index: Int, focus: Boolean): Boolean {
        if (itemCount == 0 || width == 0 || height == 0) return false
        val clamped = index.coerceIn(0, max(0, itemCount - 1))
        val desired = yTop() + clamped * itemPitchPx - screenCenter()
        cancelSettleAnimation()
        scrollYPx = desired.coerceIn(minScroll(), maxScroll())
        focusAnimator?.cancel()
        if (focus) {
            val changed = selectedIndex != clamped
            selectedIndex = clamped
            focusProgress = 1f
            if (changed) notifySelectionChanged()
        } else {
            val hadSelection = selectedIndex != null
            selectedIndex = null
            focusProgress = 0f
            if (hadSelection) notifySelectionChanged()
        }
        return true
    }

    private fun notifySelectionChanged() {
        selectionListener?.invoke(currentSelectionIndex())
    }

    private fun applyOverScrollResistance(candidate: Float): Float {
        if (itemCount == 0) return candidate
        val minBound = minScroll()
        val maxBound = maxScroll()
        val allowance = maxOverScrollPx()
        if (allowance <= 0f) return candidate.coerceIn(minBound, maxBound)

        val minLimit = minBound - allowance
        val maxLimit = maxBound + allowance
        return candidate.coerceIn(minLimit, maxLimit)
    }

    private fun maxOverScrollPx(): Float {
        val screenAllowance = if (height > 0) height * 0.9f else 0f
        val pitchAllowance = itemPitchPx * 3f
        return max(baseOverScrollLimitPx, max(screenAllowance, pitchAllowance))
    }

    private fun cancelSettleAnimation() {
        settleAnimator?.cancel()
        settleAnimator = null
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val desired = yTop() + targetPosition * itemPitchPx - screenCenter()
        val dir = if (desired > scrollYPx) 1f else -1f
        return PointF(0f, dir)
    }

    private fun computeCurvePlacement(py: Float, centerY: Float): CurvePlacement {
        if (itemPitchPx == 0) return CurvePlacement(0f, 0f)

        val signedStepsFromCenter = (py - centerY) / itemPitchPx
        val direction = if (signedStepsFromCenter >= 0f) 1f else -1f
        val magnitude = abs(signedStepsFromCenter) / curveRotationRadiusItems
        val eased = magnitude.coerceAtMost(1f).pow(curveRotationPow)
        val rotation = -direction * eased * curveMaxRotationDeg
        val extraRight = eased * curveExtraRightShiftPx

        return CurvePlacement(extraRight, rotation)
    }

    private class GainLookup(private val focusRadiusPx: Float) {
        private val invTwoSigmaSq = if (focusRadiusPx == 0f) 0f else 1f / (2f * focusRadiusPx * focusRadiusPx)
        private val stepPx = max(1f, focusRadiusPx / 80f)
        private val maxDistance = focusRadiusPx * 6f
        private val table: FloatArray

        init {
            val size = (maxDistance / stepPx).roundToInt().coerceAtLeast(1)
            table = FloatArray(size + 2)
            for (i in table.indices) {
                val dist = i * stepPx
                val value = if (focusRadiusPx == 0f) 1f else kotlin.math.exp(-(dist * dist) * invTwoSigmaSq)
                table[i] = value
            }
        }

        fun gainFor(distance: Float): Float {
            if (focusRadiusPx == 0f) return 1f
            val clamped = distance.coerceIn(0f, maxDistance)
            val pos = clamped / stepPx
            val idx = pos.toInt()
            val frac = pos - idx
            val a = table.getOrElse(idx) { 0f }
            val b = table.getOrElse(idx + 1) { 0f }
            return a + (b - a) * frac
        }
    }

    private class IntQuantizer(private val stepPx: Int = 4) {
        fun snap(value: Int): Int {
            if (stepPx <= 1) return value
            val snapped = ((value + stepPx / 2) / stepPx) * stepPx
            return snapped.coerceAtLeast(0)
        }
    }

    private enum class GainBand {
        FOCUSED,
        PRIMARY,
        SECONDARY,
        TERTIARY
    }

    private class ScatterCalculator {
        fun compute(
            index: Int,
            gain: Float,
            normalizedScroll: Float,
            isSelected: Boolean,
            focusProgress: Float
        ): ScatterOffsets {
            val basePhase = index * 0.83f + normalizedScroll * 0.9f
            val secondaryPhase = index * 1.27f - normalizedScroll * 0.45f
            val tertiaryPhase = (index + normalizedScroll) * 0.55f

            val waveA = FastTrig.sin(basePhase)
            val waveB = FastTrig.cos(secondaryPhase)
            val waveC = FastTrig.sin(tertiaryPhase + waveB * 0.35f)

            val scatterStrength = (0.12f + (1f - gain) * 0.88f).coerceIn(0f, 1f)
            val focusDamp = if (isSelected) (1f - focusProgress).coerceAtLeast(0f) else 1f
            val amount = scatterStrength * focusDamp

            val shiftX = (waveA * 14f + waveB * 8f) * amount
            val shiftY = (waveB * 6f + waveC * 10f) * amount
            val rotation = (waveA * 2.6f + waveC * 1.1f) * amount

            return ScatterOffsets(shiftX, shiftY, rotation)
        }
    }
    private object FastTrig {
        private const val TABLE_SIZE = 2048
        private val TWO_PI = (Math.PI * 2.0).toFloat()
        private val HALF_PI = (Math.PI / 2.0).toFloat()
        private val step = TWO_PI / TABLE_SIZE
        private val table = FloatArray(TABLE_SIZE + 1).apply {
            for (i in indices) {
                this[i] = kotlin.math.sin(i * step)
            }
        }

        fun sin(angle: Float): Float = lookup(angle)

        fun cos(angle: Float): Float = lookup(angle + HALF_PI)

        private fun lookup(angle: Float): Float {
            if (angle.isNaN() || angle.isInfinite()) return 0f
            var wrapped = angle % TWO_PI
            if (wrapped < 0f) wrapped += TWO_PI
            val position = wrapped / step
            val index = position.toInt().coerceIn(0, TABLE_SIZE - 1)
            val frac = position - index
            val a = table[index]
            val b = table[index + 1]
            return a + (b - a) * frac
        }
    }
}
