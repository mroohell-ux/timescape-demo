package com.example.timescapedemo

import android.animation.ValueAnimator
import android.graphics.PointF
import android.view.View
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Timescape-like floating stack with variable-height cards:
 *  - Height grows with text (wrap-content), capped at 2/3 of the screen.
 *  - Cards follow a diagonal rail that leans toward the lower-right of the screen.
 *  - Farther cards shrink, fade, and shift backward to create a depthy glass-stack look.
 *  - Near cards bloom and sit above neighbors; focus animation still enlarges the active card.
 */
class RightRailFlowLayoutManager(
    private val baseSidePx: Int,          // width near center (non-focused)
    private val focusSidePx: Int,         // width when focused (main)
    private val itemPitchPx: Int,         // visual spacing (center-to-center)
    private val rightInsetPx: Int,
    private val topInsetPx: Int = 0,
    private val bottomInsetPx: Int = 0,

    // Visual tuning
    private val minEdgeScale: Float = 0.78f,
    private val edgeAlphaMin: Float = 0.45f,
    private val depthScaleDrop: Float = 0.18f
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    // focus animation state
    private var selectedIndex: Int? = null
    private var focusProgress: Float = 0f
    private var focusAnimator: ValueAnimator? = null
    private val interp = FastOutSlowInInterpolator()

    // scroll state
    private var scrollYPx = 0f
    private var pendingRestore: PendingRestore? = null

    var selectionListener: ((Int?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentSelectionIndex())
        }

    // Precomputed helpers to reduce per-frame work
    private val gainLookup = GainLookup(itemPitchPx * 0.95f)
    private val widthQuantizer = WidthQuantizer()
    private val scatterCalculator = ScatterCalculator()
    private var resolvedCameraDistance = 0f

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
        val lp = child.layoutParams as RecyclerView.LayoutParams
        if (!forceMeasure && !child.isLayoutRequested &&
            cache.lastQuantizedSide == widthPx && cache.lastMaxHeight == maxHeightPx
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

        if (cache.lastMaxHeight != maxHeightPx) {
            cache.container?.setMaxHeightPx(maxHeightPx)
            cache.lastMaxHeight = maxHeightPx
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
        @Suppress("UNUSED_PARAMETER") gain: Float,
        @Suppress("UNUSED_PARAMETER") focused: Boolean
    ): Boolean {
        var textChanged = false
        val snippet = cache.snippet

        if (snippet != null && cache.lastSnippetMaxLines != Integer.MAX_VALUE) {
            snippet.maxLines = Integer.MAX_VALUE
            cache.lastSnippetMaxLines = Integer.MAX_VALUE
            textChanged = true
        }

        return textChanged
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
        var lastQuantizedSide: Int = -1
    )

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cy = screenCenter()
        val rail = railX()
        val yT = yTop(); val yB = yBottom()

        // Visible window of indices
        val layoutOverscan = 2
        val firstIdx = max(
            0,
            floor(((scrollYPx + (yT - itemPitchPx)) - yT) / itemPitchPx).toInt() - layoutOverscan
        )
        val lastIdx = min(
            itemCount - 1,
            ceil(((scrollYPx + (yB - yT) + itemPitchPx) - yT) / itemPitchPx).toInt() + layoutOverscan
        )

        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            val pos = getPosition(child)
            if (pos !in firstIdx..lastIdx) {
                removeAndRecycleView(child, recycler)
            }
        }

        // Size growth radius (how quickly a card "blooms" near center)
        val heightCap = (height * 2f / 3f).roundToInt()

        for (i in firstIdx..lastIdx) {
            val child = findViewByPosition(i) ?: recycler.getViewForPosition(i).also { addView(it) }
            val cache = ensureCache(child)

            // Y position of item center for index i
            val py = yT + i * itemPitchPx - scrollYPx
            val dist = abs(py - cy)

            // Gain ~ 1 at center, ~ 0 at edges
            val gain = gainLookup.gainFor(dist)

            val progress = stackProgress(py)
            val closeness = 1f - progress

            val stackAnchorX = (rail - baseSidePx * STACK_BASE_OFFSET_FRACTION)
            val diagonalShift = baseSidePx * STACK_DIAGONAL_RANGE_FRACTION * progress
            val pxBase = stackAnchorX + diagonalShift

            val isSelected = (selectedIndex == i)

            val clampedMinWidth = minEdgeScale.coerceIn(0.2f, 0.98f)
            var sideFraction = clampedMinWidth + (1f - clampedMinWidth) * closeness
            if (isSelected) {
                sideFraction = (sideFraction + (1f - sideFraction) * focusProgress).coerceIn(clampedMinWidth, 1f)
            }
            var side = (baseSidePx * sideFraction).roundToInt()
            if (isSelected) side = (side + (focusSidePx - side) * focusProgress).roundToInt()
            side = widthQuantizer.snap(side)

            // Text size influences measurement, so adjust before measuring
            val textChanged = applyTextByGain(cache, gain, isSelected && focusProgress > 0.5f)

            // Measure: fixed width, wrap-content height, capped to 2/3 screen
            measureCardWithWidthAndCap(child, side, heightCap, cache, textChanged)

            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val px = pxBase.coerceIn(
                paddingLeft + w / 2f,
                width - paddingRight - w / 2f
            )
            val l = (px - w / 2f).toInt()
            val t = (py - h / 2f).toInt()
            layoutDecoratedWithMargins(child, l, t, l + w, t + h)

            if (resolvedCameraDistance == 0f && child.resources != null) {
                val density = child.resources.displayMetrics.density
                resolvedCameraDistance = CAMERA_DISTANCE_DP * density
            }
            if (resolvedCameraDistance > 0f) child.cameraDistance = resolvedCameraDistance

            val clampedAlphaMin = edgeAlphaMin.coerceIn(0f, 1f)
            val alpha = if (isSelected) 1f else clampedAlphaMin + (1f - clampedAlphaMin) * closeness

            val drop = depthScaleDrop.coerceIn(0f, 0.8f)
            val minScale = max(0.1f, 1f - drop)
            val maxScale = 1f + drop
            val scale = if (isSelected) maxScale + focusProgress * 0.08f
            else minScale + (maxScale - minScale) * closeness

            val baseRotationZ = STACK_ROTATION_Z_FAR + (STACK_ROTATION_Z_NEAR - STACK_ROTATION_Z_FAR) * closeness
            val rotationY = STACK_ROTATION_Y_FAR + (STACK_ROTATION_Y_NEAR - STACK_ROTATION_Y_FAR) * closeness

            val zBase = STACK_FAR_Z + closeness * (STACK_NEAR_Z - STACK_FAR_Z)
            val z = if (isSelected) STACK_NEAR_Z + 600f else zBase

            val scatter = scatterCalculator.compute(
                index = i,
                gain = gain,
                normalizedScroll = scrollYPx / itemPitchPx,
                isSelected = isSelected,
                focusProgress = focusProgress
            )
            val verticalOverlap = -baseSidePx * STACK_VERTICAL_OVERLAP_FRACTION * progress

            child.pivotX = w * 0.3f
            child.pivotY = h * 0.45f
            child.alpha = alpha
            child.scaleX = scale
            child.scaleY = scale
            child.translationX = scatter.shiftX
            child.translationY = scatter.shiftY + verticalOverlap
            child.rotation = baseRotationZ + scatter.rotationDeg
            child.rotationY = rotationY
            child.translationZ = z
            child.elevation = z
        }
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (itemCount == 0 || height == 0) return 0
        if (dy != 0 && selectedIndex != null && focusProgress > 0f) {
            clearFocus(immediate = true)
        }

        val old = scrollYPx
        val newY = (old + dy).coerceIn(minScroll(), maxScroll())
        val consumed = (newY - old).toInt()
        scrollYPx = newY

        layoutAll(recycler)
        return consumed
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

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val desired = yTop() + targetPosition * itemPitchPx - screenCenter()
        val dir = if (desired > scrollYPx) 1f else -1f
        return PointF(0f, dir)
    }

    private fun stackProgress(py: Float): Float {
        val start = yTop()
        val bottomLimit = height - paddingBottom - bottomInsetPx - baseSidePx * STACK_BOTTOM_BUFFER_FRACTION
        val span = (bottomLimit - start).coerceAtLeast(1f)
        val raw = (py - start) / span
        return raw.coerceIn(0f, 1f)
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

    private class WidthQuantizer(private val stepPx: Int = 4) {
        fun snap(value: Int): Int {
            if (stepPx <= 1) return value
            val snapped = ((value + stepPx / 2) / stepPx) * stepPx
            return snapped.coerceAtLeast(0)
        }
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

            val waveA = sin(basePhase)
            val waveB = cos(secondaryPhase)
            val waveC = sin(tertiaryPhase + waveB * 0.35f)

            val scatterStrength = (0.12f + (1f - gain) * 0.88f).coerceIn(0f, 1f)
            val focusDamp = if (isSelected) (1f - focusProgress).coerceAtLeast(0f) else 1f
            val amount = scatterStrength * focusDamp

            val shiftX = (waveA * 14f + waveB * 8f) * amount
            val shiftY = (waveB * 6f + waveC * 10f) * amount
            val rotation = (waveA * 2.6f + waveC * 1.1f) * amount

            return ScatterOffsets(shiftX, shiftY, rotation)
        }
    }

    private companion object {
        private const val STACK_BASE_OFFSET_FRACTION = 0.38f
        private const val STACK_DIAGONAL_RANGE_FRACTION = 0.42f
        private const val STACK_VERTICAL_OVERLAP_FRACTION = 0.08f
        private const val STACK_BOTTOM_BUFFER_FRACTION = 0.35f
        private const val STACK_NEAR_Z = 6200f
        private const val STACK_FAR_Z = 900f
        private const val STACK_ROTATION_Y_NEAR = -18f
        private const val STACK_ROTATION_Y_FAR = 8f
        private const val STACK_ROTATION_Z_NEAR = -6f
        private const val STACK_ROTATION_Z_FAR = 3f
        private const val CAMERA_DISTANCE_DP = 6400f
    }
}
