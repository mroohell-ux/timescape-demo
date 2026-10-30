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
import kotlin.math.roundToInt

/**
 * A perspective ribbon with compact browsing and scroll-linked continuous reading.
 */
class RightRailFlowLayoutManager(
    private val baseSidePx: Int,          // width near center (non-focused)
    private val focusSidePx: Int,         // width when focused (main)
    private val itemPitchPx: Int,         // visual spacing (center-to-center)
    private val rightInsetPx: Int,
    private val topInsetPx: Int = 0,
    private val bottomInsetPx: Int = 0
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    // focus animation state
    private var selectedIndex: Int? = null
    private var focusProgress: Float = 0f
    private var focusAnimator: ValueAnimator? = null
    private var readingMode = false
    private var readingScrollActive = false
    private val interp = FastOutSlowInInterpolator()

    init {
        setItemPrefetchEnabled(true)
    }

    // scroll state
    private var scrollYPx = 0f
    private var pendingRestore: PendingRestore? = null
    private var settleAnimator: ValueAnimator? = null
    private var lastViewportHeight = 0

    var selectionListener: ((Int?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentSelectionIndex())
        }

    // Precomputed helpers to reduce per-frame work
    private val widthQuantizer = IntQuantizer()
    private val heightQuantizer = IntQuantizer(stepPx = 12)
    private val layoutOverscan = 2

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
    fun isFocused(index: Int) = readingMode && selectedIndex == index && focusProgress >= 0.999f &&
        !readingScrollActive

    private fun scrollForIndex(index: Int) = yTop() + index * itemPitchPx - screenCenter()

    private fun readingPosition(): Float =
        ((scrollYPx + screenCenter() - yTop()) / itemPitchPx).coerceIn(0f, max(0, itemCount - 1).toFloat())

    private fun expansionFor(index: Int): Float {
        val selected = selectedIndex ?: return 0f
        val position = if (readingScrollActive) readingPosition() else selected.toFloat()
        val weight = (1f - abs(index - position)).coerceIn(0f, 1f)
        // Complementary smoothstep weights let neighbors exchange expansion without a midpoint jump.
        return focusProgress * weight * weight * (3f - 2f * weight)
    }

    private fun updateReadingSelection() {
        if (!readingScrollActive || selectedIndex == null) return
        val nearest = nearestIndex()
        if (selectedIndex != nearest) {
            selectedIndex = nearest
            notifySelectionChanged()
        }
    }

    /** Center index given current scroll (rounded to nearest). */
    fun nearestIndex(): Int {
        pendingRestore?.let { return it.index.coerceIn(0, max(0, itemCount - 1)) }
        val idx = ((scrollYPx + screenCenter() - yTop()) / itemPitchPx).roundToInt()
        return idx.coerceIn(0, max(0, itemCount - 1))
    }

    /** Pixel delta required to move `index` to the ribbon's active position. */
    fun offsetTo(index: Int): Int {
        val desiredScroll = yTop() + index * itemPitchPx - screenCenter()
        return (desiredScroll - scrollYPx).toInt()
    }

    fun currentSelectionIndex(): Int? {
        val sel = pendingRestore?.takeIf { it.focus }?.index ?: selectedIndex ?: return null
        if (itemCount == 0) return null
        return sel.coerceIn(0, itemCount - 1)
    }

    fun hasSelection(): Boolean = pendingRestore?.focus ?: (selectedIndex != null)

    fun focus(index: Int) {
        if (itemCount == 0) return
        val clamped = index.coerceIn(0, max(0, itemCount - 1))
        if (isFocused(clamped) && readingMode) return
        cancelSettleAnimation()
        readingMode = true
        readingScrollActive = false
        scrollYPx = scrollForIndex(clamped)
        val changed = selectedIndex != clamped
        if (changed) {
            cancelFocusAnimation()
            focusProgress = 0f
        }
        selectedIndex = clamped
        if (changed) notifySelectionChanged()
        animateFocus(1f)
    }
    fun clearFocus(immediate: Boolean = false) {
        readingMode = false
        cancelSettleAnimation()
        pendingRestore = pendingRestore?.copy(focus = false)
        if (selectedIndex == null && focusProgress == 0f) return
        if (immediate) {
            val hadSelection = selectedIndex != null
            cancelFocusAnimation()
            focusProgress = 0f
            selectedIndex = null
            readingScrollActive = false
            requestLayout()
            if (hadSelection) notifySelectionChanged()
        } else {
            animateFocus(0f) {
                val hadSelection = selectedIndex != null
                selectedIndex = null
                readingScrollActive = false
                if (hadSelection) notifySelectionChanged()
            }
        }
    }

    private fun animateFocus(to: Float, end: (() -> Unit)? = null) {
        cancelFocusAnimation()
        val from = focusProgress
        val animator = ValueAnimator.ofFloat(from, to).apply {
            duration = if (to > from) 280 else 220
            interpolator = interp
            addUpdateListener { focusProgress = it.animatedValue as Float; requestLayout() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (focusAnimator !== animation) return
                    focusAnimator = null
                    if (focusProgress <= 0.001f) end?.invoke()
                    else notifySelectionChanged()
                }
            })
        }
        focusAnimator = animator
        animator.start()
    }

    private fun cancelFocusAnimation() {
        val animator = focusAnimator
        focusAnimator = null
        animator?.cancel()
    }

    // ---- Rail geometry ----
    private fun screenCenter() = height * 0.25f
    private fun yTop() = paddingTop + topInsetPx + itemPitchPx / 2f
    private fun yBottom() = height - paddingBottom - bottomInsetPx - itemPitchPx / 2f
    private fun railX() = paddingLeft + (width - paddingLeft - paddingRight - rightInsetPx) / 2f

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
        if (lastViewportHeight != 0 && lastViewportHeight != height && pendingRestore == null) {
            scrollYPx += (lastViewportHeight - height) * 0.25f
        }
        lastViewportHeight = height
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

    private fun ensureCache(child: View): ChildCache {
        val existing = child.getTag(R.id.tag_card_cache) as? ChildCache
        if (existing != null) return existing
        val cache = ChildCache(
            container = child.findViewById(R.id.card_content),
            snippet = child.findViewById(R.id.snippet),
            card = child.findViewById(R.id.card),
            presentation = child as? TimescapeCardLayout
        )
        child.setTag(R.id.tag_card_cache, cache)
        return cache
    }

    private data class ChildCache(
        val container: MaxHeightLinearLayout?,
        val snippet: TextView?,
        val card: AspectRatioCardView?,
        val presentation: TimescapeCardLayout?,
        var lastMaxHeight: Int = -1,
        var lastMeasuredWidth: Int = -1,
        var lastQuantizedSide: Int = -1,
        var extraHeight: Float = 0f
    )

    private fun layoutWindow(scroll: Float): IntRange {
        val yT = yTop()
        val firstIdx = max(
            0,
            floor(((scroll + (yT - itemPitchPx)) - yT) / itemPitchPx).toInt() - layoutOverscan
        )
        val lastIdx = min(
            itemCount - 1,
            ceil(((scroll + (yBottom() - yT) + itemPitchPx) - yT) / itemPitchPx).toInt() + layoutOverscan
        )
        return firstIdx..lastIdx
    }

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cy = screenCenter()
        val baseX = railX()
        val yT = yTop()

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

        var totalExtraHeight = 0f

        for (i in firstIdx..lastIdx) {
            val child = findViewByPosition(i) ?: recycler.getViewForPosition(i).also { addView(it) }
            val cache = ensureCache(child)

            // Y position of item center for index i
            val py = yT + i * itemPitchPx - scrollYPx
            val depth = (py - cy) / itemPitchPx
            val opening = expansionFor(i)
            val perspectiveScale = (1f - max(0f, depth) * 0.10f - max(0f, -depth) * 0.025f).coerceIn(0.52f, 1f)
            var side = (baseSidePx * 0.84f * perspectiveScale).roundToInt()
            side = (side + (focusSidePx - side) * opening).roundToInt()
            side = widthQuantizer.snap(side)
            cache.presentation?.setExpansion(opening)
            cache.presentation?.setViewportCap(heightCap)
            cache.card?.setHeightCap(heightCap)
            val textChanged = cache.snippet?.let {
                if (it.maxLines == Int.MAX_VALUE) false else {
                    it.maxLines = Int.MAX_VALUE
                    true
                }
            } ?: false
            measureCardWithWidthAndCap(child, side, heightCap, cache, textChanged)
            cache.extraHeight = (child.measuredHeight - (cache.presentation?.previewHeight ?: child.measuredHeight))
                .toFloat().coerceAtLeast(0f)
            totalExtraHeight += cache.extraHeight
        }

        var precedingExtraHeight = 0f
        for (i in firstIdx..lastIdx) {
            val child = findViewByPosition(i) ?: continue
            val baseY = yT + i * itemPitchPx - scrollYPx
            val depth = (baseY - cy) / itemPitchPx
            val opening = expansionFor(i)
            val extraHeight = ensureCache(child).extraHeight
            // Accumulate both opening cards' growth instead of displacing around a discrete selection.
            val push = precedingExtraHeight + extraHeight / 2f - totalExtraHeight / 2f
            precedingExtraHeight += extraHeight
            val readingShift = if (selectedIndex != null) height * 0.25f * focusProgress else 0f
            val py = baseY + push + readingShift
            val px = baseX - kotlin.math.sin(depth * 0.72f) * width * 0.11f * (1f - opening)
            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val l = (px - w / 2f).toInt()
            val t = (py - h / 2f).toInt()
            layoutDecoratedWithMargins(child, l, t, l + w, t + h)

            val density = child.resources.displayMetrics.density
            child.alpha = 1f - (max(0f, depth) * 0.055f).coerceAtMost(0.28f) * (1f - opening)
            child.scaleX = 1f
            child.scaleY = 1f
            child.translationX = 0f
            child.translationY = 0f
            child.cameraDistance = 1500f * density
            child.rotationX = (8f + min(abs(depth), 4f) * 0.5f) * (1f - opening)
            child.rotationY = (3f - depth.coerceIn(-3f, 3f) * 0.5f) * (1f - opening)
            child.rotation = (-kotlin.math.sin(depth * 0.65f) * 0.6f) * (1f - opening)
            (child as? TimescapeCardLayout)?.setDepth(depth)
            child.elevation = 0f
            val ribbonZ = (14f - depth * 2f).coerceIn(1f, 24f)
            child.translationZ = (ribbonZ + (32f - ribbonZ) * opening) * density
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
        if (dy != 0) {
            cancelSettleAnimation()
            if (readingMode && selectedIndex != null && !readingScrollActive) {
                readingScrollActive = true
                notifySelectionChanged()
            } else if (!readingMode && selectedIndex != null) {
                clearFocus(immediate = true)
            }
        }

        val old = scrollYPx
        val newY = if (readingMode) (old + dy).coerceIn(minScroll(), maxScroll()) else applyOverScrollResistance(old + dy)
        val consumed = (newY - old).toInt()
        scrollYPx = newY
        updateReadingSelection()

        layoutAll(recycler)
        return consumed
    }

    fun settleScrollIfNeeded(onSettled: (() -> Unit)? = null) {
        if (itemCount == 0 || height == 0) {
            onSettled?.invoke()
            return
        }
        val target = if (readingMode) scrollForIndex(nearestIndex()) else scrollYPx.coerceIn(minScroll(), maxScroll())
        if (abs(target - scrollYPx) < 0.5f) {
            cancelSettleAnimation()
            scrollYPx = target
            finishReadingScroll()
            requestLayout()
            onSettled?.invoke()
            return
        }
        cancelSettleAnimation()
        val animator = ValueAnimator.ofFloat(scrollYPx, target).apply {
            duration = 260
            interpolator = interp
            addUpdateListener {
                scrollYPx = it.animatedValue as Float
                updateReadingSelection()
                requestLayout()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (settleAnimator === animation) {
                        settleAnimator = null
                        scrollYPx = target
                        finishReadingScroll()
                        requestLayout()
                        onSettled?.invoke()
                    }
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    if (settleAnimator === animation) settleAnimator = null
                }
            })
        }
        settleAnimator = animator
        animator.start()
    }

    private fun finishReadingScroll() {
        if (!readingScrollActive) return
        updateReadingSelection()
        readingScrollActive = false
        notifySelectionChanged()
    }

    override fun onScrollStateChanged(state: Int) {
        if (state == RecyclerView.SCROLL_STATE_DRAGGING) cancelSettleAnimation()
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
        cancelFocusAnimation()
        readingMode = focus
        readingScrollActive = false
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
        val animator = settleAnimator
        settleAnimator = null
        animator?.cancel()
    }

    override fun computeScrollVectorForPosition(targetPosition: Int): PointF? {
        val desired = yTop() + targetPosition * itemPitchPx - screenCenter()
        val dir = if (desired > scrollYPx) 1f else -1f
        return PointF(0f, dir)
    }

    private class IntQuantizer(private val stepPx: Int = 4) {
        fun snap(value: Int): Int {
            if (stepPx <= 1) return value
            val snapped = ((value + stepPx / 2) / stepPx) * stepPx
            return snapped.coerceAtLeast(0)
        }
    }

}
