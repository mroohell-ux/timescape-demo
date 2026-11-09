package com.example.timescapedemo

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.PointF
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
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
    private val itemPitchPx: Int,         // logical scroll distance per card
    private val rightInsetPx: Int,
    private val topInsetPx: Int = 0,
    private val bottomInsetPx: Int = 0,
    geometry: CardFlowGeometry = CardFlowGeometry()
) : RecyclerView.LayoutManager(), RecyclerView.SmoothScroller.ScrollVectorProvider {

    var geometry: CardFlowGeometry = geometry
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

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
    private var attachedRecycler: RecyclerView? = null
    private var edgeLimitPx = 96f
    private val edgePull = CardFlipSpring(26f, .95f, .25f)
    private var edgeRebounding = false
    private var edgeFrameTime = 0L
    private var edgeSettled: (() -> Unit)? = null
    private val edgeFrame = object : Runnable {
        override fun run() {
            val host = attachedRecycler ?: return
            if (!edgeRebounding) return
            val now = SystemClock.uptimeMillis()
            edgePull.advance((now - edgeFrameTime) / 1000f)
            edgeFrameTime = now
            if (edgePull.isAtRest || !ValueAnimator.areAnimatorsEnabled()) {
                edgePull.snap(0f)
                edgeRebounding = false
                val complete = edgeSettled
                edgeSettled = null
                finishReadingScroll()
                requestLayout()
                complete?.invoke()
            } else {
                requestLayout()
                host.postOnAnimation(this)
            }
        }
    }

    var selectionListener: ((Int?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentSelectionIndex())
        }

    // Precomputed helpers to reduce per-frame work
    private val widthQuantizer = IntQuantizer()
    private val heightQuantizer = IntQuantizer(stepPx = 12)
    private val compactLayoutWidth = widthQuantizer.snap((baseSidePx * .80f).roundToInt())
    private val readingLayoutWidth = widthQuantizer.snap(focusSidePx)
    private val layoutOverscan = 2
    private val inverseHitMatrix = Matrix()
    private val hitPoint = FloatArray(2)
    private val compactPose = CardFlowPose()
    private var minimumPreviewHeight = 0f

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        attachedRecycler = view
        edgeLimitPx = 96f * view.resources.displayMetrics.density
        minimumPreviewHeight = view.resources.getDimension(R.dimen.card_preview_min_height)
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        val hadEdgePull = edgePull.value != 0f || edgeRebounding
        stopEdgeRebound()
        edgePull.snap(0f)
        if (hadEdgePull) {
            if (itemCount > 0) finishReadingScroll() else readingScrollActive = false
        }
        attachedRecycler = null
        super.onDetachedFromWindow(view, recycler)
    }

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

    fun hasCardUnder(x: Float, y: Float): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE) continue
            val parent = child.parent as View
            hitPoint[0] = x + parent.scrollX - child.left
            hitPoint[1] = y + parent.scrollY - child.top
            if (!child.matrix.isIdentity) {
                if (!child.matrix.invert(inverseHitMatrix)) continue
                inverseHitMatrix.mapPoints(hitPoint)
            }
            if (hitPoint[0] >= 0 && hitPoint[0] < child.width &&
                hitPoint[1] >= 0 && hitPoint[1] < child.height &&
                (child !is TimescapeCardLayout || child.containsCardPoint(hitPoint[0], hitPoint[1]))
            ) return true
        }
        return false
    }

    fun focus(index: Int) {
        if (itemCount == 0) return
        val clamped = index.coerceIn(0, max(0, itemCount - 1))
        if (isFocused(clamped) && readingMode) return
        cancelSettleAnimation()
        edgePull.snap(0f)
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
        edgePull.snap(0f)
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

    private fun compactPitch(): Float {
        return minimumPreviewHeight * cos(Math.toRadians(geometry.maximumPitchDegrees.toDouble())).toFloat() *
            min(1f, geometry.distantScaleY) *
            (1f - geometry.cardOverlapFraction)
    }

    private fun ribbonOffset(depth: Float) = geometry.ribbonOffset(depth, compactPitch())

    private fun depthAtRibbonOffset(offset: Float) =
        geometry.depthAtRibbonOffset(offset, compactPitch())

    private fun compactCenterY(index: Int, scroll: Float): Float {
        val cy = screenCenter()
        return cy + ribbonOffset((yTop() + index * itemPitchPx - scroll - cy) / itemPitchPx)
    }

    /** Allow first/last items to be centered without dead space. */
    private fun minScroll() = yTop() - screenCenter()
    private fun maxScroll(): Float {
        val lastCentered = yTop() + (itemCount - 1).coerceAtLeast(0) * itemPitchPx - screenCenter()
        return max(minScroll(), lastCentered)
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        detachAndScrapAttachedViews(recycler)
        if (itemCount == 0 || width == 0 || height == 0) {
            stopEdgeRebound()
            edgePull.snap(0f)
            return
        }
        if (minimumPreviewHeight == 0f) {
            // Auto-measure can precede attachment to the window.
            val child = recycler.getViewForPosition(nearestIndex())
            addView(child)
            minimumPreviewHeight = child.resources.getDimension(R.dimen.card_preview_min_height)
        }
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
        val pose: CardFlowPose = CardFlowPose(),
        val bottomEdge: FloatArray = FloatArray(4),
        var lastMaxHeight: Int = -1,
        var lastMeasuredWidth: Int = -1,
        var lastQuantizedSide: Int = -1,
        var extraHeight: Float = 0f,
        var extraOffset: Float = 0f,
        var visualScale: Float = 1f,
        var lastLayoutWidth: Int = -1,
        var lastLayoutHeight: Int = -1
    )

    private fun layoutWindow(scroll: Float): IntRange {
        val cy = screenCenter()
        val position = (scroll + cy - yTop()) / itemPitchPx
        val firstIdx = max(
            0,
            floor(position + depthAtRibbonOffset(yTop() - itemPitchPx - cy)).toInt() - layoutOverscan
        )
        val lastIdx = min(
            itemCount - 1,
            ceil(position + depthAtRibbonOffset(yBottom() + itemPitchPx - cy)).toInt() + layoutOverscan
        )
        return firstIdx..lastIdx
    }

    private fun layoutAll(recycler: RecyclerView.Recycler) {
        val cy = screenCenter()
        val baseX = railX()
        val yT = yTop()
        val edgeOffset = -cardEdgeDisplacement(edgePull.value, min(edgeLimitPx, height * .12f).coerceAtLeast(1f))

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
            geometry.sample(depth, opening, cache.pose)
            val visualWidth = compactLayoutWidth * cache.pose.depthScale * (1f - opening) + readingLayoutWidth * opening
            // Depth changes only RenderNode scale, not text/image layout dimensions.
            val layoutWidth = widthQuantizer.snap(
                (compactLayoutWidth + (readingLayoutWidth - compactLayoutWidth) * opening).roundToInt()
            )
            cache.visualScale = visualWidth / layoutWidth
            cache.presentation?.setOverviewWidth(compactLayoutWidth)
            cache.presentation?.setReadingWidth(readingLayoutWidth)
            cache.presentation?.setExpansion(opening)
            cache.presentation?.setViewportCap(heightCap)
            cache.card?.setHeightCap(heightCap)
            val textChanged = cache.snippet?.let {
                if (it.maxLines == Int.MAX_VALUE) false else {
                    it.maxLines = Int.MAX_VALUE
                    true
                }
            } ?: false
            measureCardWithWidthAndCap(child, layoutWidth, heightCap, cache, textChanged)
            cache.extraHeight = (child.measuredHeight - (cache.presentation?.previewHeight ?: child.measuredHeight))
                .toFloat().coerceAtLeast(0f) * cache.visualScale * cache.pose.scaleY
            val compact = if (opening == 0f) cache.pose else compactPose.also {
                geometry.sample(depth, 0f, it)
            }
            val compactExcess = ((cache.presentation?.previewHeight ?: child.measuredHeight) -
                minimumPreviewHeight).coerceAtLeast(0f) *
                compact.depthScale * compact.scaleY *
                cos(Math.toRadians(compact.pitch.toDouble())).toFloat() * (1f - geometry.cardOverlapFraction)
            cache.extraHeight += compactExcess
            cache.extraOffset = totalExtraHeight + cache.extraHeight / 2f
            totalExtraHeight += cache.extraHeight
        }

        val position = (scrollYPx + cy - yT) / itemPitchPx
        val anchorIndex = floor(position).toInt().coerceIn(firstIdx, max(firstIdx, lastIdx - 1))
        val anchor = ensureCache(checkNotNull(findViewByPosition(anchorIndex))).extraOffset
        val nextAnchor = if (anchorIndex < lastIdx) {
            ensureCache(checkNotNull(findViewByPosition(anchorIndex + 1))).extraOffset
        } else anchor
        val anchorOffset = anchor + (nextAnchor - anchor) * (position - anchorIndex)
        val compactPitch = compactPitch()
        for (i in firstIdx..lastIdx) {
            val child = findViewByPosition(i) ?: continue
            val baseY = yT + i * itemPitchPx - scrollYPx
            val depth = (baseY - cy) / itemPitchPx
            val opening = expansionFor(i)
            val cache = ensureCache(child)
            val push = cache.extraOffset - anchorOffset
            val readingShift = if (selectedIndex != null) height * 0.25f * focusProgress else 0f
            val py = cy + geometry.ribbonOffset(depth, compactPitch) + push + readingShift + edgeOffset
            val pose = cache.pose
            val px = baseX + pose.bend * width
            val w = getDecoratedMeasuredWidth(child)
            val h = getDecoratedMeasuredHeight(child)
            val l = (px - w / 2f).toInt()
            val t = (py - h / 2f).toInt()
            if (child.isLaidOut && !child.isLayoutRequested &&
                cache.lastLayoutWidth == w && cache.lastLayoutHeight == h
            ) {
                val params = child.layoutParams as RecyclerView.LayoutParams
                child.offsetLeftAndRight(l + params.leftMargin + getLeftDecorationWidth(child) - child.left)
                child.offsetTopAndBottom(t + params.topMargin + getTopDecorationHeight(child) - child.top)
            } else {
                layoutDecoratedWithMargins(child, l, t, l + w, t + h)
            }
            cache.lastLayoutWidth = w
            cache.lastLayoutHeight = h

            val density = child.resources.displayMetrics.density
            child.alpha = if (cache.presentation?.isMediaPreview == false) 1f else {
                1f - (max(0f, depth) * 0.055f).coerceAtMost(0.28f) * (1f - opening)
            }
            child.scaleX = cache.visualScale
            child.scaleY = cache.visualScale * pose.scaleY
            child.pivotX = child.width * pose.pivotX
            child.pivotY = child.height * pose.pivotY
            // Keep the ribbon's center stable when the scale pivot moves; retain subpixel scroll.
            child.translationX = px - w / 2f - l +
                (child.scaleX - 1f) * (child.pivotX - child.width / 2f)
            child.translationY = py - h / 2f - t +
                (child.scaleY - 1f) * (child.pivotY - child.height / 2f)
            child.cameraDistance = geometry.cameraDistance(
                child.width, child.height, child.resources.displayMetrics.densityDpi, pose.pivotX, pose.pivotY
            )
            child.rotationX = pose.pitch
            child.rotationY = pose.yaw
            cache.presentation?.setCornerMorph(pose.corners)
            applyBottomEdgeAngle(child, cache)
            cache.presentation?.setDepth(depth, geometry)
            val visualHalfHeight = h * child.scaleY / 2f
            cache.presentation?.setRenderCaching(
                py + visualHalfHeight >= -itemPitchPx / 2f &&
                    py - visualHalfHeight <= height + itemPitchPx / 2f
            )
            child.elevation = 0f
            val ribbonZ = (14f - depth * 2f).coerceIn(1f, 24f)
            child.translationZ = (ribbonZ + (32f - ribbonZ) * opening) * density
        }
    }

    private fun applyBottomEdgeAngle(card: View, cache: ChildCache) {
        val limit = geometry.maximumRollDegrees
        if (limit == 0f) {
            card.rotation = 0f
            return
        }
        card.rotation = card.rotation.coerceIn(-limit, limit)
        // Correct the drawn edge, since perspective and face height also contribute to its angle.
        repeat(2) {
            val edge = cache.bottomEdge
            edge[0] = 0f
            edge[1] = card.height.toFloat()
            edge[2] = card.width.toFloat()
            edge[3] = edge[1]
            cache.presentation?.mapCornerMorph(edge)
            card.matrix.mapPoints(edge)
            val projected = Math.toDegrees(atan2(
                (edge[3] - edge[1]).toDouble(), (edge[2] - edge[0]).toDouble())).toFloat()
            val correction = cache.pose.bottomEdgeAngle - projected
            if (abs(correction) < .01f) return
            card.rotation = (card.rotation + correction).coerceIn(-limit, limit)
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
        val targetWindow = layoutWindow((scrollYPx + dy).coerceIn(minScroll(), maxScroll()))

        if (direction > 0) {
            val start = (currentWindow.last + 1).coerceAtLeast(0)
            val end = min(max(targetWindow.last, start), itemCount - 1)
            if (start <= end) {
                for (i in start..end) {
                    val centerY = compactCenterY(i, scrollYPx)
                    val distance = max(0f, centerY - yBottom()).toInt()
                    layoutPrefetchRegistry.addPosition(i, distance)
                }
            }
        } else {
            val end = (currentWindow.first - 1).coerceAtMost(itemCount - 1)
            val start = min(targetWindow.first, end).coerceAtLeast(0)
            if (start <= end) {
                for (i in end downTo start) {
                    val centerY = compactCenterY(i, scrollYPx)
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

        var remaining = dy.toFloat()
        var pull = edgePull.value
        // Reverse the same raw pull before spending any motion on the next card.
        if (pull * remaining < 0f) {
            val unwound = remaining.coerceIn(-max(0f, pull), -min(0f, pull))
            pull += unwound
            remaining -= unwound
        }
        val old = scrollYPx
        val newY = (old + remaining).coerceIn(minScroll(), maxScroll())
        val consumed = (newY - old).toInt()
        scrollYPx = newY
        edgePull.snap(pull + remaining - (newY - old))
        updateReadingSelection()

        layoutAll(recycler)
        return consumed
    }

    fun settleScrollIfNeeded(onSettled: (() -> Unit)? = null) {
        if (itemCount == 0 || height == 0) {
            onSettled?.invoke()
            return
        }
        if (edgeRebounding) {
            if (onSettled != null) edgeSettled = onSettled
            return
        }
        val target = if (readingMode) scrollForIndex(nearestIndex()) else scrollYPx.coerceIn(minScroll(), maxScroll())
        if (abs(target - scrollYPx) < 0.5f) {
            cancelSettleAnimation()
            scrollYPx = target
            val host = attachedRecycler
            if (abs(edgePull.value) >= .5f && host != null && ValueAnimator.areAnimatorsEnabled()) {
                edgePull.target = 0f
                edgeRebounding = true
                edgeFrameTime = SystemClock.uptimeMillis()
                edgeSettled = onSettled
                host.postOnAnimation(edgeFrame)
                return
            }
            edgePull.snap(0f)
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
        edgePull.snap(0f)
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

    private fun stopEdgeRebound() {
        attachedRecycler?.removeCallbacks(edgeFrame)
        edgeRebounding = false
        edgeSettled = null
        edgePull.snap(edgePull.value)
    }

    private fun cancelSettleAnimation() {
        val animator = settleAnimator
        settleAnimator = null
        animator?.cancel()
        stopEdgeRebound()
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

internal fun cardEdgeDisplacement(pull: Float, limit: Float): Float {
    require(limit > 0f) { "Edge displacement limit must be positive" }
    val resisted = pull * .45f
    return resisted / (1f + abs(resisted) / limit)
}
